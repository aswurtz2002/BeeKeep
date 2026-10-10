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
    fun observeDeadHives(): Flow<List<Hive>> = db.hives().observeDead().map { it.map(::toHive) }
    fun observeApiaries(): Flow<List<Apiary>> = db.apiaries().observeAll().map { it.map(::toApiary) }
    fun observeInspections(hiveId: Long): Flow<List<Inspection>> = db.inspections().observeForHive(hiveId).map { it.map(::toInspection) }
    fun observeAllInspections(): Flow<List<Inspection>> = db.inspections().observeAll().map { it.map(::toInspection) }
    fun observeFeedings(hiveId: Long): Flow<List<Feeding>> = db.feedings().observeForHive(hiveId).map { it.map(::toFeeding) }
    fun observeTreatments(hiveId: Long): Flow<List<Treatment>> = db.treatments().observeForHive(hiveId).map { it.map(::toTreatment) }
    fun observeHarvests(hiveId: Long): Flow<List<Harvest>> = db.harvests().observeForHive(hiveId).map { it.map(::toHarvest) }
    fun observeEvents(hiveId: Long): Flow<List<ActivityEvent>> = db.events().observeForHive(hiveId).map { it.map(::toEvent) }
    fun observeAllFeedings(): Flow<List<Feeding>> = db.feedings().observeAll().map { it.map(::toFeeding) }
    fun observeAllTreatments(): Flow<List<Treatment>> = db.treatments().observeAll().map { it.map(::toTreatment) }
    fun observeAllHarvests(): Flow<List<Harvest>> = db.harvests().observeAll().map { it.map(::toHarvest) }
    fun observePhotos(hiveId: Long): Flow<List<PhotoEntity>> = db.photos().observeForHive(hiveId)

    suspend fun getHive(id: Long): Hive? = withContext(Dispatchers.IO) { db.hives().get(id)?.let(::toHive) }
    suspend fun findHiveByNfc(tag: String): Hive? = withContext(Dispatchers.IO) {
        val uid = tag.trim()
        db.nfcTagAssignments().activeByTag(uid)?.let { db.hives().get(it.hiveId) }?.let(::toHive)
            ?: db.hives().byTag(uid)?.let(::toHive)
    }
    suspend fun findHiveByNumberAndApiary(number: String, apiary: String): Hive? = withContext(Dispatchers.IO) {
        db.hives().byNumberAndApiary(number.trim(), apiary.trim()).let { it?.let(::toHive) }
    }

    private suspend fun seedIfEmpty() = withContext(Dispatchers.IO) {
        db.withTransaction {
            if (db.hives().countAll() == 0) {
                val now = System.currentTimeMillis()
                val home = ApiaryEntity(100L, "Home Yard", notes = "Main apiary", forageNotes = "Willow + clover", waterNotes = "Stock tank")
                val out = ApiaryEntity(101L, "Out Yard A", forageNotes = "Canola", waterNotes = "Natural slough")
                db.apiaries().upsert(home); db.apiaries().upsert(out)
                db.hives().upsert(HiveEntity(1, "01", "Home Yard", 100, "Laying", queenMarkColor = "White", queenOrigin = "Graft", queenAgeMonths = 18, queenTemperament = 2, strength = 8, mitePercent = .8, tagUid = "BEEKEEP-HIVE-01", statusChangedAt = now))
                db.hives().upsert(HiveEntity(2, "02", "Home Yard", 100, "Spotted", queenMarkColor = "Yellow", queenOrigin = "Package", queenAgeMonths = 9, queenTemperament = 3, strength = 7, mitePercent = 1.2, tagUid = "BEEKEEP-HIVE-02", statusChangedAt = now))
                db.hives().upsert(HiveEntity(3, "03", "Out Yard A", 101, "Laying", queenMarkColor = "Red", queenOrigin = "Swarm", queenAgeMonths = 24, queenTemperament = 4, strength = 6, mitePercent = 2.3, tagUid = "BEEKEEP-HIVE-03", statusChangedAt = now))
                db.nfcTagAssignments().upsert(NfcTagAssignmentEntity(1, "BEEKEEP-HIVE-01", 1, now))
                db.nfcTagAssignments().upsert(NfcTagAssignmentEntity(2, "BEEKEEP-HIVE-02", 2, now))
                db.nfcTagAssignments().upsert(NfcTagAssignmentEntity(3, "BEEKEEP-HIVE-03", 3, now))
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
        val now = System.currentTimeMillis()
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
            // tag_uid is a cache owned by the NFC assignment ledger; ordinary
            // hive saves must not resurrect lifecycle or tombstone state.
            tagUid = existing?.tagUid ?: cleanTag,
            updatedAt = now,
            deleted = existing?.deleted ?: false,
            status = existing?.status ?: h.status,
            deadAt = existing?.deadAt ?: h.deadAt,
            statusChangedAt = existing?.statusChangedAt ?: now
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

    suspend fun markHiveDead(hiveId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val hive = db.hives().get(hiveId) ?: throw IllegalArgumentException("Hive not found.")
            if (hive.status == HiveStatus.DEAD) return@withTransaction
            val now = System.currentTimeMillis()
            val dead = hive.copy(status = HiveStatus.DEAD, deadAt = now, statusChangedAt = now, tagUid = null, updatedAt = now)
            db.hives().upsert(dead)
            enqueue("hive", hiveId, "upsert", hivePayload(dead))
            db.nfcTagAssignments().activeForHive(hiveId)?.let { assignment ->
                val closed = assignment.copy(unassignedAt = now)
                db.nfcTagAssignments().upsert(closed)
                enqueue("nfc_assignment", closed.id, "upsert", nfcAssignmentPayload(closed))
            }
            val event = ActivityEventEntity(IdGenerator.nextLong(), hiveId, now, "lifecycle", "Colony marked dead", "History preserved. NFC tag released.")
            db.events().upsert(event)
            enqueue("event", event.id, "upsert", eventPayload(event))
        }
    }

    suspend fun restoreHive(hiveId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val hive = db.hives().get(hiveId) ?: throw IllegalArgumentException("Hive not found.")
            if (hive.status == HiveStatus.ACTIVE) return@withTransaction
            val now = System.currentTimeMillis()
            val restored = hive.copy(status = HiveStatus.ACTIVE, deadAt = null, statusChangedAt = now, updatedAt = now)
            db.hives().upsert(restored)
            enqueue("hive", hiveId, "upsert", hivePayload(restored))
            val event = ActivityEventEntity(IdGenerator.nextLong(), hiveId, now, "lifecycle", "Colony restored", "Marked active again.")
            db.events().upsert(event)
            enqueue("event", event.id, "upsert", eventPayload(event))
        }
    }

    suspend fun deleteHivePermanently(hiveId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.hives().get(hiveId) ?: throw IllegalArgumentException("Hive not found.")
            permanentlyDeleteHiveLocal(hiveId)
            enqueue("hive", hiveId, "delete", JSONObject().put("id", hiveId).toString())
        }
    }

    private suspend fun permanentlyDeleteHiveLocal(hiveId: Long) {
        db.photos().listForHive(hiveId).forEach { runCatching { java.io.File(it.localPath).delete() } }
        db.events().deleteForHive(hiveId)
        // inspections, feedings, treatments, harvests, photos and NFC assignments cascade.
        db.hives().hardDelete(hiveId)
    }

    suspend fun assignNfcTag(hiveId: Long, uid: String, reassign: Boolean = false) = withContext(Dispatchers.IO) {
        val normalized = uid.trim().uppercase()
        require(normalized.isNotBlank()) { "The NFC tag has no readable UID." }
        db.withTransaction {
            val hive = db.hives().get(hiveId) ?: throw IllegalArgumentException("Hive not found.")
            require(hive.status == HiveStatus.ACTIVE) { "Cannot assign an NFC tag to a ${hive.status.lowercase()} colony." }
            val now = System.currentTimeMillis()
            db.nfcTagAssignments().activeByTag(normalized)?.let { owner ->
                if (owner.hiveId == hiveId) return@withTransaction
                if (!reassign) {
                    val ownerHive = db.hives().get(owner.hiveId)
                    throw IllegalArgumentException("This NFC tag is already assigned to Hive ${ownerHive?.number ?: owner.hiveId}.")
                }
                val closed = owner.copy(unassignedAt = now)
                db.nfcTagAssignments().upsert(closed)
                enqueue("nfc_assignment", closed.id, "upsert", nfcAssignmentPayload(closed))
                db.hives().refreshTagCache(owner.hiveId)
                val stealEvent = ActivityEventEntity(IdGenerator.nextLong(), owner.hiveId, now, "tag", "NFC tag removed", "Tag $normalized reassigned to Hive ${hive.number}")
                db.events().upsert(stealEvent)
                enqueue("event", stealEvent.id, "upsert", eventPayload(stealEvent))
            }
            db.nfcTagAssignments().activeForHive(hiveId)?.takeIf { !it.tagUid.equals(normalized, ignoreCase = true) }?.let { existing ->
                val closed = existing.copy(unassignedAt = now)
                db.nfcTagAssignments().upsert(closed)
                enqueue("nfc_assignment", closed.id, "upsert", nfcAssignmentPayload(closed))
            }
            val assignment = NfcTagAssignmentEntity(IdGenerator.nextLong(), normalized, hiveId, now)
            db.nfcTagAssignments().upsert(assignment)
            db.hives().refreshTagCache(hiveId)
            enqueue("nfc_assignment", assignment.id, "upsert", nfcAssignmentPayload(assignment))
            val event = ActivityEventEntity(IdGenerator.nextLong(), hiveId, now, "tag", "NFC tag assigned", "Tag $normalized")
            db.events().upsert(event)
            enqueue("event", event.id, "upsert", eventPayload(event))
        }
    }

    suspend fun unassignNfcTag(hiveId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val assignment = db.nfcTagAssignments().activeForHive(hiveId) ?: return@withTransaction
            val now = System.currentTimeMillis()
            val closed = assignment.copy(unassignedAt = now)
            db.nfcTagAssignments().upsert(closed)
            db.hives().refreshTagCache(hiveId)
            enqueue("nfc_assignment", closed.id, "upsert", nfcAssignmentPayload(closed))
            val event = ActivityEventEntity(IdGenerator.nextLong(), hiveId, now, "tag", "NFC tag removed", "Tag ${assignment.tagUid} unassigned")
            db.events().upsert(event)
            enqueue("event", event.id, "upsert", eventPayload(event))
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
            val previous = db.apiaries().get(a.id)
            val now = System.currentTimeMillis()
            val entity = ApiaryEntity(a.id, cleanName, a.notes.trim(), a.latitude, a.longitude, a.forageNotes.trim(), a.waterNotes.trim(), now, false)
            db.apiaries().upsert(entity)
            enqueue("apiary", entity.id, "upsert", apiaryPayload(entity))

            // Keep existing hives attached when the apiary is renamed, including
            // older records that predate apiary_id.
            if (previous != null && !previous.name.equals(cleanName, ignoreCase = true)) {
                db.hives().forApiaryRename(previous.id, previous.name).forEach { hive ->
                    val renamedHive = hive.copy(apiaryName = cleanName, apiaryId = entity.id, updatedAt = now)
                    db.hives().upsert(renamedHive)
                    enqueue("hive", renamedHive.id, "upsert", hivePayload(renamedHive))
                }
            }
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
                    // A remote permanent delete only wins over genuinely newer local edits.
                    if (doc.updated_at > it.updatedAt) db.withTransaction { permanentlyDeleteHiveLocal(doc.entity_id) }
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
                val incomingStatusChangedAt = json.optLong("status_changed_at", 0L)
                // Lifecycle (ACTIVE/DEAD) resolves by status_changed_at so a stale
                // offline save can merge field edits without resurrecting the colony.
                val lifecycleWins = existing == null || incomingStatusChangedAt > existing.statusChangedAt
                val status = if (lifecycleWins) json.optString("status", HiveStatus.ACTIVE) else existing.status
                db.hives().upsert(HiveEntity(
                    id = json.optLong("id", doc.entity_id), number = json.optString("number").trim(),
                    apiaryName = json.optString("apiary").trim().ifBlank { "Unassigned Yard" }, apiaryId = json.longOrNull("apiary_id")?.takeIf { db.apiaries().get(it) != null },
                    queenStatus = json.optString("queen_status", "Laying"),
                    queenMarkColor = json.optString("queen_mark_color"), queenOrigin = json.optString("queen_origin"),
                    queenAgeMonths = json.intOrNull("queen_age_months"), queenTemperament = json.optInt("queen_temperament", 3).coerceIn(1, 5),
                    strength = json.optInt("strength", 0).coerceIn(0, 10), mitePercent = json.optDouble("mite_percent", 0.0).coerceAtLeast(0.0),
                    tagUid = existing?.tagUid, updatedAt = incomingUpdatedAt,
                    deleted = existing?.deleted ?: json.optBoolean("deleted", false),
                    status = status,
                    deadAt = if (lifecycleWins) json.longOrNull("dead_at") else existing.deadAt,
                    statusChangedAt = if (lifecycleWins) incomingStatusChangedAt else existing.statusChangedAt
                ))
                // Older BeeKeep builds stored the tag on the hive document. Adopt it
                // into the assignment ledger so mixed-version fleets still resolve scans.
                val remoteTag = json.stringOrNull("tag_uid")
                if (remoteTag != null && status == HiveStatus.ACTIVE) {
                    val localActive = db.nfcTagAssignments().activeForHive(doc.entity_id)
                    if (localActive == null || !localActive.tagUid.equals(remoteTag, ignoreCase = true)) {
                        applyAssignment(NfcTagAssignmentEntity(IdGenerator.nextLong(), remoteTag, doc.entity_id, incomingUpdatedAt))
                    }
                }
                db.hives().refreshTagCache(doc.entity_id)
            }
            "nfc_assignment" -> {
                val incoming = NfcTagAssignmentEntity(
                    id = json.optLong("id", doc.entity_id),
                    tagUid = json.optString("tag_uid"),
                    hiveId = json.optLong("hive_id"),
                    assignedAt = json.optLong("assigned_at", doc.updated_at),
                    unassignedAt = json.longOrNull("unassigned_at")
                )
                if (incoming.tagUid.isNotBlank() && db.hives().exists(incoming.hiveId)) applyAssignment(incoming)
            }
            "inspection" -> if (db.hives().exists(json.optLong("hive_id"))) db.inspections().upsert(InspectionEntity(
                id=json.optLong("id",doc.entity_id), hiveId=json.optLong("hive_id"), createdAt=json.optLong("created_at",doc.updated_at),
                strength=json.optInt("strength").coerceIn(0, 10), queenStatus=json.optString("queen_status"), miteCount=json.optInt("mite_count").coerceAtLeast(0), sampleSize=json.optInt("sample_size",1).coerceAtLeast(1),
                notes=json.optString("notes"), photoPath=json.stringOrNull("photo_path"), latitude=json.doubleOrNull("latitude"), longitude=json.doubleOrNull("longitude"),
                emergencyCells=json.optInt("emergency_cells").coerceAtLeast(0), supercedureCells=json.optInt("supercedure_cells").coerceAtLeast(0), swarmCells=json.optInt("swarm_cells").coerceAtLeast(0), eggs=json.optInt("eggs").coerceAtLeast(0), openBrood=json.optInt("open_brood").coerceAtLeast(0), cappedBrood=json.optInt("capped_brood").coerceAtLeast(0), honeyStores=json.optInt("honey_stores").coerceAtLeast(0), pollen=json.optInt("pollen").coerceAtLeast(0), emptyDrawnComb=json.optInt("empty_drawn_comb").coerceAtLeast(0), diseaseFlags=json.optString("disease_flags"), updatedAt=json.optLong("updated_at",doc.updated_at)
            )) else Unit
            "feeding" -> if (db.hives().exists(json.optLong("hive_id"))) db.feedings().upsert(FeedingEntity(json.optLong("id",doc.entity_id),json.optLong("hive_id"),json.optLong("created_at",doc.updated_at),json.optString("feed_type"),json.optString("ratio"),json.optDouble("amount").coerceAtLeast(0.0),json.optString("unit"),json.optString("notes"))) else Unit
            "treatment" -> if (db.hives().exists(json.optLong("hive_id"))) db.treatments().upsert(TreatmentEntity(json.optLong("id",doc.entity_id),json.optLong("hive_id"),json.optLong("created_at",doc.updated_at),json.optString("treatment_type"),json.optString("product"),json.longOrNull("inserted_at"),json.longOrNull("removal_at"),json.longOrNull("withdrawal_until"),json.optString("notes"))) else Unit
            "harvest" -> if (db.hives().exists(json.optLong("hive_id"))) db.harvests().upsert(HarvestEntity(json.optLong("id",doc.entity_id),json.optLong("hive_id"),json.optLong("created_at",doc.updated_at),json.optInt("supers_pulled").coerceAtLeast(0),json.optDouble("wet_honey_weight").coerceAtLeast(0.0),json.optDouble("dry_honey_weight").coerceAtLeast(0.0),json.optString("weight_unit","lb"),json.optDouble("wax_weight").coerceAtLeast(0.0),json.optDouble("propolis_weight").coerceAtLeast(0.0),json.optString("notes"))) else Unit
            "event" -> if (db.hives().exists(json.optLong("hive_id"))) db.events().upsert(ActivityEventEntity(json.optLong("id",doc.entity_id),json.optLong("hive_id"),json.optLong("created_at",doc.updated_at),json.optString("type"),json.optString("title"),json.optString("detail"))) else Unit
        }
    }

    private suspend fun applyAssignment(incoming: NfcTagAssignmentEntity) {
        db.withTransaction {
            val merge = NfcAssignmentResolver.merge(
                tagConflict = db.nfcTagAssignments().activeByTag(incoming.tagUid),
                hiveConflict = db.nfcTagAssignments().activeForHive(incoming.hiveId),
                incoming = incoming
            )
            merge.toClose.forEach { db.nfcTagAssignments().upsert(it) }
            db.nfcTagAssignments().upsert(merge.incoming)
            (merge.toClose.map { it.hiveId } + merge.incoming.hiveId).distinct().forEach { db.hives().refreshTagCache(it) }
        }
    }

    private suspend fun enqueue(type: String, entityId: Long, operation: String, payload: String) {
        if (type in setOf("hive", "apiary", "nfc_assignment")) {
            db.outbox().deletePendingForEntity(type, entityId)
        }
        db.outbox().enqueue(SyncOutboxEntity(IdGenerator.nextLong(), type, entityId, operation, payload))
    }

    private fun hivePayload(e: HiveEntity) = JSONObject().put("id",e.id).put("number",e.number).put("apiary",e.apiaryName).put("apiary_id",e.apiaryId).put("queen_status",e.queenStatus).put("queen_mark_color",e.queenMarkColor).put("queen_origin",e.queenOrigin).put("queen_age_months",e.queenAgeMonths).put("queen_temperament",e.queenTemperament).put("strength",e.strength).put("mite_percent",e.mitePercent).put("tag_uid",e.tagUid).put("updated_at",e.updatedAt).put("deleted",e.deleted).put("status",e.status).put("dead_at",e.deadAt).put("status_changed_at",e.statusChangedAt).toString()
    private fun nfcAssignmentPayload(e: NfcTagAssignmentEntity) = JSONObject().put("id",e.id).put("tag_uid",e.tagUid).put("hive_id",e.hiveId).put("assigned_at",e.assignedAt).put("unassigned_at",e.unassignedAt).put("updated_at",maxOf(e.assignedAt, e.unassignedAt ?: 0L)).toString()
    private fun apiaryPayload(e: ApiaryEntity) = JSONObject().put("id",e.id).put("name",e.name).put("notes",e.notes).put("latitude",e.latitude).put("longitude",e.longitude).put("forage_notes",e.forageNotes).put("water_notes",e.waterNotes).put("updated_at",e.updatedAt).put("deleted",e.deleted).toString()
    private fun inspectionPayload(e: InspectionEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("strength",e.strength).put("queen_status",e.queenStatus).put("mite_count",e.miteCount).put("sample_size",e.sampleSize).put("notes",e.notes)
        // Local device paths are never valid on another device. Photos sync separately.
        .put("photo_path", JSONObject.NULL).put("latitude",e.latitude).put("longitude",e.longitude).put("emergency_cells",e.emergencyCells).put("supercedure_cells",e.supercedureCells).put("swarm_cells",e.swarmCells).put("eggs",e.eggs).put("open_brood",e.openBrood).put("capped_brood",e.cappedBrood).put("honey_stores",e.honeyStores).put("pollen",e.pollen).put("empty_drawn_comb",e.emptyDrawnComb).put("disease_flags",e.diseaseFlags).put("updated_at",e.updatedAt).toString()
    private fun feedingPayload(e: FeedingEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("feed_type",e.feedType).put("ratio",e.ratio).put("amount",e.amount).put("unit",e.unit).put("notes",e.notes).toString()
    private fun treatmentPayload(e: TreatmentEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("treatment_type",e.treatmentType).put("product",e.product).put("inserted_at",e.insertedAt).put("removal_at",e.removalAt).put("withdrawal_until",e.withdrawalUntil).put("notes",e.notes).toString()
    private fun harvestPayload(e: HarvestEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("supers_pulled",e.supersPulled).put("wet_honey_weight",e.wetHoneyWeight).put("dry_honey_weight",e.dryHoneyWeight).put("weight_unit",e.weightUnit).put("wax_weight",e.waxWeight).put("propolis_weight",e.propolisWeight).put("notes",e.notes).toString()
    private fun eventPayload(e: ActivityEventEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("type",e.type).put("title",e.title).put("detail",e.detail).toString()

    private fun JSONObject.doubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null
    private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

    private fun toHive(e: HiveEntity) = Hive(e.id, e.number, e.apiaryName, e.queenStatus, e.queenMarkColor, e.queenOrigin, e.queenAgeMonths, e.queenTemperament, e.strength, e.mitePercent, e.tagUid, e.status, e.deadAt)
    private fun toInspection(e: InspectionEntity) = Inspection(e.id, e.hiveId, e.createdAt, e.strength, e.queenStatus, e.miteCount, e.sampleSize, e.notes, e.photoPath, e.latitude, e.longitude, e.emergencyCells, e.supercedureCells, e.swarmCells, e.eggs, e.openBrood, e.cappedBrood, e.honeyStores, e.pollen, e.emptyDrawnComb, e.diseaseFlags)
    private fun toFeeding(e: FeedingEntity) = Feeding(e.id, e.hiveId, e.createdAt, e.feedType, e.ratio, e.amount, e.unit, e.notes)
    private fun toTreatment(e: TreatmentEntity) = Treatment(e.id, e.hiveId, e.createdAt, e.treatmentType, e.product, e.insertedAt, e.removalAt, e.withdrawalUntil, e.notes)
    private fun toHarvest(e: HarvestEntity) = Harvest(e.id, e.hiveId, e.createdAt, e.supersPulled, e.wetHoneyWeight, e.dryHoneyWeight, e.weightUnit, e.waxWeight, e.propolisWeight, e.notes)
    private fun toApiary(e: ApiaryEntity) = Apiary(e.id, e.name, e.notes, e.latitude, e.longitude, e.forageNotes, e.waterNotes)
    private fun toEvent(e: ActivityEventEntity) = ActivityEvent(e.id, e.hiveId, e.createdAt, e.type, e.title, e.detail)
}
