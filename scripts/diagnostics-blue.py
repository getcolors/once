import json, sys
from package_once_blue.machine import failure
for case in json.load(open(sys.argv[1])):
    opts = failure({'blue/event': case['event']}, case['result'])
    print(json.dumps([case['name'], opts['blue/exit'], opts['blue/err']], separators=(',', ':')))
