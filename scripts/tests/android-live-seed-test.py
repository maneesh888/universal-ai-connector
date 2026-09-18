#!/usr/bin/env python3
import importlib.util
import os
from pathlib import Path
import struct
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("seed", ROOT / "scripts/android-live-seed.py")
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)


class BootstrapTest(unittest.TestCase):
    def test_exact_framing_and_gateway_default_rejection(self):
        frame = seed.payload("openrouter", "synthetic-credential", "Vendor/Model@REV", "")
        self.assertEqual(struct.unpack(">I", frame[:4])[0], 1)
        values = []
        cursor = 4
        while cursor < len(frame):
            size = struct.unpack(">I", frame[cursor:cursor + 4])[0]
            cursor += 4
            values.append(frame[cursor:cursor + size].decode())
            cursor += size
        self.assertEqual(values, ["openrouter", "https://openrouter.ai/api/v1", "Vendor/Model@REV", "synthetic-credential"])
        with self.assertRaises(ValueError):
            seed.payload("gateway", "synthetic-credential", "exact", "")

    def test_rejects_incomplete_invalid_and_oversized_input(self):
        for provider, credential, model, base in (
            ("unknown", "value", "exact", ""), ("openai", "", "exact", ""),
            ("openai", "a\nb", "exact", ""), ("openai", "x" * 8193, "exact", ""),
            ("openai", "value", "", ""), ("openai", "value", " exact", ""),
            ("openai", "value", "x" * 257, ""),
            ("gateway", "value", "exact", "https://user:password@example.com/v1"),
            ("gateway", "value", "exact", "http://remote.example/v1"),
            ("gateway", "value", "exact", "https://example.com/v1?key=value"),
        ):
            with self.assertRaises(ValueError):
                seed.payload(provider, credential, model, base)

    def test_child_environment_removes_credentials_and_routing_overrides(self):
        original = {
            "PATH": "/bin", "OPENAI_API_KEY": "synthetic-credential",
            "UAC_ANDROID_SAMPLE_PROOF_CREDENTIAL": "synthetic-credential",
            "GATEWAY_LIVE_BASE_URL": "https://example.com/v1",
            "UAC_IOS_SAMPLE_PROOF_CREDENTIAL": "synthetic-credential",
            "GIT_DIR": "/wrong", "PROOF_CREDENTIAL": "synthetic-credential",
        }
        self.assertEqual({"PATH": "/bin"}, seed.sanitized_environment(original))

    def test_no_opt_in_never_builds_or_opens_device(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(seed.sys, "argv", ["seed", "openai"]), patch.object(seed.subprocess, "run") as run:
            with self.assertRaises(ValueError):
                seed.main()
            run.assert_not_called()

    def test_success_and_failure_cleanup_never_send_secrets_to_subprocess(self):
        head = "a" * 40
        environment = {
            "UAC_ANDROID_SAMPLE_LIVE_PROOF": "1", "UAC_LIVE_EXPECTED_SHA": head,
            "UAC_ANDROID_SAMPLE_PROOF_CREDENTIAL": "synthetic-sensitive-value",
            "UAC_ANDROID_SAMPLE_PROOF_MODEL": "Vendor/Exact@REV",
            "UAC_ADB": "/fake/adb", "OPENAI_API_KEY": "synthetic-sensitive-value",
        }
        for acknowledged in (True, False):
            calls = []
            def run(arguments, **kwargs):
                self.assertNotIn("synthetic-sensitive-value", repr(arguments))
                self.assertNotIn("synthetic-sensitive-value", repr(kwargs))
                calls.append(arguments)
                result = type("Result", (), {})()
                if arguments[0] == "git" and "rev-parse" in arguments:
                    result.stdout = head.encode()
                elif "getprop" in arguments:
                    result.stdout = b"1"
                elif "tcp:0" in arguments:
                    result.stdout = b"12345"
                else:
                    result.stdout = b""
                return result
            with patch.dict(os.environ, environment, clear=True), patch.object(seed.sys, "argv", ["seed", "openai"]), patch.object(seed.subprocess, "run", side_effect=run), patch.object(seed.socket, "create_connection") as connect, patch("builtins.print") as printer:
                connection = connect.return_value.__enter__.return_value
                connection.recv.return_value = b"\x01" if acknowledged else b""
                if acknowledged:
                    seed.main()
                else:
                    with self.assertRaises(ValueError): seed.main()
                self.assertIn("synthetic-sensitive-value".encode(), connection.sendall.call_args.args[0])
                wire = connection.sendall.call_args.args[0]
                self.assertEqual(len(wire) - 4, struct.unpack(">I", wire[:4])[0])
                connection.shutdown.assert_not_called()
                self.assertNotIn("synthetic-sensitive-value", repr(printer.call_args_list))
                self.assertTrue(any("--remove" in c for c in calls))
                self.assertEqual(not acknowledged, any("force-stop" in c for c in calls))


if __name__ == "__main__":
    unittest.main()
