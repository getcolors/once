from __future__ import annotations

# Bump on any change a launcher pinned to an older commit could not survive.
#
# 3: deploy keys are generated per application on every create instead of being
#    supplied as deploy-pubkey, and an application may name a GitHub repository
#    whose environment receives the connection details. A launcher pinned older
#    still expects deploy-pubkey in desired state and would ignore github
#    silently, publishing nothing.
# 4: the SSH Keypair Standard (workspace standards/ssh-keypair.md). The
#    machine-key keys leave required: their absence now selects keygen mode,
#    where ssh.py generates a profile-named ed25519 keypair in .ssh/, the
#    compute templates create the provider key resource themselves, and the
#    delete DAG gains once/ssh-cleanup. A launcher pinned older still demands
#    the machine key in desired state, refusing a colors.yml written for
#    keygen mode, and renders templates without the keygen branches.
#    (Shipped in the same commit as green's 12 and red's 4; this entry and
#    the bump were missed then and added later. Nothing reads this number
#    yet: only the green launcher checks a contract.)
# 5: the machine keypair moves from .ssh/ next to colors.yml to the operator's
#    ~/.ssh, still profile-named. A launcher pinned older generates and
#    resolves the key inside the checkout, so it cannot see a keypair living
#    in ~/.ssh and would generate a second one beside a live deployment's
#    state.
CONTRACT = 6


def registrable_domain(host: object) -> str | None:
    labels = str(host or "").split(".")
    return ".".join(labels[-2:]) if len(labels) >= 2 else None


def apps_domains(opts: dict) -> list[str]:
    apps = (opts.get("once") or {}).get("applications") or []
    return sorted({domain for app in apps if (domain := registrable_domain(app.get("host")))})
