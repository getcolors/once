# package-once-red

## Shared compute lifecycle

The package pins colors-compute for VM providers, remote compute state and SSH
ownership. One host uses the same operation as a cluster node, with an unnumbered
cloud name and a profile-based SSH alias. Compute now finishes before SMTP.
A legacy-state or ownership failure therefore prevents application resource
creation. Provider support comes from the library dependency.

Compute uses `<profile>/compute/shared.tfstate`, `<profile>/compute/nodes/0.tfstate`
and `<profile>/compute/coordination.json`. Existing `tofu-compute.tfstate` needs
an explicit state migration before convergence. R2 and S3 are supported;
local compute state and `provider-compute: no-infra` are refused. Supply
`compute-ssh-sources` and `compute-http-sources`, or the selected provider's
legacy source keys. See the bundled skill's configuration reference for details.


The TypeScript/Bun implementation of the production ONCE deployment package.
It is byte-compatible with the Green and Blue implementations and manages the
same `.colors/<profile>/` state.

The package pins Basecamp ONCE to **v0.3.3**. A real `create` installs the
Linux amd64 or arm64 release binary with its pinned SHA-256 checksum, replacing
an existing binary when it differs. The managed background service disables
binary self-updates with `ONCE_NO_SELF_UPDATE=1` and restarts when the binary or
service configuration changes. Application image `auto_update` and backups
remain available. Upgrading the binary does not recreate application containers;
v0.3.3's `BASE_URL` environment variable reaches an existing application only
when its container is recreated. The ONCE version is fixed by the package,
not a `colors.yml` setting.

```sh
bun install
./red build
./red create --dry-run
bun test
bun run typecheck
```

Desired state is the `colors.yml` found by walking up from the working
directory — the same file green and blue read, so switching colours needs no
change to it. Yandex compute supports `yandex-static-ip`,
`yandex-allow-stopping-for-update`, and optional `yandex-image-id`. Yandex Cloud
DNS creates public zones and direct records with `provider-dns: yandex`.
Secrets use `COLORS_PAR_*`; never place them in `colors.yml`. See
the unified [`../index.html`](../index.html) manual and
[`../skills/package-once-red`](../skills/package-once-red).

An application naming `github: owner/repo` gets `SSH_PRIVATE_KEY`, `SERVER_IP`,
`SERVER_USER`, and `SSH_KNOWN_HOSTS` published into an Actions environment named
after the profile on every `create` — but nothing reads them until a workflow in
that repository does.
[`../skills/package-once-red/references/github-deploy.md`](../skills/package-once-red/references/github-deploy.md)
is that workflow.

Resend CNAME targets are preserved in Cloudflare DNS records, with DNS-only
proxy mode and automatic TTL. TXT quoting and MX priorities are retained.
