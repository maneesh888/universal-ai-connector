#!/usr/bin/env python3
"""One-use ADB bootstrap. Payload exists only in process/socket memory, never argv or files."""
import os
from pathlib import Path
import re
import secrets
import socket
import struct
import subprocess
import sys
from urllib.parse import urlsplit

STAGE = "input validation"

INPUTS = (
    "UAC_ANDROID_SAMPLE_PROOF_CREDENTIAL",
    "UAC_ANDROID_SAMPLE_PROOF_MODEL",
    "UAC_ANDROID_SAMPLE_PROOF_BASE_URL",
)
PROVIDERS = {
    "openai": "https://api.openai.com/v1",
    "anthropic": "https://api.anthropic.com/v1",
    "openrouter": "https://openrouter.ai/api/v1",
    "gateway": "",
}


def payload(provider, credential, model, base):
    if provider not in PROVIDERS:
        raise ValueError("Invalid provider")
    if not credential or credential.isspace() or len(credential.encode()) > 8192 or any(ord(c) < 32 or ord(c) == 127 for c in credential):
        raise ValueError("Invalid credential")
    if not model or len(model.encode()) > 256 or any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in model):
        raise ValueError("Invalid model")
    base = base or PROVIDERS[provider]
    url = urlsplit(base)
    if (len(base.encode()) > 2048 or not url.hostname or url.username is not None or
            url.password is not None or url.query or url.fragment or
            not url.path.rstrip("/").endswith("/v1") or
            any(c.isspace() or ord(c) < 32 for c in base) or
            not (url.scheme == "https" or (url.scheme == "http" and url.hostname in ("localhost", "127.0.0.1", "::1")))):
        raise ValueError("Invalid base URL")
    if url.port is not None and not 0 < url.port <= 65535:
        raise ValueError("Invalid port")
    values = ("openai-compatible" if provider == "gateway" else provider, base, model, credential)
    return struct.pack(">I", 1) + b"".join(struct.pack(">I", len(v.encode())) + v.encode() for v in values)


def sanitized_environment(environment):
    return {k: v for k, v in environment.items() if not (
        k.startswith(("OPENAI_", "ANTHROPIC_", "OPENROUTER_", "GATEWAY_", "UAC_IOS_", "UAC_ANDROID_SAMPLE_", "SIMCTL_CHILD_", "GIT_")) or
        k in ("UAC_LIVE_ENV_FILE", "KEY_VALUE", "MODEL_VALUE", "BASE_URL_VALUE", "PROOF_CREDENTIAL", "PROOF_MODEL", "PROOF_BASE_URL")
    )}


def main():
    global STAGE
    STAGE = "input validation"
    credential, model, base = (os.environ.pop(name, "") for name in INPUTS)
    environment = dict(os.environ)
    if len(sys.argv) != 2 or environment.get("UAC_ANDROID_SAMPLE_LIVE_PROOF") != "1":
        raise ValueError("Explicit opt-in required")
    provider = sys.argv[1]
    frame = payload(provider, credential, model, base)
    root = Path(__file__).resolve().parent.parent
    clean_env = sanitized_environment(environment)
    # No credential-bearing subprocess: only this process receives the validated seed.
    def run(arguments, capture=False):
        result = subprocess.run(arguments, cwd=root, env=clean_env, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, check=True, timeout=600)
        return result.stdout.decode().strip() if capture else ""

    STAGE = "source validation"
    head = run(["git", "rev-parse", "HEAD"], True)
    if not re.fullmatch(r"[0-9a-f]{40}", head) or head != environment.get("UAC_LIVE_EXPECTED_SHA"):
        raise ValueError("Exact head required")
    if run(["git", "status", "--porcelain", "--untracked-files=all"], True):
        raise ValueError("Clean checkout required")
    adb_path = environment.get("UAC_ADB")
    if not adb_path:
        sdk = environment.get("ANDROID_HOME") or environment.get("ANDROID_SDK_ROOT") or str(Path.home() / "Library/Android/sdk")
        adb_path = str(Path(sdk) / "platform-tools/adb")
    serial = environment.get("UAC_ANDROID_SERIAL", "")
    adb = [adb_path] + (["-s", serial] if serial else [])
    STAGE = "device availability"
    if run(adb + ["shell", "getprop", "sys.boot_completed"], True) != "1":
        raise ValueError("Booted device required")
    STAGE = "Android build"
    run([str(root / "gradlew"), ":samples:android:assembleDebug", "--no-configuration-cache"])
    app = "com.myadidi.universalai.samples.android"
    STAGE = "Android install"
    run(adb + ["install", "-r", str(root / "samples/android/build/outputs/apk/debug/android-debug.apk")])
    name = "uac_live_" + secrets.token_hex(16)
    port = None
    succeeded = False
    try:
        STAGE = "bootstrap launch"
        run(adb + ["shell", "am", "start", "-S", "-W", "-n", app + "/.MainActivity",
                   "--ez", "uac_live_bootstrap", "true", "--es", "uac_live_socket", name])
        port = run(adb + ["forward", "tcp:0", "localabstract:" + name], True)
        if not port.isdecimal() or not 0 < int(port) <= 65535:
            raise ValueError("Invalid forwarding port")
        STAGE = "credential import acknowledgment"
        with socket.create_connection(("127.0.0.1", int(port)), timeout=5) as connection:
            # ADB forwarding does not preserve TCP half-close; use an explicit bounded frame.
            connection.sendall(struct.pack(">I", len(frame)) + frame)
            if connection.recv(1) != b"\x01":
                raise ValueError("Bootstrap rejected")
        STAGE = "final source validation"
        if run(["git", "rev-parse", "HEAD"], True) != head or run(["git", "status", "--porcelain", "--untracked-files=all"], True):
            raise ValueError("Source changed during proof")
        succeeded = True
    finally:
        if port and port.isdecimal():
            run(adb + ["forward", "--remove", "tcp:" + port])
        if not succeeded:
            run(adb + ["shell", "am", "force-stop", app])
    print("Android sample seeded securely; choose Live, Load models, then Test Connection.")
    print("provider=" + provider)
    print("model_identity=seeded")
    print("head_sha=" + head)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Android bootstrap failed during " + STAGE + ". Check opt-in, complete configuration, clean exact head, SDK, and authorized device. No seed details retained.", file=sys.stderr)
        sys.exit(1)
