#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fixture="$root/test/parity/ssh-install.json"
(cd "$root/green" && bb ../scripts/ssh-install-green.clj "$fixture") > "$tmp/green"
(cd "$root/red" && bun ../scripts/ssh-install-red.ts "$fixture") > "$tmp/red"
(cd "$root/blue" && uv run python ../scripts/ssh-install-blue.py "$fixture") > "$tmp/blue"
diff -u "$tmp/green" "$tmp/red"
diff -u "$tmp/green" "$tmp/blue"
python3 - "$tmp/green" <<'PY'
import json,sys
rows={row[0]:row[1:] for row in json.load(open(sys.argv[1]))}
expected={
 'install':(['preflight','install','present'],0,None),
 'install-preflight-refused':(['preflight'],7,'SSH config update failed: fixture config refusal'),
 'install-export-refused':(['preflight','install'],1,'fixture export refusal'),
 'install-config-refused':(['preflight','install','present'],7,'SSH config update failed: fixture config refusal'),
 'uninstall':(['inspect','absent','remove'],0,None),
 'uninstall-absent':(['inspect','absent','remove'],0,None),
 'uninstall-inspect-refused':(['inspect'],1,'fixture export refusal'),
 'uninstall-config-refused':(['inspect','absent'],7,'SSH config update failed: fixture config refusal'),
 'uninstall-remove-refused':(['inspect','absent','remove'],1,'fixture export refusal'),
 'install-dry-run':([],None,None), 'uninstall-dry-run':([],None,None),
 'preserve-installed':(['inspect'],'/fixture/encrypted'),
 'preserve-absent':(['inspect'],None),
 'preserve-error':(['inspect'],'fixture ownership refusal'),
 'preserve-build':([],None),'preserve-create-dry':([],None),
 'start-ssh-install':(['resource-inspect','registration-inspect','live-connection'],0),
 'start-ssh-uninstall':([],0), 'start-ssh-install-dry':([],0), 'start-ssh-uninstall-dry':([],0),
 'graph-ssh-install':([['once/ssh-install'],[]],), 'graph-ssh-uninstall':([['once/ssh-uninstall'],[]],),
}
for name,value in expected.items():
    assert rows[name]==list(value),(name,rows[name],value)
for name,inspected,installed,identity,state in [
 ('local-create-installed',['inspect'],True,'/fixture/encrypted','present'),
 ('local-create-absent',['inspect'],False,'','present'),
 ('local-delete-installed',[],False,'','absent')]:
    calls,config=rows[name]
    assert calls==inspected
    assert config['ssh_installed'] is installed and config['ssh_identity_file']==identity
    assert config['block_state']==state
    assert config['ssh_hosts'][0]['identity_file']=='/fixture/public.pub'
present,absent=rows['payload']
assert present['installed'] is True and present['identity_file']=='/fixture/encrypted'
assert present['ssh_hosts']==[{'name':'parity','ip':'203.0.113.10','user':'ubuntu'}]
assert absent['block_state']=='absent' and absent['ssh_hosts']==[]
print('green, red, and blue agree on encrypted SSH installation, offline removal, failures and preservation')
PY
