# CLAUDE.md

This file describes the `once` codebase for AI assistants. Read it before making changes.

## Project Overview

`once` provisions and operates a single-server [ONCE](https://github.com/basecamp/once) installation with [OpenTofu](https://opentofu.org/) and [Ansible](https://www.ansible.com/). It targets "vibe coders" who want one-click deployment.

It is built on [`green`](https://github.com/getcolors/green), a DAG workflow engine: a graph of steps threaded by an `opts` map, with advice (before/after/around) attached per step. This branch is a rewrite — the BigConfig SDK, `bb run package …`, `options.clj` profiles, and `BC_PAR_*` variables are gone. Do not reintroduce those concepts.

The repository ships two things from one file:

- **The launcher** `../skills/package-once-green/green`, a single Babashka script. `./green` in this directory is a symlink to it.
- **The `package-once-green` skill** (`../skills/package-once-green/SKILL.md` + `references/configuration.md`), whose payload is that launcher. Standing alone it resolves this monorepo's `green/` package and Green as pinned git dependencies; inside this checkout `bb.edn` supplies local roots.

**The launcher holds no logic of its own.** It resolves the two libraries, checks the contract, works out which desired-state file to read, and dispatches. Validation, the graph, the steps, and the report all live under `src/clj`, where the test suite reaches them — a copied payload is the one place in this project where code cannot be tested, so nothing that can live elsewhere should live there. Keep it that way when adding behaviour.

## Tech Stack

- **Language**: Clojure 1.12.5 (JVM), plus Babashka for the launcher and for the two scripts that run on the remote host
- **Workflow engine**: `io.github.getcolors/green` (`green.workflow`, `green.scaffold`, `green.tofu`, `green.ansible`, `green.cli`, `green.progress`, `green.dry-run`, `green.process`, `green.yaml`)
- **Infrastructure**: OpenTofu; **Config management**: Ansible
- **Key libraries**: `cheshire` (JSON), `selmer` (templates, via `green.scaffold`)
- **Dev environment**: Nix via `devenv` + `direnv`

## Repository Structure

```
green/
├── green                    # symlink -> ../skills/package-once-green/green
├── (colors.yml at the repo root — one desired state for every colour)
├── src/
│   ├── clj/io/github/getcolors/once/
│   │   ├── tools.clj        # the six step functions, template specs, generated JSON
│   │   ├── workflow.clj     # the DAG: start, cleanup, wire-fn, backend advice
│   │   ├── validate.clj     # the provider registry and desired-state validation
│   │   ├── github.clj       # deploy-key generation and publication to the Actions environment
│   │   ├── describe.clj     # post-provisioning report (providers, compute status, apps)
│   │   └── utils.clj        # contract number and DNS zone derivation
│   └── resources/io/github/getcolors/once/
│       ├── raw              # `<{ content|safe }>` — the template used for generated content
│       └── tools/
│           ├── tofu/{digitalocean,hcloud,yandex,oci,no-infra}/main.tf
│           ├── tofu-smtp/{resend,no-infra}/main.tf
│           ├── tofu-dns/{cloudflare,yandex,no-infra}/main.tf
│           ├── tofu-smtp-post/{resend,no-infra}/main.tf
│           ├── ansible/            # remote host: playbook, ansible.cfg, files/deploy, library/once
│           └── ansible-local/      # local machine: playbook, ansible.cfg, inventory.ini
├── tasks/pin.clj            # `bb pin`, the maintainer-only launcher stamp
├── test/clj/io/github/getcolors/once/
│   ├── tools_test.clj       # rendering, generated DNS records, ansible-local lifecycle
│   ├── workflow_test.clj    # validation gates, the graph shape, backends, a whole build
│   ├── validate_test.clj    # the provider registry and every desired-state rule
│   ├── describe_test.clj    # report parsing and assembly
│   ├── deploy_test.clj      # the deploy ForceCommand script
│   ├── once_module_test.clj # the `once` Ansible module
│   └── utils_test.clj       # zone derivation
├── test/resources/          # classpath fixtures the tests read
├── deps.edn / bb.edn        # git-pinned green; bb.edn overrides with local roots
├── plans/                   # historical task briefs, several predating the rewrite — not authoritative
└── README.md                # Green-specific development and usage notes
```

## Development Commands

```bash
bb green build                 # render <workdir>/<profile>/ only, no tofu, no ansible
bb green create                # provision and configure
bb green create --dry-run      # print the DAG actions, touch nothing
bb green delete                # destroy, in reverse
bb green describe              # providers, compute status, deployed apps, image updates
bb pin                         # stamp the launcher with this repository's HEAD (maintainers)

bb green build -f production.yml   # -f/--file overrides the colors.yml found by walking up

clojure -M:test                # cognitect test-runner over test/clj
clojure-lsp clean-ns && clojure-lsp format
clj-kondo --lint src/clj test/clj ../skills/package-once-green/green
```

The root CI runs this suite together with Red, Blue, and byte parity.

## Desired state (`colors.yml`)

A single flat YAML map, except for the nested `once.applications` collection, read by yamlstar under the YAML 1.2 core schema and found by walking up from the working directory. Provider selection and non-secret settings live here; credentials never do.

Keys arrive as kebab-case keywords, the shape the EDN reader used to deliver;
`green.cli/read-state` keywordizes every map in the tree, so `env` names become
keywords too and reach templates through `name`.

```yaml
profile: production      # names the workdir, the state keys, the compute
workdir: .colors         # resource, and the ~/.ssh/config Host alias
once:
  applications:
    - host: www.example.com
      image: ghcr.io/example/site:latest
      github: acme/site
      env:
        DATABASE_URL: app-database-url
provider-compute: digitalocean  # digitalocean | hcloud | oci | no-infra
provider-smtp: resend           # resend | no-infra
provider-dns: cloudflare        # cloudflare | yandex | no-infra
provider-backend: r2            # local | s3 | r2
compute-prevent-destroy: true
```

Load-bearing rules:

- **No domain key.** Application hosts are the source of truth and may span domains. `utils/apps-domains` derives the sorted distinct zones from their last two labels. The SMTP stage creates `notifications.<zone>` for every zone, the DNS provider manages every zone, and each application gets the matching `info@notifications.<zone>` From address. Templates read HCL-encoded derived zone collections injected by `tools/with-zones` — nothing in desired state supplies them.
- **No apex or wildcard DNS record.** Each application host gets its own `A` record (proxied on Cloudflare, plain on Yandex), so an unlisted host does not resolve.
- **Resend's relay is hard-coded** (`smtp.resend.com`, 587, user `resend`) in `tools/resend-smtp`, because it is identical for every account. Only `COLORS_PAR_RESEND_API_KEY` and `COLORS_PAR_RESEND_PASSWORD` are configurable. The `no-infra` SMTP keys stay in desired state.
- **Environment parameters are the only secret channel.** `COLORS_PAR_*` — one namespace shared by green, red and blue — overlays matching flat keys. There is no per-colour prefix and no portable alias. Overrides retain existing boolean/integer types. There is no `TF_VAR_*` mechanism.
- Application `env` maps a container variable **name** to the flat key holding its value, never to the value itself.

## Architecture

### The DAG

`workflow/wire-fn` returns `[step-fn & next-steps]` per step and switches on `:green/event`. Create and build:

```text
start ─┬─ tofu-compute ─┐                          ┌─ ansible-local
       └─ tofu-smtp ────┴─ tofu-dns ─ smtp-post ───┴─ ansible-remote ─ github
```

Delete:

```text
start ─ github ─ ansible-cleanup ─ tofu-smtp-post ─ tofu-dns ─┬─ tofu-smtp
                                                              └─ tofu-compute
```

Compute and SMTP run concurrently; `tofu-dns` is a join, and the engine hands it the fork-point opts plus `:green/branches` (a vector of branch results) — `tools/joined-params` reads the branch results out of it. The two Ansible stages then run concurrently.

`workflow` also attaches: backend advice `:before` each Tofu step, `progress/advise` (the `>>> / <<<` lines), and `dry-run/advise` over `side-effecting-steps` (so `--dry-run` skips them).

### The opts map

One map is threaded through every step. Reserved keys are namespaced; desired-state keys are plain kebab-case keywords.

| Key | Meaning |
|---|---|
| `:green/exit` | 0 success, >0 failure — how steps report, instead of throwing |
| `:green/err`, `:green/trace` | failure message and stack trace |
| `:green/event` | `:build`, `:create`, or `:delete`, stamped by `green.cli` |
| `:green/dry-run` | set by `--dry-run` |
| `:green/branches` | branch results at a join |
| `:once/compute-params`, `:once/smtp-params` | outputs adopted from earlier stages |
| `:zones` | sorted distinct DNS zones derived from the application hosts |
| `:green.scaffold/written`, `:green.scaffold/deleted` | paths a scaffold touched |

### Stages

Each stage owns an isolated directory, `tools/tool-dir` = `<workdir>/<profile>/<tool>`:

| Step | Work dir | Templates | Does |
|---|---|---|---|
| `:once/tofu-compute` | `tofu-compute` | `tools/tofu/<provider>/` | provisions the VM (or passes through `no-infra`), outputs ip/user/sudoer/name |
| `:once/tofu-smtp` | `tofu-smtp` | `tools/tofu-smtp/<provider>/` | registers `notifications.<zone>` for every application zone at Resend and outputs each id and DNS record set |
| `:once/tofu-dns` | `tofu-dns` | `tools/tofu-dns/<provider>/` | settings for every zone, plus generated `apps.tf.json` and `smtp.tf.json` |
| `:once/tofu-smtp-post` | `tofu-smtp-post` | `tools/tofu-smtp-post/<provider>/` | verifies every Resend domain once DNS resolves |
| `:once/ansible-local` | `ansible-local` | `tools/ansible-local/` | writes the managed `Host <profile>` block into `~/.ssh/config` |
| `:once/ansible-remote` | `ansible-remote` | `tools/ansible/` | installs docker, ONCE, bb; creates the restricted `deploy` user; reconciles applications |

Note the asymmetry: the compute step's work directory is `tofu-compute` but its templates live under `tools/tofu/`.

### Rendering

`green.scaffold` maps a qualified keyword to a classpath resource (`:io.github.getcolors.once.tools.tofu.oci/main.tf` → `io/github/getcolors/once/tools/tofu/oci/main.tf`) and renders it with Selmer. `tools/template-opts` overrides the delimiters, so templates use `<{ var }>` for values and `<% if … %>` for tags, leaving `{{ … }}` and `{% … %}` for Jinja2 in the Ansible files. Providers are selected by directory, not by conditionals in one file.

Content that is computed rather than templated is written through `raw-spec`, which renders the one-line `raw` template: `apps.tf.json`, `smtp.tf.json`, `inventory.json`, and `once.yml`. `tools/render-fn` builds the two DNS files from `green.tofu/construct` and `constructs-json`, which merges and sorts them so the JSON is deterministic. `backend.tf.json` is the exception — `green.tofu` writes it directly from the backend advice, outside the scaffold.

A `build` of the reference `colors.yml` produces exactly:

```text
<workdir>/<profile>/
├── tofu-compute/     backend.tf.json  main.tf
├── tofu-smtp/        backend.tf.json  main.tf
├── tofu-dns/         backend.tf.json  main.tf  apps.tf.json  smtp.tf.json
├── tofu-smtp-post/   backend.tf.json  main.tf
├── ansible-local/    ansible.cfg  inventory.ini  main.yml
└── ansible-remote/   ansible.cfg  main.yml  inventory.json  once.yml
                      deploy_keys  files/deploy  files/authorized-keys
                      library/once
```

The two generated DNS files are rendered for Cloudflare and Yandex; `no-infra` DNS renders `main.tf` alone.

### Parameter flow

1. `green.cli` reads the desired-state file, overlays `COLORS_PAR_*`, and stamps `:green/event` and `:green/state-file` (the absolute path, which `tools/tool-dir` anchors a relative `:workdir` against).
2. `workflow/start-step` overlays `COLORS_PAR_*` again (idempotent, and it also covers the REPL and test paths), then validates (`validate/state-errors`, and `validate/secret-errors` for a real create/delete).
3. Tofu stages parse their `params` output into `:once/compute-params` / `:once/smtp-params`; `joined-params` merges them into opts at the DNS join. Fallback maps (`fallback-compute-params`, `fallback-smtp-params`) stand in for `build` and dry-run so rendering never needs state.
4. Delete cannot re-derive those values, so `workflow/adopt-existing-state` reads the already-applied outputs back out of Tofu state before teardown.

### Secrets

Three separate channels, and nothing lands in a rendered file:

- **OpenTofu**: `validate/providers` is the single registry — per provider, the non-secret keys its templates need (`:required`), the credentials it needs (`:secrets`), and which of those OpenTofu reads natively (`:tofu-env`, e.g. `:do-token` → `DIGITALOCEAN_TOKEN`). Validation and `tools/credential-env` both read it, so a provider cannot be checked against one set of keys and run with another. Credentials travel in the process environment; unset ones are omitted, so build and dry-run stay credential-free. A secret absent from `:tofu-env` reaches its tool another way — the SMTP passwords go through Ansible.
- **State backends**: R2 authenticates through `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`; naming them in `backend.tf.json` would also write them to `.terraform/terraform.tfstate`.
- **Ansible**: `tools/par-lookup` emits the shared expression resolving `COLORS_PAR_*`, so secrets reach the play at run time. The exact bytes are a cross-language contract.

### Backends

`backend-advice` writes `backend.tf.json` before each Tofu step: local, S3, or R2 as an S3-compatible backend with `region = "auto"`. Remote state keys are `<profile>/<tool>.tfstate`.

### Delete semantics

Deleting has to render before it can destroy: `green.tofu/tofu-with-spec` and `green.ansible/ansible-with-spec` scaffold with `:green/event :create`, run the tool, and only then scaffold with `:delete` to remove the rendered tree. `workflow/ansible-cleanup-step` replays `ansible-local` so the managed `~/.ssh/config` block is dropped. `:compute-prevent-destroy` defaults to `true` and renders `lifecycle { prevent_destroy = true }`; a real delete refuses to start until `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false`.

### The remote host

`tools/ansible/main.yml` installs Docker, ONCE, and Babashka, then creates a `deploy` user with NOPASSWD sudo limited to `/usr/local/bin/once *`, whose authorized keys are each pinned to a `ForceCommand` (`tools/ansible/files/deploy`). That script takes the hosts its key is for as arguments and updates each of them, exiting non-zero if any failed — so one repository's key cannot redeploy another's application, and one repository serving several hosts updates all of them from a single ping. The client sends no command; one that arrives anyway is ignored, not obeyed.

Keys are per repository and ephemeral: `validate/deploy-groups` groups applications by `:github`, `github/generate-keys` shells `ssh-keygen` once per group on every create, nothing is stored, and `tools/deploy-keys-content` renders only the current generation. `tools/ansible/files/authorized-keys` merges that against what is installed, keeping one previous generation per repository so a publication that fails leaves the old key working — grouped by the key comment, not by the ForceCommand, so adding a host to a repository does not discard the previous generation. Two generations is the whole benefit; more only extends how long a leaked key stays usable. The threat model depends on `files/deploy` staying tight — `deploy_test.clj` guards it. Applications are reconciled by `tools/ansible/library/once`, a Babashka Ansible module that diffs the desired list against `once list` and deploys or removes the difference, redacting secrets from anything it reports.

## The contract number and `bb pin`

`utils/contract` and `launcher-contract` in the launcher are a compatibility handshake. A standalone launcher refuses to run when the `once` it resolved reports a lower contract — or when it cannot load the namespaces it needs at all — instead of silently rendering from an older commit.

**Bump `utils/contract` (and `launcher-contract` to match) on any change a launcher pinned to an older commit could not survive** — a changed template variable, a renamed desired-state key, a new function the launcher calls. Then, after committing and pushing: `bb pin` stamps `once-sha` (and `green-sha`, when `GREEN_LIB_ROOT` points at a green checkout) and the result is committed as `fix: re-pin bundled launcher to once <sha>`. `pin` refuses to run on a dirty tree or an unpushed HEAD, and the pins are marked *managed — do not edit by hand*.

`pin` is a bb task rather than a launcher subcommand because it reads the HEAD of whatever checkout surrounds it: in a user's project — where the launcher is a copied payload — it would stamp an unrelated SHA. Keeping it out of the payload removes the failure mode instead of documenting it.

## Code Conventions

- **Namespaces**: `io.github.getcolors.once.*`. Six of them, mapping to distinct concerns — `tools` (the steps), `workflow` (the graph), `validate` (the provider registry and its rules), `describe` (the report), `github` (deploy keys and the environment they are published to), `utils` (the contract and zone derivation). Adding a seventh needs a genuinely new concern.
- **Keys**: plain kebab-case keywords for desired state (they match template variable names); namespaced keywords for engine state (`:green/…`, `:once/…`).
- **Steps** take `opts` and return `opts`, and report failure through `:green/exit` / `:green/err`.
- **`^:private`** for everything not called from the launcher or the tests. The launcher's own helpers are `defn-`; the workflow steps it exposes are not.
- **Pure builders stay pure**: `tools/render-fn`, `tools/inventory`, `tools/ansible-once`, `utils/apps-domains` take data and return data. `describe/describe-report` keeps its single-argument arity (which shells out) separate from the arities that take an injected runner, so report construction stays process-free — preserve that split.
- **Tests avoid processes** by redefining `green.ansible/ansible-step` and `green.tofu/tofu-step`, or by driving the pure builders directly.

## Git Conventions

Stay on the `green` branch — each language has its own branch in this repository, and this one is the green rewrite. Commit only when explicitly asked. [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `refactor:`, `docs:`, `chore:`, `deps:`, with `!` and a `BREAKING CHANGE:` footer when desired state or the contract changes.

## What to Avoid

- Do not reintroduce BigConfig SDK concepts: `bb run package …`, `::workflow/params`, `BC_PAR_*`, `options.clj` profile maps.
- Do not add error handling for cases that cannot happen — failure travels through `:green/exit` and `:green/err`, and `green.workflow` converts thrown exceptions itself.
- Do not edit `.colors/` (or any configured `:workdir`) — it is generated output.
- Do not put credentials, tokens, or private keys in source, in `colors.yml`, or in a rendered file. `.envrc.private` is the local channel.
- Do not give the launcher a dependency outside `green`, `once`, and Babashka's built-ins: it has to work as a lone file copied into a stranger's project.
- Do not hand-edit `once-sha` / `green-sha`; run `bb pin`.
- When desired state changes, update the root `colors.yml`, the package README, the root manual, and `../skills/package-once-green/`.
