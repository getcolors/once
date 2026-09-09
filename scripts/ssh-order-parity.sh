#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
(cd "$root/blue" && uv run python ../scripts/ssh-order-blue.py) > "$tmp/blue.json"
(cd "$root/red" && bun ../scripts/ssh-order-red.ts) > "$tmp/red.json"
(cd "$root/green" && bb ../scripts/ssh-order-green.clj) > "$tmp/green.json"
for color in blue red green; do diff -u "$root/test/parity/ssh-order.json" "$tmp/$color.json"; done
