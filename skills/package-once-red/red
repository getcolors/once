#!/usr/bin/env bun
// Thin launcher for package-once-red. Domain logic lives in the installable
// package so the copied skill payload remains testable and replaceable.
import { existsSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { homedir } from "node:os";

// The commits a copied payload must resolve. Managed by `bb pin` — do not edit
// by hand, and keep exactly one occurrence of each: `pin` rewrites the first
// match and a second copy would silently go stale.
//
// These are live code rather than a comment because the launcher resolves them
// itself (see below). They stay out of a bundled package.json in this
// directory, which would halt Bun's upward resolution of `package-once-red` and
// break the development symlink at red/red.
const PINS = {
  "colors-compute-red": "github:getcolors/colors-compute#5040d93672b9f10e7861f527ea552177369c6851",
  "package-once-red": "github:getcolors/once#be8315f4ad84bce47de7a87b39d9facede22a9ba",
};

// PINS is the only source of versions, as green's inline SHAs and blue's PEP
// 723 metadata are for them. A project manifest is never consulted: it would be
// a second record of the same commit, and the two drift.
//
// A static import would fail during resolution, before any line of this file
// runs, with a bare "Cannot find package" naming no fix. The import stays
// dynamic so the failure can be answered instead of reported. The answer cannot
// live in the package for the obvious reason: the package is what is missing.
//
// Ordinary resolution still wins when it succeeds, which is what keeps a
// checkout usable — the same role green's classpath check plays before it calls
// add-deps.

/** The nearest directory at or above `from` holding a package.json, or null. */
function manifestDir(from) {
  let dir = from;
  for (;;) {
    if (existsSync(join(dir, "package.json"))) return dir;
    const parent = dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

function readManifest(dir) {
  try {
    return JSON.parse(readFileSync(join(dir, "package.json"), "utf8"));
  } catch {
    return null;
  }
}

/** Install `PINS` into a cache keyed by their exact specifiers.
 *
 * Keyed by content, so re-pinning lands in a new directory instead of reusing a
 * stale tree, and two projects on different pins never share one. Staged in a
 * sibling and renamed, so concurrent cold starts cannot observe a half-installed
 * tree; whichever loses the rename discards its copy and uses the winner's,
 * which is byte-identical by construction.
 */
function installToCache() {
  const home = process.env.XDG_CACHE_HOME || join(homedir(), ".cache");
  const root = join(home, "package-once-red");
  const key = Bun.hash(JSON.stringify(PINS)).toString(16);
  const target = join(root, key);
  if (existsSync(join(target, "node_modules"))) return { dir: target };

  // Announced only when something is actually fetched: every later run takes
  // the branch above, and a line claiming a first run on each of them would be
  // noise on the way to every command's real output.
  console.error("red: resolving dependencies (first run)");
  mkdirSync(root, { recursive: true });
  const staging = mkdtempSync(join(root, `.${key}.`));
  writeFileSync(
    join(staging, "package.json"),
    `${JSON.stringify({ name: "package-once-red-cache", private: true, dependencies: PINS }, null, 2)}\n`,
  );
  const installed = Bun.spawnSync([process.execPath, "install"], {
    cwd: staging,
    stdout: "ignore",
    stderr: "pipe",
  });
  if (installed.exitCode !== 0) {
    rmSync(staging, { recursive: true, force: true });
    return { error: installed.stderr.toString().trim() };
  }
  try {
    renameSync(staging, target);
  } catch {
    // Another cold start won the race; its tree is equivalent.
    rmSync(staging, { recursive: true, force: true });
  }
  return existsSync(join(target, "node_modules"))
    ? { dir: target }
    : { error: `nothing installed under ${target}` };
}

let once;
try {
  once = await import("package-once-red");
} catch (err) {
  // Only the launcher-as-script may exit; an importer keeps ESM semantics.
  if (err?.code !== "ERR_MODULE_NOT_FOUND" || !import.meta.main) throw err;

  // Resolution starts beside the launcher, not at the caller, so the answer is
  // the same from any subdirectory a colour was invoked from.
  const root = manifestDir(import.meta.dir);
  const manifest = root ? readManifest(root) : null;

  // Inside a checkout of the package itself, the working tree is the point:
  // resolving a pinned copy from the cache would quietly test the pinned commit
  // instead of the edits under test. Say what is missing and stop.
  const inCheckout = manifest?.name === "package-once-red";
  if (inCheckout || process.env.RED_NO_BOOTSTRAP) {
    console.error(
      `red: cannot resolve '${err.specifier}'\n` +
        (root
          ? `dependencies are not installed; run: bun install --cwd ${root}`
          : `no package.json at or above ${import.meta.dir}`),
    );
    process.exit(2);
  }

  const { dir, error } = installToCache();
  if (error) {
    console.error(`red: could not resolve dependencies\n${error}`);
    process.exit(2);
  }
  // Resolve by name from the cache root rather than importing its directory:
  // the package is exports-only, and "exports" applies to bare specifiers, not
  // to a path.
  once = await import(Bun.resolveSync("package-once-red", dir));
}

export const run = once.run;
if (import.meta.main) await once.exec();
