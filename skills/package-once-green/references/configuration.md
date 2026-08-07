# Configuration reference

The generated `colors.yml` is one root EDN map with flat provider and setting keys, except for applications nested under `:once {:applications [...]}`. Include only selected providers' non-secret settings. Never add credentials or passwords.

Launcher-managed provider credentials and application secrets reach the workflow through `COLORS_PAR_*` variables — one namespace shared by green, red and blue — which are overlaid onto matching flat keys before anything runs. A variable name is the key uppercased with hyphens as underscores, so `do-token` is supplied by `COLORS_PAR_DO_TOKEN`; there is no `TF_VAR_*` alias. S3 is the exception: OpenTofu resolves its ambient AWS credential chain directly. OCI authenticates through the selected profile in `~/.oci/config`, and SSH private keys remain outside the project in `ssh-agent`. From there no secret is written into a rendered file: launcher-managed OpenTofu credentials are passed to each stage under the variable that provider reads natively, and Ansible receives a byte-compatible lookup expression resolving `COLORS_PAR_*` when the play runs. Overrides are coerced to the type of the value they replace, so booleans and integers stay booleans and integers.

## Base shape

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
    - host: www.example.net
      image: ghcr.io/example/another-site:latest

provider-compute: digitalocean
provider-smtp: resend
provider-dns: cloudflare
provider-backend: r2
compute-prevent-destroy: true

