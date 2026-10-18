# BeeKeep Supabase setup

The Android app is offline-first. Supabase is the cloud backup + cross-device synchronization layer.

## 1. Create a Supabase project

Use a dedicated Supabase project for development/staging. Do not point a staging build at production data.

## 2. Run the cloud migrations

For a project that already has BeeKeep cloud sync running, keep its current `beekeep_documents` schema and policies in place, then run:

`supabase/migrations/20261017_beekeep_shared_workspaces.sql`

This Stage 1 migration creates `beekeep_workspaces` and `beekeep_workspace_members`, creates a private default workspace and Owner membership for every existing account, and installs a trigger to do the same for new sign-ups. It backfills a separate `beekeep_document_workspaces` mapping table for existing cloud records.

**Compatibility/safety:** the `beekeep_documents` column list, primary key, and per-user Row Level Security policies are intentionally preserved in Stage 1. The separate mapping table avoids adding a field to responses older app versions deserialize. This does not share or expose data across accounts. Shared reads/writes are not enabled until the later shared-sync stage.

The earlier cloud setup migration in this repository is `supabase/migrations/20261006_beekeep_cloud_v2.sql`. On a fresh Supabase project, ensure the existing base BeeKeep cloud schema is installed first, run that cloud-sync migration, then run the Stage 1 and Stage 2 workspace migrations in filename order. The two Stage 2 follow-up migrations are idempotent fixes for role checks and the invitation-acceptance conflict target. Existing deployed projects should apply every migration they have not already applied.

## 3. Build variants

### Staging (preconfigured)

A dedicated build type is wired to the isolated `BeeKeep-Staging` Supabase project:

```powershell
./gradlew :app:assembleStaging
```

The APK is written to `app/build/outputs/apk/staging/app-staging.apk`. Its application ID is `com.beekeep.app.staging`, and the launcher name is **BeeKeep Staging**, so it can be installed beside the normal BeeKeep app without replacing it.

The staging build contains only the Supabase **publishable** key, which is designed for client-side use and cannot replace database authorization. Access remains controlled by Auth, grants, and Row Level Security (RLS). Never embed a Supabase secret/service-role key in any Android build or commit one to source control.

GitHub Actions builds and uploads the staging APK as the `beekeep-staging-apk` workflow artifact (retained for 14 days). Open the repository's Actions tab, open a successful **BeeKeep Android build** run, and download that artifact.

### Configure a regular local debug build

For a local Android Studio build using a different project, pass that project's URL and publishable key as Gradle project properties:

```powershell
./gradlew :app:assembleDebug -PsupabaseUrl=https://YOUR_PROJECT.supabase.co -PsupabaseKey=YOUR_PUBLISHABLE_KEY
```

The Gradle script places these into `BuildConfig.SUPABASE_URL` and `BuildConfig.SUPABASE_PUBLISHABLE_KEY` at build time. Do not pass a secret/service-role key.

## 4. Authentication

BeeKeep currently supports email/password sign-in and account creation. Supabase may require email verification depending on the project's Auth settings. Use a dedicated tester account with the staging app.

## 5. Sync behavior

Local Room writes happen first. A sync outbox records the changed entity. When the device has connectivity and a user session exists, WorkManager attempts a sync. The app pushes queued documents, then pulls the authenticated user's cloud documents and applies them locally.

This is intentionally not dependent on realtime connectivity. Realtime can be added later as an accelerator.

## 6. Shared workspace rollout status

### Stage 1: workspace foundation
Stage 1 creates a private personal workspace for each account and maps existing cloud documents to it. The `beekeep_documents` table and its per-user row-level security policies remain compatible with older builds.

### Stage 2: invitations and membership roles
Stage 2 creates a team workspace through a role-checked database function and supports one-time, seven-day invitation codes. A workspace owner/admin enters the invitee's account email, chooses a permitted role, then shares the generated code privately through Android's share sheet. The invitee must sign in to BeeKeep using that exact email and accept the code in **More → Team workspaces**. The database stores only a SHA-256 hash of the code; the raw code is displayed once. Owners can manage all non-owner member roles. Admins can manage members/viewers but cannot assign or manage admin roles. Mobile clients cannot directly insert/update membership or invitation rows.

**Important boundary:** Stage 2 manages workspace membership only. Hive/apiary records still sync privately per user, even when users belong to a team workspace. Shared-record sync and active workspace selection need a separately tested data model and are not enabled yet; the UI states this explicitly.

Before Stage 3 enables shared sync, test with multiple staging accounts and verify that each account can only see authorized records. Keep a database backup before applying schema migrations to production.
