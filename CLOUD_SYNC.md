# BeeKeep cloud sync

BeeKeep is offline-first. Local writes are committed to Room first, then queued in a sync outbox for WorkManager and foreground synchronization. Realtime notifications are an accelerator; periodic/manual sync remains the recovery path.

## Personal data

- The existing `beekeep_documents` table retains the per-user data model for personal workspaces and older app versions.
- Personal apiaries, hives, inspections, feedings, treatments, harvests, events and NFC history continue to synchronize under the signed-in user.
- NFC tag ownership is synchronized through immutable `nfc_assignment` history, not by uploading a whole hive profile when only its cached tag UID changes. Assignment release is monotonic by assignment ID: a delayed active copy cannot reopen a locally released assignment, and an ID reused with a different UID, hive or assignment time fails closed. Each device derives its hive tag cache from the assignment ledger.
- Signing out switches the active local dataset back to the personal/offline database.
- The foreground app and background WorkManager sync share one process-wide Supabase client and sync mutex. Do not create a separate auto-refreshing Auth client for each gateway: independent refresh loops can race on the same persisted rotating refresh token and cause `refresh_token_already_used` session failures.

## Team workspace data

- Each shared team workspace uses a separate local Room database file, so personal records are not silently copied into the team's dataset.
- The active team preference is bound to the account user ID that selected it. A repository starts on personal/local data; startup restores a cached team only when that binding matches the signed-in account. A legacy unbound selection must pass a membership lookup before it is restored. Signing out or switching accounts returns to personal data. The same account can still reopen its cached team offline.
- Shared records live in `public.beekeep_workspace_documents`, keyed by `workspace_id, entity_type, entity_id`. This separate table avoids changing the legacy private record table's shape.
- Members can read records in their workspace. Owners, admins and members can write; viewers are read-only. Row-level security is enforced by Supabase; the app's local read-only guard is an additional user-experience safeguard, not the security boundary.
- The app validates membership at sync time and only fetches/uploads records for the selected workspace. A revoked membership falls back to personal data.
- Tombstones synchronize deletions. Ordinary cloud edits use last-write-wins by client `updated_at`: the server rejects lower-timestamp writes. An explicit delete of a permanent entity is authoritative: the client advances its tombstone timestamp beyond the known cloud row, and the server also advances it beyond the current row under concurrent updates/device clock skew. Permanent record tombstones cannot be changed back to live records. Inspection note/form draft clears remain timestamp-ordered because those hive-scoped draft IDs are intentionally reusable; stale clears must not erase newer drafts. Permanently deleting a hive also clears local pending note/form drafts and bee-count draft markers after their tombstones have been queued.
- Equal non-delete timestamps are resolved by the last write committed by Postgres. When a local outbox row is older than or tied with the cloud copy, the client applies the cloud winner before acknowledging the local row.
- This is conflict protection, not field-level merging. Devices with materially incorrect system clocks can still misclassify a legitimate offline edit as stale, so real two-device staging tests remain necessary.

## Database setup and verification

The migrations are ordered under `supabase/migrations/`. Stage 3's shared-record storage migration is `20261018045154_beekeep_workspace_shared_documents.sql` and `20261018045419_beekeep_workspace_document_rls_performance.sql`. It must be applied to the intended Supabase project before team sync can work.

The GitHub workflow `.github/workflows/shared-workspace-migration.yml` applies the base schema, workspace/membership/invitation migrations and both Stage 3 shared-record migrations to a disposable PostgreSQL service. It then runs:
- `supabase/tests/shared_workspace_foundation.sql`
- `supabase/tests/workspace_invitation_security.sql`
- `supabase/tests/shared_workspace_document_security.sql`

Keep staging and production schema changes separate. Verify the migration history and run the Supabase security/performance advisors after applying migrations. Never place a Supabase service-role key in the Android app; it should use the publishable key with authenticated RLS.

## Important rollout limitation

Automated SQL tests and Android CI can verify schema security, compilation and unit tests, but they do not replace a real two-device test. Before considering shared sync production-ready, test two signed-in devices in the same staging team: create a hive on device A, sync, receive it on device B, edit it on B, verify it appears on A, then test offline edits, delete propagation, viewer restrictions, role removal, account sign-out/switch, and that each user's personal records remain private.