# Advanced Analytics v1.3.0

BeeKeep now includes a unified secondary analytics layer for both individual hives and apiaries.

## Metrics
- Strength (0–10)
- Mite rate (%)
- Total brood (eggs + open brood + capped brood)
- Honey stores (frames)
- Queen score (100 laying/spotted/unspotted, 60 virgin, 20 queenless, 50 unknown)
- Dry honey harvest, normalized to kilograms when the source record is in pounds

## Scope and averaging
- Hive scope shows the selected hive's raw inspection series.
- Apiary and all-hive scope average each hive equally within each graph bucket.
- One frequently inspected hive cannot dominate an apiary trend.
- Harvest is aggregated by period as total recorded honey per hive, then averaged across producing hives.

## Data integrity
Historical inspection metrics are calculated from inspection snapshots. Editing a hive today does not rewrite older graph points.

## Interpretation
Queen score is a visualization aid, not a queen-health diagnosis. Harvest normalization assumes lb/kg are the stored units; unknown units remain unchanged and should be reviewed.
