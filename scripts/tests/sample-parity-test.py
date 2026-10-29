#!/usr/bin/env python3
"""Regressions for fail-closed test-evidence parsing; no provider inputs."""
import copy
import json
from pathlib import Path
import runpy
import tempfile
import unittest

POLICY = runpy.run_path(str(Path(__file__).resolve().parents[1] / "sample-parity.py"))


class ParityEvidenceTest(unittest.TestCase):
    def test_catalog_requires_every_host_and_existing_assertions(self):
        catalog = POLICY["load_catalog"]()
        for mutation in ("host", "method", "duplicate", "case"):
            data = copy.deepcopy(catalog)
            if mutation == "host":
                del data["cases"][0]["tests"]["ios"]
            elif mutation == "method":
                data["cases"][0]["tests"]["ios"] = ["testNotImplemented"]
            elif mutation == "duplicate":
                data["cases"].append(data["cases"][0])
            else:
                data["cases"].pop()
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "cases.json"
                path.write_text(json.dumps(data))
                with self.assertRaises(ValueError):
                    POLICY["load_catalog"](path)

    def test_junit_missing_failed_skipped_and_duplicate_results_fail(self):
        class_name = "com.myadidi.universalai.samples.host.LiveAiControllerTest"
        for status in ("passed", "failure", "error", "skipped", "missing", "duplicate"):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory:
                case = f'<testcase classname="{class_name}" name="parityExample()">'
                case += "" if status in ("passed", "duplicate") else f"<{status}/>"
                case += "</testcase>"
                if status == "missing":
                    case = ""
                if status == "duplicate":
                    case += case
                (Path(directory) / "TEST-parity.xml").write_text(f"<testsuite>{case}</testsuite>")
                if status == "passed":
                    results = POLICY["junit_results"](Path(directory))
                    POLICY["check_methods"](results, "jvm-desktop", ["parityExample"])
                else:
                    with self.assertRaises(ValueError):
                        results = POLICY["junit_results"](Path(directory))
                        POLICY["check_methods"](results, "jvm-desktop", ["parityExample"])

    def test_xctest_requires_the_expected_class_and_passed_terminal_result(self):
        for state in ("Passed", "Failed", "Skipped", "Expected Failure", None):
            node = {"nodeType": "Test Case", "nodeIdentifier":
                    "UniversalAiConnectorSampleTests/LiveAiConfigurationViewModelTests/testParityExample()",
                    "result": state}
            results = POLICY["xctest_results"]({"testNodes": [{"children": [node]}]})
            if state == "Passed":
                POLICY["check_methods"](results, "ios", ["testParityExample"])
            else:
                with self.assertRaises(ValueError):
                    POLICY["check_methods"](results, "ios", ["testParityExample"])
        for nodes in ([], [{"nodeType": "Test Suite", "result": "Passed"}],
                      [{"nodeType": "Test Case", "nodeIdentifier": "OtherClass/testParityExample()",
                        "result": "Passed"}]):
            with self.assertRaises(ValueError):
                POLICY["xctest_results"]({"testNodes": nodes})


if __name__ == "__main__":
    unittest.main()
