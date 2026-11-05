#!/usr/bin/env python3
"""One opt-in generation, bounded typed diagnostics; no raw upstream text or retries."""
import base64
import datetime
import email.utils
import http.client
import json
import os
import re
import sys
import urllib.error
import urllib.request

URL = "https://openrouter.ai/api/v1/chat/completions"
PROMPT = "Reply with exactly one short word: ready."
MAX_BODY = 65_536
CODE_NAMES = frozenset({"rate_limit_exceeded", "rate_limited", "insufficient_quota", "quota_exceeded", "insufficient_credits", "provider_error", "provider_unavailable", "no_available_provider", "server", "server_error", "invalid_api_key", "invalid_request_error", "context_length_exceeded", "model_not_found", "overloaded_error", "overloaded"})
META_ENUMS = {
    "error_type": CODE_NAMES,
    "provider_code": CODE_NAMES,
    "reason": frozenset({"in_flight_budget_exhausted", "request_exceeds_in_flight_budget", "rate_limit_exceeded", "quota_exceeded"}),
    "limit_source": frozenset({"openrouter_in_flight_budget", "openrouter_guardrail_budget", "openrouter_workspace_budget", "openrouter_key_limit", "openrouter_free_model_daily", "openrouter_free_model_per_minute"}),
    "scope": frozenset({"workspace_default_member", "workspace_default_key", "member", "api_key", "workspace", "key_limit"}),
}
PROVIDERS = frozenset({"OpenAI", "Anthropic", "Google", "Google AI Studio", "Google Vertex", "Azure", "Amazon Bedrock", "DeepInfra", "Together", "TogetherAI", "Fireworks", "Groq", "Cerebras", "SambaNova", "xAI", "Cohere", "Mistral", "NVIDIA", "Cloudflare", "Novita", "SiliconFlow", "Chutes", "Hyperbolic", "Nscale", "Nebius", "Baseten", "Parasail", "Atoma", "OpenRouter"})
RATE_HEADERS = tuple("x-ratelimit-" + suffix for suffix in ("limit", "remaining", "reset", "limit-requests", "remaining-requests", "reset-requests", "limit-tokens", "remaining-tokens", "reset-tokens")) + ("ratelimit-limit", "ratelimit-remaining", "ratelimit-reset")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


def invalid_constant(_):
    raise ValueError("nonfinite JSON")


def parse(body):
    return json.loads(body, object_pairs_hook=unique_object, parse_constant=invalid_constant)


def code(value):
    if type(value) is int and 100 <= value <= 599:
        return value
    return value if type(value) is str and value in CODE_NAMES else None


def message(value):
    # Recognize only fixed phrases; never copy or truncate an upstream message into output.
    if not isinstance(value, str) or len(value) > MAX_BODY:
        return []
    text = value.casefold()
    result = []
    if re.search(r"rate[- ]limited upstream|upstream (?:provider )?(?:is )?(?:rate[- ]limit|capacity)", text):
        result.append("Selected model is rate-limited upstream")
    elif any(s in text for s in ("rate limit", "rate-limit", "too many requests")):
        result.append("Rate limit exceeded")
    if "quota" in text and any(s in text for s in ("exceed", "exhaust", "insufficient")):
        result.append("Quota exceeded")
    if "credit" in text and any(s in text for s in ("insufficient", "not enough", "exhaust")):
        result.append("Insufficient credits reported")
    if "in-flight" in text or "in_flight_budget" in text:
        result.append("In-flight budget mentioned")
    if "provider returned error" in text:
        result.append("Provider returned error")
    if "capacity" in text or "overloaded" in text:
        result.append("Capacity or overload mentioned")
    if any(s in text for s in ("daily", "per-day", "per day")):
        result.append("Daily limit mentioned")
    if any(s in text for s in ("per-minute", "per minute", "limit_rpm")):
        result.append("Per-minute limit mentioned")
    return result


