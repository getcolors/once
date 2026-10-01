# package-once-red

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

The remote play waits for SSH, then explicitly gathers the platform facts
needed to validate Linux, CPU architecture, and the service manager before
installing ONCE. Automatic gathering stays disabled so it cannot precede SSH
readiness.

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
