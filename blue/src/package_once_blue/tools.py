from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from blue import tofu
from blue.ansible import ansible_step, ansible_with_spec
from blue.scaffold import scaffold

from .github import public_keys
from .utils import apps_domains, registrable_domain
from .validate import tofu_env

_RESOURCE_ROOT = Path(__file__).parent / "resources"
_TEMPLATE_OPTS = {"tag_open": "<", "tag_close": ">", "filter_open": "{", "filter_close": "}"}


def _template(path: str) -> dict:
    return {"name": path, "content": (_RESOURCE_ROOT / path).read_text()}


_RAW = _template("raw")
_COMPUTE = {name: _template(f"tools/tofu/{name}/main.tf") for name in ["azure", "aws", "digitalocean", "google", "hcloud", "yandex", "oci", "no-infra"]}
_SMTP = {name: _template(f"tools/tofu-smtp/{name}/main.tf") for name in ["resend", "no-infra"]}
_DNS = {name: _template(f"tools/tofu-dns/{name}/main.tf") for name in ["cloudflare", "yandex", "no-infra"]}
_SMTP_POST = {name: _template(f"tools/tofu-smtp-post/{name}/main.tf") for name in ["resend", "no-infra"]}


def tool_dir(opts: dict, tool: str) -> str:
    """A relative workdir is resolved against the directory holding colors.yml,
    not the current one, so every colour shares one work directory however deep
    in the project it was invoked from."""
    workdir = Path(str(opts.get("workdir") or ".colors"))
    state_file = opts.get("blue/state-file")
    root = Path(state_file).parent / workdir if not workdir.is_absolute() and state_file else workdir
    return str(root / str(opts.get("profile") or "default") / tool)


def _spec(template: dict, target: str, data: dict) -> dict:
    return {"template": template, "target": target, "data": data, "opts": _TEMPLATE_OPTS}


def _raw_spec(target: str, content: str) -> dict:
    return _spec(_RAW, target, {"content": content})


def _credential_env(opts: dict, *slots: str) -> dict[str, str] | None:
    mappings: dict[str, str] = {}
    for slot in [*slots, "provider-backend"]:
        mappings.update(tofu_env(opts, slot))
    result = {variable: str(opts[key]) for key, variable in mappings.items() if opts.get(key) not in (None, "")}
    return result or None


def backend_credential_env(opts: dict) -> dict[str, str] | None:
    return _credential_env(opts)


def fallback_compute_params(opts: dict) -> dict:
    name = str(opts.get("profile") or "once")
    provider = opts.get("provider-compute")
    if provider == "azure":
        return {"ip": "192.168.0.1", "sudoer": "ubuntu", "uid": "1000", "name": name, "user": "ubuntu"}
    if provider == "aws":
        return {"ip": "192.168.0.1", "sudoer": "ubuntu", "uid": "1000", "name": name, "user": "ubuntu"}
    if provider == "oci":
        return {"ip": "192.168.0.1", "sudoer": "ubuntu", "uid": "1001", "name": name, "user": "ubuntu"}
    if provider == "yandex":
        return {"ip": "192.168.0.1", "sudoer": "ubuntu", "uid": "1000", "name": name, "user": "ubuntu"}
    if provider == "google":
        return {"ip": "192.168.0.1", "sudoer": "ubuntu", "uid": "1000", "name": name, "user": "ubuntu"}
    if provider == "no-infra":
        return {
            "ip": opts.get("no-infra-compute-ip") or "192.168.0.1",
            "sudoer": opts.get("no-infra-compute-sudoer") or "root",
            **({"uid": opts["no-infra-compute-uid"]} if opts.get("no-infra-compute-uid") is not None else {}),
            "name": name,
            "user": opts.get("no-infra-compute-user") or "root",
        }
    return {"ip": "192.168.0.1", "sudoer": "root", "name": name, "user": "root"}


def fallback_smtp_params(opts: dict) -> dict:
    if opts.get("provider-smtp") == "no-infra":
        return {"domains": [], "smtp_username": opts.get("no-infra-smtp-username"), "smtp_password": opts.get("no-infra-smtp-password"), "smtp_server": opts.get("no-infra-smtp-server"), "smtp_port": opts.get("no-infra-smtp-port")}
    if opts.get("provider-smtp") == "resend":
        return {
            "domains": [{"zone": zone, "id": f"domain-id-not-defined-{zone}", "records": []} for zone in apps_domains(opts)],
            "smtp_server": "smtp.resend.com", "smtp_port": 587, "smtp_username": "resend", "smtp_password": opts.get("resend-password"),
        }
    return {"domains": []}


