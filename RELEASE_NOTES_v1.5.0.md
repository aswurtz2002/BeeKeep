# BeeKeep Kotlin v1.5.0

## Native NFC production + tag management

- Direct Android `NfcAdapter.enableReaderMode()` with NFC-A/B/F/V polling
- Custom BeeKeep NDEF MIME payload (`application/vnd.beekeep.hive`)
- Stable payload format `BEEKEEP:HIVE:<hiveId>`
- Rich NFC tag metadata: UID, technologies, NDEF type, capacity, writable state, stored payload
- Safer read/write error handling, including tag-loss feedback
- NDEF formatting support for compatible unformatted tags
- Scan & Open Hive flow
- Dedicated NFC Tag Management screen
- Assign, replace, verify, and remove hive tags
- NFC changes recorded in the hive timeline
- NDEF tag-dispatch support with singleTop activity reuse
- Android-only package; no PC companion/runtime included