def error_summary(error):
    if not isinstance(error, dict):
        return None
    metadata = error.get("metadata")
    metadata = metadata if isinstance(metadata, dict) else {}
    typed = {name: metadata[name] for name, choices in META_ENUMS.items()
             if isinstance(metadata.get(name), str) and metadata[name] in choices}
    if type(metadata.get("provider_code")) is int and code(metadata["provider_code"]) is not None:
        typed["provider_code"] = metadata["provider_code"]
    if isinstance(metadata.get("provider_name"), str) and metadata["provider_name"] in PROVIDERS:
        typed["provider_name"] = metadata["provider_name"]
    raw = metadata.get("raw")
    raw_error = None
    if isinstance(raw, str) and len(raw) <= MAX_BODY:
        try:
            raw = parse(raw)
        except (ValueError, UnicodeError):
            raw = None
    if isinstance(raw, dict):
        nested = raw.get("error", raw)
        if isinstance(nested, dict):
            raw_error = {"code": code(nested.get("code")), "message_normalized": message(nested.get("message"))}
    # Non-JSON provider raw text can still contribute fixed classification labels, never text.
    raw_message = message(metadata.get("raw")) if raw_error is None else []
    return {"code": code(error.get("code")), "message_normalized": message(error.get("message")),
            "metadata": typed, "upstream_error": raw_error, "upstream_message_normalized": raw_message,
            "unfiltered_text_retained": False}


def header_values(headers, name):
    if headers is None:
        return []
    if hasattr(headers, "get_all"):
        return headers.get_all(name, [])
    return [v for k, v in headers.items() if k.lower() == name]


def header(headers, name):
    values = header_values(headers, name)
    return values[0] if len(values) == 1 and isinstance(values[0], str) and len(values[0]) <= 128 else None


def integer(value):
    if isinstance(value, str) and re.fullmatch(r"[0-9]{1,15}", value):
        return int(value)
    return None


def rate_headers(headers):
    result = {}
    for name in RATE_HEADERS:
        value = header(headers, name)
        number = integer(value)
        if number is not None:
            result[name] = {"number": number}
        elif value and (match := re.fullmatch(r"([0-9]{1,5})(s|m|h)", value)):
            seconds = int(match[1]) * {"s": 1, "m": 60, "h": 3600}[match[2]]
            if seconds <= 86400:
                result[name] = {"duration_seconds": seconds}
    return result


def retry_after(headers):
    value = header(headers, "retry-after")
    seconds = integer(value)
    if seconds is not None and seconds <= 86400:
        return {"seconds": seconds}
    try:
        parsed = email.utils.parsedate_to_datetime(value) if value else None
        if parsed is not None and parsed.tzinfo is not None:
            return {"at_utc": parsed.astimezone(datetime.timezone.utc).isoformat()}
    except (TypeError, ValueError, OverflowError):
        pass
    return None


def summarize(status, headers, body):
    result = {"http_status": status, "rate_limit_headers": rate_headers(headers), "retry_after": retry_after(headers)}
    if len(body) > MAX_BODY:
        return result | {"outcome": "oversized_response"}, 1
    try:
        payload = parse(body)
        if not isinstance(payload, dict):
            raise ValueError("invalid object")
    except (ValueError, UnicodeError):
        return result | {"outcome": "invalid_response"}, 1
    error = error_summary(payload.get("error"))
    if error is not None:
        return result | {"outcome": "provider_error", "error": error}, 1
    choices = payload.get("choices")
    text_received = False
    if isinstance(choices, list) and choices and isinstance(choices[0], dict):
        entry = choices[0].get("message")
        text_received = isinstance(entry, dict) and isinstance(entry.get("content"), str) and bool(entry["content"].strip())
    succeeded = status == 200 and text_received
    return result | {"outcome": "generation_succeeded" if succeeded else "no_successful_text_generation", "text_received": text_received}, 0 if succeeded else 1


