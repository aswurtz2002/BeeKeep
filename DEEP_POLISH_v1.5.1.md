# BeeKeep v1.5.1 — Deep NFC Polish

- Replaced adapter lookup with `NfcManager.defaultAdapter`.
- Made NFC session state atomic and one-shot to prevent duplicate callbacks.
- Added `FLAG_READER_SKIP_NDEF_CHECK` and `FLAG_READER_NO_PLATFORM_SOUNDS` to reduce redundant work and unwanted platform sounds during foreground scans.
- Avoided reconnecting to NFC tags during NDEF launch-intent handling when the NDEF payload was not included.
- Normalized UID formatting byte-by-byte to avoid signed-byte representation issues.
- Added safe overwrite protection: an unassigned hive cannot overwrite a BeeKeep tag already carrying another hive ID.
- Added read-back verification after NDEF writes.
- Tightened BeeKeep payload validation so malformed/zero hive IDs are rejected consistently.
- Kept explicit REPLACE behavior for a hive that already owns a tag.
- Added unit coverage for malformed payloads and whitespace handling.

These changes preserve the mobile-only Android/Kotlin architecture and do not add any desktop test application.
