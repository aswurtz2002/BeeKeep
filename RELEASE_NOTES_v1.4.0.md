# BeeKeep Kotlin v1.4.0 — Release Notes

## Field Mode 2.0

- Reworked inspection workflow into a phone-first single-screen field form.
- Added persistent bottom actions for photo, voice, GPS, and save.
- Added inline previous-inspection context for strength and mite results.
- Added a focused **What should I check?** assistant panel with selectable recommendations.
- Added one-tap completion and calendar-task actions for suggested checks.
- Added haptic ticks to high-frequency field controls.
- Replaced the large vertically-scrolled inspection `Column` with `LazyColumn` for better composition behavior on long forms.
- Preserved existing CameraX, Location, NFC, Room, notification, and cloud-sync paths.

## Compatibility

- Android-only native Kotlin build.
- No PC test companion is included.
- No changes to the Hive Stack/Equipment Builder because that feature was previously removed.
