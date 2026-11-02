#!/usr/bin/env python3
"""Validate the complete unsigned local publication graph, never a Central release claim."""
import hashlib
import io
import json
import stat
from pathlib import PurePosixPath
from pathlib import Path
import re
import sys
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class InventoryError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InventoryError(message)


def properties(path):
    result = {}
    for line in path.read_text().splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            require(key not in result, "Duplicate metadata property.")
            result[key] = value
    return result


def reference(directory, url, repository):
    parsed = urllib.parse.urlsplit(url)
    require(not any([parsed.scheme, parsed.netloc, parsed.query, parsed.fragment]), "Metadata reference must be a local relative path.")
    path = (directory / urllib.parse.unquote(parsed.path)).resolve()
    require(path.is_relative_to(repository.resolve()) and path.is_file(), "Missing or escaped metadata reference.")
    return path


def archive_entries(path):
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)) and len(names) < 100_000, "Duplicate or excessive archive entries.")
            require(sum(i.file_size for i in archive.infolist()) < 128 * 1024 * 1024, "Oversized artifact contents.")
            return names
    except zipfile.BadZipFile:
        raise InventoryError("Invalid artifact archive.") from None



def licensed_archive(body, license_bytes, nested_android=False):
    """Retain every original entry, adding only the unchanged project license."""
    source = io.BytesIO(body)
    output = io.BytesIO()
    try:
        opened = zipfile.ZipFile(source)
    except zipfile.BadZipFile:
        raise InventoryError("Invalid artifact archive.") from None
    with opened as archive:
        infos = archive.infolist()
        require(len(infos) == len({entry.filename for entry in infos}) and len(infos) < 100_000, "Invalid archive entries.")
        require(sum(entry.file_size for entry in infos) < 128 * 1024 * 1024, "Oversized archive.")
        for entry in infos:
            path = PurePosixPath(entry.filename)
            require(not path.is_absolute() and ".." not in path.parts and "\\" not in entry.filename and not stat.S_ISLNK(entry.external_attr >> 16), "Unsafe archive path.")
        has_license = "META-INF/LICENSE" in archive.namelist()
        if has_license:
            require(archive.read("META-INF/LICENSE") == license_bytes, "Existing project license differs.")
        changed = not has_license
        require(not any(re.fullmatch(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)", entry.filename, re.I) for entry in infos), "Refusing to rewrite a signed archive.")
        with zipfile.ZipFile(output, "w") as prepared:
            prepared.comment = archive.comment
            for entry in infos:
                data = archive.read(entry)
                if nested_android and entry.filename == "classes.jar":
                    transformed = licensed_archive(data, license_bytes)
                    changed = changed or transformed != data
                    data = transformed
                prepared.writestr(entry, data)
            if not has_license:
                entry = zipfile.ZipInfo("META-INF/LICENSE", (1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = (stat.S_IFREG | 0o644) << 16
                prepared.writestr(entry, license_bytes)
    return output.getvalue() if changed else body


def prepare_unsigned(repository, license_bytes):
    """Prepare only the managed local unsigned graph; never sign or upload."""
    repository = Path(repository)
    require(repository.is_dir() and not repository.is_symlink(), "Unsafe local repository.")
    entries = list(repository.rglob("*"))
    require(not any(path.is_symlink() for path in entries), "Unsafe repository entry.")
    require(not any(path.suffix == ".asc" for path in entries), "Refusing to rewrite signed artifacts.")
    payloads = [path for path in entries if path.is_file() and path.suffix in {".pom", ".module", ".jar", ".aar", ".klib", ".json"}]
    for path in payloads:
        if path.suffix in {".jar", ".aar", ".klib"}:
            body = licensed_archive(path.read_bytes(), license_bytes, nested_android=path.suffix == ".aar")
            path.write_bytes(body)
    for path in payloads:
        if path.suffix != ".module":
            continue
        module = json.loads(path.read_text())
        for variant in module["variants"]:
            for file in variant.get("files", []):
                target = reference(path.parent, file["url"], repository)
                require(target.parent == path.parent.resolve(), "Variant artifact escaped its coordinate.")
                body = target.read_bytes()
                file["size"] = len(body)
                for algorithm in ("md5", "sha1", "sha256", "sha512"):
                    if algorithm in file or algorithm == "sha256":
                        file[algorithm] = hashlib.new(algorithm, body).hexdigest()
        path.write_text(json.dumps(module, indent=2) + "\n")
    for path in payloads:
        body = path.read_bytes()
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            path.with_name(path.name + "." + algorithm).write_text(hashlib.new(algorithm, body).hexdigest() + "\n")


def verify_project_license(path, license_bytes):
    with zipfile.ZipFile(path) as archive:
        require("META-INF/LICENSE" in archive.namelist() and archive.read("META-INF/LICENSE") == license_bytes, "Missing or changed project license.")


def validate(repository, group, version, metadata):
    require(group == "com.myadidi" and re.fullmatch(r"0\.1\.0-alpha\.0\.p8\.[1-9][0-9]*", version), "Invalid candidate identity.")
    require(metadata.get("RELEASE_SCOPE") == "libraries-first", "Publication scope is not the approved library scope.")
    repository = Path(repository).resolve()
    base = metadata["MAVEN_ARTIFACT_ID"]
    expected = {base: "jar", base + "-jvm": "jar", base + "-android": "aar", base + "-iosarm64": "klib", base + "-iossimulatorarm64": "klib"}
    group_dir = repository / group.replace(".", "/")
    require(group_dir.is_dir(), "Publication group is missing.")
    require({p.name for p in group_dir.iterdir() if p.is_dir()} == set(expected), "Missing or unexpected publication target.")
    root_module = group_dir / base / version / f"{base}-{version}.module"
    references = set()
    inventory = []
    for artifact, extension in expected.items():
        directory = group_dir / artifact / version
        stem = f"{artifact}-{version}"
        pom_path = directory / (stem + ".pom")
        require(pom_path.is_file(), "Required POM is missing.")
        pom_bytes = pom_path.read_bytes()
        require(len(pom_bytes) < 1024 * 1024 and b"<!DOCTYPE" not in pom_bytes.upper(), "Unsafe POM encoding.")
        try:
            pom = ET.fromstring(pom_bytes)
        except ET.ParseError:
            raise InventoryError("Invalid POM.") from None
        def text(path):
            return (pom.findtext(path, "", NS)).strip()
        required = {
            "m:groupId": group, "m:artifactId": artifact, "m:version": version,
            "m:name": metadata["POM_NAME"], "m:description": metadata["POM_DESCRIPTION"], "m:url": metadata["POM_URL"],
            "m:licenses/m:license/m:name": metadata["POM_LICENSE_NAME"], "m:licenses/m:license/m:url": metadata["POM_LICENSE_URL"],
            "m:developers/m:developer/m:id": metadata["POM_DEVELOPER_ID"], "m:developers/m:developer/m:name": metadata["POM_DEVELOPER_NAME"],
            "m:developers/m:developer/m:url": metadata["POM_DEVELOPER_URL"],
            "m:scm/m:url": metadata["POM_SCM_URL"], "m:scm/m:connection": metadata["POM_SCM_CONNECTION"],
            "m:scm/m:developerConnection": metadata["POM_SCM_DEVELOPER_CONNECTION"],
        }
        require(all(text(k) == v for k, v in required.items()), "POM coordinate or required metadata drift.")
        for dependency in pom.findall("m:dependencies/m:dependency", NS):
            if dependency.findtext("m:groupId", "", NS) == group:
                require(dependency.findtext("m:artifactId", "", NS) in expected and dependency.findtext("m:version", "", NS) == version, "Unpublished internal POM dependency.")
        module_path = directory / (stem + ".module")
        require(module_path.is_file(), "Required Gradle metadata is missing.")
        module = json.loads(module_path.read_text())
        component = module["component"]
        require(component["group"] == group and component["version"] == version, "Gradle component identity drift.")
        if artifact == base:
            require(component["module"] == base and "url" not in component, "Invalid root component.")
        elif "url" in component:
            require(component["module"] == base and reference(directory, component["url"], repository) == root_module.resolve(), "Invalid target-to-root component link.")
        else:
            require(component["module"] == artifact, "Invalid target component.")
        for variant in module["variants"]:
            if "available-at" in variant:
                target = variant["available-at"]
                require(artifact == base and target["group"] == group and target["version"] == version and target["module"] in expected and target["module"] != base, "Invalid publication graph target.")
                target_path = group_dir / target["module"] / version / f"{target['module']}-{version}.module"
                require(reference(directory, target["url"], repository) == target_path.resolve(), "Publication graph URL drift.")
                references.add(target["module"])
            for file in variant.get("files", []):
                path = reference(directory, file["url"], repository)
                require(path.parent == directory.resolve(), "Variant artifact escaped its coordinate.")
                body = path.read_bytes()
                require(file["size"] == len(body), "Gradle artifact size drift.")
                require(file.get("sha256") == hashlib.sha256(body).hexdigest(), "Gradle artifact checksum drift.")
        primary = directory / f"{stem}.{extension}"
        sources = directory / f"{stem}-sources.jar"
        docs = directory / f"{stem}-javadoc.jar"
        require(all(p.is_file() for p in [primary, sources, docs]), "Missing binary, sources or documentation artifact.")
        expected_names = {primary.name, sources.name, docs.name, pom_path.name, module_path.name}
        if extension == "klib":
            expected_names.add(stem + "-metadata.jar")
        if artifact == base:
            tooling = directory / (stem + "-kotlin-tooling-metadata.json")
            expected_names.add(tooling.name)
            require(tooling.is_file() and isinstance(json.loads(tooling.read_text()), dict), "Missing or invalid Kotlin tooling metadata.")
        allowed_names = expected_names | {n + "." + h for n in expected_names for h in ["md5", "sha1", "sha256", "sha512"]}
        require(all(p.name in allowed_names for p in directory.iterdir()), "Unexpected artifact or signature in unsigned repository.")
        license_bytes = (ROOT / "LICENSE").read_bytes()
        for name in expected_names:
            candidate = directory / name
            if candidate.suffix in {".jar", ".aar", ".klib"}:
                archive_entries(candidate)
                verify_project_license(candidate, license_bytes)
        source_names = archive_entries(sources)
        require(any(n.endswith("UniversalAiConnector.kt") for n in source_names) and any(n.endswith("UniversalAiConnectorVersion.kt") for n in source_names), "Incomplete canonical sources.")
        version_files = [n for n in source_names if n.endswith("UniversalAiConnectorVersion.kt")]
        require(len(version_files) == 1, "Ambiguous generated version source.")
        with zipfile.ZipFile(sources) as archive:
            declaration = archive.read(version_files[0]).decode("utf-8")
        require(re.findall(r'const val UNIVERSAL_AI_CONNECTOR_VERSION: String = "([^"]+)"', declaration) == [version], "Source artifact version drift.")
        doc_names = archive_entries(docs)
        require("index.html" in doc_names and "META-INF/LICENSE" in doc_names and len(doc_names) > 2, "Empty documentation or missing license.")
        names = archive_entries(primary)
        if artifact == base:
            require(any(n.endswith("kotlin-project-structure-metadata.json") for n in names) and any(n.endswith(".knm") for n in names), "Root artifact contains no Kotlin metadata.")
        if artifact.endswith("-jvm"):
            require("com/myadidi/universalai/connector/UniversalAiConnector.class" in names, "Public JVM client is missing.")
        if artifact.endswith("-android"):
            require("classes.jar" in names and "AndroidManifest.xml" in names, "Invalid Android AAR.")
            with zipfile.ZipFile(primary) as aar, zipfile.ZipFile(io.BytesIO(aar.read("classes.jar"))) as classes:
                require("com/myadidi/universalai/connector/UniversalAiConnector.class" in classes.namelist(), "Public Android client is missing.")
                require("META-INF/LICENSE" in classes.namelist() and classes.read("META-INF/LICENSE") == license_bytes, "Android classes project license is missing.")
        if extension == "klib":
            require(any(n.endswith("/manifest") for n in names) and any("linkdata" in n for n in names), "Invalid native publication.")
        for path in sorted(directory.iterdir()):
            if path.suffix not in {".pom", ".module", ".jar", ".aar", ".klib", ".json"}:
                continue
            require(path.is_file() and not path.is_symlink(), "Unsafe artifact file.")
            body = path.read_bytes()
            for algorithm in ["md5", "sha1", "sha256", "sha512"]:
                checksum = path.with_name(path.name + "." + algorithm)
                require(checksum.is_file() and checksum.read_text().strip() == hashlib.new(algorithm, body).hexdigest(), "Missing or incorrect publication checksum.")
            inventory.append({"path": path.relative_to(repository).as_posix(), "bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()})
    require(references == set(expected) - {base}, "Root metadata does not reference every target.")
    return {"mode": "unsigned-local", "group": group, "version": version, "publications": sorted(expected), "artifacts": inventory, "signatures_verified": False, "remote_distribution_verified": False}


if __name__ == "__main__":
    try:
        props = properties(ROOT / "gradle.properties")
        result = validate(Path(sys.argv[1]), props["GROUP"], props["VERSION_NAME"], properties(ROOT / "distribution/release.properties"))
        if len(sys.argv) > 2:
            Path(sys.argv[2]).write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps({"mode": result["mode"], "publication_count": len(result["publications"]), "artifact_count": len(result["artifacts"]), "signatures_verified": False}))
    except (InventoryError, OSError, KeyError, ValueError, TypeError, IndexError):
        print("Local Maven inventory validation failed; no release readiness is established.", file=sys.stderr)
        sys.exit(1)
