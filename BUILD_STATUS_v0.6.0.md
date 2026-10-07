# BeeKeep Kotlin v0.6.0 — Build Status

## Removed from the Android app
- Hive Stack / Equipment Builder UI
- HiveComponent domain/entity/DAO/repository methods
- Component sync payload handling
- Component data during the Room 2 -> 3 migration

## Added in this batch
- Per-hive health dashboard
- Hive status indicator
- Editable queen profile
- Strength trend from recent inspections
- Mite-rate trend summary
- Recent inspection photo gallery
- Per-hive task preview
- Feeding/treatment/harvest record summary

## Verification
- Kotlin source brace/parenthesis structural check: pass
- Android XML parsing: pass
- No component/equipment production references outside historical Room migration code
- ZIP integrity: to be checked after packaging
- Full Android APK compilation: not available in this environment
