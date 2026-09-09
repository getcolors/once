import json
from package_once_blue.workflow import wire_fn
print(json.dumps([[event, list(wire_fn('once/tofu-smtp-post', {'blue/event':event})[1:]), list(wire_fn('once/ansible-local', {'blue/event':event})[1:]), list(wire_fn('once/ansible-remote', {'blue/event':event})[1:])] for event in ['create','build']], separators=(',',':')))
