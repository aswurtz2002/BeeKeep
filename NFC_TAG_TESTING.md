# NFC physical test checklist

## Set up a tag

1. Install the latest debug build on an NFC-capable phone and enable NFC.
2. Open BeeKeep → More → NFC tag management.
3. Choose a hive and tap ASSIGN, then hold the physical tag to save its UID.
4. When BeeKeep asks, tap the same tag again to write its launch payload. Wait for the write confirmation.
5. Alternatively, from a hive detail page, use WRITE TAG; this writes the payload and assigns the UID after the write succeeds.
6. Tap VERIFY. A correctly prepared tag should show that its UID matches and it contains BeeKeep launch data.
7. Existing tags assigned by UID alone need a one-time payload write. A UID is only stored in BeeKeep and is not enough for Android to know which app to open when BeeKeep is closed.

## Behaviour checks

1. With BeeKeep open on Home, Apiaries, or a hive detail screen, tap an assigned and written tag. BeeKeep should stay in the foreground and open the assigned hive.
2. Close or background BeeKeep, then tap the same written tag. Android should launch BeeKeep and open that hive without offering the generic system tag scanner.
3. Tap an unprogrammed/empty tag while BeeKeep is closed. It should not launch an app chooser; open BeeKeep and use ASSIGN/WRITE TAG to prepare it first.
4. Test a read-only tag and confirm BeeKeep shows a clear write failure.
