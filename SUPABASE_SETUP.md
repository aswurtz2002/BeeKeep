# BeeKeep Supabase setup

The Android app is offline-first. Supabase is the cloud backup + cross-device synchronization layer.

## 1. Create a Supabase project

Use a new project in the Supabase dashboard. The current Supabase Android/Kotlin quickstart recommends the Kotlin client and a publishable project key rather than a server secret.

## 2. Run the migration

Open the Supabase SQL Editor and run:

`supabase/migrations/20261006_beekeep_cloud.sql`

The migration creates `beekeep_documents` and enables Row Level Security so an authenticated user can only read/write their own documents.

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
