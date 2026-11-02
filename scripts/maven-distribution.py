#!/usr/bin/env python3
"""Local Maven build/inventory/consumers and owner-operated signing. Never uploads."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("inventory", ROOT / "scripts/maven-inventory.py")
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


def safe_environment():
    prefixes = ("OPENAI_", "ANTHROPIC_", "OPENROUTER_", "GATEWAY_", "UAC_", "ORG_GRADLE_PROJECT_signing", "ORG_GRADLE_PROJECT_mavenCentral")
    return {key: value for key, value in os.environ.items() if not key.startswith(prefixes)}


def run(stage, command, logs, environment, receipts):
    print("Local Maven check: " + stage, flush=True)
    log = logs / (stage + ".log")
    descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    os.fchmod(descriptor, 0o600)
    with os.fdopen(descriptor, "w") as output:
        result = subprocess.run(command, cwd=ROOT, env=environment, stdout=output, stderr=subprocess.STDOUT, timeout=1800)
    if result.returncode:
        raise RuntimeError(stage + " failed; inspect private generated diagnostics.")
    receipts[stage] = "passed"
    return log


def adb_path(environment):
    found = shutil.which("adb", path=environment.get("PATH"))
    if found:
        return found
    sdk = environment.get("ANDROID_HOME") or environment.get("ANDROID_SDK_ROOT")
    candidate = Path(sdk) / "platform-tools/adb" if sdk else Path.home() / "Library/Android/sdk/platform-tools/adb"
    if not candidate.is_file():
        raise RuntimeError("Android runtime proof requires the configured SDK adb tool.")
    return str(candidate)


def android_runtime(fixture, serial, logs, environment, receipts):
    if not re.fullmatch(r"emulator-[0-9]{4,5}", serial or ""):
        raise RuntimeError("Android runtime proof requires an explicit emulator serial.")
    adb = adb_path(environment)
    devices = subprocess.check_output([adb, "devices"], env=environment, text=True)
    if not any(line.split() == [serial, "device"] for line in devices.splitlines()):
        raise RuntimeError("The requested emulator is not available.")
    apks = list((fixture / "build/outputs/apk/debug").glob("*.apk"))
    tests = list((fixture / "build/outputs/apk/androidTest/debug").glob("*.apk"))
    if len(apks) != 1 or len(tests) != 1:
        raise RuntimeError("Expected exactly one standalone app and instrumentation APK.")
    run("android-install", [adb, "-s", serial, "install", "-r", str(apks[0])], logs, environment, receipts)
    run("android-test-install", [adb, "-s", serial, "install", "-r", str(tests[0])], logs, environment, receipts)
    log = run("android-runtime", [adb, "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", "example.uac.consumer.CoordinateConsumerTest", "example.uac.consumer.test/androidx.test.runner.AndroidJUnitRunner"], logs, environment, receipts)
    output = log.read_text()
    if not android_test_passed(output):
        receipts["android-runtime"] = "failed"
        raise RuntimeError("Android coordinate runtime did not report exactly one passed test.")
    receipts["android-runtime"] = {"tests": 1, "failures": 0, "device": "emulator", "physical_device": False}


def android_test_passed(output):
    lines = set(output.splitlines())
    return (re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?[0-9]+)\s*$", output, re.M) == ["1", "0"] and
            "INSTRUMENTATION_STATUS: class=example.uac.consumer.CoordinateConsumerTest" in lines and
            "INSTRUMENTATION_STATUS: test=constructsChecksVersionAndClosesWithoutNetwork" in lines and
            "INSTRUMENTATION_STATUS: numtests=1" in lines and
            re.findall(r"^INSTRUMENTATION_CODE: (-?[0-9]+)\s*$", output, re.M) == ["-1"] and
            "FAILURES!!!" not in output and "INSTRUMENTATION_FAILED" not in output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["local", "sign-local"])
    parser.add_argument("--android-runtime", metavar="EMULATOR_SERIAL")
    args = parser.parse_args()
    if args.mode == "sign-local":
        if args.android_runtime:
            parser.error("sign-local does not install or run consumers.")
        signing_spec = importlib.util.spec_from_file_location("maven_signing", ROOT / "scripts/maven-signing.py")
        signing = importlib.util.module_from_spec(signing_spec)
        signing_spec.loader.exec_module(signing)
        try:
            signing.main(ROOT, inventory, safe_environment())
        except signing.CleanupError:
            print("Dedicated signing agent cleanup failed or timed out. No signed output accepted; stop and clear only the dedicated signing home's agent before retrying. No upload occurred.", file=sys.stderr)
            sys.exit(1)
        except (signing.SigningError, OSError, ValueError, subprocess.SubprocessError, EOFError, KeyboardInterrupt, Warning):
            print("Local signing stopped safely; no upload occurred. Check the public setup receipt and prepared source; do not paste secrets.", file=sys.stderr)
            sys.exit(1)
        return
    if sys.platform != "darwin":
        raise RuntimeError("Complete local publication proof currently requires a Mac for the accepted Apple targets.")
    props = inventory.properties(ROOT / "gradle.properties")
    metadata = inventory.properties(ROOT / "distribution/release.properties")
    version = props["VERSION_NAME"]
    environment = safe_environment()
    output = ROOT / "bridge/build/distribution"
    logs = output / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    start_head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    start_dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT))
    for name in ["verification.json", "inventory.json"]:
        (output / name).unlink(missing_ok=True)
    repository = output / "maven"
    if repository.is_symlink():
        raise RuntimeError("Refusing a symlink at the managed local repository path.")
    if repository.exists():
        shutil.rmtree(repository)
    stages = {}
    run("metadata", [str(ROOT / "scripts/check-distribution-metadata.sh")], logs, environment, stages)
    run("producer", [str(ROOT / "gradlew"), ":bridge:publishAllPublicationsToLocalReleaseRepository", ":bridge:checkKotlinAbi", "-PuacSignPublication=false"], logs, environment, stages)
    inventory.prepare_unsigned(repository, (ROOT / "LICENSE").read_bytes())
    stages["license-preparation"] = "passed; unchanged MIT in all own archives"
    result = inventory.validate(repository, props["GROUP"], version, metadata)
    inventory_digest = inventory.digest(result)
    (output / "inventory.json").write_text(json.dumps(result, indent=2) + "\n")
    jar = repository / props["GROUP"].replace(".", "/") / (metadata["MAVEN_ARTIFACT_ID"] + "-jvm") / version / (metadata["MAVEN_ARTIFACT_ID"] + "-jvm-" + version + ".jar")
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    for host in ("jvm", "android", "native"):
        fixture = output / "consumers" / host
        if fixture.exists():
            shutil.rmtree(fixture)
        shutil.copytree(ROOT / "distribution/consumers" / host, fixture)
        command = [str(ROOT / "gradlew"), "-p", str(fixture), "--refresh-dependencies", "-PuacVersion=" + version, "-PuacRepository=" + repository.as_uri()]
        commands = {
            "jvm": ["run", "-PuacExpectedJarSha256=" + digest],
            "android": ["assembleDebug", "assembleDebugAndroidTest"],
            "native": ["compileKotlinIosArm64", "compileKotlinIosSimulatorArm64"],
        }
        command += commands[host]
        run(host + "-consumer", command, logs, environment, stages)
        if host == "android" and args.android_runtime:
            android_runtime(fixture, args.android_runtime, logs, environment, stages)
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if head != start_head:
        raise RuntimeError("HEAD changed during local Maven verification.")
    verified = inventory.validate(repository, props["GROUP"], version, metadata)
    if inventory.digest(verified) != inventory_digest:
        raise RuntimeError("Prepared Maven bytes changed during consumer verification.")
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT))
    receipt = {"head": head, "working_tree_dirty": start_dirty or dirty, "mode": "unsigned-local", "version": version, "inventory_sha256": inventory_digest, "stages": stages, "android_runtime": "passed" if args.android_runtime else "not run; compilation only", "signing_verified": False, "remote_distribution_verified": False}
    (output / "verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, inventory.InventoryError, OSError, ValueError, subprocess.SubprocessError):
        # Deliberately avoid raw subprocess output, provider data and environment values.
        print("Unsigned local Maven verification failed; inspect private build/distribution diagnostics.", file=sys.stderr)
        sys.exit(1)
