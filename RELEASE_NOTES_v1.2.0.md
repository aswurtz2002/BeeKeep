# BeeKeep Kotlin v1.2.0 — Health Analytics & Trends

## Added
- Interactive health trend graph in Insights.
- Scope selector for all apiaries, individual apiaries, and individual hives.
- 30-day, 90-day, 6-month, and 1-year graph ranges.
- Latest-known average health, range average, and first-to-latest trend delta.
- Compact 90-day health trend graph on each hive dashboard.

## Analytics behavior
- Historical health is calculated from inspection snapshots, not the hive's current edited state.
- Apiary trend points first average each hive within a period, then average those hive scores, preventing frequently inspected hives from dominating the yard average.
- Health analytics are descriptive heuristics, not diagnoses.