async def _tofu_with_specs(opts: dict, dir: str, specs: list[dict], fallback: dict, result_key: str | None, env: dict | None) -> dict:
    result = await tofu.tofu_with_spec(opts, specs, dir=dir, env=env)
    if not result_key or (result.get("blue/exit") or 0) > 0 or opts.get("blue/event") == "delete":
        return result
    if opts.get("blue/event") == "build":
        return {**result, result_key: fallback}
    outputs = (result.get("tofu/outputs") or {}).get("params") or {}
    return {**result, result_key: {**fallback, **outputs}}


def _with_zones(opts: dict) -> dict:
    zones = apps_domains(opts)
    return {**opts, "zones": zones, "zones-hcl": tofu.hcl_list(zones)}


async def tofu_compute_step(opts: dict) -> dict:
    provider = str(opts.get("provider-compute") or "hcloud")
    dir = tool_dir(opts, "tofu-compute")
    return await _tofu_with_specs(opts, dir, [_spec(_COMPUTE[provider], f"{dir}/main.tf", opts)], fallback_compute_params(opts), "once/compute-params", _credential_env(opts, "provider-compute"))


async def tofu_smtp_step(original: dict) -> dict:
    opts = _with_zones(original)
    provider = str(opts.get("provider-smtp") or "resend")
    dir = tool_dir(opts, "tofu-smtp")
    return await _tofu_with_specs(opts, dir, [_spec(_SMTP[provider], f"{dir}/main.tf", opts)], fallback_smtp_params(opts), "once/smtp-params", _credential_env(opts, "provider-smtp"))


def _add_suffix(name: str, suffix: str) -> str:
    namespace, slash, local = name.partition("/")
    return f"{namespace}/{local}{suffix}" if slash else f"{name}{suffix}"


def _zone_id(zone: str) -> str:
    return '${data.cloudflare_zone.domains[' + json.dumps(zone) + "].id}"


def _yandex_zone_id(zone: str) -> str:
    return "${yandex_dns_zone.domains[" + json.dumps(zone) + "].id}"


def _yandex_fqdn(s: object) -> str:
    """Yandex record names and targets are absolute; without the trailing dot
    the API would read them as relative to the zone."""
    value = str(s)
    return value if value.endswith(".") else f"{value}."


# DNS provider -> the record resource its generated .tf.json files declare.
# A provider absent here (no-infra) gets no generated records at all.
_DNS_RECORD_RESOURCES = {
    "cloudflare": "cloudflare_dns_record",
    "yandex": "yandex_dns_recordset",
}


def _app_record(provider: str, ip: object, host: str) -> dict:
    zone = registrable_domain(host)
    if provider == "cloudflare":
        return {"zone_id": _zone_id(zone), "name": host, "content": ip, "type": "A", "proxied": True, "ttl": 1}
    # Yandex has no proxy: the record resolves straight to the server.
    return {"zone_id": _yandex_zone_id(zone), "name": _yandex_fqdn(host), "type": "A", "ttl": 300, "data": [ip]}


def _smtp_record(provider: str, zone: str, record: dict) -> dict:
    if provider == "cloudflare":
        block = {"zone_id": _zone_id(zone), "name": record.get("name"), "ttl": "1", "type": record.get("type"), "proxied": False}
        if record.get("type") == "TXT":
            block["content"] = f'"{record.get("value")}"'
        if record.get("type") == "MX":
            block.update({"priority": record.get("priority"), "content": record.get("value")})
        return block
    # Yandex recordsets carry everything in data — the MX priority is part of
    # the value, and TXT values are quoted like a zone file.
    if record.get("type") == "TXT":
        data = f'"{record.get("value")}"'
    elif record.get("type") == "MX":
        data = f"{record.get('priority')} {_yandex_fqdn(record.get('value'))}"
    else:
        data = record.get("value")
    return {"zone_id": _yandex_zone_id(zone), "name": _yandex_fqdn(record.get("name")), "ttl": 300, "type": record.get("type"), "data": [data]}


