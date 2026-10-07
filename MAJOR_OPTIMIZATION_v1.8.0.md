# BeeKeep v1.8.0 — Major Optimization Run

This release is a performance/stability pass with no new product features.

## Runtime & UI
- Long-lived Compose Flow collection uses lifecycle-aware collection.
- Advanced analytics point calculation runs on `Dispatchers.Default` so large histories do not block the main UI thread.
- Insights screen avoids repeated hive×inspection scans for recent-inspection counts.

## Database
- Added composite Room indexes for the hottest hive/timeline/task/outbox/photo queries.
- Added Room migration 6→7 so existing user data is preserved.
- Enabled SQLite WAL journaling for better concurrent read/write behavior on a single mobile process.
- Seasonal schedule generation now writes tasks in a single Room transaction instead of one transaction per task.

## Build performance
- Enabled Gradle build caching, configuration cache, and Kotlin incremental compilation.

## Validation
- Kotlin logic smoke tests, source/resource checks, Room schema/index checks, PC-artifact scan, and archive integrity checks were run before release.
