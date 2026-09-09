import type { Opts } from "red/workflow";

// Bump on any change a launcher pinned to an older commit could not survive.
//
// 3: deploy keys are generated per application on every create instead of being
//    supplied as deploy-pubkey, and an application may name a GitHub repository
//    whose environment receives the connection details. A launcher pinned older
//    still expects deploy-pubkey in desired state and would ignore github
//    silently, publishing nothing.
// 4: the SSH Keypair Standard (workspace standards/ssh-keypair.md). The
//    machine-key keys leave required: their absence now selects keygen mode,
//    where ssh.ts generates a profile-named ed25519 keypair in .ssh/, the
//    compute templates create the provider key resource themselves, and the
//    delete DAG gains once/ssh-cleanup. A launcher pinned older still demands
//    the machine key in desired state, refusing a colors.yml written for
//    keygen mode, and renders templates without the keygen branches.
// 5: the machine keypair moves from .ssh/ next to colors.yml to the
//    operator's ~/.ssh, still profile-named. A launcher pinned older
//    generates and resolves the key inside the checkout, so it cannot see a
//    keypair living in ~/.ssh and would generate a second one beside a live
//    deployment's state.
export const contract = 6;

export function registrableDomain(host: unknown): string | undefined {
  const labels = String(host ?? "").split(".");
  return labels.length >= 2 ? labels.slice(-2).join(".") : undefined;
}

export function appsDomains(opts: Opts): string[] {
  const apps = (opts.once as { applications?: Array<{ host?: string }> } | undefined)?.applications ?? [];
  return [...new Set(apps.map((app) => registrableDomain(app.host)).filter(Boolean) as string[])].sort();
}
