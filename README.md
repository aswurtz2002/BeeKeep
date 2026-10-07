# BeeKeep — Native Android v1.7.0

BeeKeep is a mobile-first beekeeping log built natively in Kotlin for Android.


## v1.0.1 — Deep Regression Polish
- Hardened cloud sync against concurrent runs and stale offline uploads
- Realtime is limited to signed-in interactive app sessions; background sync workers do not open websockets
- Debounced realtime sync bursts
- Hardened task reminders against rescheduled/stale alarms
- Improved camera failure reporting and photo bitmap lifecycle
- Hardened NFC reader lifecycle
- Backfilled inspections no longer regress hive edit timestamps

## v1.0.0 — Smart Inspection Assistant
- Added a per-hive BeeKeep Health Score with transparent, heuristic inputs
- Added “What to check next” recommendations driven by recent hive history
- Recommendations include queen follow-up, mite trend checks, health flags, queen-cell follow-up, strength drops, low stores, stale queen confirmation, and routine inspection timing
- Each recommendation can be added to the BeeKeep calendar with one tap
- Added inspection-to-inspection comparison for strength, mites, honey stores, brood, and queen-status changes
- Quick Inspect now surfaces the top suggested checks before the field form
- Added unit-style regression coverage for the smart recommendation engine

## Product boundary
This is a native Android-only package. No PC Test Lab or browser companion is included.

## Build

Run the host preflight first:

```text
./scripts/verify-build-host.sh
```

Then build a real debug APK:

```text
./scripts/build-debug.sh
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk` and is signature-verified when Android Build Tools are available.

Open this project root in Android Studio Rabbit 1 (2026.2.1) with JDK 17. Android Gradle Plugin 9.4.0 requires Gradle 9.6.0, JDK 17, and SDK Build Tools 36.0.0. The project now uses AGP built-in Kotlin and KSP for Room code generation.

For a debug APK, use **Build > Build APK(s)** or run `:app:assembleDebug`. The APK will be under `app/build/outputs/apk/debug/`. See `BUILD_ANDROID.md` and `scripts/doctor.ps1` for setup checks.


## v1.3.0 — Advanced Analytics
- Added unified Hive & Apiary analytics for strength, mites, total brood, honey stores, queen status, and harvest.
- Added metric and time-range switching with searchable Hive/Apiary scope.
- Apiary metrics equal-weight inspected hives within each time bucket.
- Harvest trends are normalized to kilograms and aggregated per producing hive.
- Added dedicated analytics smoke tests and preserved the existing Health Trends graph.

## v1.2.1 Deep Polish
- Health graph ranges now use local calendar days, avoiding daylight-saving-time drift.
- Apiary buckets align to the selected range start.
- Apiary averages equal-weight inspected hives within each bucket.
- Latest-average metric is constrained to the selected range.
- Health scope selection supports search for large apiaries.
- Chart rendering is clearer on small mobile screens and handles sparse data explicitly.
- Historical inspection scoring shares the same baseline weighting as Smart Inspection.


## v1.5.0
Native NFC tag management is now production-oriented: scan/open, assign, replace, verify, remove, and NDEF dispatch are built directly on Android NFC APIs.

## v1.5.2 — Deep NFC Polish
- Atomic one-shot NFC scan sessions prevent duplicate callbacks.
- Faster foreground reader setup by skipping the redundant platform NDEF check and platform reader sounds.
- NFC adapter lookup uses Android's `NfcManager` service.
- Launch-time NDEF handling avoids reconnecting to a tag that may already have left the field.
- Safe write guard blocks overwriting a BeeKeep tag belonging to another hive unless the user explicitly uses REPLACE.
- NDEF writes are read back and verified before BeeKeep reports success.
- UID formatting is normalized byte-by-byte.
- BeeKeep payload validation rejects malformed and non-positive hive IDs consistently.


## v1.6.0 Stability Focus
This baseline prioritizes data integrity, calendar save feedback, reminder de-duplication, notification settings, and cloud photo synchronization. The Android app remains native Kotlin; no PC test runtime is included.

## v1.7.0 — Build & Install Foundation
- Migrated Room from KAPT to KSP for compatibility with AGP 9 built-in Kotlin.
- Added a reproducible Android build checklist and environment doctor script.
- Added debug-install/update scripts for Windows.
- Added version 1.7.0 / versionCode 23 for the production-hardening phase.


## v1.7.0 — Build & install foundation
- Migrated Room from KAPT to KSP for AGP 9 built-in Kotlin compatibility.
- Added an actual Gradle wrapper JAR plus pinned Gradle 9.6.0 checksum verification.
- Added Windows build/diagnostic scripts and GitHub Actions debug-build validation.
- Added real-device acceptance checklist.
- Version 1.7.0 / versionCode 23.
