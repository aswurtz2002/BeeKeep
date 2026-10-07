package com.beekeep.app.data

import android.content.Context
import androidx.room.withTransaction
import com.beekeep.app.cloud.CloudDocumentRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LocalHiveRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = BeeKeepRoomDb.get(appContext)

    suspend fun initialize() {
        LegacyImporter.importIfNeeded(appContext, db)
        seedIfEmpty()
    }

    fun observeHives(): Flow<List<Hive>> = db.hives().observeAll().map { it.map(::toHive) }
    fun observeApiaries(): Flow<List<Apiary>> = db.apiaries().observeAll().map { it.map(::toApiary) }
    fun observeInspections(hiveId: Long): Flow<List<Inspection>> = db.inspections().observeForHive(hiveId).map { it.map(::toInspection) }
    fun observeAllInspections(): Flow<List<Inspection>> = db.inspections().observeAll().map { it.map(::toInspection) }
    fun observeFeedings(hiveId: Long): Flow<List<Feeding>> = db.feedings().observeForHive(hiveId).map { it.map(::toFeeding) }
    fun observeTreatments(hiveId: Long): Flow<List<Treatment>> = db.treatments().observeForHive(hiveId).map { it.map(::toTreatment) }
    fun observeHarvests(hiveId: Long): Flow<List<Harvest>> = db.harvests().observeForHive(hiveId).map { it.map(::toHarvest) }
    fun observeEvents(hiveId: Long): Flow<List<ActivityEvent>> = db.events().observeForHive(hiveId).map { it.map(::toEvent) }
    fun observeTasks(): Flow<List<Task>> = db.tasks().observeAll().map { it.map(::toTask) }
    fun observeAllFeedings(): Flow<List<Feeding>> = db.feedings().observeAll().map { it.map(::toFeeding) }
    fun observeAllTreatments(): Flow<List<Treatment>> = db.treatments().observeAll().map { it.map(::toTreatment) }
    fun observeAllHarvests(): Flow<List<Harvest>> = db.harvests().observeAll().map { it.map(::toHarvest) }
    fun observePhotos(hiveId: Long): Flow<List<PhotoEntity>> = db.photos().observeForHive(hiveId)

    suspend fun getHive(id: Long): Hive? = withContext(Dispatchers.IO) { db.hives().get(id)?.let(::toHive) }
    suspend fun findHiveByTag(tag: String): Hive? = withContext(Dispatchers.IO) { db.hives().byTag(tag.trim())?.let(::toHive) }
    suspend fun findHiveByNumberAndApiary(number: String, apiary: String): Hive? = withContext(Dispatchers.IO) {
        db.hives().byNumberAndApiary(number.trim(), apiary.trim()).let { it?.let(::toHive) }
    }

    private suspend fun seedIfEmpty() = withContext(Dispatchers.IO) {
        db.withTransaction {
            if (db.hives().countAll() == 0) {
                val home = ApiaryEntity(100L, "Home Yard", notes = "Main apiary", forageNotes = "Willow + clover", waterNotes = "Stock tank")
                val out = ApiaryEntity(101L, "Out Yard A", forageNotes = "Canola", waterNotes = "Natural slough")
                db.apiaries().upsert(home); db.apiaries().upsert(out)
                db.hives().upsert(HiveEntity(1, "01", "Home Yard", 100, "Laying", queenMarkColor = "White", queenOrigin = "Graft", queenAgeMonths = 18, queenTemperament = 2, strength = 8, mitePercent = .8, tagUid = "BEEKEEP-HIVE-01"))
                db.hives().upsert(HiveEntity(2, "02", "Home Yard", 100, "Spotted", queenMarkColor = "Yellow", queenOrigin = "Package", queenAgeMonths = 9, queenTemperament = 3, strength = 7, mitePercent = 1.2, tagUid = "BEEKEEP-HIVE-02"))
                db.hives().upsert(HiveEntity(3, "03", "Out Yard A", 101, "Laying", queenMarkColor = "Red", queenOrigin = "Swarm", queenAgeMonths = 24, queenTemperament = 4, strength = 6, mitePercent = 2.3, tagUid = "BEEKEEP-HIVE-03"))
            }
        }
    }

    suspend fun saveHive(h: Hive) = withContext(Dispatchers.IO) {
        require(h.number.trim().isNotBlank()) { "Hive number cannot be blank" }
        db.withTransaction {
        val existing = db.hives().get(h.id)
        val cleanApiary = h.apiary.trim()
        val resolvedApiaryId = cleanApiary.takeIf { it.isNotBlank() }?.let { db.apiaries().byName(it)?.id }
        val cleanTag = h.tagUid?.trim()?.takeIf { it.isNotBlank() }
        val entity = HiveEntity(
            id = h.id,
            number = h.number.trim(),
            apiaryName = cleanApiary.ifBlank { "Unassigned Yard" },
            apiaryId = resolvedApiaryId,
            queenStatus = h.queenStatus.trim().ifBlank { "Laying" },
            queenMarkColor = h.queenMarkColor.trim(),
            queenOrigin = h.queenOrigin.trim(),
            queenAgeMonths = h.queenAgeMonths?.coerceAtLeast(0),
            queenTemperament = h.queenTemperament.coerceIn(1, 5),
            strength = h.strength.coerceIn(0, 10),
            mitePercent = h.mitePercent.coerceAtLeast(0.0),
            tagUid = cleanTag,
            updatedAt = System.currentTimeMillis(),
            deleted = false
        )
        db.hives().upsert(entity)
        enqueue("hive", h.id, "upsert", hivePayload(entity))
        if (existing?.tagUid != entity.tagUid) {
            val now = System.currentTimeMillis()
            val title = if (entity.tagUid.isNullOrBlank()) "NFC tag removed" else "NFC tag assigned"
            val detail = entity.tagUid?.let { "Tag $it" } ?: "Hive no longer has an NFC tag assigned"
            val event = ActivityEventEntity(IdGenerator.nextLong(), h.id, now, "tag", title, detail)
            db.events().upsert(event)
            enqueue("event", event.id, "upsert", eventPayload(event))
        }
        }
    }

    suspend fun saveInspection(i: Inspection) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val safeSample = i.sampleSize.coerceAtLeast(1)
            val safeMites = i.miteCount.coerceIn(0, safeSample)
            val entity = InspectionEntity(
                id = i.id, hiveId = i.hiveId, createdAt = i.createdAt, strength = i.strength.coerceIn(0, 10),
                queenStatus = i.queenStatus.trim().ifBlank { "Laying" }, miteCount = safeMites, sampleSize = safeSample,
                notes = i.notes.trim(), photoPath = i.photoPath?.trim()?.takeIf { it.isNotBlank() }, latitude = i.latitude, longitude = i.longitude,
                emergencyCells = i.emergencyCells.coerceAtLeast(0), supercedureCells = i.supercedureCells.coerceAtLeast(0), swarmCells = i.swarmCells.coerceAtLeast(0),
                eggs = i.eggs.coerceAtLeast(0), openBrood = i.openBrood.coerceAtLeast(0), cappedBrood = i.cappedBrood.coerceAtLeast(0),
                honeyStores = i.honeyStores.coerceAtLeast(0), pollen = i.pollen.coerceAtLeast(0), emptyDrawnComb = i.emptyDrawnComb.coerceAtLeast(0),
                diseaseFlags = i.diseaseFlags.trim(), updatedAt = i.createdAt
            )
            db.inspections().upsert(entity)
            if (!i.photoPath.isNullOrBlank()) {
                db.photos().upsert(PhotoEntity(i.id, i.hiveId, i.id, i.photoPath, null, i.createdAt, "pending"))
            }
            db.hives().get(i.hiveId)?.let {
                // The inspection timestamp describes the observation, not when the hive record was edited.
                // Use wall-clock update time so backfilled inspections cannot make the hive look stale during sync.
                db.hives().upsert(it.copy(strength = entity.strength, queenStatus = entity.queenStatus, mitePercent = entity.mitePercent, updatedAt = System.currentTimeMillis()))
            }
            val event = ActivityEventEntity(i.id, i.hiveId, i.createdAt, "inspection", "Inspection", "Strength ${entity.strength}/10 • Mites ${String.format(java.util.Locale.US, "%.2f", entity.mitePercent)}%")
            db.events().upsert(event)
            enqueue("inspection", i.id, "upsert", inspectionPayload(entity))
            val updatedHive = db.hives().get(i.hiveId) ?: return@withTransaction
            enqueue("hive", i.hiveId, "upsert", hivePayload(updatedHive))
            enqueue("event", event.id, "upsert", eventPayload(event))
        }
    }

    suspend fun saveFeeding(f: Feeding) = withContext(Dispatchers.IO) {
        require(f.feedType.trim().isNotBlank()) { "Feed type cannot be blank" }
        require(f.amount.isFinite() && f.amount >= 0.0) { "Feeding amount must be a valid non-negative number" }
        require(f.unit.trim().isNotBlank()) { "Feeding unit cannot be blank" }
        db.withTransaction {
            val entity = FeedingEntity(f.id, f.hiveId, f.createdAt, f.feedType.trim(), f.ratio.trim(), f.amount, f.unit.trim(), f.notes.trim())
            db.feedings().upsert(entity)
            val event = ActivityEventEntity(f.id, f.hiveId, f.createdAt, "feeding", "Feeding", "${entity.amount} ${entity.unit} • ${entity.feedType} ${entity.ratio}")
            db.events().upsert(event)
            enqueue("feeding", f.id, "upsert", feedingPayload(entity))
            enqueue("event", event.id, "upsert", eventPayload(event))
        }
    }

    suspend fun saveTreatment(t: Treatment) = withContext(Dispatchers.IO) {
        require(t.treatmentType.trim().isNotBlank()) { "Treatment type cannot be blank" }
        require(t.product.trim().isNotBlank()) { "Product cannot be blank" }
        db.withTransaction {
            val entity = TreatmentEntity(t.id, t.hiveId, t.createdAt, t.treatmentType.trim(), t.product.trim(), t.insertedAt, t.removalAt, t.withdrawalUntil, t.notes.trim())
            db.treatments().upsert(entity)
            val event = ActivityEventEntity(t.id, t.hiveId, t.createdAt, "treatment", "Treatment", "${entity.treatmentType} • ${entity.product}")
            db.events().upsert(event)
            enqueue("treatment", t.id, "upsert", treatmentPayload(entity))
            enqueue("event", event.id, "upsert", eventPayload(event))
        }
    }

    suspend fun saveHarvest(h: Harvest) = withContext(Dispatchers.IO) {
        require(h.weightUnit.trim().isNotBlank()) { "Harvest weight unit cannot be blank" }
        listOf(h.wetHoneyWeight, h.dryHoneyWeight, h.waxWeight, h.propolisWeight).forEach { value -> require(value.isFinite() && value >= 0.0) { "Harvest values must be valid non-negative numbers" } }
        db.withTransaction {
            val entity = HarvestEntity(h.id, h.hiveId, h.createdAt, h.supersPulled.coerceAtLeast(0), h.wetHoneyWeight, h.dryHoneyWeight, h.weightUnit.trim(), h.waxWeight, h.propolisWeight, h.notes.trim())
            db.harvests().upsert(entity)
            val event = ActivityEventEntity(h.id, h.hiveId, h.createdAt, "harvest", "Harvest", "${entity.dryHoneyWeight} ${entity.weightUnit} dry honey")
            db.events().upsert(event)
            enqueue("harvest", h.id, "upsert", harvestPayload(entity))
            enqueue("event", event.id, "upsert", eventPayload(event))
        }
    }

    suspend fun saveApiary(a: Apiary) = withContext(Dispatchers.IO) {
        val cleanName = a.name.trim()
        require(cleanName.isNotBlank()) { "Apiary name cannot be blank" }
        db.withTransaction {
            val existingByName = db.apiaries().byName(cleanName)
            if (existingByName != null && existingByName.id != a.id) {
                throw IllegalArgumentException("Apiary $cleanName already exists.")
            }
            val entity = ApiaryEntity(a.id, cleanName, a.notes.trim(), a.latitude, a.longitude, a.forageNotes.trim(), a.waterNotes.trim(), System.currentTimeMillis(), false)
            db.apiaries().upsert(entity)
            enqueue("apiary", entity.id, "upsert", apiaryPayload(entity))
        }
    }

    suspend fun saveTask(t: Task) = withContext(Dispatchers.IO) {
        require(t.title.trim().isNotBlank()) { "Task title cannot be blank" }
        require(t.dueAt > 0L) { "Task date is invalid" }
        db.withTransaction {
            val entity = TaskEntity(t.id, t.hiveId, t.title.trim(), t.dueAt, t.completed, t.kind.trim().ifBlank { "manual" }, t.reminderEnabled, System.currentTimeMillis())
            db.tasks().upsert(entity)
            enqueue("task", t.id, "upsert", taskPayload(entity))
        }
    }

    suspend fun saveTasks(tasks: List<Task>) = withContext(Dispatchers.IO) {
        if (tasks.isEmpty()) return@withContext
        tasks.forEach {
            require(it.title.trim().isNotBlank()) { "Task title cannot be blank" }
            require(it.dueAt > 0L) { "Task date is invalid" }
        }
        val now = System.currentTimeMillis()
        db.withTransaction {
            val entities = tasks.map {
                TaskEntity(
                    it.id, it.hiveId, it.title.trim(), it.dueAt, it.completed,
                    it.kind.trim().ifBlank { "manual" }, it.reminderEnabled, now
                )
            }
            db.tasks().upsertAll(entities)
            entities.forEach { enqueue("task", it.id, "upsert", taskPayload(it)) }
        }
    }

    suspend fun completeTask(id: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.tasks().complete(id, System.currentTimeMillis())
            db.tasks().get(id)?.let { enqueue("task", id, "update", taskPayload(it)) }
        }
    }

    suspend fun pendingPhotos(limit: Int = 10): List<PhotoEntity> = db.photos().pending(limit)
    suspend fun markPhotoUploaded(id: Long, cloudPath: String) = db.photos().markUploaded(id, cloudPath)
    suspend fun markPhotoError(id: Long, error: String) = db.photos().markError(id, error)

    suspend fun pendingSync(limit: Int = 50): List<SyncOutboxEntity> = db.outbox().pending(limit)
    suspend fun markSyncDone(id: Long) = db.outbox().setState(id, "done")
    suspend fun markSyncError(id: Long, message: String) = db.outbox().setState(id, "pending", message.take(500))
    suspend fun pruneSync() = db.outbox().pruneDone(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000)

    suspend fun applyCloudDocument(doc: CloudDocumentRow) = withContext(Dispatchers.IO) {
        if (doc.deleted) {
            when (doc.entity_type) {
                "apiary" -> db.apiaries().get(doc.entity_id)?.let {
                    if (doc.updated_at > it.updatedAt) db.apiaries().upsert(it.copy(deleted = true, updatedAt = doc.updated_at))
                }
                "hive" -> db.hives().get(doc.entity_id)?.let {
                    if (doc.updated_at > it.updatedAt) db.hives().upsert(it.copy(deleted = true, updatedAt = doc.updated_at))
                }
                "task" -> db.tasks().get(doc.entity_id)?.let {
                    if (doc.updated_at > it.updatedAt) db.tasks().upsert(it.copy(completed = true, updatedAt = doc.updated_at))
                }
            }
            return@withContext
        }
        val json = runCatching { JSONObject(doc.payload) }.getOrNull() ?: return@withContext
        when (doc.entity_type) {
            "apiary" -> {
                val existing = db.apiaries().get(doc.entity_id)
                val incomingUpdatedAt = json.optLong("updated_at", doc.updated_at)
                if (existing != null && existing.updatedAt >= incomingUpdatedAt) return@withContext
                db.apiaries().upsert(ApiaryEntity(
                    id = json.optLong("id", doc.entity_id),
                    name = json.optString("name"),
                    notes = json.optString("notes"),
                    latitude = json.doubleOrNull("latitude"),
                    longitude = json.doubleOrNull("longitude"),
                    forageNotes = json.optString("forage_notes"),
                    waterNotes = json.optString("water_notes"),
                    updatedAt = incomingUpdatedAt,
                    deleted = json.optBoolean("deleted", false)
                ))
            }
            "hive" -> {
                val existing = db.hives().get(doc.entity_id)
                val incomingUpdatedAt = json.optLong("updated_at", doc.updated_at)
                if (existing != null && existing.updatedAt >= incomingUpdatedAt) return@withContext
                db.hives().upsert(HiveEntity(
                    id = json.optLong("id", doc.entity_id), number = json.optString("number").trim(),
                    apiaryName = json.optString("apiary").trim().ifBlank { "Unassigned Yard" }, apiaryId = json.longOrNull("apiary_id")?.takeIf { db.apiaries().get(it) != null },
                    queenStatus = json.optString("queen_status", "Laying"),
                    queenMarkColor = json.optString("queen_mark_color"), queenOrigin = json.optString("queen_origin"),
                    queenAgeMonths = json.intOrNull("queen_age_months"), queenTemperament = json.optInt("queen_temperament", 3).coerceIn(1, 5),
                    strength = json.optInt("strength", 0).coerceIn(0, 10), mitePercent = json.optDouble("mite_percent", 0.0).coerceAtLeast(0.0),
                    tagUid = json.stringOrNull("tag_uid"), updatedAt = incomingUpdatedAt,
                    deleted = json.optBoolean("deleted", false)
                ))
            }
            "inspection" -> db.inspections().upsert(InspectionEntity(
                id=json.optLong("id",doc.entity_id), hiveId=json.optLong("hive_id"), createdAt=json.optLong("created_at",doc.updated_at),
                strength=json.optInt("strength").coerceIn(0, 10), queenStatus=json.optString("queen_status"), miteCount=json.optInt("mite_count").coerceAtLeast(0), sampleSize=json.optInt("sample_size",1).coerceAtLeast(1),
                notes=json.optString("notes"), photoPath=json.stringOrNull("photo_path"), latitude=json.doubleOrNull("latitude"), longitude=json.doubleOrNull("longitude"),
                emergencyCells=json.optInt("emergency_cells").coerceAtLeast(0), supercedureCells=json.optInt("supercedure_cells").coerceAtLeast(0), swarmCells=json.optInt("swarm_cells").coerceAtLeast(0), eggs=json.optInt("eggs").coerceAtLeast(0), openBrood=json.optInt("open_brood").coerceAtLeast(0), cappedBrood=json.optInt("capped_brood").coerceAtLeast(0), honeyStores=json.optInt("honey_stores").coerceAtLeast(0), pollen=json.optInt("pollen").coerceAtLeast(0), emptyDrawnComb=json.optInt("empty_drawn_comb").coerceAtLeast(0), diseaseFlags=json.optString("disease_flags"), updatedAt=json.optLong("updated_at",doc.updated_at)
            ))
            "feeding" -> db.feedings().upsert(FeedingEntity(json.optLong("id",doc.entity_id),json.optLong("hive_id"),json.optLong("created_at",doc.updated_at),json.optString("feed_type"),json.optString("ratio"),json.optDouble("amount").coerceAtLeast(0.0),json.optString("unit"),json.optString("notes")))
            "treatment" -> db.treatments().upsert(TreatmentEntity(json.optLong("id",doc.entity_id),json.optLong("hive_id"),json.optLong("created_at",doc.updated_at),json.optString("treatment_type"),json.optString("product"),json.longOrNull("inserted_at"),json.longOrNull("removal_at"),json.longOrNull("withdrawal_until"),json.optString("notes")))
            "harvest" -> db.harvests().upsert(HarvestEntity(json.optLong("id",doc.entity_id),json.optLong("hive_id"),json.optLong("created_at",doc.updated_at),json.optInt("supers_pulled").coerceAtLeast(0),json.optDouble("wet_honey_weight").coerceAtLeast(0.0),json.optDouble("dry_honey_weight").coerceAtLeast(0.0),json.optString("weight_unit","lb"),json.optDouble("wax_weight").coerceAtLeast(0.0),json.optDouble("propolis_weight").coerceAtLeast(0.0),json.optString("notes")))
            "task" -> {
                val existing = db.tasks().get(doc.entity_id)
                val incomingUpdatedAt = json.optLong("updated_at", doc.updated_at)
                if (existing != null && existing.updatedAt >= incomingUpdatedAt) return@withContext
                db.tasks().upsert(TaskEntity(json.optLong("id",doc.entity_id),json.longOrNull("hive_id"),json.optString("title"),json.optLong("due_at"),json.optBoolean("completed"),json.optString("kind","manual"),json.optBoolean("reminder_enabled",true),incomingUpdatedAt))
            }
            "event" -> db.events().upsert(ActivityEventEntity(json.optLong("id",doc.entity_id),json.optLong("hive_id"),json.optLong("created_at",doc.updated_at),json.optString("type"),json.optString("title"),json.optString("detail")))
        }
    }

    private suspend fun enqueue(type: String, entityId: Long, operation: String, payload: String) {
        if (type in setOf("hive", "apiary", "task")) {
            db.outbox().deletePendingForEntity(type, entityId)
        }
        db.outbox().enqueue(SyncOutboxEntity(IdGenerator.nextLong(), type, entityId, operation, payload))
    }

    private fun hivePayload(e: HiveEntity) = JSONObject().put("id",e.id).put("number",e.number).put("apiary",e.apiaryName).put("apiary_id",e.apiaryId).put("queen_status",e.queenStatus).put("queen_mark_color",e.queenMarkColor).put("queen_origin",e.queenOrigin).put("queen_age_months",e.queenAgeMonths).put("queen_temperament",e.queenTemperament).put("strength",e.strength).put("mite_percent",e.mitePercent).put("tag_uid",e.tagUid).put("updated_at",e.updatedAt).put("deleted",e.deleted).toString()
    private fun apiaryPayload(e: ApiaryEntity) = JSONObject().put("id",e.id).put("name",e.name).put("notes",e.notes).put("latitude",e.latitude).put("longitude",e.longitude).put("forage_notes",e.forageNotes).put("water_notes",e.waterNotes).put("updated_at",e.updatedAt).put("deleted",e.deleted).toString()
    private fun inspectionPayload(e: InspectionEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("strength",e.strength).put("queen_status",e.queenStatus).put("mite_count",e.miteCount).put("sample_size",e.sampleSize).put("notes",e.notes)
        // Local device paths are never valid on another device. Photos sync separately.
        .put("photo_path", JSONObject.NULL).put("latitude",e.latitude).put("longitude",e.longitude).put("emergency_cells",e.emergencyCells).put("supercedure_cells",e.supercedureCells).put("swarm_cells",e.swarmCells).put("eggs",e.eggs).put("open_brood",e.openBrood).put("capped_brood",e.cappedBrood).put("honey_stores",e.honeyStores).put("pollen",e.pollen).put("empty_drawn_comb",e.emptyDrawnComb).put("disease_flags",e.diseaseFlags).put("updated_at",e.updatedAt).toString()
    private fun feedingPayload(e: FeedingEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("feed_type",e.feedType).put("ratio",e.ratio).put("amount",e.amount).put("unit",e.unit).put("notes",e.notes).toString()
    private fun treatmentPayload(e: TreatmentEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("treatment_type",e.treatmentType).put("product",e.product).put("inserted_at",e.insertedAt).put("removal_at",e.removalAt).put("withdrawal_until",e.withdrawalUntil).put("notes",e.notes).toString()
    private fun harvestPayload(e: HarvestEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("supers_pulled",e.supersPulled).put("wet_honey_weight",e.wetHoneyWeight).put("dry_honey_weight",e.dryHoneyWeight).put("weight_unit",e.weightUnit).put("wax_weight",e.waxWeight).put("propolis_weight",e.propolisWeight).put("notes",e.notes).toString()
    private fun taskPayload(e: TaskEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("title",e.title).put("due_at",e.dueAt).put("completed",e.completed).put("kind",e.kind).put("reminder_enabled",e.reminderEnabled).put("updated_at",e.updatedAt).toString()
    private fun eventPayload(e: ActivityEventEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("type",e.type).put("title",e.title).put("detail",e.detail).toString()

    private fun JSONObject.doubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null
    private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

    private fun toHive(e: HiveEntity) = Hive(e.id, e.number, e.apiaryName, e.queenStatus, e.queenMarkColor, e.queenOrigin, e.queenAgeMonths, e.queenTemperament, e.strength, e.mitePercent, e.tagUid)
    private fun toInspection(e: InspectionEntity) = Inspection(e.id, e.hiveId, e.createdAt, e.strength, e.queenStatus, e.miteCount, e.sampleSize, e.notes, e.photoPath, e.latitude, e.longitude, e.emergencyCells, e.supercedureCells, e.swarmCells, e.eggs, e.openBrood, e.cappedBrood, e.honeyStores, e.pollen, e.emptyDrawnComb, e.diseaseFlags)
    private fun toFeeding(e: FeedingEntity) = Feeding(e.id, e.hiveId, e.createdAt, e.feedType, e.ratio, e.amount, e.unit, e.notes)
    private fun toTreatment(e: TreatmentEntity) = Treatment(e.id, e.hiveId, e.createdAt, e.treatmentType, e.product, e.insertedAt, e.removalAt, e.withdrawalUntil, e.notes)
    private fun toHarvest(e: HarvestEntity) = Harvest(e.id, e.hiveId, e.createdAt, e.supersPulled, e.wetHoneyWeight, e.dryHoneyWeight, e.weightUnit, e.waxWeight, e.propolisWeight, e.notes)
    private fun toApiary(e: ApiaryEntity) = Apiary(e.id, e.name, e.notes, e.latitude, e.longitude, e.forageNotes, e.waterNotes)
    private fun toTask(e: TaskEntity) = Task(e.id, e.hiveId, e.title, e.dueAt, e.completed, e.kind, e.reminderEnabled)
    private fun toEvent(e: ActivityEventEntity) = ActivityEvent(e.id, e.hiveId, e.createdAt, e.type, e.title, e.detail)
}
