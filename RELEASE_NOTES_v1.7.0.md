# BeeKeep Kotlin v1.7.0 — Build & install foundation

This is a stabilization/build-system milestone. No new product feature was added.

## Build-system fixes

- AGP 9.4.0 remains pinned.
- Gradle 9.6.0 is pinned and checksum-verified by the included wrapper.
- Room code generation moved from KAPT to KSP because AGP 9 built-in Kotlin is incompatible with the Kotlin KAPT plugin.
- Kotlin remains native Android/Kotlin; no Capacitor mobile layer is used.
- Added Windows build doctor/build scripts.
- Added GitHub Actions debug-build validation.
- Added a real-device acceptance checklist for install/update, NFC, camera, GPS, voice, calendar, sync, analytics, and field mode.
- Version 1.7.0 / versionCode 23.
