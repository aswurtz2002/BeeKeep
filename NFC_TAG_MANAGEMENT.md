# BeeKeep NFC Tag Management — v1.5.0

## Production flow

BeeKeep uses the Android NFC reader/writer APIs directly. The app reads the physical tag UID and stores that UID on the hive record. The tag also receives a compact BeeKeep NDEF record using the custom MIME type `application/vnd.beekeep.hive` and payload `BEEKEEP:HIVE:<hiveId>`.

The tag does **not** contain the hive history, inspection notes, photos, or health information. Those remain in Room/cloud storage.

### Supported field actions

- Scan & Open Hive
- Assign an unassigned tag
- Replace an existing tag
- Verify that the physical UID and BeeKeep payload match the hive
- Remove a tag assignment from BeeKeep
- Write/format compatible NDEF tags

### Recommended tag type

Use durable, outdoor-rated NFC tags intended for the mounting surface. For the first production version, NDEF-compatible tags are preferred because Android provides strong framework support for NDEF.

### Android behavior

When BeeKeep is already open, the Scan screen uses `NfcAdapter.enableReaderMode()` with NFC-A/B/F/V polling. When a BeeKeep NDEF tag is scanned while the app is not in the foreground, Android's NDEF tag-dispatch system can launch/reuse BeeKeep because the activity filters for the BeeKeep MIME type.

Do not write an `http://` or `https://` URL as the BeeKeep tag payload. The app uses a custom MIME record so tag identification remains app-specific.
