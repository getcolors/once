---
name: package-once-red
description: Create and operate single-server Basecamp ONCE deployments with Red. Use for colors.yml configuration, builds, dry-runs, provisioning, deletion and status reports.
license: MIT
---

# ONCE with Red

Use the bundled `red` launcher in the deployment directory. It runs with
Bun and resolves immutable package dependencies. Read
[configuration.md](references/configuration.md) before changing desired state.
Read [github-deploy.md](references/github-deploy.md) when adding continuous
deployment to an application's repository.

DMARC management is opt-in through `smtp-dmarc-policy`; see the configuration
reference for provider restrictions, reporting addresses, existing-record import,
and preserving a DNS record when removing management. Review any inherited
policy before setting an explicit sending-domain policy.

The package calls colors-compute for one host. The library owns provider
selection, credentials, remote state and SSH key lifecycle. Update its dependency
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
Validate with `./red build` and `./red create --dry-run`. These commands
contact no provider and do not prove credentials or live health.

A real create or delete needs user authorization. Existing authorization for
the task applies; do not ask again. Create validates, converges compute, then
SMTP, DNS, verification and application configuration. Compute ownership failure
stops subsequent resource creation. Delete loads recorded compute inventory,
withdraws published credentials and SSH configuration, removes DNS and SMTP,
then destroys compute. The library removes its SSH key after successful destroy.
Keep `compute-prevent-destroy: true` in desired state; an authorized delete can
use `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` for that invocation.

Existing monolithic compute state requires an explicit reviewed migration. Do
not run a new create against it to discover what happens. Missing, unreadable or
inconsistent state is a refusal, not permission to recreate resources. A failed
operation retains state needed for inspection and retry.

Use `./red describe` for recorded compute status and application inspection.
It reads compute through the library and requires a verified address before SSH.
For an application naming a GitHub repository, establish whether continuous
deployment is part of the user's request before adding its workflow. Confirm
the target repository matches `owner/repo`; the deployment repository may be a
different checkout. Follow the linked reference for the exact published values.

Create and build serialize the package-owned SSH alias stage before remote Ansible. A failed local ownership check stops application convergence; GitHub publication remains after remote convergence.
