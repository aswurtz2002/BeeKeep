# BeeKeep Kotlin v1.6.0 — Stability Release

This release pauses new feature work and hardens the current BeeKeep feature set before the next product milestone.

## Reliability and data integrity
- Room demo seeding now runs only on a genuinely empty hive table.
- Scheduled task creation reports persistence errors to the calendar UI instead of closing silently.
- Automatically generated follow-up reminders are de-duplicated within a two-day window.
- Feeding, treatment, and harvest dialogs reject malformed numeric input instead of silently converting invalid text to zero.
- Existing local save transactions remain atomic for hive, inspection, feeding, treatment, harvest, apiary, and task writes.

## Sync
- Manual cloud sync now includes pending inspection-photo uploads.
- Photo-upload cancellation is propagated instead of being swallowed as a generic failure.
- Background sync no longer performs a second, duplicate photo pass after the main sync.
- Offline-first behavior remains unchanged: local data is saved without requiring cloud access.

## Notifications
- Notification management can open the Android app notification settings when a runtime permission request is not the appropriate action.

## Compatibility
- Android-only native Kotlin app; no PC test runtime or companion code is included in the mobile source tree.
- No new Room schema version is required for this stabilization release.
