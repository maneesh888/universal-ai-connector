#!/usr/bin/env python3
"""Apple packaging/manifest/API fixtures; no Xcode, network or credentials."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import shutil
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("apple_distribution", Path(__file__).resolve().parents[1] / "apple-distribution.py")
apple = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apple)


def portability_graph(relationships):
    return {"module": {"name": apple.PRODUCT}, "symbols": [
        {"identifier": {"precise": name}, "accessLevel": "public", "kind": {"identifier": "swift.struct"}, "declarationFragments": [{"spelling": "struct " + name}]} for name in ["s:fixture", "s:other"]
    ], "relationships": relationships}


class AppleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.source = self.root / (apple.NAME + ".xcframework")
        self.source.mkdir()
        libraries = []
        for identifier in ["ios-arm64", "ios-arm64-simulator"]:
            library = {"LibraryIdentifier": identifier, "LibraryPath": apple.NAME + ".framework", "SupportedPlatform": "ios", "SupportedArchitectures": ["arm64"]}
            if identifier.endswith("simulator"):
                library["SupportedPlatformVariant"] = "simulator"
            libraries.append(library)
            framework = self.source / identifier / (apple.NAME + ".framework")
            (framework / "Headers").mkdir(parents=True)
            (framework / "Info.plist").write_bytes(plistlib.dumps({"MinimumOSVersion": "17.0"}))
            (framework / apple.NAME).write_bytes(b"synthetic binary; not platform proof")
            (framework / "Headers" / (apple.NAME + ".h")).write_text("synthetic header")
        (self.source / "Info.plist").write_bytes(plistlib.dumps({"AvailableLibraries": libraries}))
        (self.source / "LICENSE").write_text("synthetic project license")
        self.config = {"version": "0.1.0-alpha.0.p8.1", "tag": "v0.1.0-alpha.0.p8.1", "asset": apple.NAME + ".xcframework.zip", "url": apple.REPOSITORY + "/releases/download/v0.1.0-alpha.0.p8.1/" + apple.NAME + ".xcframework.zip", "signing": "unsigned", "debugSymbols": "not included"}

    def test_archive_is_stable_across_input_mtimes_and_roundtrips_bytes(self):
        first, second = self.root / "first.zip", self.root / "second.zip"
        digest = apple.normalized_archive(self.source, first)
        for path in self.source.rglob("*"):
            if path.is_file():
                os.utime(path, (100, 100))
        self.assertEqual(apple.normalized_archive(self.source, second), digest)
        extracted = apple.extract_archive(first, self.root / "extracted")
        self.assertEqual(apple.file_hashes(extracted), apple.file_hashes(self.source))

    def test_wrong_slice_architecture_minimum_and_platform_are_rejected(self):
        path = self.source / "Info.plist"
        original = plistlib.loads(path.read_bytes())
        for key, bad in [("SupportedArchitectures", ["x86_64"]), ("SupportedPlatform", "macos"), ("SupportedPlatformVariant", "unexpected")]:
            value = copy.deepcopy(original)
            value["AvailableLibraries"][0][key] = bad
            path.write_bytes(plistlib.dumps(value))
            with self.assertRaises(apple.AppleError):
                apple.framework_metadata(self.source)
        path.write_bytes(plistlib.dumps(original))
        info = self.source / "ios-arm64" / (apple.NAME + ".framework/Info.plist")
        info.write_bytes(plistlib.dumps({"MinimumOSVersion": "18.0"}))
        with self.assertRaises(apple.AppleError):
            apple.framework_metadata(self.source)

    def test_equivalent_plist_key_and_library_order_does_not_change_archive(self):
        first = self.root / "first.zip"
        digest = apple.normalized_archive(self.source, first)
        info = self.source / "Info.plist"
        value = plistlib.loads(info.read_bytes())
        value["AvailableLibraries"].reverse()
        value = dict(reversed(list(value.items())))
        info.write_bytes(plistlib.dumps(value, sort_keys=False))
        self.assertEqual(apple.normalized_archive(self.source, self.root / "reordered.zip"), digest)

    def test_symlinks_debug_symbols_and_signature_trees_are_not_archived(self):
        link = self.source / "linked"
        link.symlink_to(self.source / "LICENSE")
        with self.assertRaises(apple.AppleError):
            apple.normalized_archive(self.source, self.root / "unsafe.zip")
        link.unlink()
        for name in ["symbols.dSYM/Contents/test", "_CodeSignature/CodeResources"]:
            path = self.source / name
            path.parent.mkdir(parents=True)
            path.write_text("fixture")
            with self.assertRaises(apple.AppleError):
                apple.normalized_archive(self.source, self.root / "unsafe.zip")
            path.unlink()

    def test_unsafe_archive_names_types_and_case_collisions_cannot_escape(self):
        for index, names in enumerate([["../outside"], ["/outside"], ["wrong-root/file"], [apple.NAME + ".xcframework/../outside"], [apple.NAME + ".xcframework/a", apple.NAME + ".xcframework/A"]]):
            archive = self.root / (str(index) + ".zip")
            with zipfile.ZipFile(archive, "w") as output:
                for name in names:
                    info = zipfile.ZipInfo(name)
                    info.external_attr = (stat.S_IFREG | 0o644) << 16
                    output.writestr(info, b"fixture")
            with self.assertRaises(apple.AppleError):
                apple.extract_archive(archive, self.root / ("out-" + str(index)))
        self.assertFalse((self.root.parent / "outside").exists())

    def test_root_manifest_is_exactly_bound_to_approved_url_and_checksum(self):
        (self.root / "distribution").mkdir()
        config = self.config | {"sha256": "a" * 64}
        path = self.root / "distribution/apple-release.json"
        path.write_text(json.dumps(config))
        (self.root / "Package.swift").write_text(apple.manifest_text(self.config, "a" * 64))
        self.assertEqual(apple.declared_manifest(self.root, self.config), config)
        for changed in [{**config, "url": "https://other.invalid/artifact.zip"}, {**config, "unexpected": "fixture"}, {**config, "sha256": "bad"}]:
            path.write_text(json.dumps(changed))
            with self.assertRaises(apple.AppleError):
                apple.declared_manifest(self.root, self.config)

    def test_remote_host_uses_exact_public_package_product_and_no_local_reference(self):
        for remote in [False, True]:
            root = self.root / ("remote" if remote else "local")
            root.mkdir()
            project = apple.project(root, self.root / "package", self.config["version"], remote)
            objects = plistlib.loads((project / "project.pbxproj").read_bytes())["objects"].values()
            products = [o for o in objects if o["isa"] == "XCSwiftPackageProductDependency"]
            self.assertEqual([p["productName"] for p in products], [apple.PRODUCT])
            references = [o for o in objects if o["isa"].endswith("SwiftPackageReference")]
            self.assertEqual(len(references), 1)
            if remote:
                self.assertEqual(references[0]["repositoryURL"], apple.REPOSITORY)
                self.assertEqual(references[0]["requirement"], {"kind": "exactVersion", "version": self.config["version"]})
                self.assertNotIn("relativePath", references[0])

    def test_public_api_tracks_signatures_conformance_and_rejects_bridge_leaks(self):
        graph = {"module": {"name": apple.PRODUCT}, "symbols": [{"identifier": {"precise": "s:fixture"}, "accessLevel": "public", "kind": {"identifier": "swift.func"}, "declarationFragments": [{"spelling": "func version() -> String"}]}], "relationships": [{"kind": "conformsTo", "source": "s:fixture", "target": "s:Sendable"}]}
        before = apple.public_api([graph])
        graph["location"] = {"uri": "file:///private/temporary/path"}
        graph["symbols"][0]["docComment"] = {"lines": []}
        self.assertEqual(apple.public_api([graph]), before)
        graph["symbols"][0]["availability"] = [{"domain": "iOS", "introduced": {"major": 18}}]
        self.assertNotEqual(apple.public_api([graph]), before)
        graph["symbols"][0].pop("availability")
        graph["relationships"][0]["swiftConstraints"] = [{"kind": "conformance", "lhs": "Value", "rhs": "Sendable"}]
        self.assertNotEqual(apple.public_api([graph]), before)
        graph["relationships"][0].pop("swiftConstraints")
        graph["symbols"][0]["declarationFragments"][0]["spelling"] = "func version() -> Int"
        self.assertNotEqual(apple.public_api([graph]), before)
        graph["symbols"][0]["declarationFragments"][0]["spelling"] = "func bridge() -> UACBInternal"
        with self.assertRaises(apple.AppleError):
            apple.public_api([graph])

    def test_remote_pin_requires_both_exact_version_and_source_commit(self):
        record = {"pins": [{"identity": "universal-ai-connector", "state": {"version": self.config["version"], "revision": "a" * 40}}]}
        apple.verify_pins(record, self.config["version"], "a" * 40)
        for version, revision in [("0.1.0-alpha.0.p8.2", "a" * 40), (self.config["version"], "b" * 40)]:
            with self.assertRaises(apple.AppleError):
                apple.verify_pins(record, version, revision)

    def test_api_mismatch_retains_public_differences_without_accepting_or_rewriting_baseline(self):
        expected = {"module": apple.PRODUCT, "symbols": {"s:kept": {"declaration": "func kept()"}, "s:removed": {}}, "relationships": []}
        actual = {"module": apple.PRODUCT, "symbols": {"s:kept": {"declaration": "func kept() -> Int"}, "s:added": {}}, "relationships": [{"kind": "conformsTo", "source": "s:added", "target": "s:Sendable"}]}
        untouched = copy.deepcopy(expected)
        with self.assertRaises(apple.AppleError):
            apple.verify_api(expected, actual, self.root)
        report = json.loads((self.root / "swift-api-comparison.json").read_text())
        self.assertEqual(report["addedSymbols"], ["s:added"])
        self.assertEqual(report["removedSymbols"], ["s:removed"])
        self.assertEqual(report["changedSymbols"], ["s:kept"])
        self.assertTrue(report["relationshipsChanged"])
        self.assertEqual(json.loads((self.root / "swift-public-api-actual.json").read_text()), actual)
        self.assertEqual(expected, untouched)
        apple.verify_api(expected, expected, self.root)
        self.assertFalse((self.root / "swift-api-comparison.json").exists())
        (self.root / "swift-api-comparison.json").symlink_to(self.root / "outside")
        with self.assertRaises(apple.AppleError):
            apple.verify_api(expected, actual, self.root)

    def test_swift_61_and_62_redundant_metatype_relationships_have_one_portable_form(self):
        sendable = {"kind": "conformsTo", "source": "s:fixture", "target": "s:s8SendableP"}
        metatype = sendable | {"target": "s:s16SendableMetatypeP"}
        older = portability_graph([sendable])
        newer = portability_graph([metatype, sendable])
        expected = apple.public_api([older])
        self.assertEqual(apple.public_api([newer]), expected)
        newer["relationships"].reverse()
        newer["symbols"].reverse()
        self.assertEqual(apple.public_api([newer, older]), expected)
        self.assertEqual(apple.public_api([older, newer]), expected)
        self.assertEqual(expected["relationships"][0]["target"], "s:s8SendableP")

    def test_portability_retains_explicit_metatype_and_different_source_constraint_kind_or_protocol(self):
        sendable = {"kind": "conformsTo", "source": "s:fixture", "target": "s:s8SendableP"}
        metatype = sendable | {"target": "s:s16SendableMetatypeP"}
        self.assertEqual(apple.public_api([portability_graph([metatype])])["relationships"][0]["target"], metatype["target"])
        for variant in [metatype | {"source": "s:other"}, metatype | {"swiftConstraints": [{"kind": "conformance", "lhs": "T", "rhs": "P"}]}, metatype | {"kind": "inheritsFrom"}, metatype | {"target": "s:other16SendableMetatypeP"}]:
            actual = apple.public_api([portability_graph([sendable, variant])])
            self.assertEqual(len(actual["relationships"]), 2)

    def test_portability_cannot_hide_sendable_loss_or_other_public_api_changes(self):
        sendable = {"kind": "conformsTo", "source": "s:fixture", "target": "s:s8SendableP"}
        metatype = sendable | {"target": "s:s16SendableMetatypeP"}
        base = portability_graph([sendable, metatype])
        expected = apple.public_api([base])
        variants = [portability_graph([metatype]), portability_graph([]), portability_graph([sendable | {"swiftConstraints": [{"kind": "conformance", "lhs": "T", "rhs": "Q"}]}, metatype]), portability_graph([sendable, metatype, sendable | {"target": "s:Hashable"}])]
        for field, value in [("declarationFragments", [{"spelling": "struct Changed"}]), ("availability", [{"domain": "iOS", "introduced": {"major": 18}}]), ("swiftGenerics", {"constraints": [{"kind": "conformance", "lhs": "T", "rhs": "P"}]})]:
            changed = copy.deepcopy(base)
            changed["symbols"][0][field] = value
            variants.append(changed)
        for graph in variants:
            with self.assertRaises(apple.AppleError):
                apple.verify_api(expected, apple.public_api([graph]), self.root)

    def test_build_proof_is_distinct_from_strict_publisher_and_remote_checksum_binding(self):
        actual = self.config | {"sha256": "a" * 64}
        frozen = self.config | {"sha256": "b" * 64}
        self.assertFalse(apple.archive_binding(actual, frozen, "build"))
        for mode in ["local", "remote", "prepare"]:
            with self.assertRaises(apple.AppleError):
                apple.archive_binding(actual, frozen, mode)
        with self.assertRaises(apple.AppleError):
            apple.archive_binding({**actual, "version": "wrong"}, frozen, "build")

    def test_remote_tag_resolution_follows_only_bounded_annotated_tags(self):
        first = {"object": {"type": "tag", "sha": "a" * 40}}
        second = {"object": {"type": "commit", "sha": "b" * 40}}
        with patch.object(apple.subprocess, "check_output", side_effect=[json.dumps(first).encode(), json.dumps(second).encode()]) as call:
            self.assertEqual(apple.remote_source(self.config), "b" * 40)
            self.assertEqual(call.call_count, 2)
        with patch.object(apple.subprocess, "check_output", return_value=json.dumps(first).encode()):
            with self.assertRaises(apple.AppleError):
                apple.remote_source(self.config)

    def test_children_receive_no_live_signing_central_or_debug_inputs(self):
        values = dict.fromkeys(["OPENAI_API_KEY", "ANTHROPIC_API_KEY", "OPENROUTER_API_KEY", "GATEWAY_API_KEY", "ORG_GRADLE_PROJECT_signingInMemoryKey", "ORG_GRADLE_PROJECT_mavenCentralPassword", "GH_DEBUG"], "synthetic-sensitive-input")
        with patch.dict(os.environ, values):
            self.assertFalse(set(values) & set(apple.environment()))

    def test_runtime_summary_cannot_count_skips_expected_failures_or_no_tests_as_pass(self):
        record = {"result": "Passed", "totalTestCount": 1, "passedTests": 1, "failedTests": 0, "skippedTests": 0, "expectedFailures": 0}
        self.assertEqual(apple.test_summary(record)["passedTests"], 1)
        for broken in [{**record, "skippedTests": 1}, {**record, "expectedFailures": 1}, {**record, "totalTestCount": 0}, {**record, "passedTests": True}, {**record, "result": "Failed"}]:
            with self.assertRaises(apple.AppleError):
                apple.test_summary(broken)

    def test_publication_preflight_requires_strict_bytes_source_and_successful_main_ci(self):
        (self.root / "distribution").mkdir()
        output = self.root / "bridge/build/distribution/apple"
        output.mkdir(parents=True)
        body = b"synthetic attested archive fixture"
        checksum = hashlib.sha256(body).hexdigest()
        config = self.config | {"sha256": checksum}
        (self.root / "distribution/apple-release.json").write_text(json.dumps(config))
        (self.root / "Package.swift").write_text(apple.manifest_text(self.config, checksum))
        (output / self.config["asset"]).write_bytes(body)
        receipt = {"head": "1" * 40, "tree": "2" * 40, "mode": "local", "version": self.config["version"], "signing": "unsigned", "workingTreeDirty": False, "rebuildReproduced": True, "frozenChecksumMatches": True, "consumerTests": {"totalTestCount": 1, "passedTests": 1, "failedTests": 0, "skippedTests": 0, "expectedFailures": 0}, "genericDeviceExecutableLinked": True, "publicSwiftSymbols": 264, "sha256": checksum}
        path = output / "verification.json"
        path.write_text(json.dumps(receipt))
        def process(command, **kwargs):
            if command[:3] == ["git", "rev-parse", "HEAD"]:
                return receipt["head"] + "\n"
            if command[:3] == ["git", "rev-parse", "HEAD^{tree}"]:
                return receipt["tree"] + "\n"
            if command[:2] == ["git", "status"]:
                return ""
            return json.dumps([{"headSha": receipt["head"], "event": "push", "status": "completed", "conclusion": "success"}])
        with patch.object(apple.subprocess, "check_output", side_effect=process), patch.object(apple.subprocess, "run"):
            self.assertFalse(apple.publication_ready(self.root, self.config)["writesPerformed"])
        for bad in [{**receipt, "mode": "build"}, {**receipt, "workingTreeDirty": True}, {**receipt, "rebuildReproduced": False}, {**receipt, "frozenChecksumMatches": False}]:
            path.write_text(json.dumps(bad))
            with self.assertRaises(apple.AppleError), patch.object(apple.subprocess, "check_output") as check:
                apple.publication_ready(self.root, self.config)
            check.assert_not_called()
        path.write_text(json.dumps(receipt))
        (output / self.config["asset"]).write_bytes(b"changed")
        with self.assertRaises(apple.AppleError):
            apple.publication_ready(self.root, self.config)

    def test_remote_notice_helper_exercises_actual_archive_only_cli_without_local_resolution(self):
        root = self.root / 'notice-source'
        (root / 'scripts').mkdir(parents=True)
        shutil.copytree(apple.ROOT / 'distribution/licenses', root / 'distribution/licenses')
        shutil.copyfile(apple.ROOT / 'distribution/release.properties', root / 'distribution/release.properties')
        shutil.copyfile(apple.ROOT / 'LICENSE', root / 'LICENSE')
        shutil.copyfile(apple.ROOT / 'scripts/native-notices.py', root / 'scripts/native-notices.py')
        spec = importlib.util.spec_from_file_location('archive_notices_fixture', root / 'scripts/native-notices.py')
        notices = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(notices)
        manifest = json.loads((root / 'distribution/licenses/manifest.json').read_text())
        resolved = root / 'synthetic-inputs.tsv'
        resolved.write_text(''.join('\t'.join(row[k] for k in ['configuration', 'coordinate', 'artifact', 'sha256']) + '\n' for row in manifest['inputs']))
        destination = root / 'Framework.xcframework'
        destination.mkdir()
        notices.validate(root, resolved, destination)
        resolved.unlink()
        output = root / 'logs'
        output.mkdir()
        apple.verify_notices(root, output, destination)
        (destination / 'LICENSE').write_text('tampered')
        with self.assertRaises(apple.AppleError):
            apple.verify_notices(root, output, destination)


if __name__ == "__main__":
    unittest.main()
