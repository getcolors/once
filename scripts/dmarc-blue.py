"""Compare DMARC diagnostics independently of older provider error formatting."""
import json
import sys

from package_once_blue.validate import state_errors

with open(sys.argv[1]) as source:
    fixture = json.load(source)
for case in fixture["cases"]:
    errors = state_errors(fixture["base"] | case["opts"])
    print(json.dumps([case["name"], [error for error in errors if error.startswith("smtp-dmarc-")]], separators=(",", ":")))