def render_fn(source: str, data: dict) -> str:
    provider = str(data.get("provider") or "cloudflare")
    resource = _DNS_RECORD_RESOURCES[provider]
    if source == "apps":
        # One A record per application host — proxied on Cloudflare, plain on
        # Yandex. There is no implicit apex or wildcard record: only the hosts
        # desired state names resolve to the server.
        return tofu.constructs_json([
            tofu.construct("resource", resource, _add_suffix("io.github.getcolors.once.tools/app-dns", f"-{app['host']}"),
                           _app_record(provider, data.get("ip"), app["host"]))
            for app in data.get("applications", [])
        ])
    constructs = []
    for domain in data.get("domains", []):
        for record in domain.get("records", []):
            constructs.append(tofu.construct("resource", resource, _add_suffix("io.github.getcolors.once.tools/smtp-dns", f"-{domain['zone']}-{record.get('record')}-{record.get('type')}"), _smtp_record(provider, domain["zone"], record)))
    return tofu.constructs_json(constructs)


def _joined_params(opts: dict) -> dict:
    branches = opts.get("blue/branches") or []
    compute = next((b.get("once/compute-params") for b in branches if b.get("once/compute-params")), None) or opts.get("once/compute-params") or fallback_compute_params(opts)
    smtp = next((b.get("once/smtp-params") for b in branches if b.get("once/smtp-params")), None) or opts.get("once/smtp-params") or fallback_smtp_params(opts)
    return {**opts, **compute, **smtp, "once/compute-params": compute, "once/smtp-params": smtp}


async def tofu_dns_step(original: dict) -> dict:
    opts = _with_zones(original if original.get("blue/event") == "delete" else _joined_params(original))
    provider = str(opts.get("provider-dns") or "cloudflare")
    dir = tool_dir(opts, "tofu-dns")
    specs = [_spec(_DNS[provider], f"{dir}/main.tf", opts)]
    if provider in _DNS_RECORD_RESOURCES:
        specs += [_raw_spec(f"{dir}/apps.tf.json", render_fn("apps", {"provider": provider, "applications": (opts.get("once") or {}).get("applications", []), "ip": opts.get("ip")})), _raw_spec(f"{dir}/smtp.tf.json", render_fn("smtp", {"provider": provider, "domains": opts.get("domains", [])}))]
    return await _tofu_with_specs(opts, dir, specs, {}, None, _credential_env(opts, "provider-dns"))


async def tofu_smtp_post_step(original: dict) -> dict:
    ids = {domain["zone"]: domain["id"] for domain in sorted(original.get("domains", []), key=lambda d: d["zone"])}
    opts = {**original, "domain-ids-hcl": tofu.hcl_map(ids)}
    provider = str(opts.get("provider-smtp") or "resend")
    dir = tool_dir(opts, "tofu-smtp-post")
    return await _tofu_with_specs(opts, dir, [_spec(_SMTP_POST[provider], f"{dir}/main.tf", opts)], {}, None, _credential_env(opts, "provider-smtp"))


def _data(opts: dict) -> dict:
    return {**opts, "sudoer": opts.get("sudoer") or "root", "hosts": [opts.get("ip") or "64.227.72.100"], "users": []}


def _pretty_json(value: Any, indent: int = 0) -> str:
    if isinstance(value, list):
        return "[ ]" if not value else "[ " + ", ".join(_pretty_json(x, indent) for x in value) + " ]"
    if isinstance(value, dict):
        if not value:
            return "{ }"
        pad, close = " " * (indent + 2), " " * indent
        return "{\n" + ",\n".join(f"{pad}{json.dumps(str(k))} : {_pretty_json(v, indent + 2)}" for k, v in value.items()) + f"\n{close}}}"
    return json.dumps(value)


def inventory(data: dict) -> str:
    users = [{**user, "host": host} for user in data.get("users", []) if not user.get("remove") for host in data.get("hosts", [])]
    user_hosts = {f"{user['name']}@{user['host']}": {"ansible_host": user["host"], "ansible_user": user["name"], "uid": user.get("uid")} for user in users}
    admin_hosts = {f"root@{host}": {"ansible_host": host, "ansible_user": data.get("sudoer") or "root"} for host in data.get("hosts", [])}
    return _pretty_json({"all": {"children": {"admin": {"hosts": admin_hosts}, "users": {"hosts": user_hosts}}}})


def _yaml_scalar(value: Any) -> str | None:
    if value is None:
        return "null"
    if isinstance(value, str):
        return json.dumps(value)
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, (dict, list)) and not value:
        return "{}" if isinstance(value, dict) else "[]"
    return None


def _yaml_lines(value: Any, indent: int = 0) -> list[str]:
    scalar = _yaml_scalar(value)
    if scalar is not None:
        return [" " * indent + scalar]
    if isinstance(value, list):
        result = []
        for item in value:
            child = _yaml_lines(item, indent + 2)
            result += [" " * indent + "- " + child[0][indent + 2 :], *child[1:]]
        return result
    result = []
    for key, nested in value.items():
        scalar = _yaml_scalar(nested)
        result += ([" " * indent + f"{key}: {scalar}"] if scalar is not None else [" " * indent + f"{key}:", *_yaml_lines(nested, indent + 2)])
    return result


