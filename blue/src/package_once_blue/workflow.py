from __future__ import annotations

import os
from pathlib import Path

from blue import dry_run, progress, tofu
from blue.cli import par_name
from blue.lifecycle import preflight
from blue.workflow import advice_add, workflow

from blue.cli import read_pars

from . import github, ssh, tools, machine
from .validate import secret_errors, state_errors


async def _with_deploy_keys(opts: dict, real: bool) -> dict:
    """Attach the keys ansible-remote installs and the github step publishes.

    Generating them is a create-time side effect, so a build or a dry-run takes
    fixed placeholders instead: a fresh key rendered into the artifact would make
    the build nondeterministic and break byte parity between the colours.
    """
    if real and opts.get("blue/event") == "create":
        keys, err = await github.generate_keys(opts)
        if err:
            return {**opts, "blue/exit": 1, "blue/err": err}
        key_dir = {"once/key-dir": str(Path(keys[0]["private-file"]).parent)} if keys else {}
        return {**opts, "blue/exit": 0, "once/deploy-keys": keys, **key_dir}
    return {**opts, "blue/exit": 0, "once/deploy-keys": github.placeholder_keys(opts)}


async def _state_output(opts: dict, tool: str) -> dict | None:
    try:
        return (await tofu.outputs(tools.tool_dir(opts, tool), tools.backend_credential_env(opts))).get("params")
    except Exception:
        return None


async def _adopt_existing_state(opts: dict) -> dict:
    loaded = await machine.load(opts)
    if loaded.get('blue/exit'):
        return loaded
    smtp = await _state_output(opts, 'tofu-smtp')
    return {**loaded, **(smtp or {}), **({'once/smtp-params': smtp} if smtp else {})}


async def start_step(original: dict, env: dict[str, str] | None = None) -> dict:
    async def after(opts, _env, context):
        if context['real'] and context['event'] == 'delete':
            return await _adopt_existing_state(opts)
        return await _with_deploy_keys(opts, context['real'])
    return await preflight(
        original, defaults={"compute-prevent-destroy": True}, overlay=read_pars, env=env,
        validators=[
            lambda opts, _env, _ctx: state_errors(opts),
            lambda opts, _env, ctx: secret_errors(opts) if ctx["real"] and ctx["event"] in ("create", "delete") else [],
            lambda opts, _env, ctx: [f"compute destruction is protected; set {par_name('compute-prevent-destroy')}=false to delete"] if ctx["real"] and ctx["event"] == "delete" and opts.get("compute-prevent-destroy") else [],
        ], after_validate=after,
    )


async def ansible_cleanup_step(opts: dict) -> dict:
    return await tools.ansible_remote_step(await tools.ansible_local_step(opts))


tofu_steps = ["once/tofu-compute", "once/tofu-smtp", "once/tofu-dns", "once/tofu-smtp-post"]
side_effecting_steps = [*tofu_steps, "once/ansible-local", "once/ansible-remote", "once/ansible-cleanup", "once/github", "once/ssh-cleanup"]


def wire_fn(step: str, run_opts: dict):
    if run_opts.get("blue/event") == "delete":
        return {
            # Revoking runs before anything is destroyed: a withdrawn credential
            # against a live host is a loud, recoverable broken deploy, while a
            # live credential against a destroyed host is silent. It needs no
            # key material, so it also works when the box is already gone.
            "once/start": (start_step, "once/github"),
            "once/github": (github.github_step, "once/ansible-cleanup"),
            "once/ansible-cleanup": (ansible_cleanup_step, "once/tofu-smtp-post"),
            "once/tofu-smtp-post": (tools.tofu_smtp_post_step, "once/tofu-dns"),
            "once/tofu-dns": (tools.tofu_dns_step, "once/tofu-smtp", "once/tofu-compute"),
            "once/tofu-smtp": (tools.tofu_smtp_step,),
            # The local keypair goes last, strictly after a successful compute
            # destroy: a failed delete leaves the key, which is still the only
            # credential to whatever survived.
            "once/tofu-compute": (tools.tofu_compute_step,),
            "once/ssh-cleanup": (ssh.cleanup_step,),
        }.get(step)
    return {
        "once/start": (start_step, "once/tofu-compute"),
        "once/tofu-compute": (tools.tofu_compute_step, "once/tofu-smtp"),
        "once/tofu-smtp": (tools.tofu_smtp_step, "once/tofu-dns"),
        "once/tofu-dns": (tools.tofu_dns_step, "once/tofu-smtp-post"),
        "once/tofu-smtp-post": (tools.tofu_smtp_post_step, "once/ansible-local"),
        "once/ansible-local": (tools.ansible_local_step, "once/ansible-remote"),
        # Publishing follows the remote stage, not the local one: the credentials
        # describe a configured host, and a workstation-side failure should not
        # gate them.
        "once/ansible-remote": (tools.ansible_remote_step, "once/github"),
        "once/github": (github.github_step,),
    }.get(step)


def backend_advice(tool: str):
    return tofu.conventional_backend_advice(
        dir=lambda opts: tools.tool_dir(opts, tool),
        key=lambda opts: f"{opts.get('profile') or 'default'}/{tool}.tfstate",
    )


def create_workflow():
    result = workflow(start="once/start", wire_fn=wire_fn)
    for step in tofu_steps[1:]:
        tool = step.removeprefix("once/")
        result = advice_add(result, step, "before", "once.workflow/backend", backend_advice(tool))
    result = progress.advise(result)
    return dry_run.advise(result, side_effecting_steps)


once_workflow = create_workflow()
