"""Failure injection for the shared, root-owned dispatcher; no daemon required."""
import copy
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import tempfile
import sys

sys.dont_write_bytecode = True
import unittest
from unittest.mock import patch, MagicMock

PATH = Path(__file__).resolve().parents[2] / 'green/src/resources/io/github/getcolors/once/tools/ansible/files/deploy-app'
loader = importlib.machinery.SourceFileLoader('deploy_app', str(PATH))
spec = importlib.util.spec_from_loader(loader.name, loader)
app = importlib.util.module_from_spec(spec)
loader.exec_module(app)
HOST = 'wiki.example.com'
IMAGE = 'ghcr.io/example/wiki:latest'
DIGEST = 'ghcr.io/example/wiki@sha256:' + 'a' * 64
POLICY = dict(host=HOST, image=IMAGE, namespace='once', strategy='stop-first', timeout=120)
OLD = dict(id='old', image='old-image', auto_update=False, state=dict(Running=True, ExitCode=0, OOMKilled=False), volumes=[('wiki', '/data')], binds=False)
STOPPED = {**OLD, 'state': dict(Running=False, ExitCode=0, OOMKilled=False)}
NEW = {**OLD, 'id': 'new', 'image': 'new-image'}

class DeploymentTests(unittest.TestCase):
    def execute(self, containers=None, failure=None):
        self.calls = []
        def run(*args):
            self.calls.append(args)
            if failure and args[:len(failure)] == failure:
                raise RuntimeError('injected failure')
            if args[:3] == ('docker', 'image', 'inspect'):
                return json.dumps([dict(Id='new-image', RepoDigests=[DIGEST])])
            return ''
        with patch.object(app, 'only_container', side_effect=containers or [OLD, STOPPED, NEW]), patch.object(app, 'run', run):
            app.deploy(HOST, POLICY, before_stop=lambda: self.calls.append(('mark',)))

    def test_stop_precedes_exact_digest_update(self):
        self.execute()
        self.assertEqual(self.calls, [
            ('docker','pull',IMAGE), ('docker','image','inspect',IMAGE), ('mark',),
            ('docker','update','--restart=no','old'), ('docker','stop','--time','120','old'),
            ('once','-n','once','update',HOST,'--image',DIGEST,'--auto-update=false')])

    def test_pull_failure_does_not_stop_or_mark(self):
        with self.assertRaises(RuntimeError): self.execute(failure=('docker','pull'))
        self.assertEqual(self.calls, [('docker','pull',IMAGE)])

    def test_automatic_updates_refused(self):
        with self.assertRaises(RuntimeError): self.execute([{**OLD,'auto_update':True}])
        self.assertEqual(self.calls, [])

    def test_missing_auto_update_label_refused(self):
        with self.assertRaises(RuntimeError): self.execute([{**OLD,'auto_update':None}])

    def test_shutdown_failure_never_starts_replacement(self):
        with self.assertRaises(RuntimeError): self.execute([OLD,{**STOPPED,'state':dict(Running=False,ExitCode=137,OOMKilled=False)}])
        self.assertFalse(any(c[0]=='once' for c in self.calls))

    def test_still_running_refused(self):
        with self.assertRaises(RuntimeError): self.execute([OLD,OLD])
        self.assertFalse(any(c[0]=='once' for c in self.calls))

    def test_oom_refused(self):
        with self.assertRaises(RuntimeError): self.execute([OLD,{**STOPPED,'state':dict(Running=False,ExitCode=0,OOMKilled=True)}])

    def test_update_failure_never_restarts_old(self):
        with self.assertRaises(RuntimeError): self.execute(failure=('once',))
        self.assertFalse(any(c[:2]==('docker','start') for c in self.calls))

    def test_volume_change_refused(self):
        with self.assertRaises(RuntimeError): self.execute([OLD,STOPPED,{**NEW,'volumes':[('other','/data')]}])

    def test_wrong_image_refused(self):
        with self.assertRaises(RuntimeError): self.execute([OLD,STOPPED,{**NEW,'image':'other'}])

    def test_rolling_is_ordinary_update(self):
        with patch.object(app,'run') as run:
            app.deploy(HOST,{**POLICY,'strategy':'rolling'})
            run.assert_called_once_with('once','-n','once','update',HOST)

    def test_ambiguous_containers_refused(self):
        with patch.object(app,'containers',return_value=[OLD,NEW]):
            with self.assertRaises(RuntimeError): app.only_container(HOST,'once')

    def test_metadata_projection_excludes_credentials(self):
        raw={'Id':'old','Image':'old-image','Name':'/once-app-wiki-abc123',
             'Config':{'Labels':{'once':json.dumps({'host':HOST,'name':'wiki','autoUpdate':False,'smtp':{'password':'SECRET'}})},'Env':['TOKEN=SECRET']},
             'State':OLD['state'],'Mounts':[{'Type':'volume','Name':'wiki','Destination':'/data'}]}
        with patch.object(app,'run',side_effect=['old',json.dumps([raw])]):
            result=app.containers(HOST,'once')
            self.assertEqual(result,[OLD])
            self.assertNotIn('SECRET',json.dumps(result))

    def test_namespace_prefix_collision_and_invalid_suffix_refused(self):
        for name in ('/once-app-other-app-wiki-abc123', '/once-app-wiki-abc123-extra', '/once-app-wiki-12345', '/once-app-other-abc123'):
            raw = {'Name': name, 'Config': {'Labels': {'once': json.dumps({'host': HOST, 'name': 'wiki'})}}}
            with self.subTest(name=name), patch.object(app, 'run', side_effect=['old', json.dumps([raw])]):
                with self.assertRaisesRegex(RuntimeError, 'namespace and application'):
                    app.containers(HOST, 'once')

    def test_subprocess_failure_never_emits_captured_secrets(self):
        result=type('Result',(),dict(returncode=1,stdout='SECRET',stderr='SECRET'))()
        with patch.object(app.subprocess,'run',return_value=result):
            with self.assertRaisesRegex(RuntimeError,'output suppressed') as exc: app.run('docker','inspect','old')
        self.assertNotIn('SECRET',str(exc.exception))

    def test_interrupted_update_keeps_marker_and_blocks_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            locks = MagicMock()
            locks.lstat.return_value = type('Stat', (), {'st_uid': 0, 'st_mode': 0o700})()
            locks.is_symlink.return_value = False
            locks.__truediv__.return_value = directory / 'lock'
            def interrupted(host, policy, before_stop):
                before_stop()
                raise RuntimeError('interrupted after stopping')
            with patch.object(app, 'LOCKS', locks), patch.object(app, 'PENDING', directory), patch.object(app.os, 'geteuid', return_value=0), patch.object(app.sys, 'argv', ['helper', HOST]), patch.object(app, 'load_policy', return_value=POLICY), patch.object(app, 'deploy', side_effect=interrupted) as deploy:
                with self.assertRaisesRegex(RuntimeError, 'interrupted'): app.main()
                self.assertTrue((directory / (HOST + '.pending')).exists())
                with self.assertRaisesRegex(RuntimeError, 'unfinished'): app.main()
                self.assertEqual(deploy.call_count, 1)

    def test_untrusted_lock_directory_refused(self):
        locks = MagicMock()
        locks.lstat.return_value = type('Stat', (), {'st_uid': 1000, 'st_mode': 0o777})()
        locks.is_symlink.return_value = False
        with patch.object(app, 'LOCKS', locks), patch.object(app.os, 'geteuid', return_value=0), patch.object(app.sys, 'argv', ['helper', HOST]):
            with self.assertRaisesRegex(RuntimeError, 'private and root-owned'): app.main()

if __name__ == '__main__': unittest.main()
