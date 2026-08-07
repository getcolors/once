# Blue ONCE configuration

The copied launcher's PEP 723 metadata must resolve both packages from immutable
40-character commits:

```toml
# dependencies = ["package-once-blue", "blue"]
# [tool.uv.sources]
# package-once-blue = { git = "https://github.com/getcolors/once.git", rev = "<once-commit>", subdirectory = "blue" }
# blue = { git = "https://github.com/getcolors/blue.git", rev = "<blue-commit>" }
```

Replace both placeholders and remove development-only local paths before the
launcher is copied.

`colors.yml` has the same YAML shape as Red. Quote version-like values such as
`3.10` so YAML does not parse them as numbers.

```yaml
profile: production
workdir: .colors
once:
  applications:
    - host: www.example.com
      image: ghcr.io/example/site:latest
      github: acme/site
      env:
        DATABASE_URL: app-database-url
provider-compute: digitalocean
provider-smtp: resend
provider-dns: cloudflare
provider-backend: r2
compute-prevent-destroy: true
```

Application `github` is optional, as `owner/repo`. Deploy keys are never
configured here, and they are per repository rather than per application: every
repository named gets one keypair, generated fresh on every `create` and never
stored. The public half is installed on the server behind a ForceCommand naming
every host that repository serves, so a key leaked from one repository cannot
redeploy another's application, while two applications sharing a repository —
one image answering for several hosts — share one key that updates both. The private half is
published to a GitHub Actions environment named after the profile, alongside
`SERVER_IP`, `SERVER_USER`, and `SSH_KNOWN_HOSTS` — the server's own host key,
so a workflow can pin it instead of running `ssh-keyscan` and trusting whatever
answers on that address every deploy. The previous generation stays authorized
until the new one is published, so a failed publication heals on the next
`create`. Requires `COLORS_PAR_GITHUB_TOKEN`, for `delete` too, which withdraws
what `create` published. Nothing reads those values until a workflow in that
repository does; [github-deploy.md](github-deploy.md) is the example workflow
and the contract it consumes.

Application `env` maps a container variable to a flat key. Supply its value as
`COLORS_PAR_APP_DATABASE_URL` or `COLORS_PAR_APP_DATABASE_URL`; never put it in
YAML.

Providers:

- compute: `azure`, `aws`, `google`, `digitalocean`, `hcloud`, `yandex`, `oci`, `no-infra`
- SMTP: `resend`, `no-infra`
- DNS: `cloudflare`, `yandex`, `no-infra`
- backend: `local`, `s3`, `r2`

Credential suffixes are `DO_TOKEN`, `HCLOUD_TOKEN`, `YANDEX_TOKEN`,
`RESEND_API_KEY`, `RESEND_PASSWORD`, `NO_INFRA_SMTP_PASSWORD`,
`CLOUDFLARE_API_TOKEN`, `R2_ACCESS_KEY_ID`, and `R2_SECRET_ACCESS_KEY`. Prefix
with `COLORS_PAR_`. OCI uses its profile, Azure uses the ambient Azure CLI session, Google uses Application Default Credentials, and AWS compute and S3 use the AWS
credential chain, and SSH uses `ssh-agent`.

Application hosts derive DNS zones and Resend domains. Only explicitly listed
hosts receive application A records.
