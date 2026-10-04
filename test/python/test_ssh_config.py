"""Exercise SSH config ownership without touching operator files or the network."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

# Import resource code without polluting the byte-identical packaged trees.
sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('ssh_config', ROOT / 'green/src/resources/io/github/getcolors/once/tools/ansible-local/ssh_config.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SSHConfigTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name).resolve()
        self.directory = self.home / '.ssh'
        self.path = self.directory / 'config'
        self.payload = dict(host_alias='probe', block_state='present', keygen=True,
                            identity_file=str(self.home / 'id_ed25519'), installed=True,
                            legacy_marker_prefix='walter', ssh_hosts=[
                                dict(name='probe', ip='203.0.113.10', user='ubuntu'),
                                dict(name='probe-seat', ip='203.0.113.10', user='seat')])

    def write(self, text):
        self.directory.mkdir(mode=0o700, exist_ok=True)
        self.path.write_text(text)

    def update(self, **overrides):
        return MODULE.update({**self.payload, **overrides}, self.home)

    def test_preflight_is_read_only_and_detects_collision(self):
        self.assertFalse(self.update(check_only=True))
        self.assertFalse(self.directory.exists())
        self.write('Host probe\n    User operator\n')
        before = self.path.read_bytes()
        with self.assertRaisesRegex(ValueError, 'outside the deployment marker'):
            self.update(check_only=True)
        self.assertEqual(before, self.path.read_bytes())
        self.assertEqual(['config'], [p.name for p in self.directory.iterdir()])

    def test_normal_ssh_effective_settings_and_user_options(self):
        original = ('Host *\n    IdentityAgent /ambient/agent\n    AddKeysToAgent yes\n'
                    '    ServerAliveInterval 42\n    ControlMaster auto\n'
                    '    RemoteForward 7890 localhost:7890\n')
        self.write(original)
        self.assertTrue(self.update())
        self.assertFalse(self.update())
        self.assertTrue(self.path.read_text().endswith(original))
        command = ['ssh', '-G', '-F', str(self.path), 'probe']
        result = subprocess.run(command, capture_output=True, text=True, check=True)
        effective = result.stdout.lower()
        for line in ['hostname 203.0.113.10', 'user ubuntu', 'identityagent none',
                     'identitiesonly yes', 'forwardagent no', 'serveraliveinterval 42',
                     'controlmaster auto', 'addkeystoagent false']:
            self.assertIn(line, effective)
        self.assertIn('remoteforward', effective)
        self.assertEqual(0o600, self.path.stat().st_mode & 0o777)

    def test_offline_remove_and_retry_preserves_unrelated(self):
        original = 'Host unrelated\n    User operator\n'
        self.write(original)
        self.update()
        self.assertTrue(self.update(block_state='absent', ssh_hosts=[]))
        self.assertEqual(original, self.path.read_text())
        self.assertFalse(self.update(block_state='absent', ssh_hosts=[]))

    def test_scoped_identity_keeps_existing_multiplexing_guards(self):
        self.update(installed=False, identity_file='', ssh_hosts=[dict(
            name='probe', ip='203.0.113.10', user='ubuntu', identity_file='/public/identity.pub')])
        text = self.path.read_text()
        self.assertIn('IdentityFile "/public/identity.pub"', text)
        self.assertIn('ControlMaster no', text)
        self.assertIn('ControlPersist no', text)
        self.assertIn('ControlPath none', text)
        self.assertNotIn('AddKeysToAgent', text)

    def test_embedded_updater_matches_standalone(self):
        play = (ROOT / 'green/src/resources/io/github/getcolors/once/tools/ansible-local/main.yml').read_text()
        start = play.index('              #!/usr/bin/env python3')
        end = play.index('        stdin:', start)
        embedded = '\n'.join(line[14:] if line else '' for line in play[start:end].splitlines()) + '\n'
        self.assertEqual(Path(SPEC.origin).read_text(), embedded)

    def test_refuses_unsafe_files(self):
        target = self.home / 'outside'
        target.write_text('unchanged')
        self.directory.mkdir()
        self.path.symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'unsafe SSH config'):
            self.update()
        self.path.unlink()
        os.link(target, self.path)
        with self.assertRaisesRegex(ValueError, 'unsafe SSH config'):
            self.update()
        self.assertEqual('unchanged', target.read_text())

    def test_refuses_directory_symlink(self):
        target = self.home / 'outside'
        target.mkdir()
        self.directory.symlink_to(target, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'unsafe SSH directory'):
            self.update()
        self.assertEqual([], list(target.iterdir()))

    def test_refuses_malformed_marker_without_rewriting(self):
        original = '# BEGIN probe ANSIBLE MANAGED BLOCK\nHost probe\n'
        self.write(original)
        with self.assertRaisesRegex(ValueError, 'unterminated'):
            self.update()
        self.assertEqual(original, self.path.read_text())


if __name__ == '__main__':
    unittest.main()
