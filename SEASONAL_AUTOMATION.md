# BeeKeep v1.1.0 — Smart Seasonal Scheduling

This milestone adds a mobile-first seasonal planning layer and simple recurring tasks.

## Seasonal plan
The Calendar tab now offers **Build seasonal plan**. The beekeeper chooses editable planning templates and a 90-day, 6-month, or 1-year horizon. Templates are intentionally generic starting points; BeeKeep does not prescribe treatments or silently alter hive records.

## Included templates
- Spring colony inspections — every 14 days in March–May
- Swarm-watch inspections — every 7 days in May–June
- Flow & stores checks — every 14 days in June–August
- Mite checks — every 14 days in July–September
- Winter-prep checks — every 14 days in September–October
- Winter checks — every 30 days in November–February

## Recurring tasks
Manual calendar tasks can be set to repeat every 7, 14, or 30 days. Completing one occurrence creates the next occurrence and preserves the reminder setting.

## Duplicate protection
Seasonal tasks use a deterministic rule/date fingerprint in the existing task `kind` field. Rebuilding the same plan does not duplicate existing occurrences.
