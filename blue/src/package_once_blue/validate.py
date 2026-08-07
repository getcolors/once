from __future__ import annotations

import re
from typing import Any

from blue.cli import par_name

providers: dict[str, dict[str, dict[str, Any]]] = {
    "provider-compute": {
        "azure": {"required": ["azure-subscription-id", "azure-location", "azure-resource-group", "azure-name", "azure-vm-size", "azure-image-publisher", "azure-image-offer", "azure-image-sku", "azure-image-version", "azure-vnet-cidr", "azure-subnet-cidr", "azure-boot-disk-size-gb", "azure-ssh-authorized-keys"], "secrets": [], "tofu-env": {}},
        "aws": {"required": ["aws-region", "aws-availability-zone", "aws-name", "aws-instance-type", "aws-image-id", "aws-vpc-cidr", "aws-subnet-cidr", "aws-root-volume-size-gb", "aws-ssh-authorized-keys"], "secrets": [], "tofu-env": {}},
        "google": {"required": ["google-project", "google-region", "google-zone", "google-name", "google-machine-type", "google-image-project", "google-image-family", "google-image-id", "google-subnet-cidr", "google-boot-disk-size-gb", "google-ssh-authorized-keys"], "secrets": [], "tofu-env": {}},
        "digitalocean": {"required": ["digitalocean-name", "digitalocean-region", "digitalocean-size", "digitalocean-image", "digitalocean-ssh-keys"], "secrets": ["do-token"], "tofu-env": {"do-token": "DIGITALOCEAN_TOKEN"}},
        "hcloud": {"required": ["hcloud-name", "hcloud-image", "hcloud-server-type", "hcloud-location", "hcloud-ssh-keys"], "secrets": ["hcloud-token"], "tofu-env": {"hcloud-token": "HCLOUD_TOKEN"}},
        "yandex": {"required": ["yandex-cloud-id", "yandex-folder-id", "yandex-zone", "yandex-image-family", "yandex-name", "yandex-subnet-cidr", "yandex-platform-id", "yandex-cores", "yandex-memory-gb", "yandex-core-fraction", "yandex-disk-size-gb", "compute-pubkey"], "secrets": ["yandex-token"], "tofu-env": {"yandex-token": "YC_TOKEN"}},
        "oci": {"required": ["oci-config-file-profile", "oci-subnet-id", "oci-compartment-id", "oci-availability-domain", "oci-display-name", "oci-shape", "oci-ocpus", "oci-memory-in-gbs", "oci-boot-volume-size-in-gbs", "oci-boot-volume-vpus-per-gb", "oci-ssh-authorized-keys"], "secrets": [], "tofu-env": {}},
        "no-infra": {"required": ["no-infra-compute-ip", "no-infra-compute-user", "no-infra-compute-sudoer", "no-infra-compute-uid"], "secrets": [], "tofu-env": {}},
    },
    "provider-smtp": {
        "resend": {"required": [], "secrets": ["resend-api-key", "resend-password"], "tofu-env": {"resend-api-key": "RESEND_API_KEY"}},
        "no-infra": {"required": ["no-infra-smtp-server", "no-infra-smtp-port", "no-infra-smtp-username"], "secrets": ["no-infra-smtp-password"], "tofu-env": {}},
    },
    "provider-dns": {
        "cloudflare": {"required": [], "secrets": ["cloudflare-api-token"], "tofu-env": {"cloudflare-api-token": "CLOUDFLARE_API_TOKEN"}},
        # Unlike Cloudflare, the Yandex DNS stage creates the public zones
        # itself, so it needs the folder to put them in. The token is the same
        # one the Yandex compute provider uses; selecting both demands it once.
        "yandex": {"required": ["yandex-cloud-id", "yandex-folder-id"], "secrets": ["yandex-token"], "tofu-env": {"yandex-token": "YC_TOKEN"}},
        "no-infra": {"required": [], "secrets": [], "tofu-env": {}},
    },
    "provider-backend": {
        "local": {"required": [], "secrets": [], "tofu-env": {}},
        "s3": {"required": ["s3-bucket", "s3-region"], "secrets": [], "tofu-env": {}},
        "r2": {"required": ["r2-bucket", "r2-endpoint"], "secrets": ["r2-access-key-id", "r2-secret-access-key"], "tofu-env": {"r2-access-key-id": "AWS_ACCESS_KEY_ID", "r2-secret-access-key": "AWS_SECRET_ACCESS_KEY"}},
    },
}

