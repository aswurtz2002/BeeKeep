# BeeKeep Kotlin v1.0.0

## Smart Inspection Assistant
This milestone turns BeeKeep’s inspection history into practical, explainable field suggestions.

### New
- Hive health score (0–100) based on recent strength, queen status, mite rate, inspection freshness, health flags, stores, and direction of strength trend.
- “What to check next” card with up to four prioritized recommendations.
- One-tap conversion of a recommendation into a calendar task.
- Inspection comparison showing changes since the previous inspection.
- Suggested-checks panel at the start of an inspection.

### Safety / behavior
- Suggestions are heuristic workflow aids, not diagnoses.
- BeeKeep does not automatically start treatments or change hive records from a recommendation.
- Existing calendar and offline-first behavior remains unchanged.

### Verification
- Smart inspection engine compiled in isolation with representative domain stubs.
- Recommendation, comparison, and health-score smoke checks passed.
- Android Kotlin source balanced and project archive integrity verified.
