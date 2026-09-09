"""ONCE's single host requirement and application parameter adapter."""
import json
from pathlib import Path
from blue.cli import stage_dir
from colors_compute import validate, backend_plan
from colors_compute.deployment_request import source_cidrs
from colors_compute.inspection import read_deployment
from colors_compute.orchestration import orchestrate
from colors_compute.planning import plan_deployment
from colors_compute.ssh import _mode

TOPOLOGY = [{'role': None, 'count': 1}]


def requirements(opts):
    ingress = []
    for name, port in [('ssh', 22), ('http', 80), ('https', 443)]:
        sources = source_cidrs(opts, 'ssh-sources' if name == 'ssh' else 'http-sources', 'compute-ssh-sources' if name == 'ssh' else 'compute-http-sources')
        if not sources:
            raise ValueError('compute-' + ('ssh' if name == 'ssh' else 'http') + '-sources is required')
        ingress.append({'id': name, 'protocol': 'tcp', 'from_port': port, 'to_port': port, 'sources': sources})
    return {'single_host': True, 'private': False, 'security': {'ingress': ingress, 'egress': 'all', 'private_filter': False},
            'legacy_state_keys': [str(opts.get('profile')) + '/tofu-compute.tfstate']}


def errors(opts):
    result = validate(opts)
    if not result:
        try:
            plan_deployment(opts, TOPOLOGY, requirements(opts))
        except ValueError as exc:
            result.append(str(exc))
    return result


def params(opts, result):
    node = dict(result['cluster']['nodes'][0])
    managed = _mode(opts)['mode'] == 'managed'
    path = result.get('key', {}).get('private_key_path') or node.get('ssh_identity_file')
    if path and result['status'] == 'planned':
        path = path.replace('$HOME/.ssh', '/home/build-placeholder/.ssh')
    return {**node, 'ssh-keygen': managed, **({'ssh-private-key-path': path} if path else {})}


def fallback_params(opts):
    if opts.get('blue/event') != 'build' and not opts.get('blue/dry-run'):
        raise ValueError('compute inventory unavailable')
    return params(opts, plan_deployment(opts, TOPOLOGY, requirements(opts)))


async def step(opts):
    planning = opts.get('blue/event') == 'build' or opts.get('blue/dry-run')
    result = plan_deployment(opts, TOPOLOGY, requirements(opts)) if planning else await orchestrate(opts, TOPOLOGY, requirements(opts))
    if result['status'] not in ('planned', 'ready', 'destroyed'):
        return {**opts, 'blue/exit': 1, 'blue/err': '\n'.join(result.get('errors', [])) or 'compute lifecycle refused'}
    if planning:
        directory = Path(stage_dir(opts, 'tofu-compute'))
        for stage, documents in [('shared', result['documents']['shared']), *[(f'nodes/{node}', docs) for node, docs in result['documents']['nodes'].items()]]:
            state_key = result["state_keys"]["shared"] if stage == "shared" else result["state_keys"]["nodes"][stage.split("/")[1]]
            documents = {**documents, "backend.tf.json": backend_plan(opts, state_key)["config"]}
            for filename, document in documents.items():
                target = directory / stage / filename
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(json.dumps(document, sort_keys=True, indent=2) + '\n')
    if 'cluster' not in result:
        return {**opts, 'blue/exit': 0}
    adopted = params(opts, result)
    return {**opts, **adopted, 'once/compute-params': adopted, 'colors-compute/cluster': result['cluster'], 'blue/exit': 0}


async def load(opts, env=None):
    result = await read_deployment(opts, env)
    if result['status'] == 'destroyed' and opts.get('blue/event') == 'delete':
        return {**opts, 'blue/exit': 0, 'colors-compute/already-destroyed': True}
    if result['status'] != 'present':
        return {**opts, 'blue/exit': 1, 'blue/err': 'compute inventory unavailable; legacy state requires explicit migration'}
    adopted = params(opts, result)
    return {**opts, **adopted, 'once/compute-params': adopted, 'colors-compute/cluster': result['cluster'], 'blue/exit': 0}
