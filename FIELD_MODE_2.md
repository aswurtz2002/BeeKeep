# Field Mode 2.0 — BeeKeep 1.4.0

Field Mode is optimized for phone-first, one-handed hive inspections. It uses a single `LazyColumn` inspection flow with a persistent bottom action bar, large tap targets, haptic ticks on quick controls, and context from the prior inspection.

## Workflow

1. Open a hive from the Hive list or NFC scan.
2. Tap **Inspect**.
3. The top panel surfaces the next incomplete Smart Inspection recommendation.
4. Queen, strength, mites, brood/stores, queen cells and health flags can be captured without leaving the screen.
5. Use the persistent **PHOTO / VOICE / GPS / SAVE** actions.
6. Save writes the inspection locally first; existing sync/reminder behavior remains unchanged.

## Mobile UX choices

- `LazyColumn` is used instead of composing a large vertically-scrolling `Column`, reducing unnecessary composition work for long inspection forms.
- Haptic `SegmentTick` feedback is used for discrete field controls.
- The previous inspection is shown inline for strength and mite comparison.
- Suggested checks can be marked done locally for the current inspection or converted into Calendar tasks.
- Photo processing remains off the UI thread.
- NFC, camera, GPS, voice, Room, notifications, and sync continue to use their existing native services.

Android documents `LazyColumn` as the appropriate lazy layout for longer scrolling content and recommends stable/lazy layouts to avoid composing everything up front. Compose also exposes `LocalHapticFeedback`/`HapticFeedbackType` for tactile feedback.
