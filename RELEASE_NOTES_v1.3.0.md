# BeeKeep Kotlin v1.3.0 — Advanced Analytics

## Highlights
- Added a unified Hive & Apiary Analytics card to Insights.
- Metrics: strength, mite rate, total brood, honey stores, queen score, and honey harvest.
- Added all-apiaries, apiary, and hive scopes with search.
- Added 30D, 90D, 6M, and 1Y ranges.
- Apiary inspection metrics use equal hive weighting inside each bucket.
- Harvest metrics normalize lb to kg when possible.
- Individual Hive Dashboard now includes the same secondary analytics for the selected hive.

## Safety/interpretation
Queen score is a visualization aid, not a diagnosis. Harvest trends normalize only recognized lb/kg units; unknown units are preserved numerically and should be reviewed.

## Regression
The existing Health Trends graph remains separate and unchanged. New analytics use a separate engine and database-free calculations over the existing Room-backed observations.