def probe(key, model, head, opener=None):
    base = {"head_sha": head if re.fullmatch(r"[0-9a-f]{40}", head) else None,
            "request_attempts": 0, "client_retries": 0, "provider_fallbacks_enabled": False,
            "proof": "isolated direct HTTP diagnostic; not connector live-suite acceptance"}
    if not re.fullmatch(r"[!-~]{1,8192}", key) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,255}", model) or base["head_sha"] is None:
        return base | {"outcome": "invalid_inputs"}, 1
    payload = {"model": model, "messages": [{"role": "user", "content": PROMPT}], "max_tokens": 64,
               "stream": False, "provider": {"allow_fallbacks": False}}
    request = urllib.request.Request(URL, data=json.dumps(payload).encode(), method="POST",
        headers={"Authorization": "Bearer " + key, "Accept": "application/json", "Content-Type": "application/json"})
    base["request_attempts"] = 1
    try:
        client = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        try:
            response = client.open(request, timeout=30)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            status = response.code
            headers = response.headers
            body = response.read(MAX_BODY + 1)
        result, exit_status = summarize(status, headers, body)
        result = base | result
        # A recognized enum/header may coincidentally equal a sensitive input: fail closed.
        serialized = json.dumps(result)
        if any(value in serialized for value in (key, model, PROMPT)):
            return {"outcome": "sensitive_collision_redacted", "request_attempts": 1}, 1
        return result, exit_status
    except (urllib.error.URLError, TimeoutError, OSError, http.client.HTTPException):
        return base | {"outcome": "network_error_outcome_uncertain"}, 1
    except (ValueError, TypeError, UnicodeError):
        return base | {"outcome": "invalid_response"}, 1


def captured_response(envelope):
    """Pure formatter for the real connector's bounded in-memory response capture."""
    import email.message
    if not isinstance(envelope, dict) or type(envelope.get("http_status")) is not int or not 100 <= envelope["http_status"] <= 599:
        raise ValueError("invalid capture")
    pairs = envelope.get("headers")
    encoded = envelope.get("body_base64")
    if not isinstance(pairs, list) or len(pairs) > 64 or not isinstance(encoded, str) or len(encoded) > 90_000:
        raise ValueError("invalid capture")
    headers = email.message.Message()
    for pair in pairs:
        if not isinstance(pair, list) or len(pair) != 2 or not all(isinstance(v, str) for v in pair):
            raise ValueError("invalid header")
        name, value = pair
        if name.lower() in (*RATE_HEADERS, "retry-after") and len(value) <= 128 and not any(ord(c) < 32 or ord(c) == 127 for c in value):
            headers[name] = value
    body = base64.b64decode(encoded, validate=True)
    if len(body) > MAX_BODY:
        result, _ = summarize(envelope["http_status"], headers, body)
    else:
        error_body = body
        if envelope.get("event_stream") is True:
            error_body = b"{}"
            for event in re.split(br"\r?\n\r?\n", body):
                data = b"\n".join(line[5:].lstrip(b" ") for line in event.splitlines() if line.startswith(b"data:"))
                try:
                    payload = parse(data)
                except (ValueError, UnicodeError):
                    continue
                if isinstance(payload, dict) and isinstance(payload.get("error"), dict):
                    error_body = json.dumps({"error": payload["error"]}).encode()
                    break
        if envelope.get("event_stream") is not True:
            try:
                payload = parse(error_body)
                choices = payload.get("choices") if isinstance(payload, dict) else None
                if isinstance(choices, list) and len(choices) == 1 and isinstance(choices[0], dict) and isinstance(choices[0].get("error"), dict):
                    error_body = json.dumps({"error": choices[0]["error"]}).encode()
            except (ValueError, UnicodeError):
                pass
        result, _ = summarize(envelope["http_status"], headers, error_body)
    return result | {"response_source": "actual_connector_transport", "body_capture_complete": envelope.get("body_complete") is True}


def captured_stdin(stream):
    raw = stream.read(100_001)
    if len(raw) > 100_000:
        raise ValueError("oversized capture")
    return captured_response(parse(raw))


if __name__ == "__main__":
    if sys.argv[1:] == ["--captured-stdin"]:
        try:
            result = captured_stdin(sys.stdin.buffer)
            status = 0
        except (ValueError, TypeError, UnicodeError, KeyError):
            result, status = {"outcome": "invalid_connector_capture"}, 1
    elif sys.argv[1:]:
        result, status = {"outcome": "invalid_diagnostic_mode"}, 1
    else:
        result, status = probe(os.environ.get("OPENROUTER_API_KEY", ""), os.environ.get("OPENROUTER_LIVE_MODEL", ""), os.environ.get("UAC_LIVE_EXPECTED_SHA", ""))
    print(json.dumps(result, sort_keys=True))
    sys.exit(status)
