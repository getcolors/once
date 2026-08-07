# Once

`once` provisions and operates a single-server [ONCE](https://github.com/basecamp/once)
installation with OpenTofu and Ansible. It uses the
[`green`](https://github.com/getcolors/green) DAG workflow engine. This is the
Clojure implementation in a byte-compatible Green/Red/Blue monorepo; all three
can manage the same `.colors/<profile>/` state between completed commands.

The implementation ships a thin launcher at
`../skills/package-once-green/green`, symlinked here as `./green`. The same
file is the payload of the `package-once-green` agent skill:

```bash
npx skills use getcolors/once@package-once-green
npx skills add getcolors/once --skill package-once-green
```

Standing alone, the launcher resolves `once` and `green` as pinned git
dependencies. Inside this repository `bb.edn` supplies local roots and the
bootstrap is skipped, so commands run as `bb green <command>`.

## Workflow

Create and build use this graph:

```text
       ┌─ tofu-compute ─┐                             ┌─ ansible-local
start ─┤                ├─ tofu-dns ─ tofu-smtp-post ─┤
       └─ tofu-smtp ────┘                             └─ ansible-remote ─ github
```

Compute and SMTP run concurrently. DNS joins their outputs, SMTP verification
runs after DNS, and the two Ansible stages then run concurrently. Publishing
follows the remote stage, not the local one: the credentials describe a
configured host, so a workstation-side failure does not gate them. Build
renders the same files without invoking OpenTofu or Ansible; the join falls
back to placeholder outputs so rendering never needs state.

Delete reverses the graph:

```text
start ─ github ─ ansible-cleanup ─ tofu-smtp-post ─ tofu-dns ─┬─ tofu-smtp
                                                              └─ tofu-compute
```

Revoking runs before anything is destroyed: a withdrawn credential against a
live host is a loud, recoverable broken deploy, while a live credential against
a destroyed host is silent. It needs no key material, so it also works when the
box is already gone.

Cleanup replays the local Ansible play to drop the managed `~/.ssh/config`
block and removes the rendered Ansible trees; SMTP verification and DNS are
destroyed in order, then SMTP and compute are destroyed concurrently. Delete
reads the compute and SMTP outputs already in state so the destroy renders with
real values.

Generated files and isolated OpenTofu state directories live under
`.colors/<profile>/`.

## Requirements

- Babashka
- OpenTofu
- Ansible
- OpenSSH, with the compute key loaded into `ssh-agent`
- `skopeo` for registry comparisons in `describe`

Cloud-provider credentials are needed only for the providers selected in
`colors.yml`, and only for a real `create` or `delete`.

## Configuration

Desired state is the flat map in [`colors.yml`](../colors.yml), except for the
nested `once.applications` collection. Green finds it by walking up from the
working directory, and reads it with yamlstar under the YAML 1.2 core schema —
so `no` is the string `no`, and `012` is twelve:

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
    - host: www.example.net
      image: ghcr.io/example/site:latest
      github: acme/site      # same repository as www.example.com: one key, both hosts
provider-compute: digitalocean  # azure, aws, google, digitalocean, hcloud, yandex, oci, no-infra
provider-smtp: resend           # resend, no-infra
provider-dns: cloudflare        # cloudflare, yandex, no-infra
provider-backend: r2            # r2, s3, local
compute-prevent-destroy: true
```

A relative `workdir` is resolved next to `colors.yml`, not next to the caller,
so every colour shares one work directory whatever subdirectory you run from.

There is no domain key. Application hosts are the source of truth and may span
domains. Green derives every DNS zone from each host's last two labels, creates
and verifies a Resend sending domain at `notifications.<zone>`, and gives each
application an `info@notifications.<zone>` From address in its own zone. Each
host gets its own `A` record — proxied on Cloudflare, plain on Yandex — and
there is no implicit apex or wildcard record, so an unlisted host does not
resolve.

`env` maps a container variable **name** to the flat key holding its value,
never to the value itself.

Credential keys are absent from the committed file: they arrive as
`COLORS_PAR_*` variables — one namespace shared by green, red and blue — which
are overlaid onto the matching flat key before the workflow starts. Any flat
key can be overridden the same way; names are lowercased and underscores become
hyphens, and the override takes the type of the value it replaces:

```bash
export COLORS_PAR_DO_TOKEN="..."
export COLORS_PAR_CLOUDFLARE_API_TOKEN="..."
export COLORS_PAR_RESEND_API_KEY="..."
export COLORS_PAR_RESEND_PASSWORD="..."
export COLORS_PAR_R2_ACCESS_KEY_ID="..."
export COLORS_PAR_R2_SECRET_ACCESS_KEY="..."
export COLORS_PAR_APP_DATABASE_URL="..."   # one per application env entry
```

Nothing lands in a rendered file. OpenTofu credentials are passed to the
process environment under the variable each provider reads natively; Ansible
receives a byte-compatible expression that resolves `COLORS_PAR_*` when the
play runs.

Use `.envrc.private` for local secrets. To permit compute destruction when the
default safeguard is enabled:

```bash
export COLORS_PAR_COMPUTE_PREVENT_DESTROY=false
```

## Commands

Run from the repository root:

```bash
bb green build                 # render .colors/<profile>/ only
bb green create                # provision and configure
bb green create --dry-run      # print the DAG actions, touch nothing
bb green delete                # destroy infrastructure
bb green delete --dry-run
bb green describe              # providers, SSH status, apps, image updates
```

Maintainers of this repository also have `bb pin`, which stamps the launcher
with the current HEAD.

Use another desired-state file with `-f` or `--file`:

```bash
bb green build -f production.yml
```

`build` and `--dry-run` require no credentials. A real `create` additionally
validates every provider credential and every application `env` reference; a
real `delete` validates provider credentials and refuses while
`compute-prevent-destroy` is true.

`describe` reads compute and SMTP values from their OpenTofu state before
probing the remote host. Compute is reported as `running`, `unreachable` (state
holds an address but SSH failed) or `absent` (the `tofu-compute` stage has no
outputs, so nothing was created); a `no-infra` host is never `absent`. Anything
but `running`, and a missing remote `once` command, produces a non-zero exit;
the remaining live checks are soft failures named in the report.

## Providers and generated configuration

- Compute templates: Azure, AWS, Google Cloud, DigitalOcean, Hetzner Cloud, Yandex Cloud, OCI, and an
  existing `no-infra` host. Yandex creates its own network and subnet, installs
  `compute-pubkey` through instance metadata, and authenticates with
  `COLORS_PAR_YANDEX_TOKEN`.
- SMTP templates: Resend or `no-infra` SMTP settings.
- DNS templates: Cloudflare or `no-infra`; the per-application and Resend DNS
  records are generated as `apps.tf.json` and `smtp.tf.json` at the
  compute/SMTP join.
- Backends: local, S3, and Cloudflare R2, emitted as `backend.tf.json` and
  isolated by profile and tool under the state key `<profile>/<tool>.tfstate`.
- `ansible-local` runs a playbook that writes the managed `Host <profile>`
  block into `~/.ssh/config`, and removes it again on delete.
- `ansible-remote` installs Docker, ONCE, and Babashka, creates the restricted
  `deploy` user whose keys are each pinned to a `ForceCommand` naming the hosts
  one repository serves, and reconciles the declared applications with the
  `once` Ansible module.
- `github` publishes each repository's `SSH_PRIVATE_KEY`, `SERVER_IP`,
  `SERVER_USER`, and `SSH_KNOWN_HOSTS` into an Actions environment named after
  the profile, and withdraws them on delete. Nothing reads those values until a
  workflow in that repository does;
  [`../skills/package-once-green/references/github-deploy.md`](../skills/package-once-green/references/github-deploy.md)
  is that workflow.

A build of the example desired state produces:

```text
.colors/production/
├── tofu-compute/     backend.tf.json  main.tf
├── tofu-smtp/        backend.tf.json  main.tf
├── tofu-dns/         backend.tf.json  main.tf  apps.tf.json  smtp.tf.json
├── tofu-smtp-post/   backend.tf.json  main.tf
├── ansible-local/    ansible.cfg  inventory.ini  main.yml
└── ansible-remote/   ansible.cfg  main.yml  inventory.json  once.yml
                      files/deploy  library/once
```

## Development

```bash
clojure -M:test
clojure-lsp clean-ns
clojure-lsp format
clj-kondo --lint src/clj test/clj ../skills/package-once-green/green
```

Source namespaces are under `src/clj/io/github/getcolors/once/`; templates
are under `src/resources/io/github/getcolors/once/tools/`. `.colors/` is
generated and must not be edited.

After committing and pushing a change to the launcher, `src/clj`, or the
templates, repin so standalone copies resolve the new sources:

```bash
bb pin              # stamps the launcher with the current HEAD
```

`pin` refuses to run on a dirty tree or an unpushed HEAD. It is a bb task
rather than a launcher subcommand because it reads the HEAD of whatever
checkout surrounds it, which is only this repository. A launcher whose pin
predates the sources it needs refuses to run rather than rendering silently
from an older commit; the check is the `contract` number in `utils.clj`, which
has to be bumped whenever an older launcher could not survive the change.

## License

Copyright © 2026 Alberto Miorin

Distributed under the MIT License.
