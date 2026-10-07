# BeeKeep v1.6.0 Stability Pass

Focused on protecting existing features before adding new ones.

- Room seed initialization only runs on a genuinely empty hive table.
- Scheduled-task creation now reports failures to the UI rather than silently closing.
- Automatically generated follow-up reminders are de-duplicated within a two-day window.
- Manual cloud sync now includes pending inspection-photo uploads.
- Photo-upload cancellation is propagated correctly.
- Notification controls open Android app notification settings when runtime permission is not the right path.
- Feeding, treatment, and harvest dialogs reject malformed numeric input rather than silently turning it into zero.
- PC test companion code remains absent from the Android source tree.
