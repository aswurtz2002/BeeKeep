# BeeKeep Supabase setup

The Android app is offline-first. Supabase is the cloud backup + cross-device synchronization layer.

## 1. Create a Supabase project

Use a dedicated Supabase project for development/staging. Do not point a staging build at production data.

## 2. Run the cloud migrations

For a project that already has BeeKeep cloud sync running, keep its current `beekeep_documents` schema and policies in place, then run:

`supabase/migrations/20261017224333_beekeep_shared_workspaces.sql`

This Stage 1 migration creates `beekeep_workspaces` and `beekeep_workspace_members`, creates a private default workspace and Owner membership for every existing account, and installs a trigger to do the same for new sign-ups. It backfills a separate `beekeep_document_workspaces` mapping table for existing cloud records.

**Compatibility/safety:** the `beekeep_documents` column list, primary key, and per-user Row Level Security policies are intentionally preserved in Stage 1. The separate mapping table avoids adding a field to responses older app versions deserialize. This does not share or expose data across accounts. Shared reads/writes are not enabled until the later shared-sync stage.

The earlier cloud setup migration in this repository is `supabase/migrations/20261017224317_beekeep_cloud_v2.sql`. On a fresh Supabase project, ensure the existing base BeeKeep cloud schema is installed first, run that cloud-sync migration, then run the Stage 1 and Stage 2 workspace migrations in filename order. The two Stage 2 follow-up migrations are idempotent fixes for role checks and the invitation-acceptance conflict target. Existing deployed projects should apply every migration they have not already applied.

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

## Individual Pro and Ultimate hive-limit exceptions

Migration `20261027062000_beekeep_per_account_hive_limit_overrides.sql` introduced per-account overrides; `20261027130000_beekeep_pro_and_ultimate_hive_limit_overrides.sql` extends them to both paid tiers and records a private audit history. Pro's standard hive allowance remains 1,000 and a Pro override cannot be set below 1,000. Ultimate's standard allowance remains 10,000 and an Ultimate override cannot be set below 10,000. The database checks the trusted active subscription in `auth.users.raw_app_meta_data`; expired/downgraded accounts and user-editable profile metadata never retain the extra capacity.

The mobile app reads its own verified allowance through `beekeep_my_hive_limit_override()` and its active plan through `beekeep_my_hive_entitlement()`; neither RPC accepts a target account ID. It cannot read or write the internal override/audit tables. Cloud sync also enforces the resolved cap for personal and shared-workspace hive inserts/undeletes, including offline uploads. The client must refresh its account entitlement during cloud sync before a new custom limit appears on device.

### Managing exceptions

Prefer **More → Support admin tools** in the staging app. It checks the account's paid-plan status, shows standard vs. custom hive limits, supports Pro and Ultimate, offers limit presets, and displays the recent set/remove audit history. It never changes a customer's subscription. The server requires a reason for changes and records the operator, time, and previous/new limits.

A direct SQL override is reserved for emergency support operations by a trusted database operator. Confirm the exact target account and active plan first. Replace `customer@example.com` with the customer's exact account email.

```sql
insert into public.beekeep_user_hive_limit_overrides
    (user_id, hive_limit_override, reason)
select
    u.id,
    11000,
    'Approved support exception: increased hive allowance'
from auth.users u
where lower(u.email) = lower('customer@example.com')
on conflict (user_id) do update
set hive_limit_override = excluded.hive_limit_override,
    reason = excluded.reason,
    updated_at = now()
returning user_id, hive_limit_override, reason, updated_at;
```

The query must return exactly one row. If it returns none, confirm the email/account first; do not insert an override by guessing a user ID. The table permits values from 1,000 to 1,000,000, but application limits are plan-specific: never set Pro below 1,000 or Ultimate below 10,000. For normal support operations, use the protected admin screen so each change is audited. The new value appears on the customer's device after a successful entitlement refresh/cloud sync.

To remove an exception and restore that active paid plan's standard hive limit:

```sql
delete from public.beekeep_user_hive_limit_overrides
where user_id = (
    select id from auth.users
    where lower(email) = lower('customer@example.com')
);
```

The override and audit tables intentionally have no authenticated-client RLS policies. Do not grant the mobile app permission to write or read them, and never put Supabase service-role credentials into the Android app. The protected Edge Function is the only app-facing route for support operations.

## Private support-admin tools for Pro and Ultimate hive-limit exceptions

The app's **More → Support admin tools** entry appears only after the signed-in account passes the server-side admin check. Every lookup, set, and clear request is re-authorized by the `beekeep-admin-hive-limits` Edge Function; hiding the button is not the security boundary. The function is the only component that uses the service-role key, and that key must never be placed in the Android app or public source.

### Enable one trusted support account in staging

After the admin RPC migration and Edge Function have been deployed to **BeeKeep-Staging**, use the SQL Editor to grant the flag to your own exact staging account email. Do not grant this flag to customers or ordinary testers.

```sql
update auth.users
set raw_app_meta_data = coalesce(raw_app_meta_data, '{}'::jsonb)
    || jsonb_build_object('beekeep_support_admin', true)
where lower(email) = lower('YOUR_ADMIN_ACCOUNT_EMAIL')
returning id, email, raw_app_meta_data -> 'beekeep_support_admin' as support_admin;
```

Confirm it returns exactly one row with `support_admin = true`. If zero rows are returned, check the staging project and exact account email before trying again. This flag is in trusted `raw_app_meta_data`; a mobile user cannot grant it through their editable profile metadata.

To revoke support-admin access from that account:

```sql
update auth.users
set raw_app_meta_data = coalesce(raw_app_meta_data, '{}'::jsonb)
    - 'beekeep_support_admin'
where lower(email) = lower('YOUR_ADMIN_ACCOUNT_EMAIL')
returning id, email, raw_app_meta_data -> 'beekeep_support_admin' as support_admin;
```

### Use the screen

Open **More → Support admin tools**, look up the customer's exact BeeKeep account email, and verify the returned account and paid-plan status. A custom limit can be set from 1,000 to 1,000,000 for active Pro or active Ultimate, with the server enforcing each plan's standard minimum. Free/expired accounts cannot receive custom capacity. The panel shows account expiry, standard/effective quota, a reasoned change flow, reset-to-standard, and the most recent 10 change/removal audit events.

The customer's app refreshes its trusted plan and custom quota during sign-in and cloud sync, so it can apply the new ceiling instead of continuing to display the old plan default. Ask the customer to tap **More → BeeKeep Cloud → Sync Now** if the previous limit warning remains on screen. This initial rollout is for **staging only**. Review and separately configure the production operator flag and production function before enabling this workflow for real customer accounts.
