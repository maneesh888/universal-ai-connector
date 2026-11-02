#!/usr/bin/env python3
"""Explicit owner-operated Central publication. Offline preparation never authenticates."""
import argparse
import base64
import getpass
from contextlib import contextmanager
import hashlib
import http.client
import importlib.util
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import socket
import ssl
import stat
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
import warnings
import zipfile

ROOT = Path(__file__).resolve().parent.parent
ORIGIN = "https://central.sonatype.com"
STATES = {"PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED", "FAILED"}
HASHES = ("md5", "sha1", "sha256", "sha512")
spec = importlib.util.spec_from_file_location("central_inventory", ROOT / "scripts/maven-inventory.py")
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class PublicationError(ValueError):
    pass


class ApiError(PublicationError):
    def __init__(self, status=None, retry_after=None):
        super().__init__("Central request failed; no upstream text retained.")
        self.status, self.retry_after = status, retry_after


def require(condition, message):
    if not condition:
        raise PublicationError(message)


def deployment_id(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}", value), "Invalid deployment identity.")
    return value.lower()


def json_file(path, limit=1048576):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= limit, "Unsafe publication receipt.")
    value = json.loads(path.read_text())
    require(isinstance(value, dict), "Invalid publication receipt.")
    return value


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_receipt(path, value):
    require(path.parent.is_dir() and not path.parent.is_symlink(), "Unsafe receipt directory.")
    temporary = path.with_name("." + path.name + "." + uuid.uuid4().hex)
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(descriptor, "w") as stream:
            json.dump(value, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        require(not path.is_symlink(), "Refusing a linked publication receipt.")
        temporary.replace(path)
        sync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def signed_bundle(directory, metadata, version):
    """Revalidate the exact bytes attested by the local GnuPG signing receipt."""
    require(directory.is_dir() and not directory.is_symlink(), "Signed artifact directory is unavailable.")
    require(not any(p.is_symlink() for p in directory.rglob("*")), "Linked signed artifact input.")
    receipt = json_file(directory / "verification.json")
    require(receipt.get("mode") == "signed-local" and receipt.get("signatures_verified") is True and receipt.get("group") == "com.myadidi" and receipt.get("version") == version, "Missing verified local signing receipt.")
    require(re.fullmatch(r"[0-9a-f]{40}", receipt.get("head", "")) and re.fullmatch(r"[0-9a-f]{40}", receipt.get("tree", "")), "Invalid signing source identity.")
    require(re.fullmatch(r"[0-9A-F]{40}", receipt.get("fingerprint", "")) and re.fullmatch(r"[0-9a-f]{64}", receipt.get("verified_unsigned_inventory_sha256", "")), "Missing signing provenance.")
    base = metadata["MAVEN_ARTIFACT_ID"]
    publications = {base, base + "-jvm", base + "-android", base + "-iosarm64", base + "-iossimulatorarm64"}
    require(set(receipt.get("publications", [])) == publications, "Incomplete publication set.")
    items = receipt.get("artifacts", [])
    require(isinstance(items, list) and len(items) == 28, "Incomplete payload inventory.")
    bundle = directory / "central-bundle.zip"
    require(bundle.is_file() and bundle.stat().st_size <= 150 * 1024 * 1024, "Invalid signed bundle size.")
    with bundle.open("rb") as stream:
        body = stream.read(150 * 1024 * 1024 + 1)
    require(len(body) <= 150 * 1024 * 1024, "Oversized signed bundle.")
    require(hashlib.sha256(body).hexdigest() == receipt.get("bundle_sha256"), "Signed bundle changed after verification.")
    expected = set()
    with zipfile.ZipFile(io.BytesIO(body)) as archive:
        infos = archive.infolist()
        require(len(infos) == len({i.filename for i in infos}) and len(infos) <= 300 and sum(i.file_size for i in infos) <= 512 * 1024 * 1024, "Unsafe signed archive inventory.")
        for item in infos:
            path = PurePosixPath(item.filename)
            require(not path.is_absolute() and ".." not in path.parts and "\\" not in item.filename and not stat.S_ISLNK(item.external_attr >> 16), "Unsafe signed archive entry.")
        for item in items:
            name = item["path"]
            path = PurePosixPath(name)
            require(len(path.parts) == 5 and path.parts[:2] == ("com", "myadidi") and path.parts[2] in publications and path.parts[3] == version, "Payload coordinate differs from the approved scope.")
            prefix = path.parts[2] + "-" + version
            require(path.name.startswith(prefix + ".") or path.name.startswith(prefix + "-"), "Payload name differs from its coordinate.")
            require(name not in expected, "Duplicate signed payload.")
            payload, signature = archive.read(name), archive.read(name + ".asc")
            require(len(payload) == item["bytes"] and hashlib.sha256(payload).hexdigest() == item["sha256"] and hashlib.sha256(signature).hexdigest() == item["signature_sha256"], "Signed payload or signature differs from the GnuPG receipt.")
            require(len(signature) < 65536 and signature.startswith(b"-----BEGIN PGP SIGNATURE-----\n") and signature.rstrip().endswith(b"-----END PGP SIGNATURE-----"), "Malformed detached signature.")
            expected.update({name, name + ".asc"})
            for suffix, data in [("", payload), (".asc", signature)]:
                for algorithm in HASHES:
                    checksum = name + suffix + "." + algorithm
                    require(archive.read(checksum).decode("ascii").strip() == hashlib.new(algorithm, data).hexdigest(), "Signed bundle checksum differs.")
                    expected.add(checksum)
        require(set(archive.namelist()) == expected and len(expected) == 280, "Unexpected signed bundle file.")
        with tempfile.TemporaryDirectory(prefix=".central-inspect-", dir=directory.parent) as temporary:
            unsigned = Path(temporary)
            for name in sorted(expected):
                if ".asc" in PurePosixPath(name).name:
                    continue
                target = unsigned / name
                target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
                with os.fdopen(descriptor, "wb") as stream:
                    stream.write(archive.read(name))
            checked = inventory.validate(unsigned, "com.myadidi", version, metadata)
            require(inventory.digest(checked) == receipt["verified_unsigned_inventory_sha256"], "Signed bytes do not match the consumer-verified Maven graph.")
    return receipt, body


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


class Client:
    def __init__(self, username, password, opener=None):
        require(username and password and len(username) < 4096 and len(password) < 4096 and ":" not in username and not any(c in username + password for c in "\r\n\0"), "Invalid private token input.")
        self.authorization = "Bearer " + base64.b64encode((username + ":" + password).encode()).decode()
        self.opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))

    def close(self):
        self.authorization = None

    def request(self, path, data=b"", content_type="application/json"):
        require(path.startswith("/api/v1/publisher/") and not any(c in path for c in "\r\n"), "Invalid fixed Central operation.")
        request = urllib.request.Request(ORIGIN + path, data=data, method="POST", headers={"Authorization": self.authorization, "Content-Type": content_type})
        try:
            with self.opener.open(request, timeout=60) as response:
                body = response.read(65537)
                require(len(body) <= 65536, "Oversized Central response.")
                return response.status, body
        except urllib.error.HTTPError as error:
            value = error.headers.get("Retry-After", "")
            retry_after = min(int(value), 86400) if re.fullmatch(r"[0-9]{1,8}", value) else None
            status = error.code
            error.close()
            raise ApiError(status, retry_after) from None
        except (urllib.error.URLError, OSError, TimeoutError, socket.timeout, http.client.HTTPException):
            raise ApiError() from None

    def upload(self, body, name):
        boundary = "uac-" + uuid.uuid4().hex
        require(boundary.encode() not in body, "Multipart boundary collision.")
        multipart = ("--" + boundary + '\r\nContent-Disposition: form-data; name="bundle"; filename="central-bundle.zip"\r\nContent-Type: application/octet-stream\r\n\r\n').encode() + body + ("\r\n--" + boundary + "--\r\n").encode()
        status, result = self.request("/api/v1/publisher/upload?" + urllib.parse.urlencode({"publishingType": "USER_MANAGED", "name": name}), multipart, "multipart/form-data; boundary=" + boundary)
        require(status == 201, "Unexpected upload response; reconcile Portal state before retrying.")
        return deployment_id(result.decode("ascii").strip())

    def status(self, identifier, name):
        identifier = deployment_id(identifier)
        status, body = self.request("/api/v1/publisher/status?" + urllib.parse.urlencode({"id": identifier}))
        require(status == 200, "Unexpected status response.")
        result = json.loads(body)
        require(isinstance(result, dict) and deployment_id(result.get("deploymentId")) == identifier and result.get("deploymentName") == name and result.get("deploymentState") in STATES, "Central deployment identity or state differs.")
        errors = result.get("errors", {})
        count = len(errors) if isinstance(errors, (dict, list)) else 0
        return {"deploymentId": identifier, "deploymentState": result["deploymentState"], "errorGroups": count}

    def publish(self, identifier, name):
        require(self.status(identifier, name)["deploymentState"] == "VALIDATED", "Only a validated matching deployment may be published.")
        status, body = self.request("/api/v1/publisher/deployment/" + deployment_id(identifier))
        require(status == 204 and not body, "Unexpected publish response; reconcile deployment status before retrying.")


