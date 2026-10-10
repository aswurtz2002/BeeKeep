package com.beekeep.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "apiaries", indices = [Index(value = ["name"], unique = true)])
data class ApiaryEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val notes: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val forageNotes: String = "",
    val waterNotes: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "deleted") val deleted: Boolean = false
)

object HiveStatus {
    const val ACTIVE = "ACTIVE"
    const val DEAD = "DEAD"
    const val SOLD = "SOLD"
    const val REMOVED = "REMOVED"
}

@Entity(
    tableName = "hives",
    foreignKeys = [ForeignKey(entity = ApiaryEntity::class, parentColumns = ["id"], childColumns = ["apiary_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("apiary_id"), Index(value = ["apiary", "number"]), Index(value = ["tag_uid"], unique = true)]
)
data class HiveEntity(
    @PrimaryKey val id: Long,
    val number: String,
    @ColumnInfo(name = "apiary") val apiaryName: String,
    @ColumnInfo(name = "apiary_id") val apiaryId: Long? = null,
    val queenStatus: String,
    @ColumnInfo(name = "queen_mark_color") val queenMarkColor: String = "",
    @ColumnInfo(name = "queen_origin") val queenOrigin: String = "",
    @ColumnInfo(name = "queen_age_months") val queenAgeMonths: Int? = null,
    @ColumnInfo(name = "queen_temperament") val queenTemperament: Int = 3,
    val strength: Int,
    val mitePercent: Double,
    @ColumnInfo(name = "tag_uid") val tagUid: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "deleted") val deleted: Boolean = false,
    @ColumnInfo(name = "status", defaultValue = "ACTIVE") val status: String = HiveStatus.ACTIVE,
    @ColumnInfo(name = "dead_at") val deadAt: Long? = null,
    // Lifecycle fields merge across devices by this timestamp, not updated_at,
    // so a stale offline edit cannot resurrect a dead colony.
    @ColumnInfo(name = "status_changed_at", defaultValue = "0") val statusChangedAt: Long = 0L
)

@Entity(
    tableName = "nfc_tag_assignments",
    foreignKeys = [ForeignKey(entity = HiveEntity::class, parentColumns = ["id"], childColumns = ["hive_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("hive_id"), Index("tag_uid")]
)
data class NfcTagAssignmentEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "tag_uid") val tagUid: String,
    @ColumnInfo(name = "hive_id") val hiveId: Long,
    @ColumnInfo(name = "assigned_at") val assignedAt: Long,
    @ColumnInfo(name = "unassigned_at") val unassignedAt: Long? = null
) {
    val isActive: Boolean get() = unassignedAt == null
}

@Entity(
    tableName = "inspections",
    foreignKeys = [ForeignKey(entity = HiveEntity::class, parentColumns = ["id"], childColumns = ["hive_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["hive_id", "created_at"]), Index("created_at")]
)
data class InspectionEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "hive_id") val hiveId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val strength: Int,
    val queenStatus: String,
    @ColumnInfo(name = "mite_count") val miteCount: Int,
    @ColumnInfo(name = "sample_size") val sampleSize: Int,
    val notes: String,
    @ColumnInfo(name = "photo_path") val photoPath: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @ColumnInfo(name = "emergency_cells") val emergencyCells: Int = 0,
    @ColumnInfo(name = "supercedure_cells") val supercedureCells: Int = 0,
    @ColumnInfo(name = "swarm_cells") val swarmCells: Int = 0,
    val eggs: Int = 0,
    @ColumnInfo(name = "open_brood") val openBrood: Int = 0,
    @ColumnInfo(name = "capped_brood") val cappedBrood: Int = 0,
    @ColumnInfo(name = "honey_stores") val honeyStores: Int = 0,
    val pollen: Int = 0,
    @ColumnInfo(name = "empty_drawn_comb") val emptyDrawnComb: Int = 0,
    @ColumnInfo(name = "disease_flags") val diseaseFlags: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) {
    val mitePercent: Double get() = if (sampleSize > 0) miteCount * 100.0 / sampleSize else 0.0
}

@Entity(
    tableName = "feeding_entries",
    foreignKeys = [ForeignKey(entity = HiveEntity::class, parentColumns = ["id"], childColumns = ["hive_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["hive_id", "created_at"]), Index("created_at")]
)
data class FeedingEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "hive_id") val hiveId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "feed_type") val feedType: String,
    val ratio: String,
    val amount: Double,
    val unit: String,
    val notes: String = ""
)

@Entity(
    tableName = "treatment_entries",
    foreignKeys = [ForeignKey(entity = HiveEntity::class, parentColumns = ["id"], childColumns = ["hive_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["hive_id", "created_at"]), Index("created_at"), Index("removal_at")]
)
data class TreatmentEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "hive_id") val hiveId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "treatment_type") val treatmentType: String,
    val product: String,
    @ColumnInfo(name = "inserted_at") val insertedAt: Long?,
    @ColumnInfo(name = "removal_at") val removalAt: Long?,
    @ColumnInfo(name = "withdrawal_until") val withdrawalUntil: Long?,
    val notes: String = ""
)

@Entity(
    tableName = "harvest_entries",
    foreignKeys = [ForeignKey(entity = HiveEntity::class, parentColumns = ["id"], childColumns = ["hive_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["hive_id", "created_at"]), Index("created_at")]
)
data class HarvestEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "hive_id") val hiveId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "supers_pulled") val supersPulled: Int = 0,
    @ColumnInfo(name = "wet_honey_weight") val wetHoneyWeight: Double = 0.0,
    @ColumnInfo(name = "dry_honey_weight") val dryHoneyWeight: Double = 0.0,
    @ColumnInfo(name = "weight_unit") val weightUnit: String = "lb",
    @ColumnInfo(name = "wax_weight") val waxWeight: Double = 0.0,
    @ColumnInfo(name = "propolis_weight") val propolisWeight: Double = 0.0,
    val notes: String = ""
)

@Entity(tableName = "activity_events", indices = [Index(value = ["hive_id", "created_at"]), Index("created_at")])
data class ActivityEventEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "hive_id") val hiveId: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val type: String,
    val title: String,
    val detail: String = ""
)

@Entity(tableName = "sync_outbox", indices = [Index(value = ["state", "created_at"]), Index(value = ["entity_type", "entity_id", "state"])])
data class SyncOutboxEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: Long,
    val operation: String,
    val payload: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    val state: String = "pending",
    @ColumnInfo(name = "last_error") val lastError: String? = null
)


@Entity(tableName = "tasks", indices = [Index(value = ["completed", "due_at"]), Index("updated_at")])
data class TaskEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "hive_id") val hiveId: Long?,
    val title: String,
    @ColumnInfo(name = "due_at") val dueAt: Long,
    val completed: Boolean = false,
    val kind: String = "manual",
    @ColumnInfo(name = "reminder_enabled") val reminderEnabled: Boolean = true,
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = System.currentTimeMillis()
)



