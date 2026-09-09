from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Awaitable, Callable

from blue.cli import load_yaml
from blue.runtime import ExecResult, runtime

from blue.cli import read_pars

from .ssh import identity_args, with_machine_key
from . import machine
from .tools import backend_credential_env, tool_dir

Runner = Callable[..., Awaitable[ExecResult]]
_PLACEHOLDER_IP = "192.168.0.1"
_ANSI_RE = re.compile(r"\x1b\]8;[^\x07]*\x07|\x1b\[[0-9;?]*[ -/]*[@-~]")
_HOST_RE = re.compile(r"([A-Za-z0-9](?:[A-Za-z0-9.-]{0,253}[A-Za-z0-9])?\.[A-Za-z]{2,})(?:\s+\(([^)]*)\))?")


async def _run(args: list[str], *, cwd: str | None = None, env: dict | None = None, timeout_ms: int = 30_000) -> ExecResult:
    return await runtime.exec(args, cwd=cwd, env=env, timeout_ms=timeout_ms)


def _detail(label: str, result: ExecResult) -> str:
    text = (result.err or result.out or "").strip()
    if len(text) > 200:
        text = text[:200] + "…"
    return f"{label} failed (exit {result.exit})" + (f" — {text}" if text else "")


def _target(params: dict) -> dict:
    ip = params.get("ip")
    if params.get("provider-compute") == "no-infra" and (not ip or ip == _PLACEHOLDER_IP):
        ip = params.get("no-infra-compute-ip")
    # The keygen identity travels with the target so every probe names the
    # machine key explicitly; in opt-out mode the target is unchanged.
    identity = {"ssh-keygen": params.get("ssh-keygen"), "ssh-private-key-path": params.get("ssh-private-key-path")} if params.get("ssh-keygen") else {}
    return {"ip": ip, "user": params.get("user") or params.get("sudoer") or params.get("no-infra-compute-user") or params.get("no-infra-compute-sudoer") or "root", **identity}


def _ssh(target: dict, remote: list[str]) -> list[str]:
    return ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=5", "-o", "StrictHostKeyChecking=accept-new", *identity_args(target), f"{target['user']}@{target['ip']}", *remote]


async def _compute_status(runner: Runner, params: dict, state_detail: str | None = None) -> dict:
    target = _target(params)
    external = params.get("provider-compute") == "no-infra"
    if not target.get("ip") or target["ip"] == _PLACEHOLDER_IP:
        return {**target, "status": "unreachable" if external else "absent", "detail": "no host configured" if external else state_detail or f"no OpenTofu state in {tool_dir(params, 'tofu-compute')}"}
    result = await runner(_ssh(target, ["true"]), timeout_ms=10_000)
    return {**target, "status": "running" if result.exit == 0 else "unreachable", "detail": "ssh ok" if result.exit == 0 else _detail("ssh", result)}


def provider_summary(params: dict) -> dict:
    return {"compute": params.get("provider-compute"), "backend": params.get("provider-backend"), "smtp": params.get("provider-smtp"), "dns": params.get("provider-dns")}


def parse_once_list(output: str) -> list[dict]:
    result = []
    for line in _ANSI_RE.sub("", output or "").splitlines():
        if match := _HOST_RE.search(line):
            result.append({"host": match.group(1), **({"status": match.group(2)} if match.group(2) else {})})
    return result


def image_repository_tag(image: object) -> dict | None:
    value = str(image or "").strip()
    if not value or re.fullmatch(r"sha256:[A-Fa-f0-9]+", value):
        return None
    value = value.split("@", 1)[0]
    slash, colon = value.rfind("/"), value.rfind(":")
    repository, tag = (value[:colon], value[colon + 1 :]) if colon > slash else (value, "latest")
    return {"repository": repository, "tag": tag, "image": f"{repository}:{tag}"}


def matching_repo_digest(repository: str, digests: list | None) -> str | None:
    for item in digests or []:
        repo, _, digest = str(item).partition("@")
        if repo == repository:
            return digest
    return None


def _once_label_host(container: dict) -> str | None:
    """The host ONCE recorded on the container, from its `once` label. ONCE writes the
    application's desired state there as JSON, making this the authoritative
    container-to-host mapping; everything else is inference from names and labels."""
    raw = str(((container.get("Config") or {}).get("Labels") or {}).get("once") or "").strip()
    if not raw:
        return None
    try:
        host = json.loads(raw).get("host")
    except (ValueError, AttributeError):
        return None
    return str(host).strip().lower() or None if host else None


