#!/usr/bin/env python3
"""Fail-closed publication graph and artifact integrity regressions; no Gradle or credentials."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

spec = importlib.util.spec_from_file_location("inventory", Path(__file__).resolve().parents[1] / "maven-inventory.py")
inv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inv)
META = inv.properties(inv.ROOT / "distribution/release.properties")
GROUP = "com.myadidi"
VERSION = "0.1.0-alpha.0.p8.1"
BASE = "universal-ai-connector"


def zipped(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        for name, value in entries.items():
            archive.writestr(name, value)
    return stream.getvalue()


class InventoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / "repo"
        self.group = self.repo / "com/myadidi"
        self.modules = {BASE: "jar", BASE + "-jvm": "jar", BASE + "-android": "aar", BASE + "-iosarm64": "klib", BASE + "-iossimulatorarm64": "klib"}
        fields = {"name": "POM_NAME", "description": "POM_DESCRIPTION", "url": "POM_URL",
                  "licenses/license/name": "POM_LICENSE_NAME", "licenses/license/url": "POM_LICENSE_URL",
                  "developers/developer/id": "POM_DEVELOPER_ID", "developers/developer/name": "POM_DEVELOPER_NAME", "developers/developer/url": "POM_DEVELOPER_URL",
                  "scm/url": "POM_SCM_URL", "scm/connection": "POM_SCM_CONNECTION", "scm/developerConnection": "POM_SCM_DEVELOPER_CONNECTION"}
        client = "com/myadidi/universalai/connector/UniversalAiConnector.class"
        for module, extension in self.modules.items():
            directory = self.group / module / VERSION
            directory.mkdir(parents=True)
            stem = f"{module}-{VERSION}"
            pom = ET.Element("project", xmlns=inv.NS["m"])
            for key, value in {"groupId": GROUP, "artifactId": module, "version": VERSION, **{k: META[v] for k, v in fields.items()}}.items():
                node = pom
                for part in key.split("/"):
                    found = node.find(part)
                    node = found if found is not None else ET.SubElement(node, part)
                node.text = value
            (directory / (stem + ".pom")).write_bytes(ET.tostring(pom))
            if extension == "aar":
                binary = zipped({"classes.jar": zipped({client: "bytecode"}), "AndroidManifest.xml": "manifest"})
            elif extension == "klib":
                binary = zipped({"default/manifest": "abi", "default/linkdata/module": "metadata"})
            elif module == BASE:
                binary = zipped({"META-INF/kotlin-project-structure-metadata.json": "{}", "common/linkdata/types.knm": "metadata"})
            else:
                binary = zipped({client: "bytecode"})
            primary = directory / (stem + "." + extension)
            primary.write_bytes(binary)
            (directory / (stem + "-sources.jar")).write_bytes(zipped({"UniversalAiConnector.kt": "public API", "UniversalAiConnectorVersion.kt": f'internal const val UNIVERSAL_AI_CONNECTOR_VERSION: String = "{VERSION}"'}))
            (directory / (stem + "-javadoc.jar")).write_bytes(zipped({"index.html": "API documentation", "client.html": "client", "META-INF/LICENSE": "MIT"}))
            component = {"group": GROUP, "module": BASE, "version": VERSION}
            if module != BASE:
                component["url"] = f"../../{BASE}/{VERSION}/{BASE}-{VERSION}.module"
            variants = [{"files": [{"name": "cache-alias", "url": primary.name, "size": len(binary), "sha256": hashlib.sha256(binary).hexdigest()}]}]
            if module == BASE:
                variants += [{"available-at": {"group": GROUP, "module": m, "version": VERSION, "url": f"../../{m}/{VERSION}/{m}-{VERSION}.module"}} for m in self.modules if m != BASE]
                (directory / (stem + "-kotlin-tooling-metadata.json")).write_text('{"schemaVersion":"1.1.0"}')
            (directory / (stem + ".module")).write_text(json.dumps({"component": component, "variants": variants}))
        self.rehash()

    def rehash(self):
        for path in self.repo.rglob("*"):
            if path.is_file() and path.suffix in {".pom", ".module", ".jar", ".aar", ".klib", ".json"}:
                for algorithm in ["md5", "sha1", "sha256", "sha512"]:
                    path.with_name(path.name + "." + algorithm).write_text(hashlib.new(algorithm, path.read_bytes()).hexdigest())

    def run_check(self):
        return inv.validate(self.repo, GROUP, VERSION, META)

    def root_file(self, suffix):
        return self.group / BASE / VERSION / f"{BASE}-{VERSION}{suffix}"

    def test_complete_graph_never_claims_signatures_or_remote_distribution(self):
        result = self.run_check()
        self.assertEqual(len(result["publications"]), 5)
        self.assertFalse(result["signatures_verified"])
        self.assertFalse(result["remote_distribution_verified"])

    def test_missing_referenced_target_fails(self):
        next((self.group / (BASE + "-jvm")).rglob("*.module")).unlink()
        with self.assertRaises(inv.InventoryError): self.run_check()

    def test_remote_or_escaped_metadata_reference_fails(self):
        path = self.root_file(".module")
        value = json.loads(path.read_text())
        value["variants"][1]["available-at"]["url"] = "https://untrusted.invalid/artifact.module"
        path.write_text(json.dumps(value)); self.rehash()
        with self.assertRaises(inv.InventoryError): self.run_check()

    def test_artifact_or_checksum_tampering_fails(self):
        self.root_file(".jar.sha256").write_text("0" * 64)
        with self.assertRaises(inv.InventoryError): self.run_check()
        self.rehash()
        self.root_file(".jar").write_bytes(b"tampered")
        with self.assertRaises(inv.InventoryError): self.run_check()

    def test_license_drift_and_empty_docs_fail(self):
        path = self.root_file(".pom")
        path.write_text(path.read_text().replace(META["POM_LICENSE_NAME"], "Other")); self.rehash()
        with self.assertRaises(inv.InventoryError): self.run_check()
        path.write_text(path.read_text().replace("Other", META["POM_LICENSE_NAME"])); self.rehash()
        self.root_file("-javadoc.jar").write_bytes(zipped({"index.html": "placeholder"})); self.rehash()
        with self.assertRaises(inv.InventoryError): self.run_check()

    def test_source_version_drift_and_unexpected_signature_fail(self):
        path = self.root_file("-sources.jar")
        original = path.read_bytes()
        path.write_bytes(zipped({"UniversalAiConnector.kt": "API", "UniversalAiConnectorVersion.kt": 'const val UNIVERSAL_AI_CONNECTOR_VERSION: String = "0.0.0"'})); self.rehash()
        with self.assertRaises(inv.InventoryError): self.run_check()
        path.write_bytes(original); self.rehash()
        self.root_file(".jar.asc").write_text("not signing proof")
        with self.assertRaises(inv.InventoryError): self.run_check()


if __name__ == "__main__": unittest.main()
