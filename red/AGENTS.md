# Red ONCE

TypeScript/Bun implementation of the production ONCE package. Read the root
`../CLAUDE.md` first, and the `CLAUDE.md` of the separate `red` SDK checkout
(`github.com/getcolors/red`, pinned by SHA in `package.json`) before changing
anything that touches the engine.

```sh
bun install          # required before the first test run and after any pin change
bun test
bun run typecheck
./red build
```

`bun install` is required **here** and is not going away: inside a checkout the
working tree is the point, so the launcher deliberately refuses to fall back to
a pinned copy and tells you to install instead. It recognises a checkout by the
enclosing manifest being named `package-once-red`. Outside one, `./red`
resolves its own `PINS` into `~/.cache/package-once-red/` on first run — see the
launcher's header. Set `RED_NO_BOOTSTRAP=1` to force the error-and-exit
behaviour anywhere.

This manifest is a development dependency, not a version channel. The shipped
launcher never reads a project's `package.json`; `PINS` is its only source of
versions, as green's inline SHAs and blue's PEP 723 metadata are for them.

Source is under `src/`, packaged templates under `resources/`, tests under
`test/`. `./red` links to `../skills/package-once-red/red`. The repository root
`package.json` is the installable npm facade; this leaf manifest is development
only.

Maintain exact behavior and generated-byte parity with Green and Blue. Use
Red's immutable workflow values, return new opts objects, and keep engine keys
under `red/*`. All subprocesses use Red's runtime seam. Never mutate process
environment around parallel branches; pass per-command environments.

Never edit `.colors/`. Secrets use `COLORS_PAR_*` — the one namespace every
colour shares — remain out of `colors.yml` and rendered content, and are tested
only by presence. Do not put production logic into the copied launcher.

Red reads YAML with `Bun.YAML`, which follows the 1.2 core schema. Keep it that
way: green and blue use different parsers, and `./scripts/parity.sh` asserts
all three type every scalar identically.