# Add the selected providers' non-secret fields here.
```

`:profile` names the stack: it is the working-directory and state-key prefix, the `name` the compute stage reports, and the `Host` alias written into `~/.ssh/config`.

There is no domain key. Application hostnames are the source of truth and may span domains. Green derives each distinct DNS zone from the hostname's last two labels, creates one Resend sending domain (`notifications.<zone>`) per zone, and gives each application a matching `info@notifications.<zone>` From address. Each application gets its own proxied `A` record — no implicit apex or wildcard record is created, so a hostname that is not listed here does not resolve.

`:env` maps a container variable name to the flat key holding its value; the value itself never appears in the file, and is supplied by the `COLORS_PAR_*` variable named after that key (`:app-database-url` ← `COLORS_PAR_APP_DATABASE_URL`). Application options supported by the ONCE reconciler also include `:auto_update`, `:auto_backup`, `:backup_path`, `:disable_tls`, `:cpus`, and `:memory`.

Deploy keys are not configured here, and they are per repository rather than
per application. Every repository named under `github` gets one keypair,
generated fresh on every `create` and never stored: the public half is installed
on the server behind a ForceCommand naming every host that repository serves,
and the private half is published, with the server address, user, and host key,
to a GitHub Actions environment named after the profile. Two applications may
name the same repository — one image answering for several hosts — and share
one key that updates both. The
previous generation is kept alongside the current one so a publication that
fails leaves the old key working until the next `create` heals it.
The host key is read from the server itself and published as
`SSH_KNOWN_HOSTS`, so a workflow can pin it instead of running `ssh-keyscan`
and trusting whatever answers on that address every deploy.

`COLORS_PAR_GITHUB_TOKEN` is required whenever any application names a
repository — for `delete` too, which withdraws what `create` published.

Publishing is only half of it: nothing reads those values until a workflow in
that repository does. [github-deploy.md](github-deploy.md) is the example
workflow and the contract it consumes.

`:compute-pubkey` is required by Yandex Cloud, which installs it for the `ubuntu` user through instance metadata. It is optional and unused by the other compute providers: DigitalOcean and Hetzner reference keys already registered with them, while OCI reads a local public-key file named by `:oci-ssh-authorized-keys`. If present it must look like a public key.

## Compute providers

### Azure

```yaml
provider-compute: azure
azure-subscription-id: 00000000-0000-0000-0000-000000000000
azure-location: swedencentral
azure-resource-group: once
azure-name: once
azure-vm-size: Standard_D2pls_v6
azure-image-publisher: Canonical
azure-image-offer: ubuntu-24_04-lts
azure-image-sku: server-arm64
azure-image-version: 24.04.202608020
azure-vnet-cidr: 10.10.0.0/16
azure-subnet-cidr: 10.10.1.0/24
azure-boot-disk-size-gb: 30
azure-ssh-authorized-keys: ~/.ssh/id_ed25519.pub
```

Azure uses the native Azure CLI session from `az login`. It creates a dedicated resource group, VNet, subnet, public IP, network security group, NIC, and VM.

### AWS

```yaml
provider-compute: aws
aws-region: eu-west-1
aws-availability-zone: eu-west-1a
aws-name: once
aws-instance-type: t3.small
aws-image-id: ami-...
aws-vpc-cidr: 10.0.0.0/16
aws-subnet-cidr: 10.0.1.0/24
aws-root-volume-size-gb: 30
aws-ssh-authorized-keys: ~/.ssh/id_ed25519.pub
```

AWS uses the native credential chain (for example, temporary credentials from `aws login`). It creates an isolated VPC, public subnet, internet gateway, security group, key pair, and EC2 instance.

### Google Cloud

```yaml
provider-compute: google
google-project: example-project
google-region: europe-west4
google-zone: europe-west4-b
google-name: once
google-machine-type: t2a-standard-1
google-image-project: ubuntu-os-cloud
google-image-family: ubuntu-2404-lts-arm64
google-image-id: projects/ubuntu-os-cloud/global/images/ubuntu-2404-noble-arm64-v20260723
google-subnet-cidr: 10.20.1.0/24
google-boot-disk-size-gb: 30
google-ssh-authorized-keys: ~/.ssh/id_ed25519.pub
```

Google uses Application Default Credentials from `gcloud auth application-default login`. It creates a custom VPC, subnet, firewall rule, static address, and Compute Engine instance.

### DigitalOcean

```yaml
provider-compute: digitalocean
digitalocean-name: once
digitalocean-region: ams3
digitalocean-size: s-1vcpu-1gb-35gb-intel
digitalocean-image: ubuntu-24-04-x64
digitalocean-ssh-keys: fingerprint-or-id-already-in-the-account
# Optional:
digitalocean-vpc-uuid: non-secret-vpc-uuid
```

Required credential: `COLORS_PAR_DO_TOKEN`.

### Hetzner Cloud

```yaml
provider-compute: hcloud
hcloud-name: once
hcloud-image: ubuntu-24.04
hcloud-server-type: cx23
hcloud-location: hel1
hcloud-ssh-keys: key-name-or-id-already-in-the-project
```

Required credential: `COLORS_PAR_HCLOUD_TOKEN`.

### Yandex Cloud

```yaml
provider-compute: yandex
compute-pubkey: ssh-ed25519 AAAA... operator
yandex-cloud-id: b1g...
yandex-folder-id: b1g...
yandex-zone: kz1-a
yandex-image-family: ubuntu-2404-lts
yandex-name: once
yandex-subnet-cidr: 10.0.0.0/24
yandex-platform-id: standard-v3
yandex-cores: 2
yandex-memory-gb: 2
yandex-core-fraction: 100
yandex-disk-size-gb: 20
```

Green creates a network, subnet, NAT-enabled instance, and boot disk. The public key is installed for the `ubuntu` user; keep its private half in `ssh-agent`. Required credential: `COLORS_PAR_YANDEX_TOKEN`, passed to OpenTofu as the provider-native `YC_TOKEN`.

### Oracle Cloud Infrastructure

```yaml
provider-compute: oci
oci-config-file-profile: DEFAULT
oci-subnet-id: ocid1.subnet...
oci-compartment-id: ocid1.compartment...
oci-availability-domain: ...
oci-display-name: once
oci-shape: VM.Standard.A1.Flex
oci-image-id: ocid1.image...   # optional; pins the boot image
oci-ocpus: 1
oci-memory-in-gbs: 4
oci-boot-volume-size-in-gbs: 50
oci-boot-volume-vpus-per-gb: 30
oci-ssh-authorized-keys: /home/user/.ssh/once.pub
```

No credential variable is required: OCI authenticates through the named profile in `~/.oci/config`. `:oci-ssh-authorized-keys` is a path to a public-key file on the machine running the launcher, read at plan time.

`:oci-image-id` is optional and pins the boot image. Unset, ONCE takes the
newest Canonical Ubuntu 24.04 image compatible with `:oci-shape` — right for a
first `create`, a moving target after that, because the image id forces
replacement and Canonical keeps publishing. Set it once the stack is real;
`tofu state show oci_core_instance.ampere_vm` reports the running image as
`source_id`. Changing it destroys and recreates the server, which
`:compute-prevent-destroy` (true by default) turns into a refused apply.

### Existing server

```yaml
provider-compute: no-infra
no-infra-compute-ip: 203.0.113.10
no-infra-compute-user: root
no-infra-compute-sudoer: root
no-infra-compute-uid: 0
```

No compute API credential is required. SSH authentication must already work through `ssh-agent`. The host is reported under `:profile`, so it needs no name of its own.

## SMTP providers

### Resend

```yaml
provider-smtp: resend
```

Resend's relay (`smtp.resend.com:587`, user `resend`) is the same for every account, so it is hard-coded rather than configured. Green registers and verifies `notifications.<zone>` for every distinct application zone. Each application sends from `info@notifications.<its-zone>`.

Required credentials: `COLORS_PAR_RESEND_API_KEY` for the Resend API, and `COLORS_PAR_RESEND_PASSWORD` for the SMTP password written into the server's mail configuration.

### Existing SMTP

```yaml
provider-smtp: no-infra
no-infra-smtp-server: smtp.example.net
no-infra-smtp-port: 587
no-infra-smtp-username: smtp-user
```

Required credential: `COLORS_PAR_NO_INFRA_SMTP_PASSWORD`.

## DNS providers

Use `:provider-dns "cloudflare"`, `"yandex"`, or `"no-infra"`.

### Cloudflare

```yaml
provider-dns: cloudflare
```

Requires `COLORS_PAR_CLOUDFLARE_API_TOKEN`. Every zone derived from the application hosts must already exist in the Cloudflare account, and the token needs permission to discover and manage each of them: one proxied `A` record per application host, plus each zone's Resend verification records.

### Yandex Cloud DNS

```yaml
provider-dns: yandex
yandex-cloud-id: b1g...
yandex-folder-id: b1g...
```

Unlike Cloudflare, the zones do not have to exist beforehand: ONCE creates a public DNS zone in the configured folder for every domain derived from the application hosts, then adds one `A` record per application host and each zone's Resend verification records. Records are not proxied — hosts resolve straight to the server. Delegate each domain once at its registrar to `ns1.yandexcloud.net` and `ns2.yandexcloud.net`. Required credential: `COLORS_PAR_YANDEX_TOKEN`, passed to OpenTofu as the provider-native `YC_TOKEN` — the same token the Yandex compute provider uses, so selecting both means setting it once.

### Existing DNS

```yaml
provider-dns: no-infra
```

Renders an empty DNS module and requires no credential; application and Resend records are your responsibility.

## State backends

### Local

```yaml
provider-backend: local
```

Each tool keeps isolated state under `<workdir>/<profile>/<tool>/`.

### Amazon S3

```yaml
provider-backend: s3
s3-bucket: once-tfstate
s3-region: eu-west-1
```

No `COLORS_PAR_*` credential: the generated backend names only the bucket, key, and region, so OpenTofu resolves credentials through its own AWS chain (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`, a shared profile, or an instance role). State keys are derived as `<profile>/<tool>.tfstate`.

### Cloudflare R2

```yaml
provider-backend: r2
r2-bucket: once-tfstate
r2-endpoint: https://ACCOUNT_ID.r2.cloudflarestorage.com
```

Required credentials: `COLORS_PAR_R2_ACCESS_KEY_ID` and `COLORS_PAR_R2_SECRET_ACCESS_KEY`. R2 is configured as an S3-compatible backend with `region = "auto"`. State keys are derived as `<profile>/<tool>.tfstate`.

## Safe lifecycle

`build` and dry-run do not require credentials; unset values simply render empty, so a build never writes a secret to disk. Real create validates all selected provider credentials and every application `:env` reference before running. Real delete validates provider credentials and refuses while `:compute-prevent-destroy` is true.

To authorize an intentional delete without editing committed desired state:

```sh
export COLORS_PAR_COMPUTE_PREVENT_DESTROY=false
./green delete --dry-run
./green delete
```

`COLORS_PAR_*` overrides any flat key, not just secrets: strip the prefix, lowercase the name, and replace underscores with hyphens. For example, `COLORS_PAR_DIGITALOCEAN_REGION=fra1` overrides `:digitalocean-region`.