def _host_variant_re(variant: str) -> re.Pattern:
    """`example.com` sits inside `www.example.com`, so a plain substring lets the shorter
    host claim the longer one's container. Dots and alphanumerics block a match; `-` and
    `_` do not, because container names embed a dot-substituted host inside a longer
    name (`/once-www-example-com`)."""
    return re.compile(rf"(?<![a-z0-9.]){re.escape(variant)}(?![a-z0-9.])")


def _container_text(container: dict) -> str:
    return json.dumps({k: container.get(k) for k in ["Name", "Config", "NetworkSettings"]}).lower()


def _container_for_host(containers: list[dict], host: str) -> dict | None:
    """The `once` label wins outright: a labelled container belongs to exactly the host it
    names and can never be claimed by another, so only unlabelled containers fall
    through to matching on names and third-party labels."""
    host = host.lower()
    if labelled := next((c for c in containers if _once_label_host(c) == host), None):
        return labelled
    variants = [_host_variant_re(v) for v in dict.fromkeys([host, host.replace(".", "-"), host.replace(".", "_")])]
    return next((c for c in containers if _once_label_host(c) is None and any(rx.search(_container_text(c)) for rx in variants)), None)


async def _application_report(runner: Runner, containers: list, images: list, app: dict) -> dict:
    container = _container_for_host(containers, app["host"])
    image_ref = (container or {}).get("Config", {}).get("Image")
    parsed = image_repository_tag(image_ref)
    image = next((info for info in images if info.get("Id") == (container or {}).get("Image") or image_ref in info.get("RepoTags", []) or (parsed and parsed["image"] in info.get("RepoTags", []))), None)
    running = matching_repo_digest(parsed["repository"], (image or {}).get("RepoDigests")) if parsed else None
    fallback = (image or {}).get("Id") or (container or {}).get("Image")
    registry_digest = registry_detail = None
    if parsed:
        result = await runner(["skopeo", "inspect", "--no-tags", "--override-os", (image or {}).get("Os", "linux"), "--override-arch", (image or {}).get("Architecture", "amd64"), f"docker://{parsed['image']}"], timeout_ms=30_000)
        if result.exit == 0:
            try:
                registry_digest = json.loads(result.out).get("Digest")
            except Exception as error:
                registry_detail = f"registry response was not valid JSON: {error}"
        else:
            registry_detail = _detail("skopeo inspect", result)
    return {"host": app["host"], "status": (container or {}).get("State", {}).get("Status") or app.get("status") or "unknown", "image": (parsed or {}).get("image") or image_ref, "version": (parsed or {}).get("tag"), "digest": running or fallback, "digest-source": "repo-digest" if running else "image-id" if fallback else None, "registry-digest": registry_digest, "new-version?": running != registry_digest if running and registry_digest else None, **({"registry-detail": registry_detail} if registry_detail else {})}


async def _remote_apps(runner: Runner, compute: dict) -> dict:
    check = await runner(_ssh(compute, ["command", "-v", "once", ">/dev/null", "2>&1", "||", "test", "-x", "/usr/local/bin/once", "||", "exit", "127"]))
    if check.exit != 0:
        return {"ok": False, "fatal": True, "detail": _detail("once command check", check), "applications": []}
    listed = await runner(_ssh(compute, ["sudo", "-n", "once", "list"]))
    if listed.exit != 0:
        return {"ok": False, "fatal": listed.exit == 127 or "once: not found" in (listed.out + listed.err).lower(), "detail": _detail("once list", listed), "applications": []}
    apps = parse_once_list(listed.out)
    if not apps:
        return {"ok": True, "applications": []}
    ps = await runner(_ssh(compute, ["sudo", "-n", "docker", "ps", "-q"]))
    if ps.exit != 0:
        return {"ok": False, "detail": _detail("docker ps", ps), "applications": []}
    ids = [line.strip() for line in ps.out.splitlines() if line.strip()]
    if not ids:
        return {"ok": True, "applications": apps}
    inspected = await runner(_ssh(compute, ["sudo", "-n", "docker", "inspect", "--type", "container", *ids]))
    if inspected.exit != 0:
        return {"ok": False, "detail": _detail("docker inspect", inspected), "applications": []}
    containers = json.loads(inspected.out or "[]")
    image_ids = list(dict.fromkeys(x for container in containers for x in [container.get("Image"), container.get("Config", {}).get("Image")] if x))
    images = []
    if image_ids:
        result = await runner(_ssh(compute, ["sudo", "-n", "docker", "image", "inspect", *image_ids]))
        if result.exit != 0:
            return {"ok": False, "detail": _detail("docker image inspect", result), "applications": []}
        images = json.loads(result.out or "[]")
    return {"ok": True, "applications": [await _application_report(runner, containers, images, app) for app in apps]}


