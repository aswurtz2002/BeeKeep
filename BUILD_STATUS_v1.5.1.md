# BeeKeep Kotlin v1.5.1 build status

- Deep NFC reliability/performance polish completed.
- Atomic one-shot foreground NFC sessions added.
- Reader-mode flags optimized with `FLAG_READER_SKIP_NDEF_CHECK` and `FLAG_READER_NO_PLATFORM_SOUNDS`.
- NFC launch-intent handling no longer reconnects during activity startup when no NDEF payload is supplied.
- Cross-hive NFC overwrite protection added, with explicit REPLACE support.
- NDEF write read-back verification added.
- Payload validation tightened and UID byte formatting normalized.
- Pure Kotlin NFC payload smoke tests: PASS.
- All Kotlin source delimiter checks: PASS.
- Android XML parse checks: PASS.
- PC companion scan under app source: NONE.
- Final ZIP integrity: PASS.
- Full Android APK compilation remains unavailable in this sandbox because Android SDK/Gradle dependency downloads are unavailable.
