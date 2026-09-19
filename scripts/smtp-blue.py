import json
import sys
from pathlib import Path
from package_once_blue.tools import render_fn

print(render_fn("smtp", json.loads(Path(sys.argv[1]).read_text())))
