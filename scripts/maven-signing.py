#!/usr/bin/env python3
"""Owner-operated, offline signing of already prepared Maven bytes. Never uploads."""
import getpass
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import warnings
import zipfile

GPG = Path("/opt/homebrew/bin/gpg")
GPGCONF = Path("/opt/homebrew/bin/gpgconf")
HASHES = ("md5", "sha1", "sha256", "sha512")


class SigningError(ValueError):
    pass


class CleanupError(SigningError):
    pass


def require(condition, message):
    if not condition:
        raise SigningError(message)


def protected_path(path, directory=False):
    info = path.lstat()
    require(not stat.S_ISLNK(info.st_mode) and info.st_uid == os.getuid(), "Unsafe signing storage owner or link.")
    require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode), "Unexpected signing storage type.")
    require(not info.st_mode & 0o077, "Signing storage must be owner-only.")


def public_setup(store):
    # This reads only the setup helper's public receipt, never a private-key export.
    require(not any(p.is_symlink() for p in (store, *store.parents)), "Signing storage has a linked ancestor.")
    for parent in (store.parent, store, store / "gnupg"):
        protected_path(parent, directory=True)
    receipt = store / "setup-complete.json"
    protected_path(receipt)
    require(receipt.stat().st_size < 16384, "Oversized signing setup receipt.")
    record = json.loads(receipt.read_text())
    require(isinstance(record, dict) and record.get("openPGPVersion") == 4, "Unexpected approved signing identity.")
    identity = record.get("identity", "")
    require(isinstance(identity, str) and len(identity) < 256 and re.fullmatch(r"[^<>\r\n\0]+ <[^<>@\s]+@[^<>@\s]+>", identity), "Invalid public identity in the owner setup receipt.")
    require(record.get("protectedBackupPacketFormatVerified") is True and record.get("dedicatedAgentStopCommandPassed") is True, "Owner setup did not complete backup and agent cleanup.")
    fingerprint = record.get("fingerprint", "")
    require(isinstance(fingerprint, str) and re.fullmatch(r"[0-9A-F]{40}", fingerprint), "Invalid public signing fingerprint.")
    require(type(record.get("expiresAtUnix")) is int and record["expiresAtUnix"] > time.time(), "Signing key is expired.")
    return {"fingerprint": fingerprint, "identity": identity, "expiresAtUnix": record["expiresAtUnix"]}


def validate_key_listing(listing, fingerprint, identity, expires):
    records = [line.split(":") for line in listing.splitlines() if line]
    keys = [r for r in records if r[0] == "pub"]
    identities = [r[9] for r in records if r[0] == "uid" and len(r) > 9]
    fingerprints = [r[9] for r in records if r[0] == "fpr" and len(r) > 9]
    require(len(keys) == 1 and identities == [identity] and fingerprints == [fingerprint], "Dedicated signing key identity or count differs.")
    key = keys[0]
    require(len(key) > 11 and key[2:4] == ["4096", "1"] and key[1] not in {"r", "e", "d", "i", "n"} and "D" not in key[11], "Signing key is not the approved usable RSA-4096 primary.")
    require("s" in key[11].lower() and key[6].isdigit() and int(key[6]) > time.time(), "Signing key cannot sign or is expired.")
    require(int(key[6]) == expires, "Signing key lifetime differs from the owner setup receipt.")
    require(key[5].isdigit() and 0 < int(key[6]) - int(key[5]) <= 367 * 86400, "Signing key exceeds the approved one-year lifetime.")
    require(not any(r[0] in {"sub", "ssb"} for r in records), "Unexpected signing subkey.")


def valid_signature(status, fingerprint):
    lines = [line[len("[GNUPG:] "):].split() for line in status.splitlines() if line.startswith("[GNUPG:] ")]
    require(not any(r and r[0] in {"BADSIG", "ERRSIG", "EXPSIG", "EXPKEYSIG", "REVKEYSIG", "KEYEXPIRED", "SIGEXPIRED", "FAILURE"} for r in lines), "Signature verification failed.")
    signatures = [r for r in lines if r and r[0] == "VALIDSIG"]
    require(len(signatures) == 1, "Expected exactly one valid signature.")
    r = signatures[0]
    require(len(r) == 11 and r[1] == fingerprint and r[10] == fingerprint and r[5:10] == ["4", "0", "1", "10", "00"], "Signature identity, format or digest differs.")