async def _tofu_params(runner: Runner, opts: dict, tool: str) -> dict:
    result = await runner(["tofu", "output", "-json"], cwd=tool_dir(opts, tool), env=backend_credential_env(opts))
    if result.exit != 0:
        return {"params": {}, "detail": _detail(f"tofu output in {tool_dir(opts, tool)}", result)}
    try:
        return {"params": json.loads(result.out).get("params", {}).get("value", {})}
    except Exception as error:
        return {"params": {}, "detail": f"{tool} output was not valid JSON: {error}"}


async def describe_report(input: dict, runner: Runner = _run, resolve: bool = True) -> dict:
    opts, detail, compute_detail = input, None, None
    if resolve:
        loaded = await machine.load(opts)
        compute = {"params": loaded.get("once/compute-params", {}), "detail": loaded.get("blue/err")}
        smtp = await _tofu_params(runner, opts, "tofu-smtp")
        opts = {**{k: v for k, v in opts.items() if k != "ip"}, **compute["params"], **smtp["params"]}
        compute_detail = compute.get("detail")
        detail = "; ".join(x for x in [compute.get("detail"), smtp.get("detail")] if x) or None
    compute = await _compute_status(runner, opts, compute_detail)
    if detail and compute["status"] != "absent":
        compute["detail"] += f"; {detail}"
    apps = await _remote_apps(runner, compute) if compute["status"] == "running" else {"ok": False, "applications": [], "detail": "not checked because compute has not been created" if compute["status"] == "absent" else "not checked because compute is not reachable"}
    return {"profile": opts.get("profile"), "providers": provider_summary(opts), "compute": compute, "applications": apps.get("applications", []), "applications-error": None if apps.get("ok") else apps.get("detail"), "fatal-error?": bool(apps.get("fatal"))}


def _present(value: Any) -> str:
    return "unknown" if value is None or not str(value).strip() else str(value)


def print_report(report: dict) -> None:
    providers, compute = report["providers"], report["compute"]
    print(f"Profile: {_present(report.get('profile'))}\n\nProviders:\n  Compute: {_present(providers.get('compute'))}\n  Backend: {_present(providers.get('backend'))}\n  SMTP: {_present(providers.get('smtp'))}\n  DNS: {_present(providers.get('dns'))}\n")
    print(f"Compute:\n  IP: {_present(compute.get('ip'))}\n  SSH user: {_present(compute.get('user'))}\n  Status: {_present(compute.get('status'))}" + (f" ({compute['detail']})" if compute.get("detail") else "") + "\n")
    if report.get("applications-error"):
        print(f"Applications: {report['applications-error']}.")
    elif not report["applications"]:
        print("Applications: none found.")
    else:
        print("Applications:")
        for app in report["applications"]:
            update = "yes" if app.get("new-version?") is True else "no" if app.get("new-version?") is False else "unknown"
            print(f"  - {_present(app.get('host'))}\n    status: {_present(app.get('status'))}\n    image: {_present(app.get('image'))}\n    version: {_present(app.get('version'))}\n    digest: {_present(app.get('digest'))}\n    registry digest: {_present(app.get('registry-digest'))}\n    update available: {update}")


async def describe(opts: dict) -> dict:
    report = await describe_report(opts)
    print_report(report)
    if report.get("fatal-error?"):
        return {**opts, "once.describe/result": report, "blue/exit": 1, "blue/err": report.get("applications-error") or "describe failed"}
    if report["compute"]["status"] != "running":
        compute = report["compute"]
        return {**opts, "once.describe/result": report, "blue/exit": 1, "blue/err": f"compute is {compute['status']}" + (f" — {compute['detail']}" if compute.get("detail") else "")}
    return {**opts, "once.describe/result": report, "blue/exit": 0}


async def describe_file(path: str) -> dict:
    try:
        file = Path(path)
        if not file.exists():
            return {"blue/exit": 2, "blue/err": f"desired state file not found: {path}"}
        # Describe reads the live host, so it needs the keygen identity the
        # way create does — real event semantics, opt-out untouched.
        return await describe(with_machine_key(read_pars({
            **load_yaml(file.read_text()),
            "blue/state-file": str(file.resolve()),
        }), True))
    except Exception as error:
        return {"blue/exit": 2, "blue/err": str(error) or type(error).__name__}
