#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fixture="$root/test/parity/diagnostics.json"
(cd "$root/green" && bb ../scripts/diagnostics-green.clj "$fixture") > "$tmp/green"
(cd "$root/red" && bun ../scripts/diagnostics-red.ts "$fixture") > "$tmp/red"
(cd "$root/blue" && uv run python ../scripts/diagnostics-blue.py "$fixture") > "$tmp/blue"
diff -u "$tmp/green" "$tmp/red"
diff -u "$tmp/green" "$tmp/blue"
python3 - "$fixture" "$tmp/green" <<'PYTHON'
import json, sys
with open(sys.argv[1]) as source:
    cases = json.load(source)
with open(sys.argv[2]) as source:
    actual = [json.loads(line) for line in source]
expected = [[case["name"], 1, case["expected"]] for case in cases]
assert actual == expected, "compute diagnostics differs from the expected contract"
print(f"green, red, and blue agree on {len(cases)} compute diagnostics cases")
PYTHON