class GpgSigner:
    def __init__(self, home, fingerprint, phrase, environment, identity, expires):
        self.home, self.fingerprint, self.phrase = home, fingerprint, phrase
        self.identity, self.expires = identity, expires
        self.environment = {k: v for k, v in environment.items() if k not in {"GNUPGHOME", "GPG_AGENT_INFO"}}
        self.environment["LC_ALL"] = "C"
        self.closed = False

    def command(self, arguments, secret=False):
        command = [str(GPG), "--homedir", str(self.home), "--no-options", "--batch", "--rfc4880", "--no-auto-key-retrieve", "--auto-key-locate", "clear"] + arguments
        phrase = (self.phrase + "\n").encode() if secret else None
        result = subprocess.run(command, input=phrase, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=self.environment, timeout=30)
        require(result.returncode == 0 and len(result.stdout) < 65536, "GnuPG signing or validation failed; no secret diagnostics retained.")
        return result.stdout

    def check_identity(self):
        listing = self.command(["--with-colons", "--fixed-list-mode", "--fingerprint", "--list-keys"]).decode("utf-8")
        validate_key_listing(listing, self.fingerprint, self.identity, self.expires)

    def sign(self, payload):
        signature = self.command(["--pinentry-mode", "loopback", "--passphrase-fd", "0", "--local-user", self.fingerprint + "!", "--digest-algo", "SHA512", "--armor", "--detach-sign", "--output", "-", str(payload)], secret=True)
        require(signature.startswith(b"-----BEGIN PGP SIGNATURE-----\n") and signature.rstrip().endswith(b"-----END PGP SIGNATURE-----"), "Expected an armored detached signature.")
        return signature

    def verify(self, signature, payload):
        status = self.command(["--status-fd", "1", "--verify", str(signature), str(payload)]).decode("utf-8")
        valid_signature(status, self.fingerprint)

    def close(self):
        if self.closed:
            return
        self.closed = True
        self.phrase = None
        try:
            result = subprocess.run([str(GPGCONF), "--homedir", str(self.home), "--kill", "gpg-agent"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=self.environment, timeout=15)
            if result.returncode:
                raise CleanupError("Dedicated signing agent cleanup failed.")
        except (OSError, subprocess.SubprocessError):
            raise CleanupError("Dedicated signing agent cleanup failed.") from None


def exclusive_write(path, body):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(body)


@contextmanager
def owner_lock(store):
    protected_path(store, directory=True)
    path = store / ".signing-owner.lock"
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        os.close(descriptor)
        yield
    finally:
        path.unlink()


def stage_signed(repository, destination, manifest, signer, final_check):
    """Sign copies only; accept output atomically after verification and agent cleanup."""
    require(repository.is_dir() and not repository.is_symlink(), "Unsafe unsigned repository.")
    require(not any(p.is_symlink() for p in repository.rglob("*")), "Unsigned repository contains a link.")
    require(not destination.exists() and not destination.is_symlink(), "Signed output already exists; inspect it rather than overwrite.")
    require(re.fullmatch(r"[0-9a-f]{64}", manifest.get("verified_unsigned_inventory_sha256", "")), "Missing verified unsigned inventory identity.")
    stage = Path(tempfile.mkdtemp(prefix=".signed-maven-", dir=destination.parent))
    accepted = False
    signer_closed = False
    try:
        copied = stage / "repository"
        copied.mkdir(mode=0o700)
        expected_files = set()
        signed = []
        for item in manifest["artifacts"]:
            relative = Path(item["path"])
            require(not relative.is_absolute() and ".." not in relative.parts, "Unsafe inventory path.")
            source = repository / relative
            require(source.is_file() and not source.is_symlink(), "Unsafe unsigned payload.")
            body = source.read_bytes()
            require(len(body) == item["bytes"] and hashlib.sha256(body).hexdigest() == item["sha256"], "Unsigned payload changed after inventory validation.")
            target = copied / relative
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            exclusive_write(target, body)
            expected_files.add(relative.as_posix())
            for algorithm in HASHES:
                checksum = source.with_name(source.name + "." + algorithm)
                require(checksum.is_file() and not checksum.is_symlink(), "Unsafe unsigned checksum.")
                value = hashlib.new(algorithm, body).hexdigest()
                require(checksum.read_text().strip() == value, "Unsigned checksum changed.")
                exclusive_write(target.with_name(target.name + "." + algorithm), checksum.read_bytes())
                expected_files.add(relative.as_posix() + "." + algorithm)
            signature = target.with_name(target.name + ".asc")
            signature_bytes = signer.sign(target)
            exclusive_write(signature, signature_bytes)
            signer.verify(signature, target)
            expected_files.add(relative.as_posix() + ".asc")
            for algorithm in HASHES:
                exclusive_write(signature.with_name(signature.name + "." + algorithm), (hashlib.new(algorithm, signature_bytes).hexdigest() + "\n").encode())
                expected_files.add(relative.as_posix() + ".asc." + algorithm)
            require(target.read_bytes() == body and source.read_bytes() == body, "Payload changed during signing.")
            signed.append({**item, "signature_sha256": hashlib.sha256(signature_bytes).hexdigest()})
        require(len(signed) == 28 and len({x["path"] for x in signed}) == 28, "Incomplete signing inventory.")
        actual = {p.relative_to(copied).as_posix() for p in copied.rglob("*") if p.is_file()}
        require(actual == expected_files and not any(p.is_symlink() for p in copied.rglob("*")), "Unexpected signed repository file.")
        signer.close()
        signer_closed = True
        final_check()
        bundle = stage / "central-bundle.zip"
        with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name in sorted(expected_files):
                info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                archive.writestr(info, (copied / name).read_bytes(), compresslevel=9)
        receipt = {"mode": "signed-local", "head": manifest["head"], "tree": manifest["tree"], "version": manifest["version"], "group": manifest["group"], "verified_unsigned_inventory_sha256": manifest["verified_unsigned_inventory_sha256"], "publications": manifest["publications"], "artifacts": signed, "signatures_verified": True, "fingerprint": signer.fingerprint, "bundle_sha256": hashlib.sha256(bundle.read_bytes()).hexdigest(), "bundle_entries": len(expected_files), "remote_distribution_verified": False, "uploaded": False}
        exclusive_write(stage / "verification.json", (json.dumps(receipt, indent=2) + "\n").encode())
        final_check()
        require(not destination.exists() and not destination.is_symlink(), "Signed output appeared during verification.")
        stage.rename(destination)
        accepted = True
        return receipt
    finally:
        try:
            if not signer_closed:
                signer.close()
        finally:
            if not accepted:
                shutil.rmtree(stage)


def require_verified_inventory(previous, manifest, inventory, head):
    require(isinstance(previous, dict) and isinstance(previous.get("stages"), dict), "Invalid unsigned verification receipt.")
    require(previous.get("head") == head and previous.get("version") == manifest["version"] and previous.get("mode") == "unsigned-local" and previous.get("working_tree_dirty") is False and all(previous["stages"].get(k) == "passed" for k in ("metadata", "producer", "jvm-consumer", "android-consumer", "native-consumer")), "Run unsigned local verification on this exact clean head first.")
    require(previous.get("inventory_sha256") == inventory.digest(manifest), "Current Maven bytes differ from the graph that passed consumers; rerun unsigned verification.")


def owner_sign(store, repository, destination, manifest, factory, final_check):
    # The same protected store is shared by every linked worktree on this Mac.
    with owner_lock(store):
        signer = factory()
        try:
            signer.check_identity()
            return stage_signed(repository, destination, manifest, signer, final_check)
        finally:
            signer.close()


def main(root, inventory, environment):
    require(sys.platform == "darwin" and sys.stdin.isatty() and sys.stderr.isatty(), "Signing requires the owner's interactive Mac Terminal; no secrets in command arguments.")
    require(GPG.is_file() and GPGCONF.is_file(), "Approved Homebrew GnuPG is unavailable.")
    output = root / "bridge/build/distribution"
    repository = output / "maven"
    destination = output / "signed-maven"
    for path in (root / "bridge", root / "bridge/build", output, repository):
        require(path.is_dir() and not path.is_symlink(), "Unsafe prepared-artifact directory.")
    require(not destination.exists() and not destination.is_symlink(), "Signed output already exists; do not overwrite it.")
    def git(*arguments):
        return subprocess.check_output(["git", *arguments], cwd=root, text=True, env=environment, timeout=15).strip()
    head = git("rev-parse", "HEAD")
    tree = git("rev-parse", "HEAD^{tree}")
    require(not git("status", "--porcelain"), "Freeze a clean source commit before signing.")
    receipt = output / "verification.json"
    require(receipt.is_file() and not receipt.is_symlink() and receipt.stat().st_size < 1048576, "Unsafe unsigned verification receipt.")
    previous = json.loads(receipt.read_text())
    props = inventory.properties(root / "gradle.properties")
    metadata = inventory.properties(root / "distribution/release.properties")
    manifest = inventory.validate(repository, props["GROUP"], props["VERSION_NAME"], metadata)
    require_verified_inventory(previous, manifest, inventory, head)
    manifest.update(head=head, tree=tree, verified_unsigned_inventory_sha256=previous["inventory_sha256"])
    store = Path.home() / ".config/universal-ai-connector/signing"
    setup = public_setup(store)
    def factory():
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            phrase = getpass.getpass("Existing signing-key passphrase (hidden): ")
        require(phrase and not any(c in phrase for c in "\n\r\0"), "Invalid private passphrase input.")
        signer = GpgSigner(store / "gnupg", setup["fingerprint"], phrase, environment, setup["identity"], setup["expiresAtUnix"])
        phrase = None
        return signer
    def final_check():
        require(git("rev-parse", "HEAD") == head and git("rev-parse", "HEAD^{tree}") == tree and not git("status", "--porcelain"), "Source changed during signing.")
    result = owner_sign(store, repository, destination, manifest, factory, final_check)
    print(json.dumps({"mode": result["mode"], "source_head": head, "signatures_verified": len(result["artifacts"]), "bundle_entries": result["bundle_entries"], "bundle_sha256": result["bundle_sha256"], "uploaded": False}))


if __name__ == "__main__":
    print("Use scripts/check-maven-distribution.sh sign-local from the owner's Terminal.", file=sys.stderr)
    sys.exit(2)
