# BeeKeep v1.2.1 — Deep Analytics Polish

## Regression and polish focus
- Align historical health scoring with the shared BeeKeep baseline used by Smart Inspection.
- Fix date-range math to use local calendar days, avoiding daylight-saving-time drift.
- Align graph buckets to the selected range instead of Unix-epoch bucket boundaries.
- Make the apiary average explicitly equal-weight by hive inside each bucket.
- Report graph coverage as inspected hives / scoped hives.
- Make “Latest avg” mean the latest inspection **inside the selected range**.
- Keep the legacy `currentAverage()` behavior unchanged for other callers.
- Improve the health-scope picker with search so large apiaries/hive lists remain usable on phones.
- Avoid recalculating health series while the scope-picker search text changes.
- Improve chart rendering with an area fill, stronger point markers, and non-duplicating axis labels.
- Add clear sparse-data messaging for one-point and empty ranges.
- Document that the score is a heuristic signal, not a diagnosis.

## Regression coverage
- Calendar-day range boundaries.
- Future and out-of-range inspections excluded.
- Equal hive weighting in apiary buckets.
- Latest-in-range averaging.
- Empty/sparse datasets.
- Unsorted point summaries.
- Shared hive/inspection scoring weights.
