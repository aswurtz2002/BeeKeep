# BeeKeep Step 6 — End-to-end validation

This plan validates the full data-integrity path after sub-stages 6A–6D. The SQL and Android unit tests run in GitHub Actions; the two-device scenarios below require real Android devices and are intentionally left as manual checks.

## Safety and test setup

- Use two Android phones with the same BeeKeep debug build. Do not use production apiaries or real hive records.
- Back up any existing test data first. The deletion scenarios are permanent.
- Prepare three test accounts:
  - **Account A** — workspace owner.
  - **Account B** — invited workspace member (also test it as a viewer later).
  - **Account C** — not a member of Account A's workspace.
- Create uniquely named disposable data:
  - Account A personal: apiary `S6-PERSONAL`, hive `S6-P-01`.
  - Shared team: apiary `S6-TEAM`, hive `S6-T-01`.
  - Use clearly fake inspection notes such as `S6 device A` and `S6 device B`.
- Record the app version, Git commit, device labels, workspace name, and approximate test time. Do not put real hive details, account credentials, or full NFC UIDs into test notes.

## Device and account matrix

| Phone | Initial account | Workspace access | Purpose |
|---|---|---|---|
| Device A | Account A | Owner | Create, edit, delete, and administer team data |
| Device B | Account A | Same account on a second phone | Offline/reconnect and replica convergence |
| Device B (later) | Account B | Member, then viewer | Role and shared-data behavior |
| Device B (later) | Account C | No membership | Cross-account isolation |

## Manual scenarios

### 1. Personal and team data stay separate

- [ ] Sign in as Account A on both phones and sync.
- [ ] On Device A, create the personal apiary/hive and the team apiary/hive listed above.
- [ ] Confirm the personal data is visible in Account A's personal workspace and is not present in the team workspace unless explicitly copied.
- [ ] Activate the team on Device A, then activate the same team on Device B.
- [ ] Edit the team hive on Device A and sync. Confirm Device B receives the edit.
- [ ] Switch Device B to personal data. Confirm it shows the personal hive, not the team hive.
- [ ] Switch Device B back to the team. Confirm the team hive and its records are still present.

**Pass:** personal and team datasets remain isolated, and both same-account devices converge on the correct team records.

### 2. Offline edits and conflict convergence

Use the same existing team hive on both devices. Avoid editing it at the exact same instant; the timestamp policy needs an unambiguous ordering for this manual check.

- [ ] Sync both devices, then put Device B in airplane mode.
- [ ] On Device B, save an inspection note containing `S6 device B offline`.
- [ ] Keep Device B offline. On Device A, save a later edit to the same inspection record containing `S6 device A newer`, then sync Device A.
- [ ] Reconnect Device B and run Sync Now.
- [ ] Run Sync Now on both phones and reopen the record.

**Pass:** the cloud-winning newer edit appears on both devices; no duplicate record is created, and unrelated hive fields remain unchanged.

Repeat once with the order reversed (Device A edits first, Device B makes the later edit while offline) to validate the opposite direction.

### 3. Permanent delete while another device is offline

- [ ] Sync the team hive and its inspection history to both devices.
- [ ] Put Device B in airplane mode and leave its copy open.
- [ ] On Device A, permanently delete the disposable test hive and sync.
- [ ] Reconnect Device B and run Sync Now.
- [ ] Reopen the apiary and inspect the hive list, inspection history, feeding, treatment, harvest, event history, NFC assignment list, and unfinished inspection drafts.

**Pass:** the deleted hive and its associated history/drafts disappear from Device B and do not reappear on the next sync. The other test hive and apiary remain intact.

Do not change the phones' system clocks for this scenario. The database test `supabase/tests/delete_timestamp_reconciliation.sql` separately verifies deletion when a device timestamp is behind the cloud row.

### 4. Inspection draft cleanup

- [ ] On Device A, create an unfinished inspection form draft and an inspection note draft for a disposable test hive.
- [ ] Sync Device A and confirm both drafts appear on Device B.
- [ ] Permanently delete that hive on Device B and sync.
- [ ] Reopen Device A and sync.

**Pass:** Device A removes the deleted hive's pending note/form drafts and bee-count draft markers. A later, valid inspection draft on a different hive still saves and syncs normally.

### 5. NFC assignment release and stale cached replica

Use a disposable NFC tag; keep the full UID out of screenshots/logs.

