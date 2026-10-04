"""ONCE owns one greenfield v2 node and its encrypted SSH authority."""
import json
import re
from pathlib import Path
from blue.cli import stage_dir
from colors_compute import node_plan, compute_node, registry, ssh_plan, resolve_connection
from colors_compute.node import _directory, _write

NODE_ID = 'once-compute'
PLACEHOLDER = {'status': 'ready', 'reference': 'ssh-resource:build-placeholder',
    'public_key': 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
    'fingerprint': 'SHA256:kmYcvdi2GkPeWxB6XLjrZB8JHsy2Hm8luHMFp9GMvqk'}
API_ERROR = 'compute-api-version must be 2; existing deployments must retain their pinned launchers'


def planning(opts):
    return opts.get('blue/event') == 'build' or opts.get('blue/dry-run', False)


def sdk_workdir(opts):
    return str(Path(stage_dir(opts, NODE_ID)).resolve().parent.parent)


def library_options(opts):
    return {k: v for k, v in opts.items() if k not in ('ssh-private-key-path', 'ssh-public-key-path', 'once-ssh-passphrase') and '/' not in k}


def resource(opts):
    value = opts.get('once/ssh-resource') or (PLACEHOLDER if planning(opts) else None)
    if value is None:
        raise ValueError('SSH resource unavailable')
    return value


def registration(opts):
    return bool(registry()['compute'].get(opts.get('provider-compute'), {}).get('registration'))


def ssh_request(opts):
    return {'name': 'machine-access', 'workdir': sdk_workdir(opts), 'passphrase_env': 'COLORS_PAR_ONCE_SSH_PASSPHRASE'}


def registration_request(opts):
    return {'name': 'machine-access', 'workdir': sdk_workdir(opts), 'state_filename': 'once-ssh-registration.tfstate', 'ssh_resource': resource(opts)}


def requirements(opts):
    ingress = []
    for name, port in [('ssh', 22), ('http', 80), ('https', 443)]:
        suffix = 'ssh-sources' if name == 'ssh' else 'http-sources'
        sources = opts.get('compute-' + suffix, opts.get(str(opts.get('provider-compute')) + '-' + suffix))
        if isinstance(sources, str):
            sources = [s for s in re.split(r'[,\s]+', sources) if s]
        if not sources:
            raise ValueError('compute-' + suffix + ' is required')
        ingress.append({'id': name, 'protocol': 'tcp', 'from_port': port, 'to_port': port, 'sources': sources})
    return {'ingress': ingress, 'egress': 'all', 'private_filter': False}


def request(opts):
    result = {'node_id': NODE_ID, 'state_filename': 'once-node-0.tfstate', 'workdir': sdk_workdir(opts), 'ssh_resource': resource(opts), 'security': requirements(opts)}
    provider = opts.get('provider-compute')
    if provider in ('hcloud', 'vultr', 'digitalocean'):
        result['network'] = {'mode': 'none'}
    if registration(opts):
        result['ssh_registration'] = opts.get('once/ssh-registration') or ({'status': 'ready', 'reference': 'registration:build-placeholder', 'provider': provider, 'ssh_resource_reference': resource(opts)['reference'], 'fingerprint': resource(opts)['fingerprint'], 'id': '0'} if planning(opts) else None)
    return result


def errors(opts):
    if type(opts.get('compute-api-version')) is not int or opts['compute-api-version'] != 2:
        return [API_ERROR]
    if opts.get('provider-backend') not in ('s3', 'r2'):
        return ['compute state requires an s3 or r2 backend']
    if any(key in opts for key in ('ssh-key-path', 'ssh-private-key-path', 'ssh-public-key-path')):
        return ['external SSH keys are outside the single-node contract']
    try:
        planned = {**opts, 'blue/dry-run': True}
        ssh_plan(library_options(planned), ssh_request(planned))
        node_plan(library_options(planned), request(planned))
        return []
    except ValueError as exc:
        return [str(exc)]


def fallback_params(opts):
    if not planning(opts):
        raise ValueError('compute inventory unavailable')
    user = registry()['compute'].get(opts.get('provider-compute'), {}).get('user', 'root')
    return {'provider': opts.get('provider-compute'), 'node_id': NODE_ID, 'ip': '192.0.2.10', 'user': user, 'sudoer': user,
            'name': str(opts.get('profile')) + '-once-compute', 'ssh-keygen': True, 'ssh-private-key-path': f'/home/build-placeholder/compute/{opts.get("profile")}/ssh/machine-access/identity.pub', 'once/agent-socket': '/home/build-placeholder/agent.sock'}


