# BeeKeep Kotlin v0.9.0

Deep polish / reliability release.

## Reliability
- Local IDs are generated in a monotonic, collision-resistant sequence.
- Stale reminder alarms are ignored after a task is edited, completed, or disabled.
- Exact-alarm permission changes trigger reminder rescheduling.
- Realtime cloud sync retries with bounded backoff after disconnects.
- Legacy database import is guarded so it runs once.

## Field performance
- Photo optimization is off the main thread.
- Gallery and inspection previews decode sampled bitmaps instead of full-resolution images.
- GPS checks a recent, accurate cached location before requesting a fresh fix.
- NFC reports when the radio is unavailable/off and supports compatible blank NDEF tags.

## UX polish
- Android system back now behaves naturally on full-screen flows.
- Status/navigation bar contrast follows BeeKeep's yellow/black mode.
- Calendar reminder controls expose precise-alarm access when Android requires it.
- Camera permission denial cleans up the unused capture file.

No PC Test Lab is included in this Android package.


### Final deep-stability fixes
- Unique NFC tag index with migration-time duplicate cleanup; duplicate tag assignment is rejected safely.
- Task records now carry an `updated_at` timestamp so calendar edits/completions have a durable sync version.
- Photo filenames use a UUID suffix to prevent same-millisecond collisions.
- Inspection save is disabled while a captured photo is being optimized, preventing a race between compression and upload.
- Exact-alarm scheduling has a SecurityException fallback to inexact alarms/WorkManager.
- Device-private photo paths are never synchronized to other phones.
