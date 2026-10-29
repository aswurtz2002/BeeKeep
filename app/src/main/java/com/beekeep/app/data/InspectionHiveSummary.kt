package com.beekeep.app.data

/**
 * Updates only the Hive fields that represent the latest inspection. Hive profile
 * metadata (apiary, queen lineage/marking, NFC, location, and lifecycle) is preserved.
 */
internal fun HiveEntity.withInspectionSummary(inspection: InspectionEntity): HiveEntity {
    if (inspection.hiveId != id) return this
    return copy(
        strength = inspection.strength,
        queenStatus = inspection.queenStatus,
        mitePercent = inspection.mitePercent,
        beeCountFrames = inspection.beeCountFrames
    )
}
