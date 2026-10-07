# Deep Polish v1.5.2

Focused stabilization pass after NFC production implementation.

## Reliability fixes
- Prevent duplicate hive numbers within the same apiary label during hive creation.
- Normalize hive number, apiary name, queen fields, age, strength, mite rate, and tag UID on save.
- Preserve an existing apiary record when a duplicate name is submitted instead of letting Room REPLACE the existing row and potentially disturb hive foreign keys.
- Resolve hive `apiary_id` only when the referenced apiary actually exists when applying cloud data.
- Inspection saves are awaited by the UI; the inspection screen closes only after a successful local database write.
- Camera capture is locked while a photo is being written, preventing accidental double capture.
- NFC result delivery checks activity lifecycle before updating the UI.
- NFC writes to formattable tags are read back and verified before reporting success.
- Optimized photos use a backup/restore replacement path to reduce the chance of a partially replaced file after interruption.

## Verification
- Standalone Kotlin NFC payload smoke test: PASS.
- Kotlin source delimiter checks: PASS.
- Android XML parsing: PASS.
- No PC/test-lab source artifacts under `app/src/main`: PASS.
- Final archive integrity: PASS.
