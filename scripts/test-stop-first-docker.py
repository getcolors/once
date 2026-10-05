#!/usr/bin/env python3
"""Exercise real Docker stop/start ordering with a simulated ONCE update.

Run: python3 scripts/test-stop-first-docker.py --image LOCAL_SHELL_IMAGE [--sudo]
Only registry resolution and ONCE orchestration are simulated. Container
inspection, graceful/forced shutdown, creation and volume checks use Docker.
"""
import argparse
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import secrets
import subprocess
import time
import sys

sys.dont_write_bytecode = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True, help='Existing local image with /bin/sh and sleep')
    parser.add_argument('--sudo', action='store_true', help='Use sudo -n for local Docker access')
    args = parser.parse_args()
    docker_command = (['sudo', '-n'] if args.sudo else []) + ['docker']
    path = Path(__file__).resolve().parents[1] / 'green/src/resources/io/github/getcolors/once/tools/ansible/files/deploy-app'
    loader = importlib.machinery.SourceFileLoader('deploy_app', str(path))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    app = importlib.util.module_from_spec(spec)
    loader.exec_module(app)
    suffix = secrets.token_hex(5)
    namespace = 'review' + suffix
    host, volume = 'test-' + suffix + '.invalid', namespace + '-data'
    digest = 'registry.example.test/test@sha256:' + '1' * 64
    names, events = [], []

    def docker(*arguments):
        result = subprocess.run([*docker_command, *arguments], capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError('synthetic Docker operation failed; output suppressed')
        return result.stdout

    image_id = json.loads(docker('image', 'inspect', args.image))[0]['Id']
    label = json.dumps({'host': host, 'name': 'test', 'autoUpdate': False})

    def start(name, script):
        names.append(name)
        return docker('run', '-d', '--name', name, '--label', 'once=' + label,
            '--mount', 'type=volume,src=' + volume + ',dst=/storage',
            '--entrypoint', '/bin/sh', args.image, '-c', script).strip()

    normal = 'trap "exit 0" TERM; while :; do sleep 0.2; done'
    policy = {'namespace': namespace, 'strategy': 'stop-first', 'timeout': 5,
              'image': 'registry.example.test/test:latest'}
    try:
        docker('volume', 'create', volume)
        old = start(namespace + '-app-test-111111', normal)

        def run(*arguments):
            if arguments[:2] == ('docker', 'pull'):
                events.append('pull')
                return ''
            if arguments[:3] == ('docker', 'image', 'inspect'):
                return json.dumps([{'Id': image_id, 'RepoDigests': [digest]}])
            if arguments[0] == 'docker':
                return docker(*arguments[1:])
            if arguments[0] == 'once':
                # Inspect actual container state at the point ONCE would start a candidate.
                state = json.loads(docker('inspect', old))[0]['State']
                assert not state['Running'] and state['ExitCode'] == 0
                events.append('source-stopped-before-replacement')
                start(namespace + '-app-test-222222', normal)
                docker('rm', old)
                return ''
            raise AssertionError('unexpected command')

        app.run = run
        app.deploy(host, policy)
        assert events == ['pull', 'source-stopped-before-replacement']
        assert len(app.containers(host, namespace)) == 1
        print('PASS: real Docker source exit before candidate creation; matching volume and image')
        docker('rm', '-f', namespace + '-app-test-222222')
        old = start(namespace + '-app-test-333333', 'trap "" TERM; while :; do sleep 0.2; done')
        time.sleep(.3)
        events.clear()
        try:
            app.deploy(host, {**policy, 'timeout': 1})
        except RuntimeError:
            pass
        else:
            raise AssertionError('unclean stop accepted')
        assert events == ['pull']
        print('PASS: real Docker forced stop rejected before replacement')
    finally:
        for name in names:
            subprocess.run([*docker_command, 'rm', '-f', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run([*docker_command, 'volume', 'rm', volume], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
