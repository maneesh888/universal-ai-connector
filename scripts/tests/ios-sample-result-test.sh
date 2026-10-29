#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$(mktemp -d "${TMPDIR:-/tmp}/uac-ios-result-test.XXXXXX")"
trap 'rm -rf -- "$FIXTURE"' EXIT
mkdir -p "$FIXTURE/scripts" "$FIXTURE/bin"
cp "$ROOT/scripts/test-ios-sample.sh" "$FIXTURE/scripts/test-ios-sample.sh"
cat > "$FIXTURE/scripts/simulator-destination.sh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' 'platform=iOS Simulator,id=synthetic-simulator'
STUB
cat > "$FIXTURE/scripts/build-xcframework.sh" <<'STUB'
#!/usr/bin/env bash
touch "$UAC_TEST_BUILD_MARKER"
STUB
cat > "$FIXTURE/bin/xcodebuild" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$UAC_TEST_ARGUMENTS"
STUB
chmod +x "$FIXTURE/scripts/"*.sh "$FIXTURE/bin/xcodebuild"
export PATH="$FIXTURE/bin:$PATH"
export UAC_TEST_BUILD_MARKER="$FIXTURE/built"
export UAC_TEST_ARGUMENTS="$FIXTURE/arguments"

# Exercise the Mac's original Bash behavior, including set -u and paths with spaces.
env -u UAC_IOS_SAMPLE_TEST_RESULT_BUNDLE -u UAC_SKIP_XCFRAMEWORK_BUILD \
  /bin/bash "$FIXTURE/scripts/test-ios-sample.sh"
[[ -f "$UAC_TEST_BUILD_MARKER" ]]
! grep -q -- '-resultBundlePath' "$UAC_TEST_ARGUMENTS"
grep -qx 'test' "$UAC_TEST_ARGUMENTS"
grep -qx 'platform=iOS Simulator,id=synthetic-simulator' "$UAC_TEST_ARGUMENTS"

rm "$UAC_TEST_BUILD_MARKER"
UAC_SKIP_XCFRAMEWORK_BUILD=1 \
  UAC_IOS_SAMPLE_TEST_RESULT_BUNDLE="$FIXTURE/results with spaces.xcresult" \
  /bin/bash "$FIXTURE/scripts/test-ios-sample.sh"
[[ ! -f "$UAC_TEST_BUILD_MARKER" ]]
grep -qx -- '-resultBundlePath' "$UAC_TEST_ARGUMENTS"
grep -qx "$FIXTURE/results with spaces.xcresult" "$UAC_TEST_ARGUMENTS"
echo 'iOS sample result-bundle regressions passed.'
