#!/usr/bin/env python3
"""Bind the P8-D case catalog to successful host-native test results.

Report modes are internal gate steps: callers must first run the corresponding
Gradle/Xcode tasks. They do not turn historical reports into fresh execution.
"""

import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "samples/parity/cases.json"
HOSTS = ("android", "jvm-desktop", "ios")
SOURCES = {
    "android": "samples/android/src/test/kotlin/com/myadidi/universalai/samples/android/LiveAiControllerTest.kt",
    "jvm-desktop": "samples/host-controller/src/test/kotlin/com/myadidi/universalai/samples/host/LiveAiControllerTest.kt",
    "ios": "samples/ios/UniversalAiConnectorSampleSupport/Tests/UniversalAiConnectorSampleSupportTests/LiveAiConfigurationViewModelTests.swift",
    "console": "samples/jvm-console/src/test/kotlin/com/myadidi/universalai/samples/jvm/LiveConsoleTest.kt",
    "desktop-storage": "samples/desktop/src/test/kotlin/com/myadidi/universalai/samples/desktop/DesktopVaultTest.kt",
}
REPORTS = {
    "android": "samples/android/build/test-results/testDebugUnitTest",
    "jvm-desktop": "samples/host-controller/build/test-results/test",
    "console": "samples/jvm-console/build/test-results/test",
    "desktop-storage": "samples/desktop/build/test-results/test",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load_catalog(path=CATALOG):
    data = json.loads(path.read_text())
    require(data.get("version") == 1, "Unsupported sample parity catalog version")
    cases = data["cases"]
    require(isinstance(cases, list) and len(cases) >= 10, "Incomplete parity catalog")
    required_ids = {f"P8D-{number:02d}" for number in range(1, 13)}
    ids = set()
    for case in cases:
        require(re.fullmatch(r"P8D-[0-9]{2}", case["id"]), "Invalid parity case ID")
        require(case["id"] not in ids, "Duplicate parity case ID")
        ids.add(case["id"])
        require(bool(case["expectation"].strip()), "Missing observable expectation")
        require(set(case["tests"]) == set(HOSTS), "Each case must cover all controller hosts")
        for host, methods in case["tests"].items():
            validate_methods(host, methods)
    require(required_ids <= ids, "Missing required parity case")
    for host in ("console", "desktop-storage"):
        validate_methods(host, data["consumer_tests"][host])
    return data


def validate_methods(host, methods):
    require(isinstance(methods, list) and methods, f"Missing {host} test binding")
    source = (ROOT / SOURCES[host]).read_text()
    for method in methods:
        require(re.fullmatch(r"[A-Za-z][A-Za-z0-9]+", method), "Invalid test method")
        declaration = rf"\bfunc\s+{method}\s*\(" if host == "ios" else rf"@Test\s+fun\s+{method}\s*\("
        require(re.search(declaration, source), f"Missing {host} test declaration: {method}")


def junit_results(directory):
    files = sorted(directory.glob("TEST-*.xml"))
    require(files, "Missing Gradle test results")
    results = {}
    for file in files:
        suite = ET.parse(file).getroot()
        for test in suite.iter("testcase"):
            # Kotlin/JUnit names include (), unlike XCTest identifiers.
            name = test.attrib["name"].removesuffix("()")
            key = (test.attrib.get("classname", suite.attrib.get("name", "")), name)
            passed = all(test.find(tag) is None for tag in ("failure", "error", "skipped"))
            require(key not in results, "Duplicate Gradle test result")
            results[key] = passed
    return results


def xctest_results(payload):
    results = {}

    def walk(node, suite=""):
        if isinstance(node, dict):
            kind = node.get("nodeType")
            if kind == "Test Suite":
                suite = node.get("name", suite)
            if kind == "Test Case":
                identifier = node.get("nodeIdentifier", "")
                # Xcode reports Class/testMethod() (possibly module-qualified).
                match = re.search(r"(?:^|/|\.)(LiveAiConfigurationViewModelTests)/(\w+)\(\)$", identifier)
                if match:
                    key = (match.group(1), match.group(2))
                    require(key not in results, "Duplicate XCTest result")
                    results[key] = node.get("result") == "Passed"
            for value in node.values():
                if isinstance(value, (dict, list)):
                    walk(value, suite)
        elif isinstance(node, list):
            for value in node:
                walk(value, suite)

    walk(payload)
    require(results, "Missing iOS parity test results")
    return results


def check_methods(results, host, methods):
    class_name = {
        "android": "com.myadidi.universalai.samples.android.LiveAiControllerTest",
        "jvm-desktop": "com.myadidi.universalai.samples.host.LiveAiControllerTest",
        "ios": "LiveAiConfigurationViewModelTests",
        "console": "com.myadidi.universalai.samples.jvm.LiveConsoleTest",
        "desktop-storage": "com.myadidi.universalai.samples.desktop.DesktopVaultTest",
    }[host]
    for method in methods:
        require(results.get((class_name, method)) is True, f"Missing, skipped or failed {host} parity test: {method}")


def main():
    data = load_catalog()
    mode = sys.argv[1] if len(sys.argv) > 1 else "catalog"
    if mode == "catalog":
        print(f"Sample parity catalog validated: {len(data['cases'])} cases, three host controllers.")
        return
    require(mode in ("kotlin", "jvm", "ios"), "Unknown sample parity report mode")
    hosts = HOSTS[:2] if mode == "kotlin" else (("jvm-desktop",) if mode == "jvm" else ("ios",))
    results = {host: junit_results(ROOT / REPORTS[host]) for host in hosts} if mode != "ios" else {
        "ios": xctest_results(json.loads(Path(sys.argv[2]).read_text()))
    }
    for case in data["cases"]:
        for host in hosts:
            check_methods(results[host], host, case["tests"][host])
        print(f"Sample parity {case['id']}: passed ({', '.join(hosts)}).")
    if mode != "ios":
        for host, methods in data["consumer_tests"].items():
            check_methods(junit_results(ROOT / REPORTS[host]), host, methods)
        print("Console and desktop storage consumer parity: passed.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, IndexError, OSError, json.JSONDecodeError, ET.ParseError) as error:
        print(f"Sample parity failed: {error}", file=sys.stderr)
        sys.exit(1)
