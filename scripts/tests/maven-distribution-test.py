#!/usr/bin/env python3
"""Consumer proof and secret-isolation regressions without SDK, network or signing."""
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("distribution_driver", Path(__file__).resolve().parents[1] / "maven-distribution.py")
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class DriverTests(unittest.TestCase):
    def test_consumer_receipt_binds_stable_inventory_and_rejects_changes_during_consumers(self):
        for tamper in [False, True]:
            with self.subTest(tamper=tamper), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / "distribution").mkdir()
                version = "0.1.0-alpha.0.p8.1"
                (root / "gradle.properties").write_text("GROUP=com.myadidi\nVERSION_NAME=" + version + "\n")
                (root / "distribution/release.properties").write_text("MAVEN_ARTIFACT_ID=universal-ai-connector\n")
                (root / "LICENSE").write_text("synthetic license")
                for host in ["jvm", "android", "native"]:
                    (root / "distribution/consumers" / host).mkdir(parents=True)
                output = root / "bridge/build/distribution"
                jar = output / "maven/com/myadidi/universal-ai-connector-jvm" / version / ("universal-ai-connector-jvm-" + version + ".jar")
                state = {"revision": "synthetic original graph"}
                def run(stage, command, logs, environment, stages):
                    stages[stage] = "passed"
                    if stage == "producer":
                        jar.parent.mkdir(parents=True)
                        jar.write_bytes(b"synthetic jar")
                    if stage == "native-consumer" and tamper:
                        state["revision"] = "synthetic rebuilt graph"
                def validate(*args):
                    return {"mode": "unsigned-local", "version": version, "artifacts": [{"path": "synthetic", "sha256": state["revision"]}]}
                def git(command, **kwargs):
                    return "1" * 40 if "rev-parse" in command else b""
                with patch.object(driver, "ROOT", root), patch.object(driver.sys, "platform", "darwin"), patch.object(driver.sys, "argv", ["driver", "local"]), patch.object(driver.subprocess, "check_output", side_effect=git), patch.object(driver, "run", side_effect=run), patch.object(driver.inventory, "prepare_unsigned"), patch.object(driver.inventory, "validate", side_effect=validate), patch("sys.stdout", new_callable=io.StringIO):
                    if tamper:
                        with self.assertRaisesRegex(RuntimeError, "bytes changed"):
                            driver.main()
                    else:
                        driver.main()
                receipt = output / "verification.json"
                if tamper:
                    self.assertFalse(receipt.exists())
                else:
                    self.assertEqual(json.loads(receipt.read_text())["inventory_sha256"], driver.inventory.digest(validate()))

    def test_noninteractive_signing_fails_without_emitting_environment_values(self):
        environment = dict(os.environ)
        value = "synthetic-publishing-secret-must-not-be-printed"
        environment["ORG_GRADLE_PROJECT_mavenCentralPassword"] = value
        result = subprocess.run([sys.executable, "-I", "-B", str(Path(driver.__file__)), "sign-local"], input="", capture_output=True, text=True, env=environment, timeout=15)
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(value, result.stdout + result.stderr)
        self.assertIn("no upload occurred", result.stderr.lower())

    def test_private_logs_restrict_existing_files_and_refuse_symlinks(self):
        with tempfile.TemporaryDirectory() as temporary:
            logs = Path(temporary)
            log = logs / "fixture.log"
            log.write_text("old")
            log.chmod(0o644)
            with patch.object(driver.subprocess, "run") as process:
                process.return_value.returncode = 0
                driver.run("fixture", ["unused"], logs, {}, {})
            self.assertEqual(log.stat().st_mode & 0o777, 0o600)
            target = logs / "untouched"
            target.write_text("preserve")
            log.unlink()
            log.symlink_to(target)
            with self.assertRaises(OSError):
                driver.run("fixture", ["unused"], logs, {}, {})
            self.assertEqual(target.read_text(), "preserve")

    def test_unsigned_build_children_receive_no_provider_or_signing_inputs(self):
        inputs = dict.fromkeys(["OPENAI_API_KEY", "ANTHROPIC_API_KEY", "OPENROUTER_API_KEY", "GATEWAY_API_KEY", "UAC_IOS_SAMPLE_PROOF_CREDENTIAL", "ORG_GRADLE_PROJECT_signingInMemoryKey", "ORG_GRADLE_PROJECT_mavenCentralPassword"], "synthetic-sensitive-value")
        with patch.dict(os.environ, inputs, clear=False):
            environment = driver.safe_environment()
        self.assertFalse(set(inputs) & set(environment))
        self.assertIn("PATH", environment)

    def test_only_the_expected_single_runtime_test_counts_as_success(self):
        output = "\n".join(["INSTRUMENTATION_STATUS: class=example.uac.consumer.CoordinateConsumerTest", "INSTRUMENTATION_STATUS: test=constructsChecksVersionAndClosesWithoutNetwork", "INSTRUMENTATION_STATUS: numtests=1", "INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_CODE: -1"])
        self.assertTrue(driver.android_test_passed(output))
        for broken in [output.replace("CODE: 0", "CODE: -2"), output.replace("numtests=1", "numtests=0"), output.replace("numtests=1", "numtests=10"), output.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: -10"), output.replace("CoordinateConsumerTest", "OtherTest"), output.replace("constructsChecksVersionAndClosesWithoutNetwork", "differentTest"), output + "\nINSTRUMENTATION_STATUS_CODE: -3", output + "\nFAILURES!!!"]:
            self.assertFalse(driver.android_test_passed(broken))

    def test_a_failed_run_cannot_leave_a_previous_success_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "distribution").mkdir()
            (root / "gradle.properties").write_text("GROUP=com.myadidi\nVERSION_NAME=0.1.0-alpha.0.p8.1\n")
            (root / "distribution/release.properties").write_text("MAVEN_ARTIFACT_ID=universal-ai-connector\n")
            output = root / "bridge/build/distribution"
            output.mkdir(parents=True)
            for name in ["verification.json", "inventory.json"]:
                (output / name).write_text('{"status":"old success"}')
            def git(command, **kwargs):
                return "a" * 40 if "rev-parse" in command else b""
            with patch.object(driver, "ROOT", root), patch.object(driver.sys, "platform", "darwin"), patch.object(driver.sys, "argv", ["driver", "local"]), patch.object(driver.subprocess, "check_output", side_effect=git), patch.object(driver, "run", side_effect=RuntimeError("synthetic build failure")):
                with self.assertRaises(RuntimeError): driver.main()
            self.assertFalse((output / "verification.json").exists())
            self.assertFalse((output / "inventory.json").exists())


if __name__ == "__main__": unittest.main()
