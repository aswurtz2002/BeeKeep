# BeeKeep Native Android v0.5.0

This build is Android-native Kotlin only. The previous PC/browser companion is intentionally not included.

## Field features
- Room offline database with migration from v0.4.
- Native CameraX photo capture with local-first inspection storage.
- Native GPS capture.
- Native speech-to-text notes.
- Native NFC read/write and stable tag UID mapping.
- WorkManager reminders.
- Supabase email/password auth and document sync.
- Supabase Realtime listener for faster cross-device refresh.
- Private Supabase Storage bucket for inspection photos, scoped by authenticated user.

## Build
Open the `android/` directory in Android Studio.

Supply the Supabase values as Gradle properties:

`./gradlew :app:assembleDebug -PsupabaseUrl=https://YOUR_PROJECT.supabase.co -PsupabaseKey=YOUR_PUBLISHABLE_KEY`

Only a Supabase publishable key belongs in the mobile build. Never ship a service-role/secret key in the Android app.
