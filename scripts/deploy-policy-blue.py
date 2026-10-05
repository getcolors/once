import json
import sys
from package_once_blue.validate import state_errors
for app in json.load(open(sys.argv[1])):
    print(json.dumps([s for s in state_errors({'once':{'applications':[{'host':'wiki.example.com','image':'example/wiki',**app}]}}) if s.startswith(('deploy-', 'stop-first', 'application smtp'))], separators=(',', ':')))