def release_source(root, receipt, version, remote=True):
    def git(*arguments):
        return subprocess.check_output(["git", *arguments], cwd=root, text=True, timeout=30).strip()
    require(git("rev-parse", "HEAD") == receipt["head"] and git("rev-parse", "HEAD^{tree}") == receipt["tree"] and not git("status", "--porcelain"), "Signing source must remain the clean current checkout.")
    if not remote:
        return
    require(git("rev-parse", "refs/tags/v" + version + "^{commit}") == receipt["head"], "Publication requires the approved tag on this exact source.")
    subprocess.run(["git", "merge-base", "--is-ancestor", receipt["head"], "origin/main"], cwd=root, check=True, timeout=30, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    runs = json.loads(subprocess.check_output(["gh", "run", "list", "--repo", "maneesh888/universal-ai-connector", "--branch", "main", "--commit", receipt["head"], "--workflow", "ci.yml", "--json", "headSha,event,status,conclusion"], cwd=root, text=True, timeout=30))
    require(any(r["headSha"] == receipt["head"] and r["event"] == "push" and r["status"] == "completed" and r["conclusion"] == "success" for r in runs), "Successful resulting-main CI is required before credential entry.")


def upload_once(client, body, record, journal):
    require(not journal.exists() and not journal.is_symlink(), "An upload receipt already exists; reconcile status instead of uploading again.")
    record = {**record, "operation": "upload-pending", "deploymentId": None}
    write_receipt(journal, record)
    try:
        record["deploymentId"] = client.upload(body, record["name"])
        record["operation"] = "uploaded"
        write_receipt(journal, record)
    except BaseException:
        record["operation"] = "upload-uncertain"
        write_receipt(journal, record)
        raise
    return record


def shared_store(root, version):
    common = subprocess.check_output(["git", "rev-parse", "--git-common-dir"], cwd=root, text=True, stderr=subprocess.DEVNULL, timeout=15).strip()
    common = Path(common) if Path(common).is_absolute() else root / common
    common = common.resolve()
    require(common.is_dir(), "Git common metadata is unavailable.")
    parent = common / "uac-release-state"
    store = parent / version
    for path in (parent, store):
        if not path.exists():
            path.mkdir(mode=0o700)
        info = path.lstat()
        require(stat.S_ISDIR(info.st_mode) and not path.is_symlink() and info.st_uid == os.getuid() and not info.st_mode & 0o077, "Unsafe shared publication journal directory.")
        # Persist newly created ancestor entries before any irreversible request.
        # Repeat for existing paths so recovery from interrupted first creation is safe.
        sync_directory(path.parent)
    return store


@contextmanager
def exclusive_operation(store):
    lock = store / ".operation.lock"
    descriptor = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        os.close(descriptor)
        yield
    finally:
        lock.unlink()


def owner_operation(args, receipt, body, name, store):
    journal = store / "central-deployment.json"
    with exclusive_operation(store):
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            username = getpass.getpass("Central token username (hidden): ")
            password = getpass.getpass("Central token password (hidden): ")
        client = Client(username, password)
        username = password = None
        try:
            if args.mode == "upload":
                record = upload_once(client, body, {"name": name, "head": receipt["head"], "tree": receipt["tree"], "version": receipt["version"], "bundle_sha256": receipt["bundle_sha256"]}, journal)
                print(json.dumps({"deploymentId": record["deploymentId"], "state": "uploaded; validation not yet verified"}))
                return
            record = json_file(journal)
            require(all(record.get(k) == v for k, v in {"name": name, "head": receipt["head"], "tree": receipt["tree"], "bundle_sha256": receipt["bundle_sha256"]}.items()), "Deployment journal does not match these signed bytes.")
            known = record.get("deploymentId")
            if known is not None:
                known = deployment_id(known)
            requested = deployment_id(args.deployment_id) if args.deployment_id else None
            if requested is not None:
                require(known is None or requested == known, "A known deployment ID cannot be replaced.")
                if known is None:
                    require(args.mode == "status" and record.get("operation") in {"upload-pending", "upload-uncertain"}, "Only status may reconcile a missing upload ID.")
            identifier = known or requested
            attempted = record.get("publishAttempted") is True or record.get("operation") in {"publish-pending", "publish-requested", "publish-uncertain"}
            require(args.mode != "publish" or not attempted, "A previous publish attempt requires status-only reconciliation, never another publish POST.")
            state = client.status(identifier, name)
            record.update(deploymentId=state["deploymentId"], state=state["deploymentState"], lastObservation="status-verified")
            write_receipt(journal, record)
            if args.mode != "publish":
                print(json.dumps(state))
                return
            require(state["deploymentState"] == "VALIDATED", "The matching deployment has not reached VALIDATED.")
            record["operation"] = "publish-pending"
            record["publishAttempted"] = True
            write_receipt(journal, record)
            try:
                client.publish(identifier, name)
                record["operation"] = "publish-requested"
                write_receipt(journal, record)
            except BaseException:
                record["operation"] = "publish-uncertain"
                write_receipt(journal, record)
                raise
            print(json.dumps({"deploymentId": identifier, "state": "publish requested; PUBLISHED and public resolution not yet verified"}))
        finally:
            client.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["prepare", "upload", "status", "publish"])
    parser.add_argument("--confirmed-recipient", choices=["central.sonatype.com"])
    parser.add_argument("--deployment-id", help="Public Portal ID for reconciliation of an uncertain upload.")
    args = parser.parse_args()
    props = inventory.properties(ROOT / "gradle.properties")
    metadata = inventory.properties(ROOT / "distribution/release.properties")
    require(props["GROUP"] == "com.myadidi" and re.fullmatch(r"0\.1\.0-alpha\.0\.p8\.[1-9][0-9]*", props["VERSION_NAME"]) and metadata["RELEASE_SCOPE"] == "libraries-first", "Unapproved publication scope.")
    directory = ROOT / "bridge/build/distribution/signed-maven"
    receipt, body = signed_bundle(directory, metadata, props["VERSION_NAME"])
    release_source(ROOT, receipt, props["VERSION_NAME"], remote=args.mode != "prepare")
    name = "universal-ai-connector-" + props["VERSION_NAME"] + "-" + receipt["bundle_sha256"][:12]
    print(json.dumps({"recipient": ORIGIN, "version": props["VERSION_NAME"], "source": receipt["head"], "bundle_sha256": receipt["bundle_sha256"], "publishingType": "USER_MANAGED", "requiresCredentialTransmission": args.mode != "prepare"}))
    if args.mode == "prepare":
        return
    require(args.confirmed_recipient == "central.sonatype.com" and sys.stdin.isatty() and sys.stderr.isatty(), "Explicit recipient confirmation and owner private Terminal entry are required.")
    owner_operation(args, receipt, body, name, shared_store(ROOT, props["VERSION_NAME"]))


if __name__ == "__main__":
    try:
        main()
    except ApiError as error:
        print(json.dumps({"result": "Central request failed; reconcile journal/Portal before retrying", "httpStatus": error.status, "retryAfterSeconds": error.retry_after}), file=sys.stderr)
        sys.exit(1)
    except (PublicationError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError, EOFError, KeyboardInterrupt, Warning):
        print("Central operation stopped safely. No upstream text or secret retained; reconcile the protected journal before any retry.", file=sys.stderr)
        sys.exit(1)