- [ ] Create two disposable hives, `S6-NFC-OLD` and `S6-NFC-NEW`, and assign the tag to `S6-NFC-OLD` on Device A.
- [ ] Sync Device A and confirm Device B shows the same tag owner.
- [ ] Put Device B in airplane mode while it still has the old active assignment cached.
- [ ] On Device A, unassign the tag from `S6-NFC-OLD`, sync, then assign it to `S6-NFC-NEW` and sync again.
- [ ] Reconnect Device B and run Sync Now.
- [ ] Scan the tag on both devices and inspect the tag owner and both hive profiles.

**Pass:** the tag routes only to `S6-NFC-NEW`; the old hive remains untagged; no hive number, apiary, queen data, strength, inspection history, or other profile field changes.

The same-assignment-ID stale replay and ID-collision cases are covered by `NfcAssignmentResolverTest.kt`; do not attempt to manufacture corrupt IDs in normal field use.

### 6. Account switching on one phone

- [ ] On Device B, sign in as Account A and activate the shared team.
- [ ] Sign out, then sign in as Account C (the non-member).
- [ ] Wait for account refresh to complete. Open the workspace manager and personal apiaries/hives.
- [ ] Run Sync Now. Confirm Account C cannot see or activate Account A's team.
- [ ] Sign out and sign back in as Account A. Select the team again and sync.
- [ ] Confirm the original team data is still present.

**Pass:** switching accounts returns to the new account's personal data; the previous account's team is not shown as active or exposed by the workspace manager. Account A can regain team access after its membership is verified.

### 7. Roles and membership changes

- [ ] As Account A, change Account B to viewer.
- [ ] On Device B signed in as Account B, sync and confirm the team is view-only.
- [ ] Attempt a team hive edit and a new team record. Confirm the app blocks writes and the cloud record remains unchanged.
- [ ] As Account A, remove Account B from the workspace while Device B is online.
- [ ] Sync Device B. Confirm it falls back to personal data and cannot read or write the team.

**Pass:** viewer and removed-member permissions match the server role. No team write is uploaded by a viewer or non-member.

A device that is completely offline cannot learn that membership was revoked until it reconnects; the server enforces access as soon as requests resume.

### 8. Restart and background-sync recovery

- [ ] With Account A's team selected, close and reopen BeeKeep on both devices.
- [ ] On Device B, leave Wi-Fi disabled for startup, then re-enable it.
- [ ] Let WorkManager/foreground sync complete, then run Sync Now once.
- [ ] Repeat after switching Device B to personal data.

**Pass:** the same account's cached team can be restored offline when its saved account binding matches. After reconnect, membership/role is refreshed. Personal and team outboxes/cursors do not cross.

## Automated checks already in the repository

| Coverage | Automated source |
|---|---|
| Personal/team RLS, owner/member/viewer/non-member permissions, copy isolation | `supabase/tests/shared_workspace_document_security.sql` |
| Personal sync privacy, workspace membership isolation, cursor paging and tombstones | `supabase/tests/incremental_cloud_sync.sql` |
| Older cloud writes, permanent tombstones and reusable inspection drafts | `supabase/tests/document_conflict_guards.sql` |
| Explicit delete under client clock skew | `supabase/tests/delete_timestamp_reconciliation.sql` |
| NFC ledger ownership, stale same-ID replay, release monotonicity and corrupt-ID fail-closed behavior | `app/src/test/java/com/beekeep/app/data/NfcAssignmentResolverTest.kt` |
| Conflict timestamp selection | `app/src/test/java/com/beekeep/app/cloud/CloudConflictPolicyTest.kt` |
| Account-bound team cache restoration predicate | `app/src/test/java/com/beekeep/app/cloud/ActiveWorkspaceSessionPolicyTest.kt` |
| Local inspection draft cleanup keys | `app/src/test/java/com/beekeep/app/data/InspectionNoteDraftTest.kt` and `InspectionFormDraftStoreTest.kt` |

GitHub Actions runs the Android unit tests/lint and debug APK build, plus the disposable PostgreSQL migration/security suite when SQL tests or migrations change.

## Results

Fill this in after the real-device run:

| Scenario | Result | Notes / issue reference |
|---|---|---|
| 1. Personal/team separation | Not run | |
| 2. Offline conflict convergence | Not run | |
| 3. Delete while other device offline | Not run | |
| 4. Inspection draft cleanup | Not run | |
| 5. NFC release and stale cached replica | Not run | |
| 6. Account switching | Not run | |
| 7. Viewer/removal permissions | Not run | |
| 8. Restart/background sync | Not run | |

**Do not mark Step 6 fully field-validated until scenarios 1–8 pass on the two real devices.**
