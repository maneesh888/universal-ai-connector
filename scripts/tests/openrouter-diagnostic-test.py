#!/usr/bin/env python3
"""Offline one-call, redaction and bounded-parser regressions; never uses real credentials."""
import base64
import email.message
import importlib.util
import io
import json
from pathlib import Path
import unittest
import urllib.error

spec = importlib.util.spec_from_file_location("diagnostic", Path(__file__).resolve().parents[1] / "openrouter-diagnostic.py")
diag = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diag)
KEY = "synthetic-key-never-retained"
MODEL = "synthetic-private/model"
HEAD = "a" * 40
PRIVATE = "sensitive-upstream-material"


class Response(io.BytesIO):
    def __init__(self, body, code=200, headers=None):
        super().__init__(body)
        self.code = code
        self.headers = headers or {}


class Opener:
    def __init__(self, response=None, error=None):
        self.response = response
        self.error = error
        self.calls = 0

    def open(self, request, timeout):
        self.calls += 1
        assert request.full_url == diag.URL and request.get_method() == "POST"
        assert request.get_header("Authorization") == "Bearer " + KEY
        payload = json.loads(request.data)
        assert payload == {"model": MODEL, "messages": [{"role": "user", "content": diag.PROMPT}], "max_tokens": 64, "stream": False, "provider": {"allow_fallbacks": False}}
        assert timeout == 30
        if self.error:
            raise self.error
        return self.response


