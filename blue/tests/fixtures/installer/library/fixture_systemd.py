#!/usr/bin/python
"""A local systemd seam: persist calls without contacting the host manager."""
import json
from pathlib import Path

from ansible.module_utils.basic import AnsibleModule


module = AnsibleModule(argument_spec={
    "name": {"type": "str"},
    "state": {"type": "str"},
    "enabled": {"type": "bool"},
    "daemon_reload": {"type": "bool", "default": False},
    "log_path": {"type": "path", "required": True},
    "updater_binary": {"type": "path"},
}, supports_check_mode=True)
log = Path(module.params["log_path"])
calls = json.loads(log.read_text()) if log.exists() else []
previous = next((call["state"] for call in reversed(calls) if call["state"]), None)
state = module.params["state"]
changed = state == "restarted" or (state is not None and state != previous)
if not module.check_mode:
    if state == "stopped" and module.params["updater_binary"]:
        Path(module.params["updater_binary"]).write_text("concurrent upstream self-update\n")
    calls.append(module.params)
    log.write_text(json.dumps(calls))
module.exit_json(changed=changed)
