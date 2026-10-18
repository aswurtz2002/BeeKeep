# BeeKeep cloud sync

BeeKeep is offline-first. Local writes are committed to Room first, then queued in a sync outbox for WorkManager and foreground synchronization. Realtime notifications are an accelerator; periodic/manual sync remains the recovery path.

## Personal data

- The existing `beekeep_documents` table retains the per-user data model for personal workspaces and older app versions.
- Personal apiaries, hives, inspections, feedings, treatments, harvests, events and NFC history continue to synchronize under the signed-in user.
- Signing out switches the active local dataset back to the personal/offline database.

## Team workspace data

- Each shared team workspace uses a separate local Room database file, so personal records are not silently copied into the team's dataset.
- Shared records live in `public.beekeep_workspace_documents`, keyed by `workspace_id, entity_type, entity_id`. This separate table avoids changing the legacy private record table's shape.
- Members can read records in their workspace. Owners, admins and members can write; viewers are read-only. Row-level security is enforced by Supabase; the app's local read-only guard is an additional user-experience safeguard, not the security boundary.
- The app validates membership at sync time and only fetches/uploads records for the selected workspace. A revoked membership falls back to personal data.
- Tombstones synchronize deletions. Conflicts currently use record timestamps and the same pull-before-upload pattern as personal sync; this is not a full multi-writer conflict-resolution system.

## Database setup and verification

The migrations are ordered under `supabase/migrations/`. Stage 3's shared-record storage migration is `20261018045154_beekeep_workspace_shared_documents.sql` and `20261018045419_beekeep_workspace_document_rls_performance.sql`. It must be applied to the intended Supabase project before team sync can work.

The GitHub workflow `.github/workflows/shared-workspace-migration.yml` applies the base schema, workspace/membership/invitation migrations and both Stage 3 shared-record migrations to a disposable PostgreSQL service. It then runs:
- `supabase/tests/shared_workspace_foundation.sql`
- `supabase/tests/workspace_invitation_security.sql`
- `supabase/tests/shared_workspace_document_security.sql`

Keep staging and production schema changes separate. Verify the migration history and run the Supabase security/performance advisors after applying migrations. Never place a Supabase service-role key in the Android app; it should use the publishable key with authenticated RLS.

## Important rollout limitation

Automated SQL tests and Android CI can verify schema security, compilation and unit tests, but they do not replace a real two-device test. Before considering shared sync production-ready, test two signed-in devices in the same staging team: create a hive on device A, sync, receive it on device B, edit it on B, verify it appears on A, then test offline edits, delete propagation, viewer restrictions, role removal, account sign-out/switch, and that each user's personal records remain private.