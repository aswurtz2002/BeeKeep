package com.beekeep.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiaryDao {
    @Query("SELECT * FROM apiaries WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ApiaryEntity>>
    @Query("SELECT * FROM apiaries WHERE id = :id")
    suspend fun get(id: Long): ApiaryEntity?
    @Query("SELECT * FROM apiaries WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): ApiaryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ApiaryEntity)
}

@Dao
interface HiveDao {
    @Query("SELECT * FROM hives WHERE deleted = 0 AND status = 'ACTIVE' ORDER BY CAST(number AS INTEGER), number")
    fun observeAll(): Flow<List<HiveEntity>>
    @Query("SELECT * FROM hives WHERE deleted = 0 AND status != 'ACTIVE' ORDER BY dead_at DESC")
    fun observeDead(): Flow<List<HiveEntity>>
    @Query("SELECT * FROM hives WHERE id = :id AND deleted = 0")
    suspend fun get(id: Long): HiveEntity?
    @Query("SELECT * FROM hives WHERE apiary_id = :apiaryId OR apiary = :oldName COLLATE NOCASE")
    suspend fun forApiaryRename(apiaryId: Long, oldName: String): List<HiveEntity>
    @Query("SELECT * FROM hives WHERE tag_uid = :tag COLLATE NOCASE AND deleted = 0 LIMIT 1")
    suspend fun byTag(tag: String): HiveEntity?
    @Query("SELECT * FROM hives WHERE number = :number COLLATE NOCASE AND apiary = :apiary AND deleted = 0 AND status = 'ACTIVE' LIMIT 1")
    suspend fun byNumberAndApiary(number: String, apiary: String): HiveEntity?
    @Query("SELECT COUNT(*) FROM hives")
    suspend fun countAll(): Int
    @Query("SELECT EXISTS(SELECT 1 FROM hives WHERE id = :id)")
    suspend fun exists(id: Long): Boolean
    @Query("DELETE FROM hives WHERE id = :id")
    suspend fun hardDelete(id: Long)
    @Query("UPDATE hives SET tag_uid = (SELECT tag_uid FROM nfc_tag_assignments WHERE hive_id = :hiveId AND unassigned_at IS NULL LIMIT 1) WHERE id = :hiveId")
    suspend fun refreshTagCache(hiveId: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HiveEntity)
}

@Dao
interface NfcTagAssignmentDao {
    @Query("SELECT * FROM nfc_tag_assignments WHERE tag_uid = :uid COLLATE NOCASE AND unassigned_at IS NULL LIMIT 1")
    suspend fun activeByTag(uid: String): NfcTagAssignmentEntity?
    @Query("SELECT * FROM nfc_tag_assignments WHERE hive_id = :hiveId AND unassigned_at IS NULL LIMIT 1")
    suspend fun activeForHive(hiveId: Long): NfcTagAssignmentEntity?
    @Query("SELECT * FROM nfc_tag_assignments WHERE id = :id")
    suspend fun get(id: Long): NfcTagAssignmentEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NfcTagAssignmentEntity)
}

@Dao
interface InspectionDao {
    @Query("SELECT * FROM inspections WHERE hive_id = :hiveId ORDER BY created_at DESC")
    fun observeForHive(hiveId: Long): Flow<List<InspectionEntity>>
    @Query("SELECT * FROM inspections ORDER BY created_at DESC")
    fun observeAll(): Flow<List<InspectionEntity>>
    @Query("SELECT * FROM inspections WHERE hive_id = :hiveId ORDER BY created_at DESC LIMIT 1")
    suspend fun latest(hiveId: Long): InspectionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InspectionEntity)
}

@Dao
interface FeedingDao {
    @Query("SELECT * FROM feeding_entries WHERE hive_id = :hiveId ORDER BY created_at DESC")
    fun observeForHive(hiveId: Long): Flow<List<FeedingEntity>>
    @Query("SELECT * FROM feeding_entries ORDER BY created_at DESC")
    fun observeAll(): Flow<List<FeedingEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FeedingEntity)
}

@Dao
interface TreatmentDao {
    @Query("SELECT * FROM treatment_entries WHERE hive_id = :hiveId ORDER BY created_at DESC")
    fun observeForHive(hiveId: Long): Flow<List<TreatmentEntity>>
    @Query("SELECT * FROM treatment_entries ORDER BY created_at DESC")
    fun observeAll(): Flow<List<TreatmentEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TreatmentEntity)
}

@Dao
interface HarvestDao {
    @Query("SELECT * FROM harvest_entries WHERE hive_id = :hiveId ORDER BY created_at DESC")
    fun observeForHive(hiveId: Long): Flow<List<HarvestEntity>>
    @Query("SELECT * FROM harvest_entries ORDER BY created_at DESC")
    fun observeAll(): Flow<List<HarvestEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HarvestEntity)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM activity_events WHERE hive_id = :hiveId ORDER BY created_at DESC")
    fun observeForHive(hiveId: Long): Flow<List<ActivityEventEntity>>
    @Query("DELETE FROM activity_events WHERE hive_id = :hiveId")
    suspend fun deleteForHive(hiveId: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ActivityEventEntity)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM sync_outbox WHERE state = 'pending' ORDER BY created_at LIMIT :limit")
    suspend fun pending(limit: Int = 50): List<SyncOutboxEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(entity: SyncOutboxEntity)
    @Query("DELETE FROM sync_outbox WHERE entity_type = :entityType AND entity_id = :entityId AND state = 'pending'")
    suspend fun deletePendingForEntity(entityType: String, entityId: Long)
    @Query("UPDATE sync_outbox SET state = :state, last_error = :error WHERE id = :id")
    suspend fun setState(id: Long, state: String, error: String? = null)
    @Query("DELETE FROM sync_outbox WHERE state = 'done' AND created_at < :cutoff")
    suspend fun pruneDone(cutoff: Long)
}


@Dao
interface PhotoDao {
    @Query("SELECT * FROM inspection_photos WHERE hive_id = :hiveId ORDER BY created_at DESC")
    fun observeForHive(hiveId: Long): Flow<List<PhotoEntity>>
    @Query("SELECT * FROM inspection_photos WHERE sync_state = 'pending' ORDER BY created_at ASC LIMIT :limit")
    suspend fun pending(limit: Int = 20): List<PhotoEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PhotoEntity)
    @Query("UPDATE inspection_photos SET cloud_path = :cloudPath, sync_state = 'uploaded', last_error = NULL WHERE id = :id")
    suspend fun markUploaded(id: Long, cloudPath: String)
    @Query("UPDATE inspection_photos SET sync_state = 'failed', last_error = :error WHERE id = :id")
    suspend fun markError(id: Long, error: String)
    @Query("SELECT * FROM inspection_photos WHERE hive_id = :hiveId")
    suspend fun listForHive(hiveId: Long): List<PhotoEntity>
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY due_at DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE due_at >= :timestamp ORDER BY due_at ASC")
    suspend fun pendingAfter(timestamp: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TaskEntity)
}
