# BeeKeep — Hive Lifecycle & Reusable NFC Implementation Plan

## Goal

Change BeeKeep's hive lifecycle so that:

1. A colony can be marked **Dead** without destroying its history.
2. All historical inspections, treatments, feedings, harvests, photos, etc. remain attached to the dead colony.
3. The NFC tag is treated as a **reusable physical asset**, not as the permanent identity of a colony.
4. When a colony dies, its NFC tag becomes available for reuse.
5. The same NFC tag can subsequently be assigned to a completely different colony.
6. Scanning a reused NFC tag opens the **current colony**, not the previous dead colony.
7. Explicitly deleting a hive remains a separate operation from marking a colony dead.

---

# 1. Core Data Model

## Hive / Colony

A hive record represents a specific colony/history.

It must have a permanent database ID that is independent of NFC.

Example:

```text
Hive ID: 47
Hive Number: 12
Status: Dead
NFC Tag: none
```

The Hive ID must never be reused for another colony.

Historical records continue referencing:

```text
hive_id = 47
```

even after the colony dies.

### Hive status

Add an explicit lifecycle status:

```text
ACTIVE
DEAD
SOLD
REMOVED
```

Initially, `ACTIVE` and `DEAD` are sufficient.

The important distinction is:

```text
DEAD ≠ DELETED
```

A dead colony still exists as a historical record.

---

# 2. NFC Tag Model

NFC tags should be treated as reusable physical objects.

The tag's UID is **not** the hive's primary ID.

Recommended model:

```text
NfcTag
------
uid
hive_id
assigned_at
```

Where:

```text
hive_id = NULL
```

means the physical tag currently isn't assigned to a colony.

An even better long-term model is to maintain assignment history:

```text
NfcTagAssignment
----------------
id
tag_uid
hive_id
assigned_at
unassigned_at
```

This allows BeeKeep to know:

```text
NFC A123
    ↓
Hive 47
2026-05-01 → 2026-09-14

NFC A123
    ↓
Hive 83
2027-05-03 → present
```

This is preferable if BeeKeep eventually needs a complete audit trail.

---

# 3. Important Identity Rule

Never use:

```text
NFC UID = Hive ID
```

Instead:

```text
Hive ID = permanent identity of the colony record

NFC UID = reusable physical identifier
```

This prevents a reused NFC tag from accidentally connecting a new colony to the old colony's history.

---

# 4. Mark Colony Dead

Add a **Mark Colony Dead** action to the hive.

When confirmed:

```text
Hive.status = DEAD
Hive.dead_at = current timestamp
Hive NFC assignment = NULL
```

The hive's historical records remain untouched.

Do NOT delete:

- inspections
- treatments
- feedings
- harvests
- tasks/events
- photos
- notes
- historical measurements
- other records linked to the hive

The dead hive should remain accessible from historical/archived views.

---

# 5. Release the NFC Tag

When a colony is marked dead, the NFC tag must automatically become available.

Example:

Before:

```text
NFC A123 → Hive 47
Hive 47 → ACTIVE
```

After:

```text
NFC A123 → UNASSIGNED
Hive 47 → DEAD
```

The tag is now available to assign to another hive.

---

# 6. Assign NFC to New Colony

When creating a new hive, the user can assign an available NFC tag.

Example:

```text
Create Hive 83

Hive ID: 83
Hive Number: 14
NFC: A123
```

BeeKeep creates:

```text
NFC A123 → Hive 83
```

The old Hive 47 remains completely separate.

---

# 7. NFC Scan Behavior

Scanning an NFC tag should resolve through the **current assignment**.

Example:

```text
Scan A123
      ↓
Find current NFC assignment
      ↓
Hive 83
      ↓
Open Hive 83
```

It must NOT search historical assignments and accidentally open Hive 47.

If the tag has no current assignment:

```text
NFC A123

Tag is not assigned to a colony.

[Assign to Existing Hive]
[Create New Hive]
```

This is an important UX state.

---

# 8. Prevent Duplicate NFC Assignments

Only one active hive can own an NFC tag at a time.

Database-level protection should enforce:

```text
tag_uid UNIQUE WHERE assignment is active
```

or equivalent logic.

The app must not allow:

```text
Hive 83 → A123
Hive 91 → A123
```

simultaneously.

If a user attempts this, BeeKeep should clearly explain:

> This NFC tag is already assigned to Hive 83.

Then provide an option to reassign it if appropriate.

---

# 9. Reassignment Flow

Provide an explicit reassignment operation.

Example:

```text
Hive 83
NFC: A123

[Reassign NFC]
```

Confirmation:

> This will remove NFC A123 from Hive 83 and make it available for another colony.

Then:

```text
Hive 83 → NFC removed
A123 → available
```

The NFC can then be assigned elsewhere.

Do not silently steal an NFC tag from another hive.

---

