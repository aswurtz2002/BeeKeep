package com.beekeep.app.data

import com.beekeep.app.nfc.NfcUid

import com.beekeep.app.ColonyStrength
import com.beekeep.app.BeeKeepEntitlements

import android.content.Context
import androidx.room.withTransaction
import com.beekeep.app.cloud.CloudDocumentRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
class LocalHiveRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
    private val inspectionNoteDraftPrefs = appContext.getSharedPreferences(INSPECTION_NOTE_DRAFTS, Context.MODE_PRIVATE)
    private val initialTeamWorkspaceId = prefs.getString("active_team_workspace_id", null)
        ?.trim()?.takeIf { it.isNotEmpty() }
    private val _activeWorkspaceId = MutableStateFlow(initialTeamWorkspaceId)
    val activeWorkspaceId: StateFlow<String?> = _activeWorkspaceId.asStateFlow()
    private val _activeWorkspaceReadOnly = MutableStateFlow(
        prefs.getBoolean("active_team_workspace_read_only", false)
    )
    val activeWorkspaceReadOnly: StateFlow<Boolean> = _activeWorkspaceReadOnly.asStateFlow()
    private val _readOnlyWriteAttempt = MutableStateFlow(0L)
    val readOnlyWriteAttempt: StateFlow<Long> = _readOnlyWriteAttempt.asStateFlow()
    private val _database = MutableStateFlow(
        BeeKeepRoomDb.getForWorkspace(appContext, initialTeamWorkspaceId)
    )
    private val db: BeeKeepRoomDb get() = _database.value

    /**
     * Moves the live repository to a distinct database for a team. Personal/offline records
     * remain in beekeep_room.db and are never merged into a team's local dataset.
     */
    suspend fun switchWorkspace(workspaceId: String?, readOnly: Boolean? = null) = withContext(Dispatchers.IO) {
        val cleanId = workspaceId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanReadOnly = cleanId != null && (readOnly
            ?: prefs.getBoolean("active_team_workspace_read_only", false))
        if (_activeWorkspaceId.value == cleanId && _activeWorkspaceReadOnly.value == cleanReadOnly) return@withContext
        val nextDatabase = BeeKeepRoomDb.getForWorkspace(appContext, cleanId)
        check(prefs.edit()
            .putString("active_team_workspace_id", cleanId.orEmpty())
            .putBoolean("active_team_workspace_read_only", cleanReadOnly)
            .commit()) {
            "Could not save the active workspace selection."
        }
        _database.value = nextDatabase
        _activeWorkspaceId.value = cleanId
        _activeWorkspaceReadOnly.value = cleanReadOnly
        if (cleanId == null) {
            // Import any legacy data only into the personal/offline database.
            LegacyImporter.importIfNeeded(appContext, nextDatabase)
        }
    }

    suspend fun initialize() {
        // Team databases must never import private legacy data. An empty apiary is valid.
        if (_activeWorkspaceId.value == null) LegacyImporter.importIfNeeded(appContext, db)
    }

    /**
     * Wipe the local workspace while preserving cloud deletion tombstones. The tombstones
     * prevent data from returning on a later sync, and this runs as one Room transaction.
     */
    suspend fun resetAllData() = withContext(Dispatchers.IO) {
        checkWritable()
        db.withTransaction {
            val apiaries = db.apiaries().allForBackup()
            val hives = db.hives().allForBackup()
            val inspections = db.inspections().allForBackup()
            val feedings = db.feedings().allForBackup()
            val treatments = db.treatments().allForBackup()
            val harvests = db.harvests().allForBackup()
            val events = db.events().allForBackup()
            val assignments = db.nfcTagAssignments().allForBackup()
            val pendingOutbox = db.outbox().allPendingForReset()
            val activeDraftWorkspaceId = _activeWorkspaceId.value
            val draftScopePrefix = "${inspectionNoteDraftScope(activeDraftWorkspaceId)}_hive_"
            val scopedDraftIds = inspectionNoteDraftPrefs.all.keys
                .asSequence()
                .filter { it.startsWith(draftScopePrefix) }
                .mapNotNull { it.removePrefix(draftScopePrefix).toLongOrNull() }
                .filter { it > 0L }
                .toList()
            val legacyPersonalDraftIds = if (activeDraftWorkspaceId == null) {
                inspectionNoteDraftPrefs.all.keys.asSequence()
                    .filter { it.startsWith("hive_") }
                    .mapNotNull { it.removePrefix("hive_").toLongOrNull() }
                    .filter { it > 0L }
                    .toList()
            } else emptyList()

            val resetAt = System.currentTimeMillis()
            val recordsToDelete = linkedSetOf<Pair<String, Long>>()
            apiaries.forEach { recordsToDelete.add("apiary" to it.id) }
            hives.forEach { recordsToDelete.add("hive" to it.id) }
            inspections.forEach { recordsToDelete.add("inspection" to it.id) }
            feedings.forEach { recordsToDelete.add("feeding" to it.id) }
            treatments.forEach { recordsToDelete.add("treatment" to it.id) }
            harvests.forEach { recordsToDelete.add("harvest" to it.id) }
            events.forEach { recordsToDelete.add("event" to it.id) }
            assignments.forEach { recordsToDelete.add("nfc_assignment" to it.id) }
            // Convert queued writes to deletes too, including changes for records already
            // removed locally, so a pending upload can never recreate a wiped record.
            pendingOutbox.forEach { recordsToDelete.add(it.entityType to it.entityId) }
            (scopedDraftIds + legacyPersonalDraftIds).distinct().forEach {
                recordsToDelete.add("inspection_note_draft" to it)
            }

            // Delete children before parent records to respect all Room foreign keys.
            db.events().clearAll()
            db.harvests().clearAll()
            db.treatments().clearAll()
            db.feedings().clearAll()
            db.inspections().clearAll()
            db.nfcTagAssignments().clearAll()
            db.tasks().clearAll()
            db.hives().clearAll()
            db.apiaries().clearAll()
            db.outbox().clearAll()

            recordsToDelete.forEach { (type, id) ->
                val draftKey = inspectionNoteDraftKey(activeDraftWorkspaceId, id)
                val tombstoneAt = if (type == "inspection_note_draft") {
                    maxOf(resetAt, inspectionNoteDraftPrefs.getLong(inspectionNoteDraftUpdatedAtKey(draftKey), 0L) + 1L)
                } else resetAt
                val payload = JSONObject()
                    .put("id", id)
                    .put("updated_at", tombstoneAt)
                    .put("deleted", true)
                db.outbox().enqueue(
                    SyncOutboxEntity(
                        id = IdGenerator.nextLong(),
                        entityType = type,
                        entityId = id,
                        operation = "delete",
                        payload = payload.toString(),
                        createdAt = tombstoneAt
                    )
                )
            }

            val draftEditor = inspectionNoteDraftPrefs.edit()
            (scopedDraftIds + legacyPersonalDraftIds).distinct().forEach { hiveId ->
                val draftKey = inspectionNoteDraftKey(activeDraftWorkspaceId, hiveId)
                draftEditor.remove(draftKey)
                    .remove(inspectionNoteDraftRecordedAtKey(draftKey))
                    .remove(inspectionNoteDraftUpdatedAtKey(draftKey))
                    .remove(inspectionNoteDraftPendingKey(draftKey))
                if (activeDraftWorkspaceId == null) {
                    draftEditor.remove("hive_$hiveId")
                        .remove("hive_${hiveId}_recorded_at")
                }
            }
            draftEditor.apply()
        }

    }

    fun observeHives(): Flow<List<Hive>> = _database.flatMapLatest { it.hives().observeAll() }.map { it.map(::toHive) }
    fun observeDeadHives(): Flow<List<Hive>> = _database.flatMapLatest { it.hives().observeDead() }.map { it.map(::toHive) }
    fun observeApiaries(): Flow<List<Apiary>> = _database.flatMapLatest { it.apiaries().observeAll() }.map { it.map(::toApiary) }
    fun observeInspections(hiveId: Long): Flow<List<Inspection>> = _database.flatMapLatest { it.inspections().observeForHive(hiveId) }.map { it.map(::toInspection) }
    fun observeAllInspections(): Flow<List<Inspection>> = _database.flatMapLatest { it.inspections().observeAll() }.map { it.map(::toInspection) }
    fun observeFeedings(hiveId: Long): Flow<List<Feeding>> = _database.flatMapLatest { it.feedings().observeForHive(hiveId) }.map { it.map(::toFeeding) }
    fun observeTreatments(hiveId: Long): Flow<List<Treatment>> = _database.flatMapLatest { it.treatments().observeForHive(hiveId) }.map { it.map(::toTreatment) }
    fun observeHarvests(hiveId: Long): Flow<List<Harvest>> = _database.flatMapLatest { it.harvests().observeForHive(hiveId) }.map { it.map(::toHarvest) }
    fun observeEvents(hiveId: Long): Flow<List<ActivityEvent>> = _database.flatMapLatest { it.events().observeForHive(hiveId) }.map { it.map(::toEvent) }
    fun observeAllFeedings(): Flow<List<Feeding>> = _database.flatMapLatest { it.feedings().observeAll() }.map { it.map(::toFeeding) }
    fun observeAllTreatments(): Flow<List<Treatment>> = _database.flatMapLatest { it.treatments().observeAll() }.map { it.map(::toTreatment) }
    fun observeAllHarvests(): Flow<List<Harvest>> = _database.flatMapLatest { it.harvests().observeAll() }.map { it.map(::toHarvest) }

    suspend fun getHive(id: Long): Hive? = withContext(Dispatchers.IO) { db.hives().get(id)?.let(::toHive) }
    suspend fun getAllKnownNfcTagAssignments(): List<Pair<String?, Long>> = withContext(Dispatchers.IO) {
        NfcAssignmentResolver.currentOwners(
            assignmentHistory = db.nfcTagAssignments().allForBackup(),
            hiveCache = db.hives().allForBackup()
        )
    }

    suspend fun findHiveByNfc(tag: String): Hive? = withContext(Dispatchers.IO) {
        val uid = NfcUid.normalize(tag) ?: return@withContext null
        // Use one consistent owner index for scans, duplicate checks and writes.
        // The assignment ledger wins over stale cached tag_uid values from older builds.
        val owners = NfcAssignmentResolver.currentOwners(
            assignmentHistory = db.nfcTagAssignments().allForBackup(),
            hiveCache = db.hives().allForBackup()
        ).filter { NfcUid.matches(it.first, uid) }
            .map { it.second }
            .distinct()
        // Refuse ambiguous identifiers rather than choosing a hive by query order.
        if (owners.size != 1) return@withContext null
        db.hives().get(owners.single())
            ?.takeIf { it.status == HiveStatus.ACTIVE && !it.deleted }
            ?.let(::toHive)
    }

    suspend fun isNfcTagAmbiguous(uid: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = NfcUid.normalize(uid) ?: return@withContext false
        NfcAssignmentResolver.currentOwners(
            assignmentHistory = db.nfcTagAssignments().allForBackup(),
            hiveCache = db.hives().allForBackup()
        ).filter { NfcUid.matches(it.first, normalized) }
            .map { it.second }
            .distinct()
            .size > 1
    }

    suspend fun isNfcTagUnassigned(uid: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = NfcUid.normalize(uid) ?: return@withContext false
        val history = db.nfcTagAssignments().allForBackup()
            .filter { NfcUid.matches(it.tagUid, normalized) }
        history.isNotEmpty() && history.none { it.isActive }
    }

    suspend fun getUnassignedNfcTagUids(): Set<String> = withContext(Dispatchers.IO) {
        db.nfcTagAssignments().allUnassignedTagUids()
            .mapNotNull(NfcUid::normalize)
            .toSet()
    }
    suspend fun findHiveByNumberAndApiary(number: String, apiary: String): Hive? = withContext(Dispatchers.IO) {
        db.hives().byNumberAndApiary(number.trim(), apiary.trim()).let { it?.let(::toHive) }
    }

    /**
     * Create first-run apiaries and their hives together so the setup is all-or-nothing.
     * Every created record is queued through the same outbox used by ordinary edits.
     */
    suspend fun createStarterSetup(
        apiaryNames: List<String>,
        hiveNumbersByApiary: List<List<String>>,
        allowExistingWorkspace: Boolean = false
    ): List<Long> = withContext(Dispatchers.IO) {
        checkWritable()
        require(apiaryNames.isNotEmpty()) { "Create at least one apiary." }
        require(apiaryNames.size == hiveNumbersByApiary.size) { "Each apiary needs a hive-name list." }

        val cleanApiaryNames = apiaryNames.map(String::trim)
        require(cleanApiaryNames.all(String::isNotBlank)) { "Apiary names cannot be blank." }
        require(cleanApiaryNames.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == cleanApiaryNames.size) {
            "Apiary names must be unique."
        }

        val cleanHiveNumbers = hiveNumbersByApiary.map { numbers ->
            val clean = numbers.map(String::trim)
            require(clean.all(String::isNotBlank)) { "Hive names cannot be blank." }
            require(clean.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == clean.size) {
                "Hive names must be unique within each apiary."
            }
            clean
        }

        val createdHiveIds = mutableListOf<Long>()
        db.withTransaction {
            val existingApiaries = db.apiaries().allForBackup()
            val existingHives = db.hives().allForBackup()
            val apiaryLimit = BeeKeepEntitlements.apiaryLimit(appContext)
            val currentApiaryCount = existingApiaries.count { !it.deleted }
            require(currentApiaryCount + cleanApiaryNames.size <= apiaryLimit) {
                BeeKeepEntitlements.apiaryLimitMessage(appContext)
            }
            if (!allowExistingWorkspace) {
                require(existingApiaries.isEmpty() && existingHives.isEmpty()) {
                    "Starter setup is only available when the workspace is empty. Existing records were left unchanged."
                }
            } else {
                val existingApiaryNames = existingApiaries
                    .map { it.name.trim().lowercase(java.util.Locale.ROOT) }
                    .toSet()
                val duplicateApiaryName = cleanApiaryNames.firstOrNull {
                    it.lowercase(java.util.Locale.ROOT) in existingApiaryNames
                }
                require(duplicateApiaryName == null) {
                    "An apiary named \"$duplicateApiaryName\" already exists. Choose a different name."
                }

                val existingHiveKeys = existingHives
                    .filter { !it.deleted && it.status == HiveStatus.ACTIVE }
                    .map {
                        it.apiaryName.trim().lowercase(java.util.Locale.ROOT) to
                            it.number.trim().lowercase(java.util.Locale.ROOT)
                    }
                    .toSet()
                cleanApiaryNames.forEachIndexed { index, apiaryName ->
                    val apiaryKey = apiaryName.lowercase(java.util.Locale.ROOT)
                    val duplicateHiveNumber = cleanHiveNumbers[index].firstOrNull { hiveNumber ->
                        (apiaryKey to hiveNumber.lowercase(java.util.Locale.ROOT)) in existingHiveKeys
                    }
                    require(duplicateHiveNumber == null) {
                        "Hive $duplicateHiveNumber already exists in $apiaryName."
                    }
                }
            }

            val requestedHiveCount = cleanHiveNumbers.sumOf { it.size }
            val lockedIds = BeeKeepEntitlements.lockedHiveIds(appContext)
            val currentHiveCount = BeeKeepEntitlements.countHivesSubjectToFreeLimit(
                existingHives.filter { !it.deleted }.map { it.id },
                lockedIds
            )
            val hiveLimit = BeeKeepEntitlements.hiveLimit(appContext)
            require(requestedHiveCount == 0 || currentHiveCount + requestedHiveCount <= hiveLimit) {
                BeeKeepEntitlements.hiveLimitMessage(appContext)
            }

            val now = System.currentTimeMillis()
            cleanApiaryNames.forEachIndexed { index, apiaryName ->
                val apiary = ApiaryEntity(
                    id = IdGenerator.nextLong(),
                    name = apiaryName,
                    updatedAt = now
                )
                db.apiaries().upsert(apiary)
                enqueue("apiary", apiary.id, "upsert", apiaryPayload(apiary))

                cleanHiveNumbers[index].forEach { hiveNumber ->
                    val hive = HiveEntity(
                        id = IdGenerator.nextLong(),
                        number = hiveNumber,
                        apiaryName = apiary.name,
                        apiaryId = apiary.id,
                        queenStatus = "Laying",
                        strength = 5,
                        mitePercent = 0.0,
                        updatedAt = now,
                        statusChangedAt = now
                    )
                    db.hives().upsert(hive)
                    enqueue("hive", hive.id, "upsert", hivePayload(hive))
                    createdHiveIds += hive.id
                }
            }
        }
        createdHiveIds
    }

    suspend fun saveHive(h: Hive) = withContext(Dispatchers.IO) {
        checkWritable()
        require(h.number.trim().isNotBlank()) { "Hive number cannot be blank" }
        require(h.mitePercent.isFinite() && h.mitePercent >= 0.0) { "Mite percentage must be a valid non-negative number." }
        requireValidCoordinates(h.latitude, h.longitude, "Hive")
        db.withTransaction {
        val existing = db.hives().get(h.id)
        if (existing == null) {
            val lockedIds = BeeKeepEntitlements.lockedHiveIds(appContext)
            val currentHiveCount = BeeKeepEntitlements.countHivesSubjectToFreeLimit(
                db.hives().allForBackup().filter { !it.deleted }.map { it.id },
                lockedIds
            )
            val hiveLimit = BeeKeepEntitlements.hiveLimit(appContext)
            require(currentHiveCount < hiveLimit) {
                BeeKeepEntitlements.hiveLimitMessage(appContext)
            }
        }
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
            statusChangedAt = existing?.statusChangedAt ?: now,
            latitude = if (h.latitude != null && h.longitude != null) h.latitude else existing?.latitude,
            longitude = if (h.latitude != null && h.longitude != null) h.longitude else existing?.longitude,
            beeCountFrames = h.beeCountFrames ?: existing?.beeCountFrames
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

    suspend fun updateHiveLocation(hiveId: Long, latitude: Double, longitude: Double) = withContext(Dispatchers.IO) {
        checkWritable()
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Latitude is outside the valid range." }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Longitude is outside the valid range." }
        db.withTransaction {
            val existing = db.hives().get(hiveId) ?: throw IllegalArgumentException("Hive not found.")
            val updated = existing.copy(
                latitude = latitude,
                longitude = longitude,
                updatedAt = System.currentTimeMillis()
            )
            db.hives().upsert(updated)
            enqueue("hive", hiveId, "upsert", hivePayload(updated))
        }
    }

    suspend fun markHiveDead(hiveId: Long) = withContext(Dispatchers.IO) {
        checkWritable()
        db.withTransaction {
            val hive = db.hives().get(hiveId) ?: throw IllegalArgumentException("Hive not found.")
            if (hive.status == HiveStatus.DEAD) return@withTransaction
            val now = System.currentTimeMillis()
            val dead = hive.copy(status = HiveStatus.DEAD, deadAt = now, statusChangedAt = now, tagUid = null, updatedAt = now)
            db.hives().upsert(dead)
            enqueue("hive", hiveId, "upsert", hivePayload(dead))
            // A legacy/corrupt ledger can contain more than one active tag for the
            // same colony. Marking the colony dead must release every one of them.
            db.nfcTagAssignments().activeAssignmentsForHive(hiveId).forEach { assignment ->
                val closed = assignment.copy(unassignedAt = maxOf(now, assignment.assignedAt))
                db.nfcTagAssignments().upsert(closed)
                enqueue("nfc_assignment", closed.id, "upsert", nfcAssignmentPayload(closed))
            }
            val event = ActivityEventEntity(IdGenerator.nextLong(), hiveId, now, "lifecycle", "Colony marked dead", "History preserved. NFC tag released.")
            db.events().upsert(event)
            enqueue("event", event.id, "upsert", eventPayload(event))
        }
    }

    suspend fun restoreHive(hiveId: Long) = withContext(Dispatchers.IO) {
        checkWritable()
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
        checkWritable()
        db.withTransaction {
            db.hives().get(hiveId) ?: throw IllegalArgumentException("Hive not found.")
            val deletionTime = System.currentTimeMillis()
            enqueueHiveHistoryDeletions(hiveId, deletionTime)
            permanentlyDeleteHiveLocal(hiveId)
            enqueue("hive", hiveId, "delete", JSONObject().put("id", hiveId).put("updated_at", deletionTime).toString())
        }
    }


    /**
     * Keep cloud history in sync with a permanent local deletion.
     * Tombstones contain identifiers and timestamps only, not the deleted record's notes.
     */
    private suspend fun enqueueHiveHistoryDeletions(hiveId: Long, deletionTime: Long) {
        fun tombstone(id: Long) = JSONObject()
            .put("id", id)
            .put("hive_id", hiveId)
            .put("updated_at", deletionTime)
            .toString()

        db.inspections().listForHiveForDelete(hiveId).forEach {
            enqueue("inspection", it.id, "delete", tombstone(it.id))
        }
        db.feedings().listForHiveForDelete(hiveId).forEach {
            enqueue("feeding", it.id, "delete", tombstone(it.id))
        }
        db.treatments().listForHiveForDelete(hiveId).forEach {
            enqueue("treatment", it.id, "delete", tombstone(it.id))
        }
        db.harvests().listForHiveForDelete(hiveId).forEach {
            enqueue("harvest", it.id, "delete", tombstone(it.id))
        }
        db.events().listForHiveForDelete(hiveId).forEach {
            enqueue("event", it.id, "delete", tombstone(it.id))
        }
        db.nfcTagAssignments().listForHiveForDelete(hiveId).forEach {
            enqueue("nfc_assignment", it.id, "delete", tombstone(it.id))
        }
        // The live field-note buffer is a separate cloud document from saved inspections.
        enqueue("inspection_note_draft", hiveId, "delete", tombstone(hiveId))
    }

    private suspend fun permanentlyDeleteHiveLocal(hiveId: Long) {
        clearLocalInspectionNoteDraft(hiveId, System.currentTimeMillis())
        db.events().deleteForHive(hiveId)
        // inspections, feedings, treatments, harvests and NFC assignments cascade.
        db.hives().hardDelete(hiveId)
    }

    suspend fun deleteApiaryPermanently(apiaryId: Long) = withContext(Dispatchers.IO) {
        checkWritable()
        db.withTransaction {
            val apiary = db.apiaries().get(apiaryId)
                ?: throw IllegalArgumentException("Apiary not found.")

            // Include both current and legacy hives linked by apiary name.
            val apiaryHives = db.hives().forApiaryRename(apiary.id, apiary.name)
            val deletionTime = System.currentTimeMillis()
            apiaryHives.forEach { hive ->
                enqueueHiveHistoryDeletions(hive.id, deletionTime)
                permanentlyDeleteHiveLocal(hive.id)
                enqueue(
                    "hive",
                    hive.id,
                    "delete",
                    JSONObject().put("id", hive.id).put("updated_at", deletionTime).toString()
                )
            }

            // Remove the map pin locally and sync a tombstone so connected devices
            // delete the same apiary instead of restoring it on their next sync.
            val tombstone = apiary.copy(deleted = true, updatedAt = deletionTime)
            enqueue("apiary", apiary.id, "delete", apiaryPayload(tombstone))
            db.apiaries().hardDelete(apiary.id)
        }
    }

    suspend fun assignNfcTag(hiveId: Long, uid: String, reassign: Boolean = false) = withContext(Dispatchers.IO) {
        checkWritable()
        val normalized = NfcUid.normalize(uid)
            ?: throw IllegalArgumentException("The NFC tag has no valid readable UID.")
        db.withTransaction {
            val hive = db.hives().get(hiveId) ?: throw IllegalArgumentException("Hive not found.")
            require(hive.status == HiveStatus.ACTIVE) { "Cannot assign an NFC tag to a ${hive.status.lowercase()} colony." }

            val assignmentHistory = db.nfcTagAssignments().allForBackup()
            val ledgerHistoryHiveIds = assignmentHistory.map { it.hiveId }.toSet()
            val ledgerHistoryUids = assignmentHistory.mapNotNull { NfcUid.normalize(it.tagUid) }.toSet()
            val tagConflicts = assignmentHistory
                .filter { it.isActive && NfcUid.matches(it.tagUid, normalized) }
            val matchingCacheOwners = db.hives().allForBackup()
                .filter { !it.deleted && it.id != hiveId && NfcUid.matches(it.tagUid, normalized) }
            // A cache-only owner is valid only for a hive and UID never tracked by
            // the ledger. Closed ledger rows explicitly mean the tag was released.
            val cachedOwners = matchingCacheOwners.filter {
                it.id !in ledgerHistoryHiveIds && normalized !in ledgerHistoryUids
            }
            val ledgerOwners = tagConflicts.map { it.hiveId }.toSet()
            val legacyOnlyOwners = cachedOwners.filter { it.id !in ledgerOwners }
            val foreignOwnerIds = (tagConflicts.map { it.hiveId } + legacyOnlyOwners.map { it.id })
                .distinct()
                .filter { it != hiveId }
            if (foreignOwnerIds.isNotEmpty() && !reassign) {
                val ownerHiveId = foreignOwnerIds.first()
                val ownerHive = db.hives().get(ownerHiveId)
                throw IllegalArgumentException(
                    "This NFC tag is already linked to ${ownerHive?.number ?: ownerHiveId}. No assignment was changed."
                )
            }

            val targetAssignments = db.nfcTagAssignments().activeAssignmentsForHive(hiveId)
            val alreadyCorrect = tagConflicts.size == 1 &&
                tagConflicts.single().hiveId == hiveId &&
                targetAssignments.size == 1 &&
                NfcUid.matches(targetAssignments.single().tagUid, normalized) &&
                matchingCacheOwners.isEmpty()
            if (alreadyCorrect) return@withTransaction

            val now = System.currentTimeMillis()

            // Clear stale copies of this UID only after the ledger confirms that no
            // other hive currently owns it. A released UID must be reusable even if
            // an older hive row still contains its former tag_uid cache value.
            matchingCacheOwners.forEach { cachedHive ->
                db.hives().refreshTagCache(cachedHive.id)
                val refreshed = db.hives().get(cachedHive.id) ?: return@forEach
                val repaired = refreshed.copy(updatedAt = now)
                db.hives().upsert(repaired)
                enqueue("hive", cachedHive.id, "upsert", hivePayload(repaired))
            }
            // Close every active ledger conflict, rather than trusting LIMIT 1 lookups.
            val rowsToClose = (tagConflicts + targetAssignments).distinctBy { it.id }
            rowsToClose.forEach { assignment ->
                if (!assignment.isActive) return@forEach
                val closed = assignment.copy(unassignedAt = now)
                db.nfcTagAssignments().upsert(closed)
                enqueue("nfc_assignment", closed.id, "upsert", nfcAssignmentPayload(closed))
            }

            // Some older builds wrote only hives.tag_uid. Preserve those released
            // identities in the ledger instead of leaving a stale cache owner behind.
            legacyOnlyOwners.forEach { legacyOwner ->
                val tombstone = NfcTagAssignmentEntity(
                    id = IdGenerator.nextLong(),
                    tagUid = normalized,
                    hiveId = legacyOwner.id,
                    assignedAt = legacyOwner.updatedAt.takeIf { it > 0L } ?: now,
                    unassignedAt = now
                )
                db.nfcTagAssignments().upsert(tombstone)
                enqueue("nfc_assignment", tombstone.id, "upsert", nfcAssignmentPayload(tombstone))
            }

            val previousOwners = (rowsToClose.map { it.hiveId } + legacyOnlyOwners.map { it.id })
                .distinct()
                .filter { it != hiveId }
            previousOwners.forEach { ownerHiveId ->
                db.hives().refreshTagCache(ownerHiveId)
                val ownerHive = db.hives().get(ownerHiveId)
                if (ownerHive != null) {
                    enqueue("hive", ownerHiveId, "upsert", hivePayload(ownerHive))
                }
                val ownerNumber = ownerHive?.number ?: ownerHiveId.toString()
                val event = ActivityEventEntity(
                    IdGenerator.nextLong(), ownerHiveId, now, "tag", "NFC tag removed",
                    "Tag $normalized reassigned from Hive $ownerNumber to Hive ${hive.number}"
                )
                db.events().upsert(event)
                enqueue("event", event.id, "upsert", eventPayload(event))
            }

            val assignment = NfcTagAssignmentEntity(IdGenerator.nextLong(), normalized, hiveId, now)
            db.nfcTagAssignments().upsert(assignment)
            db.hives().refreshTagCache(hiveId)
            val updatedHive = db.hives().get(hiveId)
            if (updatedHive != null) {
                enqueue("hive", hiveId, "upsert", hivePayload(updatedHive))
            }
            enqueue("nfc_assignment", assignment.id, "upsert", nfcAssignmentPayload(assignment))
            val event = ActivityEventEntity(IdGenerator.nextLong(), hiveId, now, "tag", "NFC tag assigned", "Tag $normalized")
            db.events().upsert(event)
            enqueue("event", event.id, "upsert", eventPayload(event))
        }
    }

    suspend fun unassignNfcTag(hiveId: Long): Boolean = withContext(Dispatchers.IO) {
        checkWritable()
        db.withTransaction {
            val hive = db.hives().get(hiveId)
                ?: throw IllegalArgumentException("Hive not found.")
            val activeAssignments = db.nfcTagAssignments().activeAssignmentsForHive(hiveId)
            val cachedTag = hive.tagUid?.trim()?.takeIf { it.isNotBlank() }
            val cachedTagHadActiveLedgerAssignment = cachedTag?.let { rawUid ->
                NfcUid.normalize(rawUid) != null &&
                    activeAssignments.any { NfcUid.matches(it.tagUid, rawUid) }
            } == true

            // Older app builds could save the tag only in hives.tag_uid without
            // creating a row in nfc_tag_assignments. Treat that cached UID as an
            // assignment too, otherwise Remove Tag would silently do nothing.
            if (activeAssignments.isEmpty() && cachedTag == null) {
                return@withTransaction false
            }

            val now = System.currentTimeMillis()
            activeAssignments.forEach { assignment ->
                val closed = assignment.copy(unassignedAt = now)
                db.nfcTagAssignments().upsert(closed)
                enqueue("nfc_assignment", closed.id, "upsert", nfcAssignmentPayload(closed))
            }

            // A legacy hive may have a cached UID but no ledger row. Create a closed
            // assignment for it so the stale BEEKEEP:HIVE payload can be recognized and
            // cleared on its next scan instead of reopening the former hive.
            if (cachedTag != null && !cachedTagHadActiveLedgerAssignment) {
                val legacyTombstone = NfcTagAssignmentEntity(
                    id = IdGenerator.nextLong(),
                    tagUid = NfcUid.normalize(cachedTag) ?: cachedTag.uppercase(java.util.Locale.ROOT),
                    hiveId = hiveId,
                    assignedAt = hive.updatedAt.takeIf { it > 0L } ?: now,
                    unassignedAt = now
                )
                db.nfcTagAssignments().upsert(legacyTombstone)
                enqueue("nfc_assignment", legacyTombstone.id, "upsert", nfcAssignmentPayload(legacyTombstone))
            }

            // Clear both the assignment ledger and the cached UID on the hive.
            // The explicit hive upsert also syncs legacy-cache cleanup to cloud.
            db.hives().refreshTagCache(hiveId)
            val refreshedHive = db.hives().get(hiveId)
                ?: throw IllegalArgumentException("Hive not found.")
            val clearedHive = refreshedHive.copy(tagUid = null, updatedAt = now)
            db.hives().upsert(clearedHive)
            enqueue("hive", hiveId, "upsert", hivePayload(clearedHive))

            val releasedTags = (activeAssignments.map { it.tagUid } + listOfNotNull(cachedTag))
                .distinctBy { NfcUid.normalize(it) ?: it.trim().uppercase(java.util.Locale.ROOT) }
            val detail = when (releasedTags.size) {
                0 -> "NFC tag assignment cleared and available for reuse"
                1 -> "Tag ${releasedTags.first()} unassigned and available for reuse"
                else -> "Tags ${releasedTags.joinToString()} unassigned and available for reuse"
            }
            val event = ActivityEventEntity(IdGenerator.nextLong(), hiveId, now, "tag", "NFC tag removed", detail)
            db.events().upsert(event)
            enqueue("event", event.id, "upsert", eventPayload(event))
            true
        }
    }

    suspend fun recordBeeCountChange(
        hiveId: Long,
        previousFrames: Double,
        newFrames: Double,
        changedAt: Long
    ) = withContext(Dispatchers.IO) {
        checkWritable()
        require(previousFrames.isFinite() && newFrames.isFinite()) { "Bee frame values must be valid numbers." }
        require(previousFrames in 0.5..10.0 && newFrames in 0.5..10.0) { "Bee frame values must be between 0.5 and 10." }
        require(previousFrames != newFrames) { "Bee frame count did not change." }
        db.withTransaction {
            if (db.hives().get(hiveId) == null) return@withTransaction
            val event = ActivityEventEntity(
                id = IdGenerator.nextLong(),
                hiveId = hiveId,
                createdAt = changedAt,
                type = "bee_count_change",
                title = "Bee count changed",
                detail = "Frames of bees changed from ${formatBeeCountFrames(previousFrames)} to ${formatBeeCountFrames(newFrames)}"
            )
            db.events().upsert(event)
            enqueue("event", event.id, "upsert", eventPayload(event))
        }
    }

    suspend fun saveInspection(i: Inspection) = withContext(Dispatchers.IO) {
        checkWritable()
        requireValidCoordinates(i.latitude, i.longitude, "Inspection")
        db.withTransaction {
            val safeSample = i.sampleSize.coerceAtLeast(1)
            val safeMites = i.miteCount.coerceIn(0, safeSample)
            val safeBeeCountFrames = i.beeCountFrames?.let {
                (it * 2.0).roundToInt().coerceIn(1, 20) / 2.0
            }
            val entity = InspectionEntity(
                id = i.id, hiveId = i.hiveId, createdAt = i.createdAt, strength = i.strength.coerceIn(0, 10),
                queenStatus = i.queenStatus.trim().ifBlank { "Laying" }, miteCount = safeMites, sampleSize = safeSample,
                notes = i.notes.trim(), latitude = i.latitude, longitude = i.longitude,
                emergencyCells = i.emergencyCells.coerceAtLeast(0), supercedureCells = i.supercedureCells.coerceAtLeast(0), swarmCells = i.swarmCells.coerceAtLeast(0),
                eggs = normalizeBroodStoresGauge(i.eggs), openBrood = normalizeBroodStoresGauge(i.openBrood), cappedBrood = normalizeBroodStoresGauge(i.cappedBrood),
                honeyStores = normalizeBroodStoresGauge(i.honeyStores), pollen = normalizeBroodStoresGauge(i.pollen), emptyDrawnComb = normalizeBroodStoresGauge(i.emptyDrawnComb),
                diseaseFlags = i.diseaseFlags.trim(), updatedAt = i.createdAt,
                beeCountFrames = safeBeeCountFrames
            )
            db.inspections().upsert(entity)
            db.hives().get(i.hiveId)?.let {
                // The inspection timestamp describes the observation, not when the hive record was edited.
                // Use wall-clock update time so backfilled inspections cannot make the hive look stale during sync.
                db.hives().upsert(it.copy(
                    strength = entity.strength,
                    queenStatus = entity.queenStatus,
                    mitePercent = entity.mitePercent,
                    beeCountFrames = entity.beeCountFrames,
                    updatedAt = System.currentTimeMillis()
                ))
            }
            val event = ActivityEventEntity(i.id, i.hiveId, i.createdAt, "inspection", "Inspection", "Strength ${ColonyStrength.gradeFromStored(entity.strength)}/5 • ${ColonyStrength.labelFromStored(entity.strength)} • Mites ${String.format(java.util.Locale.US, "%.2f", entity.mitePercent)}%")
            db.events().upsert(event)
            enqueue("inspection", i.id, "upsert", inspectionPayload(entity))
            val updatedHive = db.hives().get(i.hiveId) ?: return@withTransaction
            enqueue("hive", i.hiveId, "upsert", hivePayload(updatedHive))
            enqueue("event", event.id, "upsert", eventPayload(event))
        }
    }

    suspend fun saveFeeding(f: Feeding) = withContext(Dispatchers.IO) {
        checkWritable()
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
        checkWritable()
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
        checkWritable()
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
        checkWritable()
        val cleanName = a.name.trim()
        require(cleanName.isNotBlank()) { "Apiary name cannot be blank" }
        requireValidCoordinates(a.latitude, a.longitude, "Apiary")
        db.withTransaction {
            val existingByName = db.apiaries().byName(cleanName)
            if (existingByName != null && existingByName.id != a.id) {
                throw IllegalArgumentException("Apiary $cleanName already exists.")
            }
            val previous = db.apiaries().get(a.id)
            if (previous == null) {
                val activeApiaryCount = db.apiaries().allForBackup().count { !it.deleted }
                val apiaryLimit = BeeKeepEntitlements.apiaryLimit(appContext)
                require(activeApiaryCount < apiaryLimit) {
                    BeeKeepEntitlements.apiaryLimitMessage(appContext)
                }
            }
            val now = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: 0L) + 1L)
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


    suspend fun saveInspectionNoteDraft(
        hiveId: Long,
        notes: String,
        recordedAt: Long?,
        updatedAt: Long
    ): Long = withContext(Dispatchers.IO) {
        checkWritable()
        require(hiveId > 0L) { "The inspection note draft has an invalid hive ID." }
        require(updatedAt > 0L) { "The inspection note draft needs a valid update time." }

        val workspaceId = _activeWorkspaceId.value
        val key = inspectionNoteDraftKey(workspaceId, hiveId)
        val updatedAtKey = inspectionNoteDraftUpdatedAtKey(key)
        val previousUpdatedAt = inspectionNoteDraftPrefs.getLong(updatedAtKey, 0L)
        // Ensure a local edit wins over the draft snapshot it was based on, including when
        // a different device's latest note arrived while this screen was still being edited.
        val effectiveUpdatedAt = maxOf(updatedAt, System.currentTimeMillis(), previousUpdatedAt + 1L)
        val safeRecordedAt = recordedAt?.takeIf { it > 0L && notes.isNotBlank() }
        val pendingKey = inspectionNoteDraftPendingKey(key)
        val editor = inspectionNoteDraftPrefs.edit()
            .putString(key, notes)
            .putLong(updatedAtKey, effectiveUpdatedAt)
            .putBoolean(pendingKey, true)
        if (safeRecordedAt != null) editor.putLong(inspectionNoteDraftRecordedAtKey(key), safeRecordedAt)
        else editor.remove(inspectionNoteDraftRecordedAtKey(key))
        editor.apply()

        enqueue(
            "inspection_note_draft",
            hiveId,
            "upsert",
            inspectionNoteDraftPayload(hiveId, notes, safeRecordedAt, effectiveUpdatedAt)
        )
        inspectionNoteDraftPrefs.edit().putBoolean(pendingKey, false).apply()
        effectiveUpdatedAt
    }

    /**
     * Recover edits that were saved locally just before the inspection screen was closed or
     * the process died, before its debounce could enqueue them. Called before each cloud pull.
     */
    suspend fun queuePendingInspectionNoteDrafts(): Int = withContext(Dispatchers.IO) {
        if (_activeWorkspaceReadOnly.value) return@withContext 0
        val prefix = "${inspectionNoteDraftScope(_activeWorkspaceId.value)}_hive_"
        val hiveIds = inspectionNoteDraftPrefs.all.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).toLongOrNull() }
            .filter { it > 0L }
            .distinct()
            .toList()
        var queued = 0
        for (hiveId in hiveIds) {
            val key = inspectionNoteDraftKey(_activeWorkspaceId.value, hiveId)
            val pendingKey = inspectionNoteDraftPendingKey(key)
            if (!inspectionNoteDraftPrefs.getBoolean(pendingKey, false)) continue
            val notes = inspectionNoteDraftPrefs.getString(key, "").orEmpty()
            val updatedAtKey = inspectionNoteDraftUpdatedAtKey(key)
            val localUpdatedAt = inspectionNoteDraftPrefs.getLong(updatedAtKey, 0L)
            val effectiveUpdatedAt = maxOf(System.currentTimeMillis(), localUpdatedAt + 1L)
            val recordedAt = inspectionNoteDraftPrefs.getLong(inspectionNoteDraftRecordedAtKey(key), 0L)
                .takeIf { it > 0L && notes.isNotBlank() }
            enqueue(
                "inspection_note_draft",
                hiveId,
                "upsert",
                inspectionNoteDraftPayload(hiveId, notes, recordedAt, effectiveUpdatedAt)
            )
            inspectionNoteDraftPrefs.edit()
                .putLong(updatedAtKey, effectiveUpdatedAt)
                .putBoolean(pendingKey, false)
                .apply()
            queued++
        }
        queued
    }

    suspend fun pendingSync(limit: Int = 50): List<SyncOutboxEntity> = db.outbox().pending(limit)
    suspend fun markSyncDone(id: Long) = db.outbox().setState(id, "done")
    suspend fun markSyncError(id: Long, message: String) = db.outbox().setState(id, "pending", message.take(500))
    suspend fun pruneSync() = db.outbox().pruneDone(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000)

    private fun clearLocalInspectionNoteDraft(hiveId: Long, deletedAt: Long) {
        val workspaceId = _activeWorkspaceId.value
        val key = inspectionNoteDraftKey(workspaceId, hiveId)
        val previousUpdatedAt = inspectionNoteDraftPrefs.getLong(inspectionNoteDraftUpdatedAtKey(key), 0L)
        if (deletedAt < previousUpdatedAt) return
        inspectionNoteDraftPrefs.edit()
            .remove(key)
            .remove(inspectionNoteDraftRecordedAtKey(key))
            .remove(inspectionNoteDraftUpdatedAtKey(key))
            .remove(inspectionNoteDraftPendingKey(key))
            .apply()
    }

    suspend fun applyCloudDocument(doc: CloudDocumentRow) = withContext(Dispatchers.IO) {
        if (doc.deleted) {
            when (doc.entity_type) {
                "inspection_note_draft" -> clearLocalInspectionNoteDraft(doc.entity_id, doc.updated_at)
                "apiary" -> db.apiaries().get(doc.entity_id)?.let {
                    // A newer cloud tombstone removes the row locally as well, freeing
                    // the apiary name and ensuring the map pin disappears on this device.
                    if (doc.updated_at >= it.updatedAt) db.withTransaction { db.apiaries().hardDelete(doc.entity_id) }
                }
                "hive" -> db.hives().get(doc.entity_id)?.let {
                    // A remote permanent delete only wins over genuinely newer local edits.
                    if (doc.updated_at >= it.updatedAt) db.withTransaction { permanentlyDeleteHiveLocal(doc.entity_id) }
                }
                "inspection" -> db.inspections().get(doc.entity_id)?.let {
                    if (doc.updated_at >= it.updatedAt) db.withTransaction { db.inspections().hardDelete(doc.entity_id) }
                }
                "feeding" -> db.feedings().get(doc.entity_id)?.let {
                    if (doc.updated_at >= it.createdAt) db.withTransaction { db.feedings().hardDelete(doc.entity_id) }
                }
                "treatment" -> db.treatments().get(doc.entity_id)?.let {
                    if (doc.updated_at >= it.createdAt) db.withTransaction { db.treatments().hardDelete(doc.entity_id) }
                }
                "harvest" -> db.harvests().get(doc.entity_id)?.let {
                    if (doc.updated_at >= it.createdAt) db.withTransaction { db.harvests().hardDelete(doc.entity_id) }
                }
                "event" -> db.events().get(doc.entity_id)?.let {
                    if (doc.updated_at >= it.createdAt) db.withTransaction { db.events().hardDelete(doc.entity_id) }
                }
                "nfc_assignment" -> db.nfcTagAssignments().get(doc.entity_id)?.let { assignment ->
                    if (doc.updated_at >= maxOf(assignment.assignedAt, assignment.unassignedAt ?: 0L)) {
                        db.withTransaction {
                            db.nfcTagAssignments().hardDelete(doc.entity_id)
                            db.hives().refreshTagCache(assignment.hiveId)
                        }
                    }
                }
            }
            return@withContext
        }
        val json = runCatching { JSONObject(doc.payload) }.getOrNull() ?: return@withContext
        when (doc.entity_type) {
            "inspection_note_draft" -> {
                val workspaceId = _activeWorkspaceId.value
                val key = inspectionNoteDraftKey(workspaceId, doc.entity_id)
                val incomingUpdatedAt = json.optLong("updated_at", doc.updated_at)
                val localUpdatedAt = inspectionNoteDraftPrefs.getLong(inspectionNoteDraftUpdatedAtKey(key), 0L)
                // A local edit may already be persisted but not yet in the Room outbox.
                // Preserve it until the pre-sync recovery path queues it.
                if (inspectionNoteDraftPrefs.getBoolean(inspectionNoteDraftPendingKey(key), false)) return@withContext
                if (!shouldApplyInspectionNoteDraft(incomingUpdatedAt, localUpdatedAt)) return@withContext

                val notes = json.optString("notes", "")
                val recordedAt = json.longOrNull("recorded_at")?.takeIf { it > 0L && notes.isNotBlank() }
                val editor = inspectionNoteDraftPrefs.edit()
                    .putString(key, notes)
                    .putLong(inspectionNoteDraftUpdatedAtKey(key), incomingUpdatedAt)
                    .putBoolean(inspectionNoteDraftPendingKey(key), false)
                if (recordedAt != null) editor.putLong(inspectionNoteDraftRecordedAtKey(key), recordedAt)
                else editor.remove(inspectionNoteDraftRecordedAtKey(key))
                editor.apply()
            }
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
                    statusChangedAt = if (lifecycleWins) incomingStatusChangedAt else existing.statusChangedAt,
                    latitude = if (json.doubleOrNull("latitude") != null && json.doubleOrNull("longitude") != null) json.doubleOrNull("latitude") else existing?.latitude,
                    longitude = if (json.doubleOrNull("latitude") != null && json.doubleOrNull("longitude") != null) json.doubleOrNull("longitude") else existing?.longitude,
                    beeCountFrames = json.doubleOrNull("bee_count_frames")?.let {
                        (it * 2.0).roundToInt().coerceIn(1, 20) / 2.0
                    } ?: existing?.beeCountFrames
                ))
                // Older BeeKeep builds stored the tag on the hive document. Adopt it
                // into the assignment ledger so mixed-version fleets still resolve scans.
                val remoteTag = json.stringOrNull("tag_uid")?.let(NfcUid::normalize)
                if (remoteTag != null && status == HiveStatus.ACTIVE) {
                    // Hive.tag_uid is a legacy cache, not the assignment authority. Import
                    // it only when neither this hive nor this physical UID has any ledger
                    // history. Otherwise a stale hive document (updated later for an
                    // unrelated edit) could reclaim a released tag during cloud sync.
                    val hiveHasLedgerHistory = db.nfcTagAssignments().allAssignmentsForHive(doc.entity_id).isNotEmpty()
                    val uidHasLedgerHistory = db.nfcTagAssignments().allForBackup()
                        .any { NfcUid.matches(it.tagUid, remoteTag) }
                    if (!hiveHasLedgerHistory && !uidHasLedgerHistory) {
                        applyAssignment(
                            NfcTagAssignmentEntity(
                                IdGenerator.nextLong(),
                                remoteTag,
                                doc.entity_id,
                                incomingUpdatedAt
                            ),
                            publishIncomingIfNew = true
                        )
                    }
                }
                if (status != HiveStatus.ACTIVE || json.optBoolean("deleted", false)) {
                    // Older app versions can sync a lifecycle change without closing NFC
                    // rows. Close every active assignment here too; otherwise a dead hive
                    // can retain a tag and prevent it from being safely reused.
                    val lifecycleTime = maxOf(incomingStatusChangedAt, incomingUpdatedAt)
                    db.nfcTagAssignments().activeAssignmentsForHive(doc.entity_id).forEach { assignment ->
                        val closed = assignment.copy(unassignedAt = maxOf(lifecycleTime, assignment.assignedAt))
                        db.nfcTagAssignments().upsert(closed)
                        enqueue("nfc_assignment", closed.id, "upsert", nfcAssignmentPayload(closed))
                    }
                }
                val tagUidBeforeRefresh = db.hives().get(doc.entity_id)?.tagUid
                db.hives().refreshTagCache(doc.entity_id)
                db.hives().get(doc.entity_id)?.let { refreshed ->
                    if (tagUidBeforeRefresh != refreshed.tagUid) {
                        // Publish cache-only changes caused by the assignment ledger so old
                        // clients/cloud documents do not keep broadcasting a released UID.
                        enqueue("hive", doc.entity_id, "upsert", hivePayload(refreshed))
                    }
                }
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
                notes=json.optString("notes"), latitude=json.doubleOrNull("latitude"), longitude=json.doubleOrNull("longitude"),
                emergencyCells=json.optInt("emergency_cells").coerceAtLeast(0), supercedureCells=json.optInt("supercedure_cells").coerceAtLeast(0), swarmCells=json.optInt("swarm_cells").coerceAtLeast(0), eggs=json.optDouble("eggs", 0.0).coerceAtLeast(0.0), openBrood=json.optDouble("open_brood", 0.0).coerceAtLeast(0.0), cappedBrood=json.optDouble("capped_brood", 0.0).coerceAtLeast(0.0), honeyStores=json.optDouble("honey_stores", 0.0).coerceAtLeast(0.0), pollen=json.optDouble("pollen", 0.0).coerceAtLeast(0.0), emptyDrawnComb=json.optDouble("empty_drawn_comb", 0.0).coerceAtLeast(0.0), diseaseFlags=json.optString("disease_flags"), updatedAt=json.optLong("updated_at",doc.updated_at),
                beeCountFrames=json.doubleOrNull("bee_count_frames")?.let { (it * 2.0).roundToInt().coerceIn(1, 20) / 2.0 }
            )) else Unit
            "feeding" -> if (db.hives().exists(json.optLong("hive_id"))) db.feedings().upsert(FeedingEntity(json.optLong("id",doc.entity_id),json.optLong("hive_id"),json.optLong("created_at",doc.updated_at),json.optString("feed_type"),json.optString("ratio"),json.optDouble("amount").coerceAtLeast(0.0),json.optString("unit"),json.optString("notes"))) else Unit
            "treatment" -> if (db.hives().exists(json.optLong("hive_id"))) db.treatments().upsert(TreatmentEntity(json.optLong("id",doc.entity_id),json.optLong("hive_id"),json.optLong("created_at",doc.updated_at),json.optString("treatment_type"),json.optString("product"),json.longOrNull("inserted_at"),json.longOrNull("removal_at"),json.longOrNull("withdrawal_until"),json.optString("notes"))) else Unit
            "harvest" -> if (db.hives().exists(json.optLong("hive_id"))) db.harvests().upsert(HarvestEntity(json.optLong("id",doc.entity_id),json.optLong("hive_id"),json.optLong("created_at",doc.updated_at),json.optInt("supers_pulled").coerceAtLeast(0),json.optDouble("wet_honey_weight").coerceAtLeast(0.0),json.optDouble("dry_honey_weight").coerceAtLeast(0.0),json.optString("weight_unit","lb"),json.optDouble("wax_weight").coerceAtLeast(0.0),json.optDouble("propolis_weight").coerceAtLeast(0.0),json.optString("notes"))) else Unit
            "event" -> if (db.hives().exists(json.optLong("hive_id"))) db.events().upsert(ActivityEventEntity(json.optLong("id",doc.entity_id),json.optLong("hive_id"),json.optLong("created_at",doc.updated_at),json.optString("type"),json.optString("title"),json.optString("detail"))) else Unit
        }
    }


    /**
     * Portable export of apiaries and their hive history. Sync transport
     * state are device-specific and deliberately are not part of the JSON backup.
     */
    suspend fun createDataBackupJson(): String = withContext(Dispatchers.IO) {
        val records = JSONArray()
        fun addRecord(type: String, id: Long, updatedAt: Long, payload: String) {
            records.put(JSONObject()
                .put("type", type)
                .put("id", id)
                .put("updatedAt", updatedAt)
                .put("payload", JSONObject(payload)))
        }

        val apiaries = db.apiaries().allForBackup().filterNot { it.deleted }
        val hives = db.hives().allForBackup().filterNot { it.deleted }
        val hiveIds = hives.map { it.id }.toSet()
        apiaries.forEach { addRecord("apiary", it.id, it.updatedAt, apiaryPayload(it)) }
        hives.forEach { addRecord("hive", it.id, it.updatedAt, hivePayload(it)) }
        db.nfcTagAssignments().allForBackup().filter { it.hiveId in hiveIds }
            .forEach { addRecord("nfc_assignment", it.id, maxOf(it.assignedAt, it.unassignedAt ?: 0L), nfcAssignmentPayload(it)) }
        db.inspections().allForBackup().filter { it.hiveId in hiveIds }
            .forEach { addRecord("inspection", it.id, it.updatedAt, inspectionPayload(it)) }
        db.feedings().allForBackup().filter { it.hiveId in hiveIds }
            .forEach { addRecord("feeding", it.id, it.createdAt, feedingPayload(it)) }
        db.treatments().allForBackup().filter { it.hiveId in hiveIds }
            .forEach { addRecord("treatment", it.id, it.createdAt, treatmentPayload(it)) }
        db.harvests().allForBackup().filter { it.hiveId in hiveIds }
            .forEach { addRecord("harvest", it.id, it.createdAt, harvestPayload(it)) }
        db.events().allForBackup().filter { it.hiveId in hiveIds }
            .forEach { addRecord("event", it.id, it.createdAt, eventPayload(it)) }

        JSONObject()
            .put("format", "beekeep-data-backup")
            .put("schemaVersion", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("records", records)
            .toString(2)
    }

    /** Validates the entire file before any restore changes occur. */
    fun previewDataBackup(raw: String): DataBackupPreview {
        val root = JSONObject(raw)
        require(root.optString("format") == "beekeep-data-backup") { "This file is not a BeeKeep data backup." }
        require(root.optInt("schemaVersion", -1) == 1) { "This BeeKeep backup version is not supported by this app." }
        val records = root.optJSONArray("records")
            ?: throw IllegalArgumentException("The backup does not contain a records list.")
        require(records.length() <= 50000) { "The backup contains too many records." }
        val allowedTypes = setOf("apiary", "hive", "nfc_assignment", "inspection", "feeding", "treatment", "harvest", "event")
        val rows = (0 until records.length()).map { records.getJSONObject(it) }
        val backupHiveIds = rows.filter { it.optString("type") == "hive" }.map { it.optLong("id", -1L) }.toSet()
        val childTypes = setOf("nfc_assignment", "inspection", "feeding", "treatment", "harvest", "event")
        val seen = mutableSetOf<String>()
        val counts = linkedMapOf<String, Int>()
        for (recordIndex in 0 until records.length()) {
            val row = records.optJSONObject(recordIndex)
                ?: throw IllegalArgumentException("Backup record ${recordIndex + 1} is not valid JSON.")
            val type = row.optString("type")
            require(type in allowedTypes) { "The backup contains an unsupported record type." }
            val id = row.optLong("id", -1L)
            require(id > 0L) { "Backup record ${recordIndex + 1} has an invalid ID." }
            require(seen.add("$type:$id")) { "The backup contains a duplicate $type record ($id)." }
            val payload = row.optJSONObject("payload")
                ?: throw IllegalArgumentException("Backup record ${recordIndex + 1} has no payload.")
            require(payload.optLong("id", id) == id) { "Backup record ${recordIndex + 1} has mismatched IDs." }
            if (type == "apiary") require(payload.optString("name").isNotBlank()) { "A backup apiary is missing its name." }
            if (type == "hive") require(payload.optString("number").isNotBlank()) { "A backup hive is missing its number." }
            if (type in childTypes) {
                val hiveId = payload.optLong("hive_id", -1L)
                require(hiveId > 0L && hiveId in backupHiveIds) {
                    "Backup record ${recordIndex + 1} refers to a hive that is not included in the backup."
                }
            }
            counts[type] = (counts[type] ?: 0) + 1
        }
        return DataBackupPreview(root.optLong("createdAt", 0L), counts)
    }

    /**
     * Merges a fully validated backup. Logical records already present locally are matched
     * before ID collisions are remapped, so importing the same backup twice won't duplicate
     * ordinary records. Newer local records win and no local-only row is deleted.
     */
    suspend fun restoreDataBackupJson(raw: String): DataBackupRestoreResult = withContext(Dispatchers.IO) {
        checkWritable()
        previewDataBackup(raw)
        val root = JSONObject(raw)
        val records = root.getJSONArray("records")
        val rows = (0 until records.length()).map { records.getJSONObject(it) }
        val currentApiaries = db.apiaries().allForBackup()
        val currentHives = db.hives().allForBackup()
        val currentAssignments = db.nfcTagAssignments().allForBackup()
        val currentInspections = db.inspections().allForBackup()
        val currentFeedings = db.feedings().allForBackup()
        val currentTreatments = db.treatments().allForBackup()
        val currentHarvests = db.harvests().allForBackup()
        val currentEvents = db.events().allForBackup()
        val keyMap = mutableMapOf<String, Long>()
        val allBackupIds = rows.mapNotNull { row -> row.optLong("id", -1L).takeIf { it > 0L } }.toSet()
        fun mapKey(type: String, id: Long) = "$type:$id"
        fun remapId(type: String, sourceId: Long, preferred: Long): Long {
            val mapKey = mapKey(type, sourceId)
            return keyMap.getOrPut(mapKey) { preferred }
        }
        fun unusedId(used: Set<Long>): Long {
            var candidate = IdGenerator.nextLong()
            while (candidate in used || candidate in allBackupIds || candidate in keyMap.values || candidate <= 0L) {
                candidate = IdGenerator.nextLong()
            }
            return candidate
        }

        // Apiary names are unique. Match by name first, then avoid a primary-key collision.
        rows.filter { it.optString("type") == "apiary" }.forEach { row ->
            val payload = row.getJSONObject("payload")
            val sourceId = row.getLong("id")
            val name = payload.optString("name").trim()
            val sameName = currentApiaries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            val idOwner = currentApiaries.firstOrNull { it.id == sourceId }
            val targetId = sameName?.id ?: if (idOwner == null || idOwner.name.equals(name, ignoreCase = true)) {
                sourceId
            } else unusedId(currentApiaries.map { it.id }.toSet())
            remapId("apiary", sourceId, targetId)
        }

        // Match a hive by its apiary and number across phones; preserve IDs when they are safe.
        rows.filter { it.optString("type") == "hive" }.forEach { row ->
            val payload = row.getJSONObject("payload")
            val sourceId = row.getLong("id")
            val number = payload.optString("number").trim()
            val apiaryName = payload.optString("apiary").trim()
            val logical = currentHives.firstOrNull {
                !it.deleted && it.number.equals(number, ignoreCase = true) &&
                    it.apiaryName.equals(apiaryName, ignoreCase = true)
            }
            val idOwner = currentHives.firstOrNull { it.id == sourceId }
            val targetId = logical?.id ?: if (idOwner == null ||
                (idOwner.number.equals(number, ignoreCase = true) && idOwner.apiaryName.equals(apiaryName, ignoreCase = true))) {
                sourceId
            } else unusedId(currentHives.map { it.id }.toSet())
            remapId("hive", sourceId, targetId)
        }

        // For history rows, locate logical duplicates before remapping colliding row IDs.
        fun targetHive(sourceHiveId: Long): Long = keyMap[mapKey("hive", sourceHiveId)] ?: sourceHiveId
        rows.forEach { row ->
            val type = row.optString("type")
            if (type !in setOf("nfc_assignment", "inspection", "feeding", "treatment", "harvest", "event")) return@forEach
            val p = row.getJSONObject("payload")
            val sourceId = row.getLong("id")
            val mappedHiveId = targetHive(p.optLong("hive_id", -1L))
            val logicalId = when (type) {
                "nfc_assignment" -> currentAssignments.firstOrNull {
                    it.tagUid.equals(p.optString("tag_uid"), ignoreCase = true) &&
                        it.hiveId == mappedHiveId && it.assignedAt == p.optLong("assigned_at", row.optLong("updatedAt"))
                }?.id
                "inspection" -> currentInspections.firstOrNull {
                    it.hiveId == mappedHiveId && it.createdAt == p.optLong("created_at")
                }?.id
                "feeding" -> currentFeedings.firstOrNull {
                    it.hiveId == mappedHiveId && it.createdAt == p.optLong("created_at") &&
                        it.feedType.equals(p.optString("feed_type"), ignoreCase = true)
                }?.id
                "treatment" -> currentTreatments.firstOrNull {
                    it.hiveId == mappedHiveId && it.createdAt == p.optLong("created_at") &&
                        it.treatmentType.equals(p.optString("treatment_type"), ignoreCase = true)
                }?.id
                "harvest" -> currentHarvests.firstOrNull {
                    it.hiveId == mappedHiveId && it.createdAt == p.optLong("created_at")
                }?.id
                else -> currentEvents.firstOrNull {
                    it.hiveId == mappedHiveId && it.createdAt == p.optLong("created_at") &&
                        it.type.equals(p.optString("type"), ignoreCase = true) &&
                        it.title.equals(p.optString("title"), ignoreCase = true)
                }?.id
            }
            val idOwnerMatches = when (type) {
                "nfc_assignment" -> currentAssignments.firstOrNull { it.id == sourceId }?.let {
                    it.hiveId == mappedHiveId &&
                        it.tagUid.equals(p.optString("tag_uid"), ignoreCase = true) &&
                        it.assignedAt == p.optLong("assigned_at", row.optLong("updatedAt"))
                } ?: true
                "inspection" -> currentInspections.firstOrNull { it.id == sourceId }?.let {
                    it.hiveId == mappedHiveId && it.createdAt == p.optLong("created_at")
                } ?: true
                "feeding" -> currentFeedings.firstOrNull { it.id == sourceId }?.let {
                    it.hiveId == mappedHiveId && it.createdAt == p.optLong("created_at") &&
                        it.feedType.equals(p.optString("feed_type"), ignoreCase = true)
                } ?: true
                "treatment" -> currentTreatments.firstOrNull { it.id == sourceId }?.let {
                    it.hiveId == mappedHiveId && it.createdAt == p.optLong("created_at") &&
                        it.treatmentType.equals(p.optString("treatment_type"), ignoreCase = true)
                } ?: true
                "harvest" -> currentHarvests.firstOrNull { it.id == sourceId }?.let {
                    it.hiveId == mappedHiveId && it.createdAt == p.optLong("created_at")
                } ?: true
                else -> currentEvents.firstOrNull { it.id == sourceId }?.let {
                    it.hiveId == mappedHiveId && it.createdAt == p.optLong("created_at") &&
                        it.type.equals(p.optString("type"), ignoreCase = true) &&
                        it.title.equals(p.optString("title"), ignoreCase = true)
                } ?: true
            }
            val idOwner = when (type) {
                "nfc_assignment" -> currentAssignments.any { it.id == sourceId }
                "inspection" -> currentInspections.any { it.id == sourceId }
                "feeding" -> currentFeedings.any { it.id == sourceId }
                "treatment" -> currentTreatments.any { it.id == sourceId }
                "harvest" -> currentHarvests.any { it.id == sourceId }
                else -> currentEvents.any { it.id == sourceId }
            }
            val usedIds = when (type) {
                "nfc_assignment" -> currentAssignments.map { it.id }.toSet()
                "inspection" -> currentInspections.map { it.id }.toSet()
                "feeding" -> currentFeedings.map { it.id }.toSet()
                "treatment" -> currentTreatments.map { it.id }.toSet()
                "harvest" -> currentHarvests.map { it.id }.toSet()
                else -> currentEvents.map { it.id }.toSet()
            }
            val targetId = logicalId ?: if (!idOwner || idOwnerMatches) sourceId else unusedId(usedIds)
            remapId(type, sourceId, targetId)
        }

        val orderedRows = rows.sortedWith(compareBy<JSONObject> { backupRestoreOrder(it.optString("type")) }.thenBy { it.optLong("id") })
        var processed = 0
        for (row in orderedRows) {
            val type = row.getString("type")
            val sourceId = row.getLong("id")
            val targetId = keyMap[mapKey(type, sourceId)] ?: sourceId
            val payload = JSONObject(row.getJSONObject("payload").toString())
            payload.put("id", targetId)
            if (payload.has("hive_id")) payload.put("hive_id", targetHive(payload.optLong("hive_id")))
            if (type == "hive") {
                val sourceApiary = payload.optLong("apiary_id", -1L)
                if (sourceApiary > 0L) payload.put("apiary_id", keyMap[mapKey("apiary", sourceApiary)] ?: sourceApiary)
                val originalHiveId = sourceId
                val originalTagUid = payload.optString("tag_uid")
                val hasActiveLedgerRecord = originalTagUid.isNotBlank() && rows.any { assignmentRow ->
                    if (assignmentRow.optString("type") != "nfc_assignment") return@any false
                    val assignment = assignmentRow.optJSONObject("payload") ?: return@any false
                    assignment.optLong("hive_id", -1L) == originalHiveId &&
                        assignment.optString("tag_uid").equals(originalTagUid, ignoreCase = true) &&
                        (!assignment.has("unassigned_at") || assignment.isNull("unassigned_at"))
                }
                if (hasActiveLedgerRecord) payload.put("tag_uid", JSONObject.NULL)
            }
            if (type == "inspection") {
                // Device-local image paths aren't portable; keep the current local file when restoring onto the same phone.
            }
            val incomingUpdatedAt = payload.optLong("updated_at", row.optLong("updatedAt", root.optLong("createdAt")))
            val localUpdatedAt = backupLocalTimestamp(type, targetId, currentApiaries, currentHives, currentAssignments,
                currentInspections, currentFeedings, currentTreatments, currentHarvests, currentEvents)
            if (localUpdatedAt == null || localUpdatedAt <= incomingUpdatedAt) {
                applyCloudDocument(
                    CloudDocumentRow(
                        user_id = "local-backup-restore",
                        entity_type = type,
                        entity_id = targetId,
                        payload = payload.toString(),
                        updated_at = incomingUpdatedAt,
                        deleted = false,
                        device_id = "backup-restore"
                    )
                )
            }
            enqueueCurrentBackupRecord(type, targetId)
            processed++
        }
        DataBackupRestoreResult(processed)
    }

    private suspend fun backupLocalTimestamp(
        type: String,
        id: Long,
        apiaries: List<ApiaryEntity>,
        hives: List<HiveEntity>,
        assignments: List<NfcTagAssignmentEntity>,
        inspections: List<InspectionEntity>,
        feedings: List<FeedingEntity>,
        treatments: List<TreatmentEntity>,
        harvests: List<HarvestEntity>,
        events: List<ActivityEventEntity>
    ): Long? = when (type) {
        "apiary" -> apiaries.firstOrNull { it.id == id }?.updatedAt
        "hive" -> hives.firstOrNull { it.id == id }?.updatedAt
        "nfc_assignment" -> assignments.firstOrNull { it.id == id }?.let { maxOf(it.assignedAt, it.unassignedAt ?: 0L) }
        "inspection" -> inspections.firstOrNull { it.id == id }?.updatedAt
        "feeding" -> feedings.firstOrNull { it.id == id }?.createdAt
        "treatment" -> treatments.firstOrNull { it.id == id }?.createdAt
        "harvest" -> harvests.firstOrNull { it.id == id }?.createdAt
        "event" -> events.firstOrNull { it.id == id }?.createdAt
        else -> null
    }

    private fun backupRestoreOrder(type: String): Int = when (type) {
        "apiary" -> 0
        "hive" -> 1
        "nfc_assignment" -> 2
        "inspection" -> 3
        "feeding" -> 4
        "treatment" -> 5
        "harvest" -> 6
        "event" -> 7
        else -> 99
    }

    private suspend fun enqueueCurrentBackupRecord(type: String, id: Long) {
        when (type) {
            "apiary" -> db.apiaries().get(id)?.let { enqueue("apiary", id, "upsert", apiaryPayload(it)) }
            "hive" -> db.hives().get(id)?.let { enqueue("hive", id, "upsert", hivePayload(it)) }
            "nfc_assignment" -> db.nfcTagAssignments().get(id)?.let { enqueue("nfc_assignment", id, "upsert", nfcAssignmentPayload(it)) }
            "inspection" -> db.inspections().get(id)?.let { enqueue("inspection", id, "upsert", inspectionPayload(it)) }
            "feeding" -> db.feedings().get(id)?.let { enqueue("feeding", id, "upsert", feedingPayload(it)) }
            "treatment" -> db.treatments().get(id)?.let { enqueue("treatment", id, "upsert", treatmentPayload(it)) }
            "harvest" -> db.harvests().get(id)?.let { enqueue("harvest", id, "upsert", harvestPayload(it)) }
            "event" -> db.events().get(id)?.let { enqueue("event", id, "upsert", eventPayload(it)) }
        }
    }

    private suspend fun applyAssignment(
        incoming: NfcTagAssignmentEntity,
        publishIncomingIfNew: Boolean = false
    ) {
        val normalizedUid = NfcUid.normalize(incoming.tagUid)
        db.withTransaction {
            val incomingAlreadyStored = db.nfcTagAssignments().get(incoming.id) != null
            // Imported/cloud data is not trusted to be normalized. Keep malformed rows
            // as closed history, never as an active route to a hive.
            val safeIncoming = if (normalizedUid == null) {
                incoming.copy(unassignedAt = incoming.unassignedAt ?: incoming.assignedAt)
            } else {
                incoming.copy(tagUid = normalizedUid)
            }
            val targetHive = db.hives().get(safeIncoming.hiveId)
            val targetIsUnavailable = safeIncoming.isActive &&
                (targetHive == null || targetHive.status != HiveStatus.ACTIVE || targetHive.deleted)
            // Assignment documents can arrive after lifecycle documents or out of order.
            // A stale active assignment must never resurrect a tag on a dead/deleted hive.
            val lifecycleSafeIncoming = if (targetIsUnavailable) {
                safeIncoming.copy(
                    unassignedAt = maxOf(
                        safeIncoming.assignedAt,
                        targetHive?.statusChangedAt ?: 0L,
                        targetHive?.updatedAt ?: 0L
                    )
                )
            } else safeIncoming
            val merge = if (normalizedUid == null || !lifecycleSafeIncoming.isActive) {
                NfcAssignmentResolver.MergeResult(lifecycleSafeIncoming, emptyList())
            } else {
                NfcAssignmentResolver.merge(
                    tagConflicts = db.nfcTagAssignments().allActiveAssignments()
                        .filter { NfcUid.matches(it.tagUid, normalizedUid) },
                    hiveConflicts = db.nfcTagAssignments().activeAssignmentsForHive(lifecycleSafeIncoming.hiveId),
                    incoming = lifecycleSafeIncoming
                )
            }

            merge.toClose.forEach { closed ->
                db.nfcTagAssignments().upsert(closed)
                enqueue("nfc_assignment", closed.id, "upsert", nfcAssignmentPayload(closed))
            }
            db.nfcTagAssignments().upsert(merge.incoming)
            if (merge.incoming != incoming || (publishIncomingIfNew && !incomingAlreadyStored)) {
                enqueue("nfc_assignment", merge.incoming.id, "upsert", nfcAssignmentPayload(merge.incoming))
            }

            val affectedHiveIds = (merge.toClose.map { it.hiveId } + merge.incoming.hiveId).distinct()
            affectedHiveIds.forEach { affectedHiveId ->
                val beforeTagUid = db.hives().get(affectedHiveId)?.tagUid
                db.hives().refreshTagCache(affectedHiveId)
                db.hives().get(affectedHiveId)?.let { updatedHive ->
                    if (beforeTagUid != updatedHive.tagUid) {
                        // Keep legacy hive documents aligned with the assignment ledger or an
                        // older device could re-import a stale cached UID as a new assignment.
                        enqueue("hive", affectedHiveId, "upsert", hivePayload(updatedHive))
                    }
                }
            }
        }
    }

    private fun checkWritable() {
        if (_activeWorkspaceReadOnly.value) {
            _readOnlyWriteAttempt.value = _readOnlyWriteAttempt.value + 1L
            throw IllegalStateException(
                "This team workspace is view-only. Ask a workspace owner to change your role before editing."
            )
        }
    }

    private suspend fun enqueue(type: String, entityId: Long, operation: String, payload: String) {
        // Remote hydration for a viewer may adjust local caches. Never turn that into
        // a pending client write for a read-only role.
        if (_activeWorkspaceReadOnly.value) return
        db.outbox().deletePendingForEntity(type, entityId)
        db.outbox().enqueue(SyncOutboxEntity(IdGenerator.nextLong(), type, entityId, operation, payload))
    }

    private fun hivePayload(e: HiveEntity) = JSONObject().put("id",e.id).put("number",e.number).put("apiary",e.apiaryName).put("apiary_id",e.apiaryId).put("queen_status",e.queenStatus).put("queen_mark_color",e.queenMarkColor).put("queen_origin",e.queenOrigin).put("queen_age_months",e.queenAgeMonths).put("queen_temperament",e.queenTemperament).put("strength",e.strength).put("mite_percent",e.mitePercent).put("tag_uid",e.tagUid).put("updated_at",e.updatedAt).put("deleted",e.deleted).put("status",e.status).put("dead_at",e.deadAt).put("status_changed_at",e.statusChangedAt).put("latitude",e.latitude).put("longitude",e.longitude).put("bee_count_frames",e.beeCountFrames).toString()
    private fun nfcAssignmentPayload(e: NfcTagAssignmentEntity) = JSONObject().put("id",e.id).put("tag_uid",e.tagUid).put("hive_id",e.hiveId).put("assigned_at",e.assignedAt).put("unassigned_at",e.unassignedAt).put("updated_at",maxOf(e.assignedAt, e.unassignedAt ?: 0L)).toString()
    private fun apiaryPayload(e: ApiaryEntity) = JSONObject().put("id",e.id).put("name",e.name).put("notes",e.notes).put("latitude",e.latitude).put("longitude",e.longitude).put("forage_notes",e.forageNotes).put("water_notes",e.waterNotes).put("updated_at",e.updatedAt).put("deleted",e.deleted).toString()
    private fun inspectionPayload(e: InspectionEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("strength",e.strength).put("queen_status",e.queenStatus).put("mite_count",e.miteCount).put("sample_size",e.sampleSize).put("notes",e.notes)
        .put("latitude",e.latitude).put("longitude",e.longitude).put("emergency_cells",e.emergencyCells).put("supercedure_cells",e.supercedureCells).put("swarm_cells",e.swarmCells).put("eggs",e.eggs).put("open_brood",e.openBrood).put("capped_brood",e.cappedBrood).put("honey_stores",e.honeyStores).put("pollen",e.pollen).put("empty_drawn_comb",e.emptyDrawnComb).put("disease_flags",e.diseaseFlags).put("bee_count_frames",e.beeCountFrames).put("updated_at",e.updatedAt).toString()
    private fun feedingPayload(e: FeedingEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("feed_type",e.feedType).put("ratio",e.ratio).put("amount",e.amount).put("unit",e.unit).put("notes",e.notes).toString()
    private fun treatmentPayload(e: TreatmentEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("treatment_type",e.treatmentType).put("product",e.product).put("inserted_at",e.insertedAt).put("removal_at",e.removalAt).put("withdrawal_until",e.withdrawalUntil).put("notes",e.notes).toString()
    private fun harvestPayload(e: HarvestEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("supers_pulled",e.supersPulled).put("wet_honey_weight",e.wetHoneyWeight).put("dry_honey_weight",e.dryHoneyWeight).put("weight_unit",e.weightUnit).put("wax_weight",e.waxWeight).put("propolis_weight",e.propolisWeight).put("notes",e.notes).toString()
    private fun eventPayload(e: ActivityEventEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("type",e.type).put("title",e.title).put("detail",e.detail).toString()

    private fun JSONObject.doubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null
    private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

    private fun normalizeBroodStoresGauge(value: Double): Double =
        (value.coerceIn(0.0, 10.0) * 2.0).roundToInt() / 2.0

    private fun formatBeeCountFrames(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(java.util.Locale.US, "%.1f", value)

    private fun toHive(e: HiveEntity) = Hive(e.id, e.number, e.apiaryName, e.queenStatus, e.queenMarkColor, e.queenOrigin, e.queenAgeMonths, e.queenTemperament, e.strength, e.mitePercent, e.tagUid, e.status, e.deadAt, e.latitude, e.longitude, e.beeCountFrames, e.apiaryId)
    private fun toInspection(e: InspectionEntity) = Inspection(e.id, e.hiveId, e.createdAt, e.strength, e.queenStatus, e.miteCount, e.sampleSize, e.notes, e.latitude, e.longitude, e.emergencyCells, e.supercedureCells, e.swarmCells, e.eggs, e.openBrood, e.cappedBrood, e.honeyStores, e.pollen, e.emptyDrawnComb, e.diseaseFlags, e.beeCountFrames)
    private fun toFeeding(e: FeedingEntity) = Feeding(e.id, e.hiveId, e.createdAt, e.feedType, e.ratio, e.amount, e.unit, e.notes)
    private fun toTreatment(e: TreatmentEntity) = Treatment(e.id, e.hiveId, e.createdAt, e.treatmentType, e.product, e.insertedAt, e.removalAt, e.withdrawalUntil, e.notes)
    private fun toHarvest(e: HarvestEntity) = Harvest(e.id, e.hiveId, e.createdAt, e.supersPulled, e.wetHoneyWeight, e.dryHoneyWeight, e.weightUnit, e.waxWeight, e.propolisWeight, e.notes)
    private fun toApiary(e: ApiaryEntity) = Apiary(e.id, e.name, e.notes, e.latitude, e.longitude, e.forageNotes, e.waterNotes)
    private fun toEvent(e: ActivityEventEntity) = ActivityEvent(e.id, e.hiveId, e.createdAt, e.type, e.title, e.detail)
}

data class DataBackupPreview(val createdAt: Long, val counts: Map<String, Int>) {
    val totalRecords: Int get() = counts.values.sum()
}

data class DataBackupRestoreResult(val processedRecords: Int)

private fun requireValidCoordinates(latitude: Double?, longitude: Double?, subject: String) {
    require((latitude == null) == (longitude == null)) {
        "$subject coordinates must include both latitude and longitude."
    }
    if (latitude != null && longitude != null) {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "$subject latitude is outside the valid range."
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "$subject longitude is outside the valid range."
        }
    }
}