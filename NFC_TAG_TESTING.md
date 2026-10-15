# BeeKeep NFC Physical and Regression Test Checklist

Run JVM unit tests/build first, then complete the physical tests with the actual supported tags and target Android phones. Test both with BeeKeep foregrounded and with the app backgrounded/closed where noted.

## Unit coverage

- [ ] UID case and separator normalization produces a consistent canonical UID.
- [ ] Empty, odd-length, and non-hex UIDs are rejected.
- [ ] A single UID owned by multiple hive IDs is marked ambiguous and removed from the automatic lookup map.
- [ ] A UID repeated in equivalent formats for the same hive remains a single identity.
- [ ] Legacy hive-specific NDEF payloads are recognized for migration only; they never override the database assignment.

## Scan and opening flows

1. [ ] From Home, scan a tag assigned to Hive A. Exactly Hive A opens; the correct hive number is announced, and the inspection screen is opened once.
2. [ ] Repeat from the Apiaries page, a hive detail page, and the explicit Scan & Open action. The same UID-to-hive result is used everywhere.
3. [ ] Keep an assigned tag against the phone. It must not repeatedly reopen the hive, replay voice prompts, or create duplicate inspection navigation.
4. [ ] Scan an unassigned tag while Home is visible and while another hive is already open. BeeKeep must show the explicit assign/create choice; it must not infer that the open hive owns the tag.
5. [ ] Scan a released tag. BeeKeep must not reopen its former hive from the legacy NDEF payload. It must offer a new explicit assignment.
6. [ ] If the UID is represented by records for two different hives, all automatic open/assign/verify flows must stop with a clear identity-conflict message. No hive should be chosen by query order.
7. [ ] Try tags containing unrelated NDEF data. Passive scan must leave their data intact and explain that an explicit write action is needed.
8. [ ] Launch BeeKeep from a prepared tag while the app is backgrounded/closed. Confirm that the physical UID is resolved against current local assignments, even if the old legacy payload points at a different hive.

## Assignment, replacement, write and verification

1. [ ] Assign a blank tag to an untagged hive. The app must write and verify the generic marker, save the physical UID assignment, and show success only after the database save succeeds.
2. [ ] Present a different tag after choosing a specific expected UID for a write. The operation must reject it without writing.
3. [ ] Present a tag already assigned to another hive. No NDEF write or assignment mutation may happen until the user explicitly confirms reassignment.
4. [ ] Confirm reassignment. The old hive must lose the active assignment, the new hive must own the UID exactly once, history/events must be retained, and the old hive's cached tag value must be cleared.
5. [ ] Verify the correct tag, a different tag, a tag with missing app-launch data, and a tag with a legacy payload. Only the correct UID + correct launch record should be reported fully verified.
6. [ ] Use a read-only or non-NDEF tag. BeeKeep must report the limitation and must not show a false success.
7. [ ] Cancel a read/write scan, immediately start another operation, and present a tag. The cancelled operation must not receive a late result or navigate to a second hive.
8. [ ] Remove a tag assignment, then scan the physical tag again. It must not reopen the released hive; it can be explicitly assigned to a different hive.
9. [ ] Permanently delete a hive or mark a colony dead. Confirm its active tag assignment is released without affecting unrelated assignments.
10. [ ] Test a tag whose UID exists in the assignment ledger but not the hive cache, and a legacy hive with only a cached UID. Both cases must resolve safely or fail closed, never pick an arbitrary hive.
11. [ ] In cloud sync, deliver an old hive document with a stale `tag_uid` after that UID has already been released/reassigned. The legacy cache field must not recreate or reclaim the assignment; the active assignment ledger must remain authoritative.
12. [ ] Create multiple active ledger rows for one UID and/or one hive, then sync another assignment. The resolver must close every conflicting row when the incoming assignment wins, or close the incoming row without partially changing incumbents when it loses.

## Fast Tag Mode

1. [ ] Start on an untagged hive, write a tag, and wait for the assignment/save confirmation before moving to the next hive.
2. [ ] Keep the previous tag near the phone when the next hive becomes ready. The old tag must be ignored and must not produce an early "already assigned" warning for the next hive.
3. [ ] Present the next physical tag. It must assign only to the displayed next hive; confirm the visible hive number and saved UID match.
4. [ ] Try an already assigned tag during Fast Tag Mode. It must not overwrite another assignment; the mode must provide a clear error/reassignment route.
5. [ ] Cancel, leave the hive screen, turn NFC off, or background the app mid-flow. The session must stop cleanly, with no unexpected assignment.
6. [ ] Test hives at the end of an apiary's order. Completion must stop scanning and clearly report that no eligible untagged hives remain.

## Device and environmental checks

- [ ] Phone with no NFC hardware: manual access still works; NFC actions state why they are unavailable.
- [ ] NFC disabled: actions report that NFC must be enabled without claiming a scan happened.
- [ ] Test the actual tag types, phone antenna locations, cold/wet gloved-hand handling, outdoor light, and the recommended tag mounting surface.
- [ ] Test on at least two Android phones when available.
- [ ] Check the app's diagnostic status and logs for NFC communication failures.
