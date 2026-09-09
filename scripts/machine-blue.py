import json, sys
from package_once_blue import machine
cases=json.load(open(sys.argv[1]))
print(json.dumps([machine.params(case['opts'],case['result']) for case in cases],sort_keys=True))
