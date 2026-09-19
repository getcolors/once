"""Run the shipped installer tasks locally, with releases and systemd isolated.

Only external boundaries change: paths/ownership, release transport and the
service manager. Ansible executes the production conditions, file operations,
checksum verification and recovery blocks. Resource parity covers other colours.
"""
import functools
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading

import pytest
import yaml


ROOT = Path(__file__).resolve().parents[2]
RESOURCE = ROOT / "blue/src/package_once_blue/resources/tools/ansible/main.yml"
LIBRARY = Path(__file__).parent / "fixtures/installer/library"
pytestmark = pytest.mark.skipif(not shutil.which("ansible-playbook"), reason="requires ansible-playbook")


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_):
        pass


class Installer:
    def __init__(self, root, url):
        self.root = root
        self.url = url
        self.binary = root / "usr/local/bin/once"
        self.unit = root / "etc/systemd/system/once-background.service"
        self.override = root / "etc/systemd/system/once-background.service.d/10-colors-version.conf"
        self.log = root / "systemd.json"
        self.install_log = root / "install.json"
        self.binary.parent.mkdir(parents=True)
        self.unit.parent.mkdir(parents=True)
        self.asset = root / "once-linux-amd64"
        self.asset.write_text(
            f"#!{sys.executable}\n"
            "import json, os, sys\nfrom pathlib import Path\n"
            "assert sys.argv[1:] == ['background', 'install']\n"
            f"assert 'ONCE_NO_SELF_UPDATE=1' in Path({str(self.override)!r}).read_text()\n"
            f"Path({str(self.install_log)!r}).write_text(json.dumps({{'ONCE_NO_SELF_UPDATE': os.getenv('ONCE_NO_SELF_UPDATE')}}))\n"
            f"Path({str(self.unit)!r}).write_text('[Service]\\nExecStart=once background run\\n')\n"
        )
        shutil.copyfile(self.asset, root / "once-linux-arm64")
        self.checksum = hashlib.sha256(self.asset.read_bytes()).hexdigest()

    def seed_old(self):
        self.binary.write_text("old executable\n")
        self.unit.write_text("old service\n")
        self.log.write_text(json.dumps([{"state": "started"}]))

    @property
    def calls(self):
        return json.loads(self.log.read_text()) if self.log.exists() else []

    def run(self, *, arch="x86_64", checksum=None, check=False, fail_copy=False, updater_race=False):
        document = yaml.safe_load(RESOURCE.read_text())
        block = next(task for task in document[0]["tasks"] if task["name"] == "Manage pinned ONCE")

        def relocate(value):
            if isinstance(value, str):
                for prefix in ("/usr/local/bin/", "/etc/systemd/system/", "/var/cache/once"):
                    value = value.replace(prefix, str(self.root) + prefix)
                return value.replace("https://github.com/basecamp/once/releases/download/{{ once_version }}", self.url)
            if isinstance(value, list):
                return [relocate(item) for item in value]
            if isinstance(value, dict):
                result = {key: relocate(item) for key, item in value.items()}
                if "become" in result:
                    result["become"] = False
                for field, replacement in (("owner", str(os.getuid())), ("group", str(os.getgid()))):
                    if field in result:
                        result[field] = replacement
                if "retries" in result:
                    result.update(retries=0, delay=0)
                if "ansible.builtin.systemd_service" in result:
                    params = result.pop("ansible.builtin.systemd_service")
                    result["fixture_systemd"] = {**params, "log_path": str(self.log)}
                    if updater_race:
                        result["fixture_systemd"]["updater_binary"] = str(self.binary)
                if fail_copy and result.get("name") == "Install pinned ONCE binary":
                    result["ansible.builtin.copy"]["src"] = str(self.root / "deliberately-missing")
                return result
            return value

        block = relocate(block)
        for release in block["vars"]["once_architectures"].values():
            release["checksum"] = checksum or self.checksum
        playbook = self.root / "playbook.yml"
        playbook.write_text(yaml.safe_dump([{
            "hosts": "localhost", "connection": "local", "gather_facts": False,
            "vars": {"ansible_facts": {"system": "Linux", "architecture": arch, "service_mgr": "systemd"},
                     "ansible_python_interpreter": sys.executable},
            "tasks": [block],
        }]))
        env = {**os.environ, "ANSIBLE_LIBRARY": str(LIBRARY), "ANSIBLE_NOCOLOR": "1",
               "ANSIBLE_LOCAL_TEMP": str(self.root / "ansible-controller"),
               "ANSIBLE_REMOTE_TEMP": str(self.root / "ansible-remote")}
        return subprocess.run(
            ["ansible-playbook", "-i", "localhost,", str(playbook), *(["--check"] if check else [])],
            env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120,
        )