def _yaml(value: Any) -> str:
    return "\n".join(_yaml_lines(value)) + "\n"


def _par_lookup(key: str) -> str:
    suffix = key.upper().replace("-", "_")
    return "{{ lookup('env','COLORS_PAR_" + suffix + "') }}"


def _resolve_env(env: Any) -> Any:
    return [f"{name}={_par_lookup(str(key))}" for name, key in env.items()] if isinstance(env, dict) else env


def ansible_once(opts: dict) -> str:
    smtp = {key: opts.get(key) for key in ["smtp_server", "smtp_port", "smtp_username", "smtp_password"]}
    password_key = {"resend": "resend-password", "no-infra": "no-infra-smtp-password"}.get(opts.get("provider-smtp") or "resend")
    if password_key and smtp.get("smtp_password"):
        smtp["smtp_password"] = _par_lookup(password_key)
    once = opts.get("once") or {}
    apps = []
    for app in once.get("applications", []):
        # github never reaches the host. It says where the deploy credentials are
        # published, which is no business of the module reconciling containers.
        without_github = {k: v for k, v in app.items() if k != "github"}
        configured = {**without_github, **smtp, "smtp_from": f"Info <info@notifications.{registrable_domain(app['host'])}>"}
        if isinstance(app.get("env"), dict):
            configured["env"] = _resolve_env(app["env"])
        apps.append(configured)
    return _yaml([{"name": "Reconcile ONCE applications", "become": True, "once": {**once, "applications": apps}}])


def deploy_keys_content(opts: dict) -> str:
    """The authorized_keys lines for the current generation.

    One per repository named in desired state. Each key carries every host its
    repository serves inside the ForceCommand, so a key leaked from one
    repository cannot redeploy another repository's application, and the client
    never has to name a host at all. Pure and deterministic: this is rendered
    into the artifact the colours compare byte for byte, which is also why the
    key comment holds no timestamp.
    """
    lines = [
        f'restrict,command="/usr/local/bin/deploy {" ".join(key["hosts"])}" {key["public"]}'
        for key in public_keys(opts)
    ]
    return "".join(f"{line}\n" for line in lines)


def _remote_specs(opts: dict) -> list[dict]:
    dir, data = tool_dir(opts, "ansible-remote"), _data(opts)
    return [
        _spec(_template("tools/ansible/ansible.cfg"), f"{dir}/ansible.cfg", data),
        _spec(_template("tools/ansible/main.yml"), f"{dir}/main.yml", data),
        _spec(_template("tools/ansible/files/authorized-keys"), f"{dir}/files/authorized-keys", data),
        _raw_spec(f"{dir}/deploy_keys", deploy_keys_content(opts)),
        _spec(_template("tools/ansible/files/deploy"), f"{dir}/files/deploy", data),
        _spec(_template("tools/ansible/library/once"), f"{dir}/library/once", data),
        _raw_spec(f"{dir}/inventory.json", inventory(data)),
        _raw_spec(f"{dir}/once.yml", ansible_once(data)),
    ]


async def ansible_remote_step(opts: dict) -> dict:
    dir = tool_dir(opts, "ansible-remote")
    rendered = scaffold(opts, _remote_specs(opts))
    if opts.get("blue/event") in ("build", "delete"):
        return rendered
    return await ansible_step(rendered, dir=dir, inventory="inventory.json", playbooks={"create": "main.yml"}, host_key_checking=False)


async def ansible_local_step(opts: dict) -> dict:
    dir, data = tool_dir(opts, "ansible-local"), _data(opts)
    specs = [
        _spec(_template("tools/ansible-local/ansible.cfg"), f"{dir}/ansible.cfg", data),
        _spec(_template("tools/ansible-local/inventory.ini"), f"{dir}/inventory.ini", data),
        _spec(_template("tools/ansible-local/main.yml"), f"{dir}/main.yml", data),
    ]
    return await ansible_with_spec(opts, specs, dir=dir, inventory="inventory.ini", playbooks={"create": "main.yml", "delete": "main.yml"}, extra_vars={"host_alias": str(data.get("name") or data.get("profile") or "once"), "ip": data.get("ip"), "user": data.get("user"), "block_state": "absent" if opts.get("blue/event") == "delete" else "present"})
