# BeeKeep real-device acceptance checklist — v1.7.0

Run this checklist on a real Android phone before calling a release production-ready.

## Install/update

- Install `app-debug.apk` on a clean Android device.
- Install the new APK over the previous BeeKeep build using `adb install -r`.
- Confirm existing hive, inspection, calendar, photo, NFC, and settings data remains intact.
- Confirm Room migrations complete without data loss.

## Field capture

- Start an inspection while offline.
- Save an inspection with no network connection.
- Take a photo and confirm it appears immediately.
- Capture GPS and confirm the inspection retains coordinates.
- Use voice dictation and confirm the resulting note is stored.

## NFC

- Scan a valid BeeKeep tag and confirm the correct hive opens.
- Tap the same tag repeatedly and confirm only one navigation occurs.
- Attempt to write over another hive's tag and confirm BeeKeep blocks it unless REPLACE is intentional.
- Write a new tag and confirm BeeKeep reads the payload back successfully.
- Move the phone away during a write and confirm failure is surfaced without corrupting the hive record.

## Calendar/reminders

- Create a reminder for a future time.
- Complete/reschedule the task and confirm the old alarm does not fire.
- Reboot the phone and confirm future alarms are restored.
- Disable notification permission and confirm BeeKeep remains usable without crashing.

## Sync

- Create/edit records while offline.
- Restore connectivity and sync.
- Confirm remote changes appear on a second device/account session.
- Confirm a newer record is not replaced by an older queued offline edit.
- Confirm failed photo upload does not block hive-data sync.

## Analytics

- Confirm hive health scores are unchanged by the v1.7 build-pipeline changes.
- Confirm the Health Graph and Advanced Analytics open with empty, sparse, and normal datasets.
- Confirm apiary averages treat each represented hive fairly.

## Themes/field mode

- Test yellow/white mode in sunlight.
- Test yellow/black mode in low light.
- Confirm touch targets remain comfortable with gloves.
- Rotate/resize the phone only if the current portrait-only product decision is intentionally preserved.