@Entity(
    tableName = "inspection_photos",
    indices = [Index(value = ["hive_id", "created_at"]), Index(value = ["inspection_id", "created_at"]), Index(value = ["sync_state", "created_at"])]
)
data class PhotoEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "hive_id") val hiveId: Long,
    @ColumnInfo(name = "inspection_id") val inspectionId: Long,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "cloud_path") val cloudPath: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "sync_state") val syncState: String = "pending",
    @ColumnInfo(name = "last_error") val lastError: String? = null
)

// UI-facing domain models.
data class Hive(
    val id: Long,
    val number: String,
    val apiary: String,
    val queenStatus: String,
    val queenMarkColor: String,
    val queenOrigin: String,
    val queenAgeMonths: Int?,
    val queenTemperament: Int,
    val strength: Int,
    val mitePercent: Double,
    val tagUid: String?,
    val status: String = HiveStatus.ACTIVE,
    val deadAt: Long? = null
) {
    val isDead: Boolean get() = status != HiveStatus.ACTIVE
}

data class Inspection(
    val id: Long,
    val hiveId: Long,
    val createdAt: Long,
    val strength: Int,
    val queenStatus: String,
    val miteCount: Int,
    val sampleSize: Int,
    val notes: String,
    val photoPath: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val emergencyCells: Int = 0,
    val supercedureCells: Int = 0,
    val swarmCells: Int = 0,
    val eggs: Int = 0,
    val openBrood: Int = 0,
    val cappedBrood: Int = 0,
    val honeyStores: Int = 0,
    val pollen: Int = 0,
    val emptyDrawnComb: Int = 0,
    val diseaseFlags: String = ""
) {
    val mitePercent: Double get() = if (sampleSize > 0) miteCount * 100.0 / sampleSize else 0.0
}

data class Feeding(val id: Long, val hiveId: Long, val createdAt: Long, val feedType: String, val ratio: String, val amount: Double, val unit: String, val notes: String)
data class Treatment(val id: Long, val hiveId: Long, val createdAt: Long, val treatmentType: String, val product: String, val insertedAt: Long?, val removalAt: Long?, val withdrawalUntil: Long?, val notes: String)
data class Harvest(val id: Long, val hiveId: Long, val createdAt: Long, val supersPulled: Int, val wetHoneyWeight: Double, val dryHoneyWeight: Double, val weightUnit: String, val waxWeight: Double, val propolisWeight: Double, val notes: String)
data class Apiary(val id: Long, val name: String, val notes: String, val latitude: Double?, val longitude: Double?, val forageNotes: String, val waterNotes: String)
data class ActivityEvent(val id: Long, val hiveId: Long, val createdAt: Long, val type: String, val title: String, val detail: String)
data class Task(val id: Long, val hiveId: Long?, val title: String, val dueAt: Long, val completed: Boolean, val kind: String, val reminderEnabled: Boolean)
