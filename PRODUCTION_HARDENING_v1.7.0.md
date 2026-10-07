# Production hardening v1.7.0 — Build/install foundation

This release focuses only on making the native Android project reproducible and safer to update.

## Changes

- AGP 9.4.0 retained as the current stable Android build plugin.
- Migrated Room annotation processing from KAPT to KSP because AGP 9 built-in Kotlin is incompatible with the Kotlin KAPT plugin.
- Added Windows build doctor and debug-build scripts.
- Added build/install/update documentation.
- Kept `applicationId` stable as `com.beekeep.app` so normal debug/release upgrades can preserve app data when signing permits.
- Bumped version to 1.7.0 / versionCode 23.

## Intentional limitation

No signed APK is included. A signed production APK/AAB requires a real Android SDK and release keystore.
