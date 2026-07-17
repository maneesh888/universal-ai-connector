#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${POC_SIMULATOR_DESTINATION:-}" ]]; then
  echo "$POC_SIMULATOR_DESTINATION"
  exit 0
fi

PREFERRED_NAME="${POC_SIMULATOR_NAME:-iPhone 17 Pro}"
DEVICE_ID="$(
  xcrun simctl list devices available -j |
    POC_SIMULATOR_NAME="$PREFERRED_NAME" /usr/bin/python3 -c '
import json
import os
import re
import sys

payload = json.load(sys.stdin)
preferred_name = os.environ["POC_SIMULATOR_NAME"]
candidates = []
fallbacks = []

for runtime, devices in payload.get("devices", {}).items():
    if "SimRuntime.iOS-" not in runtime:
        continue
    version = tuple(int(part) for part in re.findall(r"\d+", runtime))
    for device in devices:
        if not device.get("isAvailable", False):
            continue
        entry = (device.get("state") == "Booted", version, device["udid"])
        if device.get("name") == preferred_name:
            candidates.append(entry)
        elif device.get("name", "").startswith("iPhone"):
            fallbacks.append(entry)

pool = candidates or fallbacks
if not pool:
    raise SystemExit("No available iOS simulator was found.")

pool.sort(reverse=True)
print(pool[0][2])
'
)"

echo "platform=iOS Simulator,id=$DEVICE_ID"
