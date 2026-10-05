# Once

## Shared compute lifecycle

ONCE uses colors-compute v2 for new deployments only. Set
`compute-api-version: 2`. Existing deployments keep their pinned launchers,
configuration, keys and state untouched; no migration, adoption or compatibility
layer is supplied. Provider support comes from the library dependency.

The node is `once-compute`, named `<profile>-once-compute` in the cloud, with
state at `<profile>/once-node-0.tfstate`. Providers requiring a separate public
key registration use `<profile>/once-ssh-registration.tfstate`. Encrypted SSH
authority is retained at `<profile>/ssh/machine-access/resource.json` in the
backend. Supply `COLORS_PAR_ONCE_SSH_PASSPHRASE` at runtime and back it up;
changing the binding does not rotate encryption. No decrypted private key is
persisted. Operator SSH, describe and application convergence use a temporary
scoped agent and a public identity cache. Use the launcher's `ssh` command.

R2 and S3 are supported; local compute state and `provider-compute: no-infra`
are refused. Supply `compute-ssh-sources` and `compute-http-sources`, or the
selected provider's legacy source keys. Compute completes before SMTP and DNS;
local alias ownership is checked before remote application convergence.
`compute-require-existing-state: true` guards subsequent creates against missing
ownership; it is not an import or migration operation. Build renders under
`.colors/build/<profile>/`; dry-run performs no state reads or writes.

Delete withdraws GitHub deployment credentials, removes local aliases and
application stages, then destroys DNS, SMTP, compute and provider registration
in order. It retains encrypted SSH authority. Valid destroyed compute state
allows repeated delete to finish before host access or key unlocking; missing
or incompatible ownership fails. Destroy protection remains enabled by default.

Application and SMTP credentials enter through `COLORS_PAR_*`; ONCE forwards
only selected values to Ansible as `ONCE_PAR_*` runtime bindings. Generated
files contain lookups, never secret values.


For existing deployments moving to remote state, set
`compute-require-existing-state: true`. A real create reads the recorded compute
inventory in the start step before generating deploy keys or running compute and the subsequent SMTP stage. Missing, retired, unreadable, or incompatible
ownership stops the workflow. The library then checks ownership again under its
conditional journal lock. Build and dry-run perform neither state read. This
guard does not transfer compute or application state.

Delete retires compute only after DNS and SMTP cleanup. A validated retired
compute journal makes a repeated delete return successfully before any host
access, key-file reads, or application cleanup. Invalid or unreadable ownership
still fails. Credential and destroy-protection checks remain in effect.


