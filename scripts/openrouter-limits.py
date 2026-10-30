#!/usr/bin/env python3
"""One read-only self-key request; never retain raw responses or credential values."""
import datetime
import http.client
import json
import math
import os
import re
import sys
import urllib.error
import urllib.request

URL = "https://openrouter.ai/api/v1/key"
MAX_BODY = 65_536


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def counter(value):
    return value if type(value) is int and 0 <= value <= 10**12 else None


def exhausted(value):
    if type(value) is int:
        return value <= 0
    if type(value) is float and math.isfinite(value):
        return value <= 0
    return None


def reset_time(value):
    if not isinstance(value, str) or len(value) > 64:
        return None
    try:
        parsed = datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            return None
        return parsed.astimezone(datetime.timezone.utc).isoformat()
    except (ValueError, OverflowError):
        return None


def summarize(data):
    if not isinstance(data, dict):
        raise ValueError("invalid data")
    daily = data.get("free_model_daily_requests")
    daily = daily if isinstance(daily, dict) else {}
    budget = data.get("effective_budget")
    budget = budget if isinstance(budget, dict) else {}
    return {
        "free_model_daily_requests": {k: counter(daily.get(k)) for k in ("used", "limit", "remaining")},
        "key_credit_cap_exhausted": exhausted(data.get("limit_remaining")),
        "effective_budget_exhausted": exhausted(budget.get("remaining_usd")),
        "effective_budget_resets_at": reset_time(budget.get("resets_at")),
        "per_minute_and_upstream_capacity": "not reported by this endpoint",
    }


def reject_constant(_):
    raise ValueError("invalid JSON constant")


def reject_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result


def probe(key, model, head, opener=None):
    result = {"head_sha": head, "inference_calls": 0, "configured_free_variant": model.endswith(":free")}
    if not re.fullmatch(r"[!-~]{1,8192}", key) or not re.fullmatch(r"[0-9a-f]{40}", head):
        return result | {"probe": "invalid_inputs"}, 1
    request = urllib.request.Request(URL, headers={"Authorization": "Bearer " + key, "Accept": "application/json"}, method="GET")
    try:
        client = opener or urllib.request.build_opener(NoRedirect())
        with client.open(request, timeout=20) as response:
            result["limits_endpoint_status"] = response.status
            if response.status != 200:
                return result | {"probe": "http_error"}, 1
            body = response.read(MAX_BODY + 1)
        if len(body) > MAX_BODY:
            return result | {"probe": "oversized_response"}, 1
        payload = json.loads(body, parse_constant=reject_constant, object_pairs_hook=reject_duplicates)
        if not isinstance(payload, dict):
            raise ValueError("invalid response")
        return result | {"probe": "read_only_limits", **summarize(payload.get("data"))}, 0
    except urllib.error.HTTPError as failure:
        status = failure.code
        failure.close()
        return result | {"probe": "http_error", "limits_endpoint_status": status}, 1
    except (urllib.error.URLError, TimeoutError, OSError, http.client.HTTPException):
        return result | {"probe": "network_error"}, 1
    except (ValueError, TypeError, UnicodeError):
        return result | {"probe": "invalid_response"}, 1


if __name__ == "__main__":
    output, status = probe(os.environ.get("OPENROUTER_API_KEY", ""), os.environ.get("OPENROUTER_LIVE_MODEL", ""), os.environ.get("UAC_LIVE_EXPECTED_SHA", ""))
    print(json.dumps(output, sort_keys=True))
    sys.exit(status)
