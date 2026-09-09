#!/usr/bin/env bash
set -euo pipefail

# The red launcher is the one file here that is copied out and run somewhere
# else, so its interesting behaviour happens in environments this checkout does
# not contain: no node_modules, no manifest, or a manifest pinning a different
# commit. `bun test` cannot reach any of that — it runs inside the checkout,
# where `package-once-red` self-resolves to the working tree through the root
# manifest's name and exports. parity.sh does invoke `./red build`, but only
# from inside that same checkout with dependencies installed, which is the one
# path on which none of the resolution logic runs.
#
# So this builds those environments and runs the launcher against them. Every
# failure it catches is silent: the launcher still starts and still renders, it
# just resolves the wrong commit.

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
launcher="$root/skills/package-once-red/red"
tmp=$(mktemp -d)
cache="$tmp/cache"
trap 'rm -rf "$tmp"' EXIT

checks=0
fail() {
  echo "launcher: FAIL — $*" >&2
  exit 1
}
ok() {
  checks=$((checks + 1))
  echo "  ok — $*"
}

# The pin the launcher carries, and a real older `once` commit standing in for a
# project that pins something else. Both must exist on the remote.
launcher_pin=$(grep -o '"package-once-red": "github:[^"]*"' "$launcher" | grep -o '[0-9a-f]\{40\}')
project_pin=163ce822dbdc0b07b9d547099bc479971959bfbe
[ -n "$launcher_pin" ] || fail "could not read the launcher's own pin"
[ "$launcher_pin" != "$project_pin" ] || fail "fixture pin equals the launcher pin; the precedence check would prove nothing"

# ---------------------------------------------------------------------------
# 1. A checkout of the package must never fall back to a pinned copy.
#
# Inside a checkout the working tree is the point. If this guard breaks, a
# developer whose dependencies are not installed silently exercises the pinned
# commit instead of their own edits, and every other check in this repository
# still passes — the same blind spot parity.sh exists for. Offline: the launcher
# refuses before it would install anything.
# ---------------------------------------------------------------------------
mkdir -p "$tmp/checkout"
cp "$launcher" "$tmp/checkout/red"
printf '{\n  "name": "package-once-red",\n  "private": true\n}\n' >"$tmp/checkout/package.json"

set +e
out=$(cd "$tmp/checkout" && XDG_CACHE_HOME="$cache" ./red --help 2>&1)
code=$?
set -e

[ "$code" -eq 2 ] || fail "checkout without dependencies exited $code, expected 2"
grep -q "bun install --cwd" <<<"$out" || fail "checkout error lost its actionable command: $out"
if [ -d "$cache/package-once-red" ]; then
  fail "checkout without dependencies resolved into the package cache; the working tree would be shadowed"
fi
ok "a checkout refuses to bootstrap and says what to install"

# Everything below resolves real git dependencies. Skip rather than fail when
# GitHub is unreachable, the way the end-to-end suites skip without tofu.
if ! git ls-remote https://github.com/getcolors/once.git HEAD >/dev/null 2>&1; then
  echo "launcher: skipping resolution checks — github is unreachable"
  echo "launcher: $checks check passed"
  exit 0
fi

# ---------------------------------------------------------------------------
# 2. A payload that lands where nothing declares it resolves its own pins.
#
# This is the case that lets a freshly installed skill run without an install
# step, the way ./green and ./blue already do.
# ---------------------------------------------------------------------------
mkdir -p "$tmp/bare"
cp "$launcher" "$tmp/bare/red"

# Assert the precondition rather than assume it: a stray package.json anywhere
# above the temp directory would quietly turn this into a different test.
probe=$tmp/bare
while :; do
  if [ -f "$probe/package.json" ]; then fail "precondition broken: a manifest exists at $probe"; fi
  [ "$probe" = "/" ] && break
  probe=$(dirname "$probe")
done

(cd "$tmp/bare" && XDG_CACHE_HOME="$cache" ./red --help >/dev/null) ||
  fail "a bare launcher could not resolve its own pins"
