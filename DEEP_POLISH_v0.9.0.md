# BeeKeep v0.9.0 — Deep Polish & Stabilization

This milestone is a stabilization release before adding new product features.

## Reliability fixes
- Native camera photo optimization moved off the UI thread and save is blocked until processing finishes.
- Camera cancellations/errors remove abandoned temporary files.
- Photo filenames include a UUID suffix to avoid same-millisecond collisions.
- Photo previews use sampled decoding on background dispatchers.
- EXIF orientation is corrected before compression/preview.
- Cached GPS positions are reused when recent and accurate enough; fresh high-accuracy location is only requested when needed.
- Exact alarm permission races fall back safely to inexact AlarmManager scheduling and then WorkManager if necessary.
- Scheduled reminder receivers re-check the current Room task so stale alarms do not notify after edits/completion.
- Reminder schedules are fingerprinted to avoid needless re-scheduling.
- Reminder alarms are restored after reboot and exact-alarm permission changes.
- NFC tag lookups are case-insensitive and non-unique tag assignments are prevented with a Room unique index plus conflict checks.
- Existing duplicate NFC tags are cleaned during migration by retaining the first assignment and clearing later duplicates.
- Task records now carry `updated_at` so calendar completion changes have a real sync timestamp.
- Local Android photo paths are never stored in shared cloud inspection payloads.
- Room migrations preserve existing data and include v4→v5/v5→v6 upgrade paths.
- Avoidable Kotlin force unwraps were reduced in field-critical paths.

## Performance
- Field inspection save remains Room-first and offline.
- Database work stays on background dispatchers.
- Photo work is sampled/compressed before upload.
- Cloud sync remains asynchronous and network-constrained.

## Validation completed
- XML parse validation: PASS
- Kotlin structural delimiter validation: PASS
- NFC migration smoke test: PASS
- IdGenerator 5,000-ID regression: PASS
- Build/dependency markers: PASS
- No PC Test Lab source artifacts: PASS
- ZIP integrity: PASS

## Known environment limitation
A signed APK was not produced in this environment because the Android SDK/Gradle dependency cache is unavailable locally and outbound dependency downloads are blocked. Android Studio can build the supplied project on a configured development machine.
