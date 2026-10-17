# BeeKeep Supabase setup

The Android app is offline-first. Supabase is the cloud backup + cross-device synchronization layer.

## 1. Create a Supabase project

Use a new project in the Supabase dashboard. The current Supabase Android/Kotlin quickstart recommends the Kotlin client and a publishable project key rather than a server secret.

## 2. Run the cloud migrations

For a project that already has BeeKeep cloud sync running, keep its current `beekeep_documents` schema and policies in place, then run:

`supabase/migrations/20261017_beekeep_shared_workspaces.sql`

This Stage 1 migration creates `beekeep_workspaces` and `beekeep_workspace_members`, creates a private default workspace and Owner membership for every existing account, and installs a trigger to do the same for new sign-ups. It backfills existing cloud documents with their owner's personal `workspace_id`.

**Compatibility/safety:** the existing document primary key and per-user Row Level Security policies are intentionally preserved in Stage 1. This does not share or expose data across accounts. Shared reads/writes are not enabled until the later shared-sync stage.

The earlier cloud setup migration in this repository is `supabase/migrations/20261006_beekeep_cloud_v2.sql`. On a fresh Supabase project, ensure the existing base BeeKeep cloud schema is installed first, run that cloud-sync migration, and then run the Stage 1 workspace migration. Existing deployed projects that already have the v2 schema should run only the new Stage 1 migration.

## 3. Configure Android Studio

Do not commit your project credentials into source control.

For a local Android Studio build, pass:

`./gradlew :app:assembleDebug -PsupabaseUrl=https://YOUR_PROJECT.supabase.co -PsupabaseKey=YOUR_PUBLISHABLE_KEY`

The Gradle script places these into `BuildConfig.SUPABASE_URL` and `BuildConfig.SUPABASE_PUBLISHABLE_KEY` at build time.

The mobile app never uses a Supabase service-role/secret key.

## 4. Authentication

BeeKeep currently supports email/password sign-in and account creation. Supabase may require email verification depending on the project's Auth settings.

## 5. Sync behavior

Local Room writes happen first. A sync outbox records the changed entity. When the device has connectivity and a user session exists, WorkManager attempts a sync. The app pushes queued documents, then pulls the authenticated user's cloud documents and applies them locally.

This is intentionally not dependent on realtime connectivity. Realtime can be added later as an accelerator.

## 6. Shared workspace rollout status

Stage 1 only installs the workspace/membership foundation and associates each account's current cloud documents with that account's private workspace. It does not yet allow invitations, workspace switching, cross-user synchronization, or shared NFC assignment. Membership write policies are intentionally closed to mobile clients; future invite/role operations must go through trusted, role-checked server functions.

Before Stage 3 enables shared sync, test this migration against a staging Supabase project with existing records and verify that each account still sees only its own records. Keep a database backup before applying schema migrations to production.
