---
name: package-once-blue
description: Create and operate single-server Basecamp ONCE deployments with Blue. Use for colors.yml configuration, builds, dry-runs, provisioning, deletion and status reports.
license: MIT
---

# ONCE with Blue

Use the bundled `blue` launcher in the deployment directory. It runs with
uv and resolves immutable package dependencies. Read
[configuration.md](references/configuration.md) before changing desired state.
Read [github-deploy.md](references/github-deploy.md) when adding continuous
deployment to an application's repository.

DMARC management is opt-in through `smtp-dmarc-policy`; see the configuration
reference for provider restrictions, reporting addresses, existing-record import,
and preserving a DNS record when removing management. Review any inherited
policy before setting an explicit sending-domain policy.

The package calls colors-compute for one host. The library owns provider
selection, credentials, node state and encrypted SSH resources. ONCE owns
the workflow scope and agent lifetime. Update its dependency
to obtain provider support; do not add a compute template or provider branch to
this package. ONCE owns application configuration, SMTP, DNS and GitHub publishing.

The package pins Basecamp ONCE to **v0.3.3**. A real `create` installs the
Linux amd64 or arm64 release binary with its pinned SHA-256 checksum, replacing
an existing binary when it differs. The managed background service disables
binary self-updates with `ONCE_NO_SELF_UPDATE=1` and restarts when the binary or
service configuration changes. Application image `auto_update` and backups
remain available. Upgrading the binary does not recreate application containers;
v0.3.3's `BASE_URL` environment variable reaches an existing application only
when its container is recreated. The ONCE version is fixed by the package,
not a `colors.yml` setting.

Keep secrets in `COLORS_PAR_*` environment variables. Ask for variable names
and whether they are set, never their values. Do not read `.envrc.private` or
private keys. Never read generated `.colors/` as source or edit it. Use one color
at a time for a deployment.

For initialization, preserve existing desired state and unrelated files. Copy
the bundled launcher, make it executable, and create or revise `colors.yml`
with non-secret settings. Ignore generated output and private environment files.
Validate with `./blue build` and `./blue create --dry-run`. These commands
contact no provider and do not prove credentials or live health.

A real create or delete needs user authorization. Existing authorization for
the task applies; do not ask again. Create validates, converges compute, then
SMTP, DNS, verification and application configuration. Compute ownership failure
stops subsequent resource creation. Delete loads recorded compute inventory,
withdraws published credentials and SSH configuration, removes DNS and SMTP,
then destroys compute and any provider registration. Encrypted SSH authority
is retained after compute deletion.
Keep `compute-prevent-destroy: true` in desired state; an authorized delete can
use `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` for that invocation.

This is a breaking, greenfield-only v2 implementation. Require
`compute-api-version: 2`, fresh identities and fresh state roots. Existing
deployments keep their pinned launchers and remain untouched. Do not add
migration, adoption or compatibility tooling. Supply and securely back up
`COLORS_PAR_ONCE_SSH_PASSPHRASE`. Never replace it to regenerate authority.
Use the launcher's `ssh` command for a scoped agent, or run `ssh-install` to enable ordinary SSH aliases.
Build renders separately under `.colors/build/<profile>/`.

Use `./blue describe` for recorded compute status and application inspection.
It reads compute through the library and requires a verified address before SSH.
For an application naming a GitHub repository, establish whether continuous
deployment is part of the user's request before adding its workflow. Confirm
the target repository matches `owner/repo`; the deployment repository may be a
different checkout. Follow the linked reference for the exact published values.

Create and build serialize the package-owned SSH alias stage before remote Ansible. A failed local ownership check stops application convergence; GitHub publication remains after remote convergence.

### Compute command failures

Compute failures report the lifecycle stage, safe command prefix, resolved
executable and exit status when available, plus sanitized stderr. Missing
executables, process launch failures and timeouts have distinct explanations;
an unknown or older diagnostic keeps its authored message. For `ssh`, failures
before connecting begin with `Cannot prepare SSH access`. If `tofu` is missing,
make OpenTofu available on `PATH` and retry. From this repository, use
`devenv shell -- blue/blue ssh`. Standalone launchers need OpenTofu on
their calling shell's `PATH`.

When the compute library identifies Google `invalid_rapt` reauthentication,
the error names it and explains how to renew local user Application Default
Credentials with `gcloud auth application-default login`, or renew the configured
credentials through their own authentication method. ONCE does not launch login
or retry operations automatically. Sanitized OpenTofu error text remains visible
when a diagnostic also contains structured source excerpts; those excerpts and
state/plan dumps stay suppressed to protect credentials and state. If nothing
useful remains, the message says the underlying cause could not be safely identified.

For OCI `401-NotAuthenticated`, check the configured session with
`oci session validate --local --profile <oci-config-file-profile>`. Renew a
still-refreshable session with `oci session refresh --profile <oci-config-file-profile>`.
If the session is no longer valid, authenticate again with
`oci session authenticate --region <region> --profile-name <oci-config-file-profile>`.
Then retry the ONCE command. A 401 can also mean incorrect credentials; it does
not by itself prove expiry. See the [OCI session documentation](https://docs.oracle.com/en-us/iaas/Content/API/SDKDocs/clitoken.htm).

## Ordinary SSH access

Run `blue/blue ssh-install` (or the copied launcher's `ssh-install`) to export the existing encrypted keypair to `~/.ssh/once/<profile>/` and install the `ssh <profile>` alias with the live server address. SCP and editor SSH connections can use the same alias; ordinary SSH prompts for the key passphrase. Installation checks config ownership before exporting and never writes a decrypted private key. It requires backend/provider access but does not start an agent.

Create preserves an installed identity when refreshing aliases. Delete removes aliases and retains the encrypted export. Run `blue/blue ssh-uninstall` to remove owned aliases and exported files locally; no backend/provider credentials or passphrase are needed. Both commands honor `--dry-run` without state access. Launcher `ssh`, describe and application convergence continue using temporary scoped agents.

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