A monorepo containing three byte-compatible implementations of the production
single-server [Basecamp ONCE](https://github.com/basecamp/once) deployment
workflow:

| Package | Runtime | YAML reader | Skill |
|---|---|---|---|
| [Green](green/) | Clojure / Babashka | yamlstar | `package-once-green` |
| [Red](red/) | TypeScript / Bun | `Bun.YAML` | `package-once-red` |
| [Blue](blue/) | Python / uv | PyYAML | `package-once-blue` |

All three read one `colors.yml`, render the same OpenTofu and Ansible files,
and operate the same `.colors/<profile>/` work directory and remote state.
Switching colours needs no change to desired state — only a different command.
Switch between completed commands; never run two against the same state
concurrently.

## ONCE version

The package pins Basecamp ONCE to **v0.3.3**. A real `create` installs the
Linux amd64 or arm64 release binary with its pinned SHA-256 checksum, replacing
an existing binary when it differs. The managed background service disables
binary self-updates with `ONCE_NO_SELF_UPDATE=1` and restarts when the binary or
service configuration changes. Application image `auto_update` and backups
remain available. Upgrading the binary does not recreate application containers;
v0.3.3's `BASE_URL` environment variable reaches an existing application only
when its container is recreated. The ONCE version is fixed by the package,
not a `colors.yml` setting.

Refresh the installed skill payload and copy its launcher to the deployment
root before an authorized `create` to apply this version. A build or dry-run
only checks the generated configuration; it does not upgrade a host.

## Skills

```sh
npx skills use getcolors/once@package-once-green
npx skills use getcolors/once@package-once-red
npx skills use getcolors/once@package-once-blue
```

Skill packages are under [`skills/`](skills/). Each guides desired-state setup,
protects secrets, and runs a build plus dry-run before any real provisioning.
The unified user manual is [`index.html`](index.html).

## Shared workflow

Create and build:

```text
start -> compute -> SMTP -> DNS -> SMTP verification
                                      |-- local SSH config
                                      `-- remote application -> GitHub
```

Publishing follows the remote stage, not the local one: the credentials
describe a configured host, so a workstation-side failure does not gate them.

Delete reverses the graph. It withdraws the published credentials first — a
withdrawn credential against a live host is a loud, recoverable broken deploy,
while a live credential against a destroyed host is silent — then removes the
managed local SSH block before infrastructure. Providers are Azure, AWS, Google Cloud, DigitalOcean,
Hetzner Cloud, Vultr, Yandex Cloud, OCI; Resend or existing SMTP;
Cloudflare or unmanaged DNS; and S3 or R2 state.

## Optional DMARC management

With `provider-smtp: resend` and `provider-dns: cloudflare` or `yandex`, set
`smtp-dmarc-policy` to `none`, `quarantine`, or `reject` to manage a TXT record
at `_dmarc.notifications.<zone>` for every application zone. The value is
`v=DMARC1; p=<policy>`. Optional `smtp-dmarc-rua` adds
`; rua=mailto:<email>` and accepts one bare ASCII reporting email address, only
when a policy is set. Template delimiters (`$`, `%`, `{`, `}`) are rejected.
For example:

```yaml
smtp-dmarc-policy: none
smtp-dmarc-rua: dmarc@example.com
```

Omitting both keys leaves DMARC unmanaged. `none` explicitly publishes a policy;
it does not disable management. Either `no-infra` provider rejects this opt-in.
An explicit sending-domain policy can override a policy inherited from the
parent domain, so review the existing policy before opting in.

If a TXT record already exists at that name, import it into this deployment's
DNS state before the first managed apply; do not create a duplicate DMARC
record. To stop management while preserving the record, back up state, remove
its resource address from DNS state with `tofu state rm`, and remove both
options before the next convergence. Removing the policy alone from a managed
deployment causes OpenTofu to destroy its record on the next apply.

## Secrets

`colors.yml` contains non-secret values only. Credentials travel in one
namespace, `COLORS_PAR_*`, which every colour reads — there is no per-colour
prefix. Generated Ansible expressions are byte-identical and resolve that one
name at play time. OCI, S3, and SSH continue to use their native ambient
credential mechanisms.

## Upgrading an existing project

This release renames the desired-state file, the work directory, and the
credential namespace. None of it migrates automatically.

**Move the work directory before running anything.** On the `local` backend —
the default when `provider-backend` is unset — OpenTofu state lives inside it,
so a command run against the new name finds no state and a `create` will build
a second server alongside the one you already have. `s3` and `r2` projects keep
state remotely and are unaffected.

```sh
mv .once .colors                     # do this first
```

Then rename desired state to `colors.yml` (Green projects also convert EDN to
YAML), set `workdir: .colors` inside it, rename every credential variable to
`COLORS_PAR_*`, and re-install the skill so the launcher is replaced. The old
`GREEN_PAR_*`, `RED_PAR_*`, `BLUE_PAR_*`, and `ONCE_PAR_*` names are no longer
read; a stale one is ignored and the run stops with `required credential is not
set`. An outdated launcher refuses to run rather than rendering from a stale
contract.

### Migrating DNS resource addresses (contract 10)

Contract 10 renames the Clojure namespaces to `io.github.getcolors.once.*`.
That name is not only internal: it is part of the address of every DNS record
this project manages, so the rename moves them and **an unmigrated `create`
will destroy and recreate every record in the zone.**

Run this once per project, after re-installing the skill and before the next
`create`. It rewrites the addresses in place; nothing is created or destroyed.

```sh
./green build                        # render the work tree at the new contract
cd .colors/<profile>/tofu-dns
tofu init
tofu state pull > /tmp/tofu-dns.backup.tfstate    # keep this until you have applied once

for old in $(tofu state list | grep io_github_bigconfig_ai_once_tools_); do
  tofu state mv "$old" "${old/io_github_bigconfig_ai_once_tools_/io_github_getcolors_once_tools_}"
done

tofu state list | grep -c io_github_bigconfig_ai_once_tools_   # must print 0
tofu state list | wc -l                                        # must equal the count from before
```

Those two counts are the check. A `state mv` renames an address and copies the
attributes verbatim, so a migration that moved everything and lost nothing is
correct by construction; restore with `tofu state push
/tmp/tofu-dns.backup.tfstate` if either count is wrong.

**Do not verify with a bare `tofu plan` in that directory.** It will report
changes, and they are not yours. `tofu-dns` is rendered from the outputs of the
compute and SMTP stages, which a standalone `build` has no way to supply: the A
records fall back to the placeholder `192.168.0.1`, and `smtp.tf.json` renders
with no resources at all. A plan against that tree proposes rewriting every A
record and destroying every SMTP record whether or not you have migrated
anything. The real plan happens inside `create`, where the DAG threads those
params through, and that is where a clean result means something.

Only the `tofu-dns` stage is affected — compute, smtp, and smtp-post name their
resources without the namespace. Zone settings are keyed by zone and setting
name and do not move either.

## Development

```sh
cd green && clojure -M:test
cd red && bun test && bun run typecheck
cd blue && uv run python -m pytest -q
./scripts/parity.sh
```

`parity.sh` builds a provider matrix through all three packages from one
`test/parity/colors.yml`, compares every complete generated tree byte-for-byte,
verifies that packaged resource copies match Green's reference resources, and
checks that the three YAML readers type every scalar in
`test/parity/scalars.yml` identically. The corpus covers scientific notation,
octal and hexadecimal integers, dates, sexagesimal-looking strings and quoted
values. Whole-valued floats compare as integers because JavaScript uses one
number type. Development SDK pins run this check against the repaired readers.
Blue and colors-compute declare ordinary SDK requirements so applications can supply one
explicit Blue git pin. Their development groups pin the versions used by tests.
Bundled deployment launchers keep their existing pins.

ONCE also lends downstream Package Skills two library modules in every colour — `ssh` (the SSH Keypair Standard) and `compute` (the Compute Provider Standard's operations over a package-owned registry) — whose behaviour and messages `parity.sh` diffs across the three through `scripts/ssh-*` and `scripts/compute-*`.

Generated `.colors/` directories are artifacts and must not be edited as source.

Create and build serialize the package-owned SSH alias stage before remote Ansible. A failed local ownership check stops application convergence; GitHub publication remains after remote convergence.

## Stateful application deployment

Application `deploy-strategy` defaults to `rolling`, preserving ordinary ONCE
updates for static sites. Set `deploy-strategy: stop-first`,
`deploy-stop-timeout: 300` (1–3600 seconds), and `auto_update: false` for SQLite
applications. The timeout covers the application supervisor and its final
replication shutdown, not only the HTTP server.

The SSH forced command invokes a root-owned policy helper. It serializes each
host with `/run/once-deploy/<host>.lock`, pulls an immutable image digest,
disables the old container's restart policy, stops it, requires clean exit,
then runs ONCE update with automatic updates disabled. It verifies the image,
named volumes, and single replacement container after ONCE readiness succeeds.
The deploy user has no general ONCE or Docker sudo access. Client-provided SSH
commands cannot choose the host, image, timeout, namespace, or strategy.

A durable `/var/lib/once-deploy/<host>.pending` marker blocks retries after
interruption or failure. Inspect the containers, schema and replication before
removing it as root. There is no automatic rollback: the replacement may already
have migrated the database. Pull and read-only preflight failures do not create a recovery marker.
Never restart old application code against a potentially migrated database.

Existing applications with automatic updates enabled are refused by the helper.
Adoption requires an operator-controlled maintenance window: pause the ONCE
background updater, stop the old writer, disable automatic updates during its
controlled replacement, verify there is exactly one writer, then resume the
background service. Merely adding the YAML flag does not reconcile existing
ONCE settings. Provisioning does not upgrade existing apps automatically.

All privileged maintenance and environment-update tools must honor the same
lock and stop-first protocol; direct root ONCE commands can bypass it. Existing
PocketContext wrappers use different locks and must be adapted before reuse. Locks
are local to one host. Cross-host migration still requires disabling the source
writer and its restart/deployment paths before starting the destination.
A zero process exit is not proof of remote replication durability: applications
must independently verify actual committed records and file hashes in a restored
replica before cross-host handover. Litestream 0.5.17 can exit zero after failed
replication, so its exit status alone is insufficient. Same-host replacement
retains the named volume and does not restore over its healthy database. This helper does not implement
distributed fencing or change application backup behavior.

Private registry pulls require root Docker CLI authentication as well as ONCE
registry credentials. ONCE-only stored credentials do not authenticate the pre-pull.
Run `python3 -B -m unittest discover -s test/deploy` and the disposable real-Docker
check `python3 -B scripts/test-stop-first-docker.py --image <local-shell-image> --sudo`
from the repository root. The Docker check simulates registry and ONCE orchestration;
it does not replace a live ONCE/proxy deployment test.
