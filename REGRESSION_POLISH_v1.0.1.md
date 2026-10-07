# BeeKeep v1.0.1 — Deep Regression Polish

## Reliability fixes
- Removed unconditional realtime websocket startup from app boot; realtime only starts for signed-in interactive clients.
- Background cloud sync workers disable realtime entirely, preventing a websocket from being created by WorkManager jobs.
- Added a sync mutex so manual sync, realtime-triggered sync, and background sync cannot run concurrently against the same local state.
- Changed sync ordering to pull remote state before uploading the offline outbox. Older offline entries are discarded when a newer cloud document already exists.
- Cloud uploads now use each record's `updated_at` timestamp from its payload instead of the outbox creation timestamp.
- Remote hive/apiary/task changes are applied only when the remote timestamp is newer than the local timestamp.
- Backfilled inspections no longer move a hive's editable `updated_at` timestamp backwards.
- Coalesced realtime change bursts with a 500 ms debounce to avoid sync storms.
- Realtime cancellation now propagates correctly instead of being swallowed by a broad `Throwable` catch.

## Calendar / reminder fixes
- Old alarms now self-suppress when a task's due time changes in either direction.
- Completed/disabled/expired tasks are actively cancelled even after an app restart when the in-memory reminder cache is empty.

## Camera / memory fixes
- Camera binding now reports startup failures to the UI instead of failing silently.
- Sampled inspection photo bitmaps are recycled when their Compose producer is disposed or replaced.

## NFC fixes
- NFC reader mode is disabled on the Android UI thread after a scan result.

## Validation
- Kotlin delimiter/structure scan: PASS
- Android XML parsing: PASS
- Smart Inspection regression smoke test: PASS
- Gradle version/config marker check: PASS
- No PC Test Lab artifacts in the Android package: PASS
- Final ZIP integrity: PASS

## Not validated here
- Full Android Gradle build / APK generation. The sandbox does not have the Android SDK/dependency cache needed to resolve and compile the complete native application.
