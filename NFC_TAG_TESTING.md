# NFC physical test checklist

## One-time setup for each tag

1. Install the debug build on a phone with NFC enabled.
2. Open BeeKeep → More → NFC tag management.
3. Choose an unassigned hive and tap ASSIGN, then tap the physical tag to save its UID.
4. Use that hive's WRITE/REPLACE action and tap the same tag again. This writes BeeKeep's own `application/vnd.beekeep.hive` NDEF record, which lets Android launch BeeKeep directly when the app is not already open.
5. Confirm the UID appears beside the hive, then tap VERIFY and confirm the tag matches.
6. If the tag previously contained a website link or other recognized NDEF data, WRITE/REPLACE replaces the old on-tag payload with BeeKeep's hive record. Android can route recognized NDEF content to a matching app before its technology fallback, so a UID assignment alone does not change what is stored on the tag.

## Behaviour checks

1. With BeeKeep open on Home, Apiaries, or a hive detail screen, tap an assigned tag. BeeKeep should stay in the foreground and open the assigned hive detail screen.
2. Repeat with BeeKeep open but no hive detail currently displayed.
3. Return to the home screen or close BeeKeep normally, then tap a tag written with BeeKeep's payload. Android should open BeeKeep and navigate directly to the assigned hive.
4. Scan a different/unassigned tag while BeeKeep is open. It should not switch to another app or show an assignment prompt unless you deliberately use the Scan workflow.
5. Test a read-only tag and confirm writing fails with a useful message.
6. Test with NFC disabled and confirm the manual Scan action reports that NFC must be enabled.
