#!/usr/bin/env python3
"""Consumer proof and secret-isolation regressions without SDK, network or signing."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("distribution_driver", Path(__file__).resolve().parents[1] / "maven-distribution.py")
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)


class DriverTests(unittest.TestCase):
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
            (root / "gradle.properties").write_text("GROUP=com.myadidi\nVERSION_NAME=0.1.0-0.p8.1\n")
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