@pytest.fixture
def installer(tmp_path):
    handler = functools.partial(QuietHandler, directory=str(tmp_path))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield Installer(tmp_path, f"http://127.0.0.1:{server.server_port}")
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


@pytest.mark.parametrize("arch", ["x86_64", "aarch64", "arm64"])
def test_fresh_install_is_pinned_and_idempotent(installer, arch):
    result = installer.run(arch=arch)
    assert result.returncode == 0, result.stdout
    assert installer.binary.read_bytes() == installer.asset.read_bytes()
    assert installer.binary.stat().st_mode & 0o777 == 0o755
    assert json.loads(installer.install_log.read_text())["ONCE_NO_SELF_UPDATE"] == "1"
    assert 'Environment="ONCE_NO_SELF_UPDATE=1"' in installer.override.read_text()
    assert installer.calls[-1]["enabled"] is True
    assert installer.calls[-1]["daemon_reload"] is True
    assert installer.calls[-1]["state"] == "started"
    initial_stat = installer.binary.stat()
    result = installer.run(arch=arch)
    assert result.returncode == 0, result.stdout
    assert re.search(r"changed=0\s", result.stdout), result.stdout
    assert installer.binary.stat().st_mtime_ns == initial_stat.st_mtime_ns


def test_upgrade_stops_and_restarts_service(installer):
    installer.seed_old()
    result = installer.run()
    assert result.returncode == 0, result.stdout
    assert installer.binary.read_bytes() == installer.asset.read_bytes()
    assert [call["state"] for call in installer.calls] == ["started", "stopped", "started"]
    assert not installer.install_log.exists(), "existing service must not be reinstalled"


def test_adopting_matching_binary_disables_live_self_updater(installer):
    installer.seed_old()
    shutil.copyfile(installer.asset, installer.binary)
    result = installer.run()
    assert result.returncode == 0, result.stdout
    assert [call["state"] for call in installer.calls] == ["started", "stopped", "started"]


def test_updater_race_during_adoption_still_installs_pinned_binary(installer):
    installer.seed_old()
    shutil.copyfile(installer.asset, installer.binary)
    result = installer.run(updater_race=True)
    assert result.returncode == 0, result.stdout
    assert installer.binary.read_bytes() == installer.asset.read_bytes()
    assert [call["state"] for call in installer.calls] == ["started", "stopped", "started"]


def test_bad_checksum_preserves_running_old_installation(installer):
    installer.seed_old()
    result = installer.run(checksum="0" * 64)
    assert result.returncode != 0, result.stdout
    assert "checksum" in result.stdout.lower()
    assert installer.binary.read_text() == "old executable\n"
    assert installer.calls == [{"state": "started"}]
    assert not installer.override.exists()


def test_failed_replacement_recovers_existing_service(installer):
    installer.seed_old()
    result = installer.run(fail_copy=True)
    assert result.returncode != 0, result.stdout
    assert installer.binary.read_text() == "old executable\n"
    assert [call["state"] for call in installer.calls] == ["started", "stopped", "started"]


def test_unsupported_architecture_fails_before_writes(installer):
    result = installer.run(arch="riscv64")
    assert result.returncode != 0, result.stdout
    assert "ONCE requires Linux amd64 or arm64 with systemd" in result.stdout
    assert not installer.binary.exists()
    assert not installer.override.exists()
    assert installer.calls == []


def test_check_mode_plans_fresh_install_without_writes(installer):
    result = installer.run(check=True)
    assert result.returncode == 0, result.stdout
    assert re.search(r"changed=1\s", result.stdout), result.stdout
    assert not installer.binary.exists()
    assert not installer.override.exists()
    assert installer.calls == []
