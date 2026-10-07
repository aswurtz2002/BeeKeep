# NFC physical test checklist

1. Install the debug build on a phone with NFC enabled.
2. Open BeeKeep → More → NFC tag management.
3. Choose an unassigned hive and tap ASSIGN.
4. Hold an NDEF-compatible tag against the phone until the read succeeds.
5. Confirm the UID appears beside the hive.
6. Tap VERIFY and confirm `Physical tag matches`.
7. Tap the hive's WRITE/REPLACE action and write the same tag.
8. Turn the screen off/unlock the phone and tap the tag; BeeKeep should open/reuse the app and identify the hive.
9. Test a different tag and confirm BeeKeep reports it as different instead of silently attaching it.
10. Test a read-only tag and confirm writing fails with a useful message.