# 10. Dead Hive UI

Dead hives should not appear in the normal active-hive list.

Default:

```text
My Hives

Hive 12
Hive 14
Hive 18
Hive 22
```

Dead colonies appear under:

```text
Archive / Colony History
```

Example:

```text
Dead Colonies

Hive 12
Dead: September 14, 2026

Hive 7
Dead: August 2, 2026
```

Opening a dead hive should still show its complete historical information.

---

# 11. Hive Number vs Hive ID

Keep these separate.

Example:

```text
Database ID: 47
Hive Number: 12
```

The database ID should never change.

The displayed hive number may be changed by the beekeeper.

This is especially important when physical equipment is reused.

---

# 12. Explicit Hive Deletion

Keep **Delete Hive** separate from **Mark Colony Dead**.

### Mark Colony Dead

Preserves:

```text
History
Inspections
Treatments
Photos
Harvests
Notes
```

and releases the NFC tag.

### Delete Hive

Means:

> Permanently remove this hive and its associated records.

This should be an intentionally destructive operation with confirmation.

Recommended confirmation:

> Delete Hive 12 permanently?
>
> This will remove the hive and all of its associated history. This cannot be undone.

For normal beekeeping workflow, **Mark Dead** should be the preferred operation.

---

# 13. Existing BeeKeep Sync Architecture

BeeKeep already has an important piece of infrastructure for this.

The current sync system supports a `"delete"` operation and cloud documents have a `deleted` field.

The implementation should extend the existing architecture rather than creating a second sync mechanism.

Desired flow:

```text
UI
 ↓
ViewModel
 ↓
LocalRepository
 ↓
Room database
 ↓
Sync Outbox
 ↓
Supabase
 ↓
Other devices
```

NFC assignment changes must also be synchronized.

---

# 14. Sync Requirements

All lifecycle changes must work offline.

Example:

```text
Phone A is offline

Mark Hive 47 dead
Release NFC A123
Create Hive 83
Assign NFC A123 to Hive 83
```

The app should continue working normally.

When connectivity returns:

```text
Local changes
    ↓
Outbox
    ↓
Supabase
    ↓
Other devices
```

Other devices must eventually see:

```text
Hive 47 = DEAD
A123 = assigned to Hive 83
Hive 83 = ACTIVE
```

---

# 15. Avoid Hive Resurrection

This is especially important with the existing BeeKeep implementation.

The current `saveHive()` path sets:

```kotlin
deleted = false
```

on normal saves.

That behavior must be reviewed.

A dead hive must not accidentally become active because an old copy of the hive is saved or synchronized.

Likewise, an old offline device must not resurrect a dead hive simply because it still has an older copy.

Use timestamps/versioning consistently when resolving conflicts.

---

# 16. NFC Assignment Conflict Handling

Consider this scenario:

### Device A

```text
A123 → Hive 47
```

### Device B

```text
A123 → Hive 83
```

Both devices are offline.

When they synchronize, BeeKeep needs deterministic conflict handling.

Recommended rule:

```text
Newest valid assignment wins
```

with the previous assignment automatically closed.

However, NFC reassignment should ideally generate a specific assignment event rather than simply overwriting a string on the hive.

---

# 17. Database Migration

Implement a Room database migration rather than destroying existing data.

Migration should:

1. Preserve existing hive IDs.
2. Preserve all historical records.
3. Preserve existing NFC UIDs.
4. Create the new lifecycle/status fields.
5. Create the NFC assignment structure if required.
6. Convert existing NFC relationships into the new model.
7. Ensure there are no duplicate active NFC assignments.

Existing users must not lose data during upgrade.

---

# 18. Recommended Database Structure

Long-term model:

```text
hives
----------------
id
number
apiary_id
status
created_at
dead_at
...
```

```text
nfc_tags
----------------
uid
created_at
```

```text
nfc_tag_assignments
----------------
id
tag_uid
hive_id
assigned_at
unassigned_at
```

Historical records continue referencing:

```text
hive_id
```

They do NOT reference the NFC UID.

---

# 19. API / Cloud Model

The cloud representation should follow the same separation.

Do not make the cloud document's:

```text
entity_id
```

equal to the NFC UID.

Instead:

```text
entity_type = "hive"
entity_id = hive.id
```

and separately synchronize NFC assignment data.

This keeps the cloud identity stable even when physical tags move between colonies.

---

# 20. Implementation Order

Implement in this order to minimize risk.

### Phase 1 — Database

- Add hive lifecycle status.
- Add dead timestamp.
- Design NFC tag/assignment tables.
- Create Room migration.
- Add DAO methods.
- Add database constraints.

### Phase 2 — Repository

Add explicit operations:

```text
markHiveDead()
restoreHive()
deleteHive()
assignNfcTag()
unassignNfcTag()
reassignNfcTag()
findHiveByNfc()
```

