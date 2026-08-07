# Red ONCE configuration

No manifest is involved. The bundled `red` carries both repositories pinned to
full 40-character commits in its `PINS`, and resolves them itself on first run
into `~/.cache/package-once-red/`, keyed by the pins so a re-pin never reuses an
older tree. Nothing is written into the project.

`PINS` is the only source of versions — a `package.json` in the project is never
read, exactly as green's inline SHAs and blue's PEP 723 metadata are the only
source for them. Do not write one, and do not add these dependencies to an
existing one expecting it to take effect. Repin by re-installing the skill.

`colors.yml` is a YAML map. Provider settings are flat; applications are the only
nested collection. Quote version-like YAML values such as `3.10`.

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

Application `env` maps the container variable name to a flat desired-state key.
Do not add that key's value to YAML. Supply it as `COLORS_PAR_APP_DATABASE_URL` or
`COLORS_PAR_APP_DATABASE_URL`.

Provider choices and required fields match the unified repository manual:

- compute: `azure`, `aws`, `google`, `digitalocean`, `hcloud`, `yandex`, `oci`, `no-infra`
- SMTP: `resend`, `no-infra`
- DNS: `cloudflare`, `yandex`, `no-infra`
- backend: `local`, `s3`, `r2`

Credentials use `COLORS_PAR_*`, the one namespace every colour shares:
`DO_TOKEN`, `HCLOUD_TOKEN`, `YANDEX_TOKEN`, `RESEND_API_KEY`,
`RESEND_PASSWORD`, `NO_INFRA_SMTP_PASSWORD`, `CLOUDFLARE_API_TOKEN`,
`R2_ACCESS_KEY_ID`, and `R2_SECRET_ACCESS_KEY`. OCI uses its configured profile;
Azure uses the ambient Azure CLI session; Google uses Application Default Credentials; AWS compute and S3 use OpenTofu's ambient AWS chain; SSH uses `ssh-agent`.

Application hosts derive all DNS zones. Only listed hosts receive A records.
Every zone receives its own `notifications.<zone>` Resend domain.