grep -rqs "$launcher_pin" "$cache"/package-once-red/*/package.json ||
  fail "the bare launcher did not resolve its own pin ($launcher_pin)"
ok "a payload with no manifest resolves the launcher's pins"

# ---------------------------------------------------------------------------
# 3. A project manifest never changes which commit is resolved.
#
# PINS is the single source of versions. A manifest naming a different commit
# must not quietly redirect the launcher, or the payload and the project become
# two records of the same thing — the drift this arrangement exists to remove.
# ---------------------------------------------------------------------------
mkdir -p "$tmp/project"
cp "$launcher" "$tmp/project/red"
cat >"$tmp/project/package.json" <<EOF
{
  "name": "launcher-fixture",
  "private": true,
  "dependencies": {
    "package-once-red": "github:getcolors/once#$project_pin",
    "red": "github:getcolors/red#b434e37568b91228ef14c2271f6fbeea805ae7ae"
  }
}
EOF

(cd "$tmp/project" && XDG_CACHE_HOME="$cache" ./red --help >/dev/null) ||
  fail "a project carrying an unrelated manifest could not resolve"
if grep -rqs "$project_pin" "$cache"/package-once-red/*/package.json; then
  fail "a manifest pin ($project_pin) overrode PINS; the launcher must ignore manifests"
fi
grep -rqs "$launcher_pin" "$cache"/package-once-red/*/package.json ||
  fail "PINS ($launcher_pin) was not the resolved pin"
ok "a project manifest does not override PINS"

# ---------------------------------------------------------------------------
# 4. Resolution never writes into the project.
#
# A fallback that installed in place would leave node_modules and a lockfile in
# a project that deliberately tracks neither.
# ---------------------------------------------------------------------------
for stray in node_modules bun.lock bun.lockb; do
  if [ -e "$tmp/project/$stray" ]; then fail "resolution created $stray inside the project"; fi
done
ok "resolution leaves the project directory untouched"

# ---------------------------------------------------------------------------
# 5. The cache is keyed by PINS, so a re-pin cannot reuse the old tree.
#
# Stand in for `bb pin` by rewriting one pin in a copy of the launcher. Sharing
# a cache entry across pins is the failure that would leave a re-pinned launcher
# silently running the commit before it.
# ---------------------------------------------------------------------------
before=$(find "$cache/package-once-red" -mindepth 1 -maxdepth 1 -type d | wc -l)
mkdir -p "$tmp/repinned"
sed "s/$launcher_pin/$project_pin/" "$launcher" > "$tmp/repinned/red"
chmod +x "$tmp/repinned/red"
grep -q "$project_pin" "$tmp/repinned/red" || fail "fixture: the re-pin rewrite did not apply"

(cd "$tmp/repinned" && XDG_CACHE_HOME="$cache" ./red --help >/dev/null) ||
  fail "a re-pinned launcher could not resolve"
after=$(find "$cache/package-once-red" -mindepth 1 -maxdepth 1 -type d | wc -l)
[ "$after" -eq $((before + 1)) ] ||
  fail "re-pinning did not create a new cache entry ($before -> $after)"
grep -rqs "$project_pin" "$cache"/package-once-red/*/package.json ||
  fail "the re-pinned commit was never fetched"
ok "a re-pin lands in its own cache entry"

# ---------------------------------------------------------------------------
# 6. A resolved launcher actually renders.
#
# Proves the cached tree is importable, not merely present — the package is
# exports-only, so importing the cache directory by path fails where resolving
# the bare name succeeds.
# ---------------------------------------------------------------------------
(cd "$tmp/project" &&
  XDG_CACHE_HOME="$cache" COLORS_PAR_WORKDIR="$tmp/out" \
    ./red build -f "$root/test/parity/colors.yml" >/dev/null) ||
  fail "a bootstrapped launcher could not run a build"
[ -f "$tmp/out/parity/tofu-compute/nodes/0/node-none.tf.json" ] ||
  fail "the bootstrapped build rendered no compute stage"
ok "a bootstrapped launcher renders a full work tree"

echo "launcher: $checks checks passed"