def failure_message(opts, result):
    # colors-compute supplies an authored command prefix and sanitized stderr.
    # Never print raw argv, environment, stdout or the whole error object.
    error = result.get('error') or {}
    command = error.get('command') or []
    tool = command[0] if command else None
    reason = error.get('command_reason')
    detail = ((f'Executable "{tool}" was not found on PATH.' if tool else 'Required executable was not found on PATH.') if reason == 'executable_not_found' else
              'Required command could not start.' if reason == 'process_start_failed' else
              'Required command timed out.' if reason == 'timeout' else
              error['message'] if error.get('message') is not None else 'compute lifecycle refused')
    reauth = error.get('auth_reason') == 'google_reauth_required'
    if reauth:
        detail = 'Google Cloud credentials require reauthentication (invalid_rapt).'
    lines = [('Cannot prepare SSH access: ' if opts.get('blue/event') == 'ssh' else '') + detail]
    if error.get('stage'):
        lines.append('Compute stage: ' + error['stage'])
    if command:
        lines.append('Command: ' + ' '.join(command))
    if error.get('executable'):
        lines.append('Executable: ' + error['executable'])
    if error.get('exit_code') is not None:
        lines.append('Exit status: ' + ('unavailable' if error['exit_code'] < 0 else str(error['exit_code'])))
    if error.get('stderr'):
        lines.append('Command details were withheld because structured output may contain credentials or state.' if error['stderr'] == '[structured output suppressed]' else error['stderr'])
    if reauth:
        lines.append('If using local user Application Default Credentials, run `gcloud auth application-default login`, then retry the original command. Otherwise renew the configured Google credentials through their authentication method.')
    elif error.get('stderr') == '[structured output suppressed]':
        lines.append('The underlying cause could not be safely identified from this diagnostic.')
    if reason == 'executable_not_found':
        lines.append('Make OpenTofu (tofu) available on PATH and retry.' if tool == 'tofu' else f'Make {tool} available on PATH and retry.' if tool else 'Make the required executable available on PATH and retry.')
    return '\n'.join(lines)


def failure(opts, result):
    return {**opts, 'blue/exit': 1, 'blue/err': failure_message(opts, result)}


def params(opts, result):
    values = result['params']
    return {**values, 'name': values.get('name') or str(opts.get('profile')) + '-once-compute', 'sudoer': values.get('sudoer') or values.get('user'), 'ssh-keygen': True, 'ssh-private-key-path': opts.get('ssh-private-key-path') or (f'/home/build-placeholder/compute/{opts.get("profile")}/ssh/machine-access/identity.pub' if planning(opts) else None), 'once/agent-socket': opts.get('once/agent-socket')}


def adopt(opts, result):
    values = params(opts, result)
    return {**opts, **values, 'once/compute-params': values, 'colors-compute/node': result['params'], 'blue/exit': 0}


async def step(opts):
    if planning(opts):
        canonicalize(node_plan(library_options(opts), request(opts)))
        return {**opts, **fallback_params(opts), 'once/compute-params': fallback_params(opts), 'blue/exit': 0}
    result = await compute_node(library_options(opts), request(opts), opts['blue/event'])
    if result['status'] == 'ready':
        return adopt(opts, result)
    if result['status'] == 'destroyed':
        return {**opts, 'blue/exit': 0}
    return failure(opts, result)


async def load(opts, env=None):
    result = await resolve_connection(library_options(opts), request(opts), env) if opts.get('blue/event') in ('ssh', 'ssh-install', 'describe') else await compute_node(library_options(opts), request(opts), 'inspect', env)
    if result['status'] == 'destroyed' and opts.get('blue/event') == 'delete':
        return {**opts, 'blue/exit': 0, 'colors-compute/already-destroyed': True}
    if result['status'] == 'destroyed':
        return {**opts, 'blue/exit': 1, 'blue/err': 'compute node is destroyed'}
    return adopt(opts, result) if result['status'] == 'ready' else failure(opts, result)


async def connection(opts):
    result = await resolve_connection(library_options(opts), request(opts))
    return adopt(opts, result) if result['status'] == 'ready' else failure(opts, result)


def canonicalize(plan):
    _directory(plan['directory'])
    for filename, document in plan['documents'].items():
        _write(Path(plan['directory']) / filename, (json.dumps(document, sort_keys=True, indent=2) + '\n').encode())
    return {**plan, 'status': 'built'}