class DiagnosticTests(unittest.TestCase):
    def check_no_private(self, result):
        text = json.dumps(result)
        for value in [KEY, MODEL, PRIVATE, diag.PROMPT, "Bearer", "flagged_input", "request_id"]:
            self.assertNotIn(value, text)

    def test_actual_connector_capture_reuses_redaction_without_a_network_call(self):
        body = json.dumps({"error": {"code": 429, "message": "Rate limit exceeded " + KEY,
                          "metadata": {"provider_name": "Google", "limit_source": "openrouter_free_model_per_minute", "raw": PRIVATE}}}).encode()
        envelope = {"http_status": 429, "headers": [["Retry-After", "60"], ["X-RateLimit-Remaining", "0"], ["Authorization", KEY]], "body_base64": base64.b64encode(body).decode(), "body_complete": True}
        result = diag.captured_stdin(io.BytesIO(json.dumps(envelope).encode()))
        self.assertEqual(result["response_source"], "actual_connector_transport")
        self.assertEqual(result["retry_after"], {"seconds": 60})
        self.assertEqual(result["error"]["metadata"]["provider_name"], "Google")
        self.check_no_private(result)
        envelope["headers"] = [["Retry-After", "60"], ["Retry-After", "61"]]
        self.assertIsNone(diag.captured_response(envelope)["retry_after"])

    def test_actual_stream_error_and_oversized_capture_stay_bounded(self):
        body = b'data: {"error":{"code":429,"message":"rate limited upstream"}}\n\n'
        envelope = {"http_status": 200, "headers": [], "event_stream": True, "body_base64": base64.b64encode(body).decode()}
        result = diag.captured_response(envelope)
        self.assertEqual(result["error"]["code"], 429)
        self.assertFalse(result["body_capture_complete"])
        envelope["body_base64"] = base64.b64encode(b'x' * (diag.MAX_BODY + 1)).decode()
        self.assertEqual(diag.captured_response(envelope)["outcome"], "oversized_response")
        with self.assertRaises(ValueError): diag.captured_stdin(io.BytesIO(b'x' * 100_001))

    def test_upstream_diagnostics_are_typed_not_raw_text(self):
        body = {"error": {"code": 429, "message": "Provider returned error " + KEY + " " + diag.PROMPT,
                "metadata": {"provider_name": "Chutes", "provider_code": "rate_limited", "error_type": "rate_limit_exceeded", "request_id": PRIVATE,
                "raw": MODEL + " is temporarily rate-limited upstream. " + PRIVATE + " " + KEY,
                "flagged_input": diag.PROMPT, "headers": {"Authorization": KEY}}}}
        opener = Opener(Response(json.dumps(body).encode(), 429, {"Retry-After": "60", "X-RateLimit-Remaining": "0", "Set-Cookie": KEY}))
        result, status = diag.probe(KEY, MODEL, HEAD, opener)
        self.assertEqual(status, 1); self.assertEqual(opener.calls, 1)
        self.assertEqual(result["http_status"], 429)
        self.assertEqual(result["retry_after"], {"seconds": 60})
        self.assertEqual(result["rate_limit_headers"], {"x-ratelimit-remaining": {"number": 0}})
        self.assertEqual(result["error"]["metadata"], {"provider_name": "Chutes", "provider_code": "rate_limited", "error_type": "rate_limit_exceeded"})
        self.assertEqual(result["error"]["upstream_message_normalized"], ["Selected model is rate-limited upstream"])
        self.check_no_private(result)

    def test_nested_provider_code_is_allowlisted_unknown_text_omitted(self):
        error = {"code": "rate_limit_exceeded", "message": PRIVATE, "metadata": {"provider_name": KEY, "reason": PRIVATE,
                 "raw": json.dumps({"error": {"code": "insufficient_quota", "message": "You exceeded your current quota. " + KEY}})}}
        result = diag.error_summary(error)
        self.assertEqual(result["metadata"], {})
        self.assertEqual(result["message_normalized"], [])
        self.assertEqual(result["upstream_error"], {"code": "insufficient_quota", "message_normalized": ["Quota exceeded"]})
        self.check_no_private(result)
        self.assertIsNone(diag.error_summary({"code": PRIVATE})["code"])
        self.assertIsNone(diag.error_summary({"code": True})["code"])

    def test_success_never_retains_model_content_or_provider_payload(self):
        opener = Opener(Response(json.dumps({"choices": [{"message": {"content": PRIVATE}}], "model": MODEL, "provider": PRIVATE}).encode()))
        result, status = diag.probe(KEY, MODEL, HEAD, opener)
        self.assertEqual(status, 0); self.assertEqual(opener.calls, 1)
        self.assertEqual(result["outcome"], "generation_succeeded")
        self.assertTrue(result["text_received"]); self.check_no_private(result)

    def test_daily_vs_minute_classification_never_copies_numbers_or_text(self):
        result = diag.error_summary({"code": 429, "message": "Rate limit exceeded: free-models-per-day " + PRIVATE,
                                     "metadata": {"provider_code": 429}})
        self.assertEqual(result["message_normalized"], ["Rate limit exceeded", "Daily limit mentioned"])
        self.assertEqual(result["metadata"], {"provider_code": 429})
        self.assertEqual(diag.message("Rate limit exceeded per minute " + KEY), ["Rate limit exceeded", "Per-minute limit mentioned"])
        self.check_no_private(result)

    def test_http_errors_and_redirects_do_not_retry(self):
        for status in [302, 401, 429, 503]:
            error = urllib.error.HTTPError(diag.URL, status, PRIVATE, {"Location": "https://untrusted.invalid/" + KEY}, io.BytesIO(b'{"error":{"message":"unrecognized private message"}}'))
            opener = Opener(error=error)
            result, exit_code = diag.probe(KEY, MODEL, HEAD, opener)
            self.assertEqual(exit_code, 1); self.assertEqual(opener.calls, 1)
            self.assertEqual(result["http_status"], status); self.check_no_private(result)
        self.assertIsNone(diag.NoRedirect().redirect_request(None, None, 302, "", {}, "https://untrusted.invalid"))

    def test_invalid_duplicate_oversized_nonfinite_bodies_fail_without_echo(self):
        for body in [PRIVATE.encode(), b'{"error":{},"error":{}}', b'{"error":{"code":NaN}}', b'[]', b'x' * (diag.MAX_BODY + 1)]:
            opener = Opener(Response(body, 429))
            result, status = diag.probe(KEY, MODEL, HEAD, opener)
            self.assertEqual(status, 1); self.assertEqual(opener.calls, 1); self.check_no_private(result)

    def test_header_strings_are_not_copied_and_duplicates_are_omitted(self):
        headers = email.message.Message()
        for key, value in [("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"), ("X-RateLimit-Limit", "100"), ("X-RateLimit-Limit", "200"), ("X-RateLimit-Remaining", PRIVATE), ("X-RateLimit-Reset", "30s"), ("Authorization", KEY)]:
            headers[key] = value
        self.assertEqual(diag.rate_headers(headers), {"x-ratelimit-reset": {"duration_seconds": 30}})
        self.assertEqual(diag.retry_after(headers), {"at_utc": "2026-10-21T07:28:00+00:00"})
        for value in [PRIVATE, "99999999999999999999", "-1", "NaN", "0001-01-01T00:00:00+23:00"]:
            self.assertIsNone(diag.retry_after({"retry-after": value}))

    def test_invalid_inputs_make_no_call_and_network_error_is_uncertain(self):
        opener = Opener()
        for key, model, head in [(KEY + "\n", MODEL, HEAD), (KEY, "bad\nmodel", HEAD), (KEY, MODEL, PRIVATE)]:
            result, status = diag.probe(key, model, head, opener)
            self.assertEqual(status, 1); self.assertEqual(opener.calls, 0); self.check_no_private(result)
        opener = Opener(error=urllib.error.URLError(KEY + PRIVATE))
        result, status = diag.probe(KEY, MODEL, HEAD, opener)
        self.assertEqual(status, 1); self.assertEqual(opener.calls, 1)
        self.assertEqual(result["outcome"], "network_error_outcome_uncertain"); self.check_no_private(result)

    def test_sensitive_collision_in_recognized_field_fails_closed(self):
        class MatchingOpener:
            def open(self, request, timeout):
                return Response(b'{"error":{"code":429,"metadata":{"provider_name":"Chutes"}}}', 429)
        result, status = diag.probe(KEY, "Chutes", HEAD, MatchingOpener())
        self.assertEqual(status, 1)
        self.assertEqual(result, {"outcome": "sensitive_collision_redacted", "request_attempts": 1})


if __name__ == "__main__": unittest.main()
