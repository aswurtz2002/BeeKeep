# Smart Inspection Assistant

## Purpose
BeeKeep uses the hive’s own recorded history to prioritize what the beekeeper may want to check next. The system is intentionally explainable and conservative.

## Health score
The score is a BeeKeep workflow indicator, not a veterinary or diagnostic score. Inputs include colony strength, queen status, mite rate, inspection freshness, health flags, stores, and recent strength direction.

## Recommendations
Rules currently cover:
- Queenless colonies
- High or rising mite rates
- Recorded disease/pest flags
- Recorded queen cells
- Significant strength drops
- Low honey-store counts
- Queen confirmation older than 14 days
- Routine inspection freshness over 14 days

Recommendations can be dismissed simply by leaving them alone, or scheduled into the calendar with one tap. No recommendation mutates hive data automatically.

## Comparison
When at least two inspections exist, BeeKeep compares the latest inspection against the previous one for strength, mite percentage, honey stores, total brood frames, and queen status.
