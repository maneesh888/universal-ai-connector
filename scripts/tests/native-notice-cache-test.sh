#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$(mktemp -d)"
trap 'rm -rf "$FIXTURE"' EXIT
python3 - "$ROOT" "$FIXTURE" <<'PY'
from pathlib import Path
import sys
root, fixture = map(Path, sys.argv[1:])
source = (root / 'bridge/build.gradle.kts').read_text()
task = source.split('abstract class NativeNoticeInputs', 1)[1].split('tasks.register<NativeNoticeInputs>', 1)[0]
(fixture / 'settings.gradle.kts').write_text('rootProject.name = "native-notice-cache-test"\n')
(fixture / 'input.klib').write_bytes(b'first native input')
(fixture / 'build.gradle.kts').write_text('import java.security.MessageDigest\nabstract class NativeNoticeInputs' + task + '''
tasks.register<NativeNoticeInputs>("noticeInputs") {
    artifacts.from(layout.projectDirectory.file("input.klib"))
    identities.set(listOf("fixture\\tfixture:input:1\\tinput.klib"))
    destination.set(layout.buildDirectory.file("inputs.tsv"))
}
''')
PY
"$ROOT/gradlew" -p "$FIXTURE" --configuration-cache noticeInputs > "$FIXTURE/first.log" 2>&1 || {
  echo 'Native-notice cold-cache regression failed.' >&2; exit 1;
}
printf '%s' 'changed native input' > "$FIXTURE/input.klib"
"$ROOT/gradlew" -p "$FIXTURE" --configuration-cache noticeInputs > "$FIXTURE/second.log" 2>&1 || {
  echo 'Native-notice warm-cache regression failed.' >&2; exit 1;
}
python3 - "$FIXTURE" <<'PY'
from pathlib import Path
import hashlib, sys
fixture = Path(sys.argv[1])
assert 'Reusing configuration cache.' in (fixture / 'second.log').read_text()
actual = (fixture / 'build/inputs.tsv').read_text().strip().split('\t')[-1]
assert actual == hashlib.sha256((fixture / 'input.klib').read_bytes()).hexdigest()
print('Native notice warm-cache input-mutation regression passed.')
PY
