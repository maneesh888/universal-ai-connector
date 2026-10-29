#!/usr/bin/env python3
"""Conservative, credential-free verification policy. Run from a trusted base."""
import json
import re
import subprocess
import sys

DOCS = {b"README.md", b"docs/PRODUCTION_CONSUMPTION.md"}
SHA = re.compile(r"[0-9a-f]{40}")


def git(root, *args):
    return subprocess.check_output(["git", "-C", root, *args], stderr=subprocess.DEVNULL)


def classify(root, base, head):
    # head may be an index tree for pre-commit. The caller supplies its merge base.
    git(root, "cat-file", "-e", base + "^{commit}")
    git(root, "cat-file", "-e", head + "^{tree}")
    raw = git(root, "diff", "--raw", "--no-abbrev", "--no-renames", "--no-ext-diff",
              "-z", base, head)
    if not raw:
        return "full"
    fields = raw.split(b"\0")
    if fields[-1] != b"" or len(fields) % 2 != 1:
        raise ValueError("Malformed Git diff")
    for i in range(0, len(fields)-1, 2):
        metadata, path = fields[i:i+2]
        parts = metadata.split()
        if len(parts) != 5 or not parts[0].startswith(b":"):
            raise ValueError("Malformed Git entry")
        old, new, _, _, status = parts
        # Additions/deletions, renames, symlinks, and mode changes stay full.
        # Plans, ADRs, workflow docs, AGENTS, skills, and all unknown paths stay full.
        if path not in DOCS or status != b"M" or old != b":100644" or new != b"100644":
            return "full"
    return "docs"


def review(record, head):
    """Validate private advisory metadata, never infer a clean/security result."""
    keys = {"route", "base", "head", "scope", "status", "reason", "findings", "private_report"}
    if not isinstance(record, dict) or set(record) != keys:
        raise ValueError("Review metadata fields do not match schema")
    if not SHA.fullmatch(head) or record["head"] != head or not SHA.fullmatch(record["base"]):
        raise ValueError("Review revisions are invalid or stale")
    if record["route"] not in {"app-code-review", "github-code-review", "local-security", "github-security-review", "security-cloud"}:
        raise ValueError("Unknown review route")
    if record["status"] not in {"pending", "completed", "failed", "unavailable", "not-applicable"}:
        raise ValueError("Unknown review status")
    if not all(isinstance(record[k], str) for k in ("scope", "reason", "private_report")) or not record["scope"].strip():
        raise ValueError("Review scope is required")
    if record["status"] != "completed" and not record["reason"].strip():
        raise ValueError("Incomplete or inapplicable review needs a reason")
    if record["status"] == "completed" and not record["private_report"].strip():
        raise ValueError("Completed review needs an inspected report reference")
    if not isinstance(record["findings"], list):
        raise ValueError("Findings must be explicitly listed")
    for finding in record["findings"]:
        if not isinstance(finding, dict) or set(finding) != {"id", "disposition", "rationale"}:
            raise ValueError("Invalid finding disposition")
        if finding["disposition"] not in {"blocking", "fixed", "dismissed", "out-of-scope"}:
            raise ValueError("Unknown finding disposition")
        if not all(isinstance(finding[k], str) and finding[k].strip() for k in finding):
            raise ValueError("Finding needs an ID and independent rationale")
    return True


def main(args):
    if len(args) == 4 and args[0] == "classify":
        print(classify(*args[1:]))
    elif len(args) == 3 and args[0] == "review":
        with open(args[1], encoding="utf-8") as stream:
            record = json.load(stream)
        review(record, args[2])
        print("Review metadata valid; this does not establish completion, acceptance, or security.")
    else:
        raise ValueError("Usage: verification-policy.py classify ROOT BASE TREE | review PRIVATE_JSON HEAD")


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except (ValueError, TypeError, OSError, subprocess.CalledProcessError):
        # Never echo private report content or subprocess output.
        print("Verification policy rejected invalid, incomplete, or stale input.", file=sys.stderr)
        sys.exit(1)
