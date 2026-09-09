# ONCE configuration

All colors read the same `colors.yml`. Compute providers, their credentials,
SSH key references and remote compute state come from the pinned
[colors-compute library](https://github.com/getcolors/colors-compute).
Package code owns the ONCE application, SMTP, DNS and GitHub stages.

```yaml
profile: production
workdir: .colors
provider-compute: digitalocean
provider-backend: r2
provider-smtp: resend
provider-dns: cloudflare
compute-prevent-destroy: true
compute-ssh-sources: ["203.0.113.0/24"] # replace with the operator's CIDR
compute-http-sources: ["0.0.0.0/0"]
digitalocean-region: ams3
digitalocean-size: s-1vcpu-1gb
digitalocean-image: ubuntu-24-04-x64
r2-bucket: example-state
r2-endpoint: https://ACCOUNT.r2.cloudflarestorage.com
once:
  applications:
    - host: www.example.com
      image: ghcr.io/example/site:latest
      github: example/site
      env:
        DATABASE_URL: app-database-url
```

Replace example values. Quote version strings. `compute-ssh-sources` admits TCP
22; `compute-http-sources` admits TCP 80 and 443. The library also resolves the
selected provider's legacy `*-ssh-sources` and `*-http-sources` keys. An absent
source list is an error. Only providers supporting the requested firewall and
network capabilities can run the configuration.

The machine name defaults to `profile`; an optional provider-scoped name
changes the cloud label. SSH aliases and key ownership remain profile-based.
Leave the provider's public-key reference absent to let the library manage
`~/.ssh/<profile>`. A present reference selects external ownership. Blank
references are errors. For external SSH, use an agent or an explicit
`ssh-private-key-path`. Private key content is never configuration.

Compute supports R2 and S3 remote state. S3 needs `s3-bucket` and `s3-region`
and uses the ambient AWS credential chain. R2 needs `r2-bucket`, `r2-endpoint`,
`COLORS_PAR_R2_ACCESS_KEY_ID` and `COLORS_PAR_R2_SECRET_ACCESS_KEY`.
`provider-compute: no-infra` and local compute state are unsupported.

Compute state keys are `<profile>/compute/shared.tfstate` and
`<profile>/compute/nodes/0.tfstate`. The library coordinates ownership in
`<profile>/compute/coordination.json`. Existing `<profile>/tofu-compute.tfstate`
requires a reviewed state migration before create. The library refuses to
adopt or overwrite it automatically. Back up state and review resource address
transfers and plans before a live migration. An empty build is not migration
proof. SMTP and DNS retain `<profile>/<tool>.tfstate`.

Yandex reserves a public address by default. Explicit `yandex-static-ip: false`
selects a dynamic address; true retains the reservation.
`yandex-allow-stopping-for-update` separately permits updates needing a stop.
A named `yandex-image-id` pins the boot image. The library refuses replacement
plans during ordinary convergence.

SMTP is `resend` or `no-infra`. Resend needs `COLORS_PAR_RESEND_API_KEY` and
`COLORS_PAR_RESEND_PASSWORD`. External SMTP uses `no-infra-smtp-server`,
`no-infra-smtp-port`, `no-infra-smtp-username` and
`COLORS_PAR_NO_INFRA_SMTP_PASSWORD`. DNS is `cloudflare`, `yandex` or `no-infra`.
Cloudflare uses `COLORS_PAR_CLOUDFLARE_API_TOKEN`; Yandex uses its cloud/folder
settings and `COLORS_PAR_YANDEX_TOKEN`.

Applications derive DNS zones and Resend domains from their hostnames. Only
listed hosts receive A records. Application `env` maps container variable names
to flat parameter keys, whose values arrive through `COLORS_PAR_*`.
Never put the values in YAML.

Application `github` is optional `owner/repo`. Each named repository gets one
fresh deploy key during create, restricted to its listed hosts. ONCE publishes
the private key and server connection facts to an Actions environment named
after the profile. It retains one previous authorized generation for recovery
from a failed publication. These operations need `COLORS_PAR_GITHUB_TOKEN`,
including delete, which withdraws the credentials. With no repository named,
no GitHub token is required. See [github-deploy.md](github-deploy.md) for the
application workflow consuming those values.