Do not overload `saveHive()` to perform lifecycle operations.

### Phase 3 — Sync

Add synchronization for:

```text
Hive status
dead_at
NFC assignments
NFC unassignments
NFC reassignment
```

Implement conflict handling.

### Phase 4 — ViewModel

Add:

```text
markHiveDead()
deleteHive()
assignNfc()
unassignNfc()
reassignNfc()
```

Expose active/dead hive states to the UI.

### Phase 5 — UI

Add:

```text
Mark Colony Dead
```

and:

```text
Delete Hive
```

as separate actions.

Add an archived/dead-colonies view.

Add NFC assignment/reassignment UI.

### Phase 6 — NFC Scanning

Change NFC lookup to:

```text
NFC UID
 ↓
Current assignment
 ↓
Hive ID
 ↓
Hive
```

Handle unassigned tags gracefully.

### Phase 7 — Testing

Test all lifecycle combinations.

---

# 21. Required Test Cases

## Normal lifecycle

```text
Create Hive
Assign NFC
Scan NFC
Open Hive
```

Expected: correct hive opens.

## Colony dies

```text
Hive 47
NFC A123

Mark Dead
```

Expected:

```text
Hive 47 = DEAD
NFC A123 = available
History = preserved
```

## Reuse NFC

```text
Create Hive 83
Assign A123
Scan A123
```

Expected:

```text
Hive 83 opens
```

Hive 47 must not open.

## Dead hive history

Open Hive 47 after death.

Expected:

```text
All previous inspections/history remain visible.
```

## Delete hive

Delete Hive 47.

Expected:

```text
Hive 47 and configured dependent records are removed.
NFC A123 remains available.
```

## Offline death

Mark colony dead while offline.

Restart app.

Expected:

```text
Hive remains dead.
NFC remains unassigned.
```

## Offline reuse

Assign the released NFC to a new hive while offline.

Expected:

```text
No duplicate NFC assignment.
```

## Cross-device

Device A:

```text
Hive 47 → DEAD
A123 → available
```

Device B:

```text
Eventually receives same state.
```

## Cross-device NFC reuse

Device A:

```text
A123 → Hive 83
```

Device B:

```text
Scan A123
```

Expected:

```text
Hive 83 opens.
```

## Resurrection test

Device B has an old copy of Hive 47.

Device A marks Hive 47 dead.

Device B edits/saves its stale copy.

Expected:

```text
Hive 47 does NOT become active again.
```

---

# 22. UX Terminology

Use terminology that clearly distinguishes the concepts.

Recommended:

**Mark Colony Dead**

Not:

- Delete Colony
- Remove Hive
- Delete Hive

For NFC:

**Assign NFC Tag**

**Remove NFC Tag**

**Reassign NFC Tag**

For permanent deletion:

**Delete Hive Permanently**

This makes the consequences obvious to the beekeeper.

---

# 23. Final Intended Architecture

The final relationship should be:

```text
                 ┌─────────────────────┐
                 │      NFC Tag        │
                 │      A123           │
                 └──────────┬──────────┘
                            │
                     current assignment
                            │
                            ▼
                 ┌─────────────────────┐
                 │       Hive 83       │
                 │      ACTIVE         │
                 └──────────┬──────────┘
                            │
                     historical data
                            │
          ┌─────────────────┼─────────────────┐
          ▼                 ▼                 ▼
     Inspections       Treatments         Harvests
```

Meanwhile:

```text
                 ┌─────────────────────┐
                 │       Hive 47       │
                 │        DEAD         │
                 └──────────┬──────────┘
                            │
                     historical data
                            │
          ┌─────────────────┼─────────────────┐
          ▼                 ▼                 ▼
     Inspections       Treatments         Harvests
```

Both colonies have completely independent histories.

The physical NFC tag can move between them without moving any historical data.

---

# Definition of Done

The implementation is complete when:

- [ ] A colony can be marked dead.
- [ ] Dead colonies retain all history.
- [ ] Dead colonies disappear from the active-hive list.
- [ ] Dead colonies remain accessible in history/archive.
- [ ] Marking a colony dead releases its NFC tag.
- [ ] An NFC tag can be assigned to another colony.
- [ ] Scanning a reused NFC tag opens only its current colony.
- [ ] An NFC tag cannot be simultaneously assigned to two active colonies.
- [ ] NFC assignments synchronize between devices.
- [ ] Hive lifecycle changes synchronize between devices.
- [ ] Offline changes work correctly.
- [ ] Old offline data cannot resurrect dead colonies.
- [ ] Deleting a hive is separate from marking it dead.
- [ ] Permanent hive deletion does not accidentally delete the physical NFC asset.
- [ ] Existing BeeKeep data survives the database migration.
- [ ] Automated tests cover death, deletion, NFC reuse, offline operation, and cross-device synchronization.