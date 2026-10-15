# BeeKeep NFC Tag Management

## Identity model

BeeKeep identifies the physical tag by its NFC UID and resolves that UID against the local Room database's active assignment ledger. The NDEF payload is only a generic app-launch marker; it never decides which hive is opened.

Current tags contain two NDEF records:

- MIME record: `application/vnd.beekeep.tag` with payload `BEEKEEP:TAG`
- Android Application Record for `com.beekeep.app`

Older `application/vnd.beekeep.hive` / `BEEKEEP:HIVE:<hiveId>` records are recognized for migration only. The embedded legacy hive ID must never override the current UID-to-hive assignment. Hive history and inspection data stay in Room/cloud storage, not on the tag.

## Safety rules

- A valid UID is normalized before comparison. Formatting differences must not create a second identity.
- A UID with conflicting active or cached owners is considered ambiguous. BeeKeep must not open, verify, or assign it automatically; resolve the duplicate in NFC Tag Management first.
- Unknown or unassigned tags never inherit the identity of whichever hive happens to be open. The user must explicitly choose the intended hive.
- A tag already assigned to another hive cannot be overwritten during a normal assignment. Reassignment requires an explicit confirmation and is written to assignment history.
- Fast Tag Mode ignores the UID just assigned while it advances to the next untagged hive. A tag that is already assigned cannot silently replace a different hive's tag.
- The controller verifies the generic marker and the Android Application Record after writing. A successful NDEF write alone is not enough to claim a valid app-launch setup.
- Passive scans do not automatically erase a tag that contains unrelated NDEF data. Use an explicit WRITE TAG action if you intend to replace it.
- Cancelled scans must not deliver late callbacks into a later scan session.
- Removing a tag releases its assignment in BeeKeep; it does not remotely erase the physical tag. Reassignment should be confirmed before another hive takes ownership.

## Recommended tags and mounting

Use durable outdoor-rated NFC tags intended for the mounting surface. Ordinary NFC tags can behave poorly close to metal; use a suitable on-metal tag or a non-metal mounting location. Keep the tag in a consistent location on each hive, and label the hive number visibly as a human cross-check.

## Android behaviour

When BeeKeep is foregrounded, it uses Android's NFC reader mode. When a compatible marker is present and BeeKeep is not foregrounded, Android can route the NDEF discovery back to BeeKeep through its MIME-type intent filter and application record. Device-specific behaviour varies, so verify this on the target phones.

An NFC UID is an identifier, not cryptographic proof: some tag technologies can be cloned or emulated. Do not treat the UID as an authentication secret. BeeKeep protects against assignment conflicts and stale payloads, but it cannot prove that every physical UID is impossible to clone.
