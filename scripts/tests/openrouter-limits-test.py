#!/usr/bin/env python3
"""Credential-free failure/redaction checks for the read-only self-key probe."""
import importlib.util
import io
import json
from pathlib import Path
import unittest
import urllib.error

spec = importlib.util.spec_from_file_location("limits_probe", Path(__file__).resolve().parents[1] / "openrouter-limits.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
HEAD = "a" * 40
KEY = "synthetic-test-key"


class Response(io.BytesIO):
    status = 200


class Opener:
    def __init__(self, body=b"", error=None):
        self.body = body
        self.error = error
        self.calls = 0

    def open(self, request, timeout):
        self.calls += 1
        assert request.full_url == "https://openrouter.ai/api/v1/key"
        assert request.get_method() == "GET"
        assert request.get_header("Authorization") == "Bearer " + KEY
        assert timeout == 20
        if self.error:
            raise self.error
        return Response(self.body)


class LimitsTests(unittest.TestCase):
    def test_reports_only_allowlisted_quota_and_reset_fields(self):
        private = "sensitive-label-and-provider-content"
        data = {"label": private, "limit_remaining": 12.5, "usage": 999,
                "free_model_daily_requests": {"used": 50, "limit": 50, "remaining": 0},
                "effective_budget": {"remaining_usd": 0, "resets_at": "2026-10-31T00:00:00Z", "budget_id": private}}
        opener = Opener(json.dumps({"data": data}).encode())
        result, status = probe.probe(KEY, "test-model:free", HEAD, opener)
        self.assertEqual(status, 0)
        self.assertEqual(opener.calls, 1)
        self.assertEqual(result["free_model_daily_requests"]["remaining"], 0)
        self.assertFalse(result["key_credit_cap_exhausted"])
        self.assertTrue(result["effective_budget_exhausted"])
        self.assertTrue(result["configured_free_variant"])
        self.assertEqual(result["effective_budget_resets_at"], "2026-10-31T00:00:00+00:00")
        for value in [private, KEY, "test-model:free", "12.5", "999"]:
            self.assertNotIn(value, json.dumps(result))

    def test_missing_or_invalid_counters_are_unknown_not_zero(self):
        data = {"free_model_daily_requests": {"used": True, "limit": -1, "remaining": 1.5},
                "limit_remaining": float("inf"), "effective_budget": {"resets_at": "private-invalid-value"}}
        result = probe.summarize(data)
        self.assertEqual(result["free_model_daily_requests"], dict.fromkeys(["used", "limit", "remaining"]))
        self.assertIsNone(result["key_credit_cap_exhausted"])
        self.assertIsNone(result["effective_budget_resets_at"])

    def test_http_errors_never_read_or_echo_bodies_and_do_not_retry(self):
        for code in [302, 401, 429, 500]:
            error = urllib.error.HTTPError(probe.URL, code, "private-message", {"Location": "https://untrusted.invalid"}, io.BytesIO(b"private-body"))
            opener = Opener(error=error)
            result, status = probe.probe(KEY, "test", HEAD, opener)
            self.assertEqual(status, 1)
            self.assertEqual(opener.calls, 1)
            self.assertEqual(result["limits_endpoint_status"], code)
            self.assertNotIn("private", json.dumps(result))
        self.assertIsNone(probe.NoRedirect().redirect_request(None, None, 302, "", {}, "https://untrusted.invalid"))

    def test_oversized_and_invalid_json_are_bounded_failures(self):
        for body, reason in [(b"x" * (probe.MAX_BODY + 1), "oversized_response"),
                             (b"private-not-json", "invalid_response"),
                             (b'{"data":{},"data":{}}', "invalid_response"),
                             (b'{"data":{"limit_remaining":NaN}}', "invalid_response")]:
            result, status = probe.probe(KEY, "test", HEAD, Opener(body))
            self.assertEqual(status, 1)
            self.assertEqual(result["probe"], reason)
            self.assertNotIn("private", json.dumps(result))

    def test_invalid_inputs_and_network_failures_cannot_leak_credentials(self):
        opener = Opener()
        result, status = probe.probe("private\nkey", "test", HEAD, opener)
        self.assertEqual(status, 1)
        self.assertEqual(opener.calls, 0)
        self.assertNotIn("private", json.dumps(result))
        result, status = probe.probe(KEY, "test", HEAD, Opener(error=urllib.error.URLError("private-error-value")))
        self.assertEqual(status, 1)
        self.assertEqual(result["probe"], "network_error")
        self.assertNotIn("private", json.dumps(result))


if __name__ == "__main__":
    unittest.main()
