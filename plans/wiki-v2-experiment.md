# WikiContext object-storage experiment

This worktree develops ONCE's opt-in `stop-first` deployment policy. The companion
`../wikicontext-v2` worktree develops direct object storage and
Litestream database recovery. Production checkouts and infrastructure are separate.

## Local configuration

- Deployment profile: `once-v2`.
- OCI CLI profile and tenancy: `oracle-ampere-2`.
- Application origin: `https://wiki-v2.pocketcontext.com`.
- Private EU R2 buckets: `once-v2-state`, `wikicontext-v2-files`,
  `wikicontext-v2-replica`.
- `colors.yml` is a safe template. `colors-once-v2.yml` contains the local OCI
  identifiers and is ignored by Git. Pass it explicitly with `-f`.
- `.envrc.private` is ignored, owner-only, and contains independent bucket-scoped
  credentials plus references copied from the authorized infrastructure setup.
  Never print it or include it in a container image.
- The existing Google client is reused. Its new redirect is
  `https://wiki-v2.pocketcontext.com/api/oauth2-redirect`; production redirects stay.

The experiment uses `provider-dns: no-infra` to avoid reconciling production zone
settings. Manage only the new hostname's DNS record separately. SMTP uses the
existing service through `provider-smtp: no-infra`; do not create or adopt an
existing sending domain under the experiment's state.

Before first provisioning, `compute-require-existing-state` is false. Set it true
after a successful create. Keep destroy protection enabled. Verify the active OCI
tenancy before running any real lifecycle operation.

## Validation and release

Run the ONCE language suites, parity and launcher checks. Run the companion
application's documented tests, its object-storage integration/recovery drills and
the actual container configuration, smoke and restoration checks. Use synthetic
data and disposable test buckets only.

Publish an experimental image, record its immutable digest and deploy that exact
image. Use `ghcr.io/pocketcontext/wikicontext-v2:latest`; make the new package public
before the unauthenticated ONCE pull. Do not publish production `latest`. A local Docker image alone is not enough:
Basecamp ONCE pulls the image through its registry even when the image is cached.
Do not commit or push source branches without explicit authorization.

GitHub CD must use a separate `once-v2` environment. Do not change the production
`COLORS_PROFILE` variable or replace production deployment secrets. The local
configuration initially omits `github` so a create cannot publish credentials into
any repository before the experimental workflow is ready.

## Writer ownership and recovery

`stop-first` coordinates updates on one host. It does not fence another host.
Disable ONCE automatic updates for the experimental app; all maintenance must use
the same per-host deployment lock. A shutdown timeout, ambiguous container state,
or interrupted update must fail closed. Check the durable pending marker before
retrying; never restart old code automatically after a candidate may have migrated
the database.

For a host handover, block and drain writes, stop the source application, confirm
Litestream's final replication succeeded, stop its publisher and prevent restart.
Litestream 0.5.17 can exit successfully despite an unavailable replica, so exit
status alone does not confirm final replication. Restore SQLite into an isolated
destination and compare its complete database contents with the stopped source.
Only after equality and remote-original checks pass may the destination start and
traffic switch. Never run two publishers against one replica.
Preserve the source volume until validation completes.

No live deployment is implied by successful local tests. Record the actual image,
host, completed checks and unresolved checks when the experiment is deployed.

Workflows in this worktree run only on pushes to `experiment/stop-first`, with
no release tagging. Companion workflows run only on `experiment/object-storage`;
its reusable tests enforce that branch too. Manual and pull-request triggers are disabled.