_slots = ["provider-compute", "provider-smtp", "provider-dns", "provider-backend"]
_domain_re = re.compile(r"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
_env_re = re.compile(r"^[A-Z_][A-Z0-9_]*$")


def _entry(opts: dict, slot: str) -> dict:
    return providers.get(slot, {}).get(str(opts.get(slot)), {})


def tofu_env(opts: dict, slot: str) -> dict[str, str]:
    return _entry(opts, slot).get("tofu-env", {})


def _slot_keys(opts: dict, field: str) -> list[str]:
    return [key for slot in _slots for key in _entry(opts, slot).get(field, [])]


def placeholder(value: object) -> bool:
    return value is None or (isinstance(value, str) and (not value.strip() or value.upper() == "REPLACE_ME"))


def _applications(opts: dict) -> list | None:
    value = (opts.get("once") or {}).get("applications")
    return value if isinstance(value, list) else None


_repo_re = re.compile(r"[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")


def deploy_groups(opts: dict) -> list[dict]:
    """One entry per distinct GitHub repository named in desired state.

    Each carries every host that repository serves:
    ``{"github": "owner/repo", "hosts": [...]}``.

    The repository, not the application, is the unit a deploy key belongs to. It
    is where the key is stored — a GitHub environment — and what triggers its
    use. Grouping here is what lets one image answer for several hosts: those
    hosts are one repository, one pipeline, one push. Keyed per application
    instead, two applications naming the same repository publish into the same
    environment and the second silently overwrites the first's key.

    Applications without ``github`` produce no group, and so no key at all.

    Order is first appearance in ``applications``, and hosts within a group keep
    their desired-state order, so the rendered artifact stays a pure function of
    the file all three colours read.
    """
    groups: dict[str, dict] = {}
    for app in _applications(opts) or []:
        if placeholder(app.get("github")):
            continue
        repo = str(app.get("github"))
        groups.setdefault(repo, {"github": repo, "hosts": []})["hosts"].append(str(app.get("host")))
    return list(groups.values())


def state_errors(opts: dict) -> list[str]:
    errors: list[str] = []
    for key in ["profile", "workdir", *_slot_keys(opts, "required")]:
        if placeholder(opts.get(key)):
            errors.append(f"{key} is required")
    for slot in _slots:
        if opts.get(slot) not in providers[slot]:
            errors.append(f"unsupported {slot} {opts.get(slot)!r}")
    apps = _applications(opts)
    if not apps:
        errors.append("once applications must be a non-empty sequence")
    for index, app in enumerate(apps or []):
        if placeholder(app.get("host")) or not _domain_re.fullmatch(str(app.get("host"))):
            errors.append(f"once applications[{index}] has an invalid host")
        if placeholder(app.get("image")):
            errors.append(f"once applications[{index}] requires image")
        # github is optional; a value that is present has to name a repository,
        # because it is interpolated straight into a gh invocation.
        if app.get("github") is not None and not _repo_re.fullmatch(str(app.get("github"))):
            errors.append(f"once applications[{index}] github must be owner/repo")
        env = app.get("env")
        if env is not None and not isinstance(env, (dict, list)):
            errors.append(f"once applications[{index}] env must map container variable names to colors.yml keys")
        if isinstance(env, dict):
            for name, key in env.items():
                if not _env_re.fullmatch(str(name)):
                    errors.append(f"once applications[{index}] has an invalid container variable name {name}")
                if placeholder(key):
                    errors.append(f"once applications[{index}] env {name} needs a colors.yml key")
    if not isinstance(opts.get("compute-prevent-destroy"), bool):
        errors.append("compute-prevent-destroy must be true or false")
    key = opts.get("compute-pubkey")
    if not placeholder(key) and not str(key).startswith("ssh-"):
        errors.append("compute-pubkey must be an SSH public key")
    return errors


def secret_errors(opts: dict) -> list[str]:
    apps = _applications(opts) or []
    app_keys = (
        [str(key) for app in apps if isinstance(app.get("env"), dict) for key in app["env"].values()]
        if opts.get("blue/event") == "create"
        else []
    )
    # A GitHub token is needed for both create and delete, because delete has to
    # revoke what create published.
    github_keys = ["github-token"] if deploy_groups(opts) else []
    keys = list(dict.fromkeys([*_slot_keys(opts, "secrets"), *github_keys, *app_keys]))
    return [f"required credential is not set: {par_name(key)}" for key in keys if placeholder(opts.get(key))]
