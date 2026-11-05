package com.beekeep.app.data

import com.beekeep.app.nfc.NfcUid

import com.beekeep.app.ColonyStrength
import com.beekeep.app.BeeKeepEntitlements
import com.beekeep.app.BeeKeepQuotaLimits

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.beekeep.app.cloud.CloudConflictPolicy
import com.beekeep.app.cloud.CloudDocumentRow
import com.beekeep.app.cloud.LegacyNfcProfileSnapshotGuard
import com.beekeep.app.cloud.NfcMutationSyncTimestamp
import com.beekeep.app.cloud.ActiveWorkspaceSessionPolicy
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
import java.util.UUID
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
class LocalHiveRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("beekeep_prefs", Context.MODE_PRIVATE)
    private val inspectionNoteDraftPrefs = appContext.getSharedPreferences(INSPECTION_NOTE_DRAFTS, Context.MODE_PRIVATE)
    private val inspectionFormDraftPrefs = appContext.getSharedPreferences(InspectionFormDraftStore.PREFERENCES_NAME, Context.MODE_PRIVATE)

    // Bootstrap only a cache bound to the last known account. The saved workspace ID alone
    // is not proof of membership, and must never make a previous account's data the active dataset.
    // The gateway revalidates the live Supabase session and workspace role in the background.
    private val initialActiveWorkspaceId = prefs.getString("active_team_workspace_id", "")
        .orEmpty().trim().takeIf { workspaceId ->
            val storedOwnerId = prefs.getString("active_team_workspace_user_id", null)
                ?.trim()?.takeIf { it.isNotEmpty() }
            val lastKnownUserId = prefs.getString("last_cloud_user_id", null)
                ?.trim()?.takeIf { it.isNotEmpty() } ?: storedOwnerId
            ActiveWorkspaceSessionPolicy.canKeepCachedWorkspace(
                workspaceId,
                lastKnownUserId,
                storedOwnerId
            )
        }
    private val initialActiveWorkspaceReadOnly = initialActiveWorkspaceId != null &&
        prefs.getBoolean("active_team_workspace_read_only", true)
    // The account-bound cache can be displayed immediately, but writes wait until the gateway
    // verifies the current session or confirms the account's cached offline access state.
    private val startupWorkspaceAccessPending = MutableStateFlow(
        prefs.getString("active_team_workspace_id", "").orEmpty().isNotBlank()
    )
    private val _activeWorkspaceId = MutableStateFlow(initialActiveWorkspaceId)
    val activeWorkspaceId: StateFlow<String?> = _activeWorkspaceId.asStateFlow()
    private val _activeWorkspaceReadOnly = MutableStateFlow(initialActiveWorkspaceReadOnly)
    val activeWorkspaceReadOnly: StateFlow<Boolean> = _activeWorkspaceReadOnly.asStateFlow()
    internal fun completeStartupWorkspaceAccessResolution() {
        startupWorkspaceAccessPending.value = false
    }
    private val _readOnlyWriteAttempt = MutableStateFlow(0L)
    val readOnlyWriteAttempt: StateFlow<Long> = _readOnlyWriteAttempt.asStateFlow()
    private val _database = MutableStateFlow(
        initialActiveWorkspaceId?.let { BeeKeepRoomDb.getForWorkspace(appContext, it) }
            ?: BeeKeepRoomDb.get(appContext)
    )
    private val db: BeeKeepRoomDb get() = _database.value
    @Volatile
    private var activeQuotaLimits = BeeKeepQuotaLimits.forPlan(
        BeeKeepEntitlements.currentPlan(appContext),
        BeeKeepEntitlements.hiveLimit(appContext)
    )

    /** The ViewModel supplies limits for the currently selected personal or team dataset. */
    internal fun updateQuotaLimits(limits: BeeKeepQuotaLimits) {
        activeQuotaLimits = limits
    }

    /**
     * Moves the live repository to a distinct database for a team. Personal/offline records
     * remain in beekeep_room.db and are never merged into a team's local dataset.
     */
    suspend fun switchWorkspace(
        workspaceId: String?,
        readOnly: Boolean? = null,
        ownerUserId: String? = null
    ) = withContext(Dispatchers.IO) {
        val cleanId = workspaceId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanOwnerUserId = if (cleanId == null) null else {
            ownerUserId?.trim()?.takeIf { it.isNotEmpty() }
        }
        require(cleanId == null || cleanOwnerUserId != null) {
            "A signed-in account is required to select a team workspace."
        }
        val cleanReadOnly = cleanId != null && (readOnly
            ?: prefs.getBoolean("active_team_workspace_read_only", false))
        val storedWorkspaceId = prefs.getString("active_team_workspace_id", "")
            .orEmpty().trim().ifBlank { null }
        val storedReadOnly = prefs.getBoolean("active_team_workspace_read_only", false)
        val storedOwnerUserId = prefs.getString("active_team_workspace_user_id", null)
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (_activeWorkspaceId.value == cleanId &&
            _activeWorkspaceReadOnly.value == cleanReadOnly &&
            storedWorkspaceId == cleanId &&
            storedReadOnly == cleanReadOnly &&
            storedOwnerUserId == cleanOwnerUserId
        ) return@withContext

        val nextDatabase = BeeKeepRoomDb.getForWorkspace(appContext, cleanId)
        val editor = prefs.edit()
            .putString("active_team_workspace_id", cleanId.orEmpty())
            .putBoolean("active_team_workspace_read_only", cleanReadOnly)
        if (cleanOwnerUserId != null) {
            editor.putString("active_team_workspace_user_id", cleanOwnerUserId)
        } else {
            editor.remove("active_team_workspace_user_id")
        }
        check(editor.commit()) {
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

    /** Returns whether the currently selected local workspace has records worth continuing offline with. */
    suspend fun hasLocalData(): Boolean = withContext(Dispatchers.IO) {
        db.hives().allForBackup().any { !it.deleted } ||
            db.apiaries().allForBackup().any { !it.deleted }
    }

    /**
     * Wipe the local workspace while preserving cloud deletion tombstones. The tombstones
     * prevent data from returning on a later sync, and this runs as one Room transaction.
     */
    /**
     * Captures the currently selected personal database and its personal inspection drafts into
     * an immutable app-private file. It deliberately does not enqueue, upload, apply, or delete
     * any cloud document. The account-attachment gate remains owned by SupabaseGateway.
     */
    internal suspend fun createLocalDataAttachmentSnapshot(
        targetAccountId: String
    ): LocalDataAttachmentSnapshotInfo = withContext(Dispatchers.IO) {
        val cleanAccountId = targetAccountId.trim()
        require(cleanAccountId.isNotEmpty()) { "Sign in before capturing a local-data snapshot." }
        check(_activeWorkspaceId.value == null) {
            "Switch to your personal data before reviewing this device's account attachment."
        }
        check(!startupWorkspaceAccessPending.value) {
            "BeeKeep is still verifying workspace access. Try the data review again shortly."
        }

        val personalDatabase = db
        val createdAt = System.currentTimeMillis()
        val snapshotParts = personalDatabase.withTransaction {
            val apiaries = personalDatabase.apiaries().allForBackup()
            val hives = personalDatabase.hives().allForBackup()
            val assignments = personalDatabase.nfcTagAssignments().allForBackup()
            val inspections = personalDatabase.inspections().allForBackup()
            val feedings = personalDatabase.feedings().allForBackup()
            val treatments = personalDatabase.treatments().allForBackup()
            val harvests = personalDatabase.harvests().allForBackup()
            val events = personalDatabase.events().allForBackup()
            val tasks = personalDatabase.tasks().allForBackup()
            val syncOutbox = personalDatabase.outbox().allForAttachmentSnapshot()
            val actionBatches = personalDatabase.actionBatchOutbox().allForAttachmentSnapshot()
            val workspaceActions = personalDatabase.workspaceActionOutbox().allForAttachmentSnapshot()
            val workspaceSettings = personalDatabase.workspaceActionSettings().allForAttachmentSnapshot()

            val records = JSONArray()
            fun addRecord(type: String, id: Long, updatedAt: Long, payload: String) {
                records.put(
                    JSONObject()
                        .put("type", type)
                        .put("id", id)
                        .put("updatedAt", updatedAt)
                        .put("payload", JSONObject(payload))
                )
            }
            apiaries.forEach { addRecord("apiary", it.id, it.updatedAt, apiaryPayload(it)) }
            hives.forEach { addRecord("hive", it.id, it.updatedAt, hivePayload(it)) }
            assignments.forEach {
                addRecord("nfc_assignment", it.id, maxOf(it.assignedAt, it.unassignedAt ?: 0L), nfcAssignmentPayload(it))
            }
            inspections.forEach { addRecord("inspection", it.id, it.updatedAt, inspectionPayload(it)) }
            feedings.forEach { addRecord("feeding", it.id, it.createdAt, feedingPayload(it)) }
            treatments.forEach { addRecord("treatment", it.id, it.createdAt, treatmentPayload(it)) }
            harvests.forEach { addRecord("harvest", it.id, it.createdAt, harvestPayload(it)) }
            events.forEach { addRecord("event", it.id, it.createdAt, eventPayload(it)) }

            val taskJson = JSONArray().apply {
                tasks.forEach { task ->
                    put(
                        JSONObject()
                            .put("id", task.id)
                            .put("hive_id", task.hiveId)
                            .put("title", task.title)
                            .put("due_at", task.dueAt)
                            .put("completed", task.completed)
                            .put("kind", task.kind)
                            .put("reminder_enabled", task.reminderEnabled)
                            .put("updated_at", task.updatedAt)
                    )
                }
            }
            val syncOutboxJson = JSONArray().apply {
                syncOutbox.forEach { item ->
                    put(
                        JSONObject()
                            .put("id", item.id)
                            .put("entity_type", item.entityType)
                            .put("entity_id", item.entityId)
                            .put("operation", item.operation)
                            .put("payload", item.payload)
                            .put("created_at", item.createdAt)
                            .put("state", item.state)
                            .put("last_error", item.lastError)
                    )
                }
            }
            val actionBatchJson = JSONArray().apply {
                actionBatches.forEach { item ->
                    put(
                        JSONObject()
                            .put("batch_id", item.batchId)
                            .put("workspace_id", item.workspaceId)
                            .put("schema_version", item.schemaVersion)
                            .put("payload", item.payload)
                            .put("created_at", item.createdAt)
                            .put("state", item.state)
                            .put("last_error", item.lastError)
                    )
                }
            }
            val workspaceActionJson = JSONArray().apply {
                workspaceActions.forEach { item ->
                    put(
                        JSONObject()
                            .put("id", item.id)
                            .put("client_action_id", item.clientActionId)
                            .put("workspace_id", item.workspaceId)
                            .put("entity_type", item.entityType)
                            .put("entity_id", item.entityId)
                            .put("operation", item.operation)
                            .put("payload", item.payload)
                            .put("action_at", item.actionAt)
                            .put("created_at", item.createdAt)
                            .put("state", item.state)
                            .put("last_error", item.lastError)
                    )
                }
            }
            val workspaceSettingsJson = JSONArray().apply {
                workspaceSettings.forEach { item ->
                    put(
                        JSONObject()
                            .put("scope_key", item.scopeKey)
                            .put("workspace_id", item.workspaceId)
                            .put("owner_user_id", item.ownerUserId)
                            .put("harvest_quantity_mode", item.harvestQuantityMode)
                            .put("updated_at", item.updatedAt)
                            .put("updated_by", item.updatedBy)
                            .put("sync_state", item.syncState)
                            .put("last_error", item.lastError)
                    )
                }
            }

            fun isPersonalDraftRoot(key: String): Boolean =
                Regex("^(personal_hive_|hive_)[0-9]+$").matches(key)

            val noteDrafts = personalDraftPreferencesSnapshot(inspectionNoteDraftPrefs) { key ->
                key.startsWith("personal_hive_") ||
                    Regex("^hive_[0-9]+(_recorded_at)?$").matches(key)
            }
            val formDrafts = personalDraftPreferencesSnapshot(inspectionFormDraftPrefs) { key ->
                key.startsWith("personal_hive_") ||
                    Regex("^hive_[0-9]+$").matches(key) ||
                    Regex("^bee_count_history_start_personal_[0-9]+$").matches(key) ||
                    Regex("^bee_count_history_start_[0-9]+$").matches(key)
            }
            val noteDraftCount = inspectionNoteDraftPrefs.all.keys.count(::isPersonalDraftRoot)
            val formDraftCount = inspectionFormDraftPrefs.all.keys.count(::isPersonalDraftRoot)

            val counts = linkedMapOf(
                "apiary" to apiaries.size,
                "hive" to hives.size,
                "nfc_assignment" to assignments.size,
                "inspection" to inspections.size,
                "feeding" to feedings.size,
                "treatment" to treatments.size,
                "harvest" to harvests.size,
                "event" to events.size,
                "task" to tasks.size
            )
            val pendingSync = syncOutbox.count { it.state == "pending" }
            val pendingBatches = actionBatches.count { it.state == "pending" }
            val pendingWorkspace = workspaceActions.count { it.state == "pending" }

            val countJson = JSONObject().apply { counts.forEach { (key, value) -> put(key, value) } }
            val snapshotJson = JSONObject()
                .put("format", "beekeep-local-attachment-snapshot")
                .put("schemaVersion", 1)
                .put("createdAt", createdAt)
                .put("targetAccountId", cleanAccountId)
                .put("deviceId", prefs.getString("device_id", null))
                .put("databaseVersion", 15)
                .put("recordCounts", countJson)
                .put("records", records)
                .put("tasks", taskJson)
                .put("syncOutbox", syncOutboxJson)
                .put("actionBatchOutbox", actionBatchJson)
                .put("workspaceActionOutbox", workspaceActionJson)
                .put("workspaceActionSettings", workspaceSettingsJson)
                .put("inspectionNoteDraftPreferences", noteDrafts)
                .put("inspectionFormDraftPreferences", formDrafts)
                .put(
                    "pendingCounts",
                    JSONObject()
                        .put("syncWrites", pendingSync)
                        .put("actionBatches", pendingBatches)
                        .put("workspaceActions", pendingWorkspace)
                )

            AttachmentSnapshotParts(
                json = snapshotJson.toString(),
                recordCounts = counts,
                pendingSyncWrites = pendingSync,
                pendingActionBatches = pendingBatches,
                pendingWorkspaceActions = pendingWorkspace,
                noteDrafts = noteDraftCount,
                formDrafts = formDraftCount
            )
        }

        check(_activeWorkspaceId.value == null && _database.value === personalDatabase) {
            "The active dataset changed during the local-data snapshot. No attachment was started."
        }
        LocalDataAttachmentSnapshotStore.save(
            filesDirectory = appContext.filesDir,
            createdAt = createdAt,
            snapshotJson = snapshotParts.json,
            recordCounts = snapshotParts.recordCounts,
            pendingSyncWrites = snapshotParts.pendingSyncWrites,
            pendingActionBatches = snapshotParts.pendingActionBatches,
            pendingWorkspaceActions = snapshotParts.pendingWorkspaceActions,
            noteDrafts = snapshotParts.noteDrafts,
            formDrafts = snapshotParts.formDrafts
        )
    }

    /**
     * Applies the approved identity remaps to the personal Room database. The operation is
     * atomic: foreign-key references and primary keys move through temporary IDs with deferred
     * FK validation, so a process interruption cannot leave half-remapped hive histories.
     * Pending queues are retained in a non-replayable state; they are never uploaded here.
     */
    internal suspend fun applyLocalDataAttachmentRemap(
        plan: com.beekeep.app.cloud.LocalDataAttachmentMergePlan,
        snapshotJson: String,
        snapshotSha256: String
    ) = withContext(Dispatchers.IO) {
        require(plan.isSafeToProceed) { "Resolve every manual-review item before attaching local data." }
        check(_activeWorkspaceId.value == null && !startupWorkspaceAccessPending.value) {
            "Switch to personal data and finish workspace verification before attaching records."
        }
        val database = db
        require(snapshotSha256.matches(Regex("^[a-f0-9]{64}$"))) {
            "The recovery snapshot checksum is invalid."
        }
        val root = JSONObject(snapshotJson)
        require(root.optString("targetAccountId").trim() == plan.accountId) {
            "The recovery snapshot belongs to a different signed-in account."
        }
        require(root.optString("format") == "beekeep-local-attachment-snapshot" &&
            root.optInt("schemaVersion", -1) == 1
        ) { "The local recovery snapshot format is not supported." }

        val supportedTables = mapOf(
            "apiary" to "apiaries",
            "hive" to "hives",
            "nfc_assignment" to "nfc_tag_assignments",
            "inspection" to "inspections",
            "feeding" to "feeding_entries",
            "treatment" to "treatment_entries",
            "harvest" to "harvest_entries",
            "event" to "activity_events"
        )
        val idMaps = plan.items
            .filter { it.entityType in supportedTables && it.targetId != it.sourceId }
            .groupBy { it.entityType }
            .mapValues { (_, items) ->
                val map = items.associate { it.sourceId to it.targetId }
                require(map.size == items.size) { "The attachment plan contains repeated source IDs." }
                map
            }
        val hiveIdMap = plan.items.filter { it.entityType == "hive" }
            .associate { it.sourceId to it.targetId }
        val apiaryItems = plan.items.filter { it.entityType == "apiary" }
        val hiveItems = plan.items.filter { it.entityType == "hive" }
        val childItems = plan.items.filter {
            it.entityType in setOf("feeding", "treatment", "harvest")
        }
        require(hiveIdMap.values.distinct().size == hiveIdMap.size) {
            "Two local hives resolve to the same target ID. Refresh the merge plan."
        }
        require(apiaryItems.map { it.targetId }.distinct().size == apiaryItems.size) {
            "Two local apiaries resolve to the same target ID. Refresh the merge plan."
        }

        val existingMarker = database.localDataAttachmentMarkers().get(snapshotSha256)
        require(existingMarker == null || existingMarker.accountId == plan.accountId) {
            "This recovery snapshot was already attached to a different account."
        }
        val sql = database.openHelper.writableDatabase
        fun exists(table: String, id: Long): Boolean =
            sql.query("SELECT 1 FROM $table WHERE id = ? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

        val moves = mutableMapOf<String, List<Pair<Long, Long>>>()
        for ((type, table) in supportedTables) {
            val mappings = idMaps[type].orEmpty().toList()
            val sourceIdsBeingMoved = mappings.map { it.first }.toSet()
            val pendingMoves = mutableListOf<Pair<Long, Long>>()
            for ((sourceId, targetId) in mappings) {
                val sourceExists = exists(table, sourceId)
                val targetExists = exists(table, targetId)
                when {
                    sourceExists && targetExists && targetId !in sourceIdsBeingMoved ->
                        throw IllegalStateException(
                            "Both local $type IDs $sourceId and $targetId exist. No local records were changed; refresh the plan."
                        )
                    !sourceExists && !targetExists ->
                        throw IllegalStateException(
                            "Neither local $type ID $sourceId nor its planned target $targetId exists. Restore the recovery snapshot before continuing."
                        )
                    sourceExists -> pendingMoves += sourceId to targetId
                    // Source missing + target present is the expected state after an earlier
                    // successful Room transaction; this makes retry after process death safe.
                }
            }
            moves[type] = pendingMoves
        }

        if (existingMarker == null) database.withTransaction {
            check(_activeWorkspaceId.value == null && db === database) {
                "The active dataset changed. No account attachment changes were applied."
            }
            // SQLite supports deferred FK validation for the duration of this Room transaction.
            sql.execSQL("PRAGMA defer_foreign_keys = ON")

            fun update(table: String, column: String, from: Long, to: Long) {
                sql.execSQL("UPDATE $table SET $column = ? WHERE $column = ?", arrayOf(to, from))
            }

            // Move references first, then entity primary keys, all via temporary negative IDs.
            // The database's FK checks are deferred until this transaction commits.
            moves["apiary"].orEmpty().forEach { (source, _) ->
                val temporary = -source
                update("hives", "apiary_id", source, temporary)
                update("feeding_entries", "apiary_id", source, temporary)
                update("treatment_entries", "apiary_id", source, temporary)
                update("harvest_entries", "apiary_id", source, temporary)
            }
            moves["hive"].orEmpty().forEach { (source, _) ->
                val temporary = -source
                update("nfc_tag_assignments", "hive_id", source, temporary)
                update("inspections", "hive_id", source, temporary)
                update("feeding_entries", "hive_id", source, temporary)
                update("treatment_entries", "hive_id", source, temporary)
                update("harvest_entries", "hive_id", source, temporary)
                update("activity_events", "hive_id", source, temporary)
                update("tasks", "hive_id", source, temporary)
            }
            moves.forEach { (type, pairs) ->
                val table = supportedTables.getValue(type)
                pairs.forEach { (source, _) -> update(table, "id", source, -source) }
            }

            // Now that every source ID has been vacated, move the rows and their references
            // to the final deterministic target IDs.
            moves.forEach { (type, pairs) ->
                val table = supportedTables.getValue(type)
                pairs.forEach { (source, target) -> update(table, "id", -source, target) }
            }
            moves["apiary"].orEmpty().forEach { (source, target) ->
                update("hives", "apiary_id", -source, target)
                update("feeding_entries", "apiary_id", -source, target)
                update("treatment_entries", "apiary_id", -source, target)
                update("harvest_entries", "apiary_id", -source, target)
            }
            moves["hive"].orEmpty().forEach { (source, target) ->
                update("nfc_tag_assignments", "hive_id", -source, target)
                update("inspections", "hive_id", -source, target)
                update("feeding_entries", "hive_id", -source, target)
                update("treatment_entries", "hive_id", -source, target)
                update("harvest_entries", "hive_id", -source, target)
                update("activity_events", "hive_id", -source, target)
                update("tasks", "hive_id", -source, target)
            }

            // History records may inherit apiary_id from their hive when legacy snapshots did
            // not store it. Persist the planner's approved parent mapping, not a guessed name.
            hiveItems.forEach { item ->
                item.targetApiaryId?.let { apiaryId ->
                    sql.execSQL(
                        "UPDATE hives SET apiary_id = ? WHERE id = ?",
                        arrayOf(apiaryId, item.targetId)
                    )
                }
            }
            childItems.forEach { item ->
                item.targetApiaryId?.let { apiaryId ->
                    val table = supportedTables.getValue(item.entityType)
                    sql.execSQL(
                        "UPDATE $table SET apiary_id = ? WHERE id = ?",
                        arrayOf(apiaryId, item.targetId)
                    )
                }
            }

            // Keep every unacknowledged legacy operation for recovery, but ensure no old-ID
            // outbox item, action batch or audit action can replay into the newly attached account.
            database.outbox().holdPendingForAttachment()
            database.actionBatchOutbox().holdPendingForAttachment()
            database.workspaceActionOutbox().holdPendingForAttachment()
            database.localDataAttachmentMarkers().upsert(
                LocalDataAttachmentMarkerEntity(
                    snapshotSha256 = snapshotSha256,
                    accountId = plan.accountId,
                    appliedAt = System.currentTimeMillis()
                )
            )
            check(_activeWorkspaceId.value == null && db === database) {
                "The active dataset changed during attachment. The local transaction was rolled back."
            }
        }

        // SharedPreferences are separate from Room's transaction. This transform is idempotent:
        // rerunning it after process death finds the value at its final key and leaves it there.
        remapPersonalDraftPreferences(inspectionNoteDraftPrefs, hiveIdMap, isFormDraft = false)
        remapPersonalDraftPreferences(inspectionFormDraftPrefs, hiveIdMap, isFormDraft = true)
        check(_activeWorkspaceId.value == null && db === database) {
            "The active dataset changed while inspection drafts were being re-keyed."
        }
    }

    /** Re-key personal drafts after hive-ID remapping without changing their saved values. */
    private fun remapPersonalDraftPreferences(
        preferences: SharedPreferences,
        hiveIdMap: Map<Long, Long>,
        isFormDraft: Boolean
    ) {
        if (hiveIdMap.isEmpty()) return
        val entries = preferences.all
        val notePattern = Regex("^(personal_hive_|hive_)([0-9]+)(.*)$")
        val formHistoryPattern = Regex("^bee_count_history_start_(?:personal_)?([0-9]+)$")
        val moved = entries.mapNotNull { (oldKey, originalValue) ->
            val noteMatch = notePattern.matchEntire(oldKey)
            val historyMatch = if (isFormDraft) formHistoryPattern.matchEntire(oldKey) else null
            val oldId = noteMatch?.groupValues?.get(2)?.toLongOrNull()
                ?: historyMatch?.groupValues?.get(1)?.toLongOrNull()
                ?: return@mapNotNull null
            val newId = hiveIdMap[oldId]?.takeIf { it != oldId } ?: return@mapNotNull null
            val newKey = when {
                noteMatch != null -> noteMatch.groupValues[1] + newId + noteMatch.groupValues[3]
                oldKey.startsWith("bee_count_history_start_personal_") ->
                    "bee_count_history_start_personal_$newId"
                else -> "bee_count_history_start_$newId"
            }
            val value = if (isFormDraft && originalValue is String &&
                (noteMatch?.groupValues?.get(3).orEmpty().isEmpty())
            ) {
                runCatching {
                    JSONObject(originalValue).put("id", newId).put("hive_id", newId).toString()
                }.getOrDefault(originalValue)
            } else originalValue
            Triple(oldKey, newKey, value)
        }
        if (moved.isEmpty()) return
        val movedKeys = moved.map { it.second }
        val oldKeysBeingMoved = moved.map { it.first }.toSet()
        require(movedKeys.distinct().size == movedKeys.size &&
            movedKeys.none { key -> key in entries && key !in oldKeysBeingMoved }
        ) {
            "Inspection drafts already exist at a remapped hive ID. Resolve the draft conflict before attaching."
        }
        val editor = preferences.edit()
        moved.forEach { (oldKey, _, _) -> editor.remove(oldKey) }
        moved.forEach { (_, newKey, value) ->
            when (value) {
                is String -> editor.putString(newKey, value)
                is Boolean -> editor.putBoolean(newKey, value)
                is Int -> editor.putInt(newKey, value)
                is Long -> editor.putLong(newKey, value)
                is Float -> editor.putFloat(newKey, value)
                is Set<*> -> editor.putStringSet(newKey, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit()) { "BeeKeep could not safely re-key an inspection draft." }
    }

    internal suspend fun isLocalAttachmentRoomApplyCommitted(
        snapshotSha256: String,
        accountId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val marker = db.localDataAttachmentMarkers().get(snapshotSha256) ?: return@withContext false
        marker.accountId == accountId
    }

    /**
     * Verifies that every live cloud entity is represented locally and every cloud tombstone is
     * absent. Draft documents remain in their own preference stores and are handled separately.
     */
    internal suspend fun verifyPersonalAttachmentDocuments(
        documents: Collection<com.beekeep.app.cloud.CloudDocumentRow>
    ): Boolean = withContext(Dispatchers.IO) {
        check(_activeWorkspaceId.value == null) { "Personal attachment verification requires the personal dataset." }
        val database = db
        val localIds = mapOf(
            "apiary" to database.apiaries().allForBackup().map { it.id }.toSet(),
            "hive" to database.hives().allForBackup().map { it.id }.toSet(),
            "nfc_assignment" to database.nfcTagAssignments().allForBackup().map { it.id }.toSet(),
            "inspection" to database.inspections().allForBackup().map { it.id }.toSet(),
            "feeding" to database.feedings().allForBackup().map { it.id }.toSet(),
            "treatment" to database.treatments().allForBackup().map { it.id }.toSet(),
            "harvest" to database.harvests().allForBackup().map { it.id }.toSet(),
            "event" to database.events().allForBackup().map { it.id }.toSet()
        )
        documents.all { row ->
            val present = localIds[row.entity_type]?.contains(row.entity_id) ?: return@all true
            if (row.deleted) !present else present
        }
    }

    /** Prevent a second copy of any attachment-era outbox operation from being replayed. */
    internal suspend fun holdPendingAttachmentQueues() = withContext(Dispatchers.IO) {
        val database = db
        database.withTransaction {
            database.outbox().holdPendingForAttachment()
            database.actionBatchOutbox().holdPendingForAttachment()
            database.workspaceActionOutbox().holdPendingForAttachment()
        }
    }

    private data class AttachmentSnapshotParts(
        val json: String,
        val recordCounts: Map<String, Int>,
        val pendingSyncWrites: Int,
        val pendingActionBatches: Int,
        val pendingWorkspaceActions: Int,
        val noteDrafts: Int,
        val formDrafts: Int
    )

    private fun personalDraftPreferencesSnapshot(
        preferences: SharedPreferences,
        includeKey: (String) -> Boolean
    ): JSONArray = JSONArray().apply {
        preferences.all.entries.asSequence()
            .filter { includeKey(it.key) }
            .sortedBy { it.key }
            .forEach { (key, value) ->
                val item = JSONObject().put("key", key)
                when (value) {
                    is String -> item.put("type", "string").put("value", value)
                    is Boolean -> item.put("type", "boolean").put("value", value)
                    is Int -> item.put("type", "int").put("value", value)
                    is Long -> item.put("type", "long").put("value", value)
                    is Float -> item.put("type", "float").put("value", value.toDouble())
                    is Set<*> -> item.put("type", "string_set").put(
                        "value",
                        JSONArray().apply {
                            value.filterIsInstance<String>().sorted().forEach { put(it) }
                        }
                    )
                    else -> item.put("type", "unsupported").put("value", value.toString())
                }
                put(item)
            }
    }

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
            val formDraftScopePrefix = InspectionFormDraftStore.keyPrefix(activeDraftWorkspaceId)
            val scopedFormDraftIds = inspectionFormDraftPrefs.all.keys.asSequence()
                .filter { it.startsWith(formDraftScopePrefix) }
                .mapNotNull { it.removePrefix(formDraftScopePrefix).toLongOrNull() }
                .filter { it > 0L }
                .toList()
            val legacyPersonalFormDraftIds = if (activeDraftWorkspaceId == null) {
                inspectionFormDraftPrefs.all.keys.asSequence()
                    .filter { it.startsWith("hive_") }
                    .mapNotNull { it.removePrefix("hive_").toLongOrNull() }
                    .filter { it > 0L }
                    .toList()
            } else emptyList()

            val resetAt = System.currentTimeMillis()
            val recordsToDelete = linkedSetOf<Pair<String, Long>>()
            apiaries.forEach { recordsToDelete.add("apiary" to it.id) }
            hives.forEach {
                recordsToDelete.add("hive" to it.id)
                // Also remove unfinished inspection state on every synced device.
                recordsToDelete.add("inspection_form_draft" to it.id)
            }
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
            (scopedFormDraftIds + legacyPersonalFormDraftIds).distinct().forEach {
                recordsToDelete.add("inspection_form_draft" to it)
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
            // A reset must not let a queued atomic batch or offline audit replay
            // recreate business records that the user intentionally cleared.
            db.actionBatchOutbox().clearAll()
            db.workspaceActionOutbox().clearAll()

            recordsToDelete.forEach { (type, id) ->
                val draftKey = inspectionNoteDraftKey(activeDraftWorkspaceId, id)
                val tombstoneAt = when (type) {
                    "inspection_note_draft" -> maxOf(
                        resetAt, inspectionNoteDraftPrefs.getLong(inspectionNoteDraftUpdatedAtKey(draftKey), 0L) + 1L
                    )
                    "inspection_form_draft" -> maxOf(
                        resetAt,
                        InspectionFormDraftStore.updatedAt(
                            InspectionFormDraftStore.readRaw(inspectionFormDraftPrefs, activeDraftWorkspaceId, id)
                        ) + 1L
                    )
                    else -> resetAt
                }
                val payload = JSONObject()
                    .put("id", id)
                    .put("updated_at", tombstoneAt)
                    .put("deleted", true)
                if (type == "inspection_form_draft") payload.put("hive_id", id)
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

            val formDraftEditor = inspectionFormDraftPrefs.edit()
            (scopedFormDraftIds + legacyPersonalFormDraftIds + hives.map { it.id }).distinct().forEach { hiveId ->
                val key = InspectionFormDraftStore.key(activeDraftWorkspaceId, hiveId)
                formDraftEditor.remove(key)
                    .remove(InspectionFormDraftStore.pendingKey(key))
                    .remove(InspectionFormDraftStore.beeCountHistoryStartKey(activeDraftWorkspaceId, hiveId))
                if (activeDraftWorkspaceId == null) {
                    formDraftEditor.remove(InspectionFormDraftStore.legacyBeeCountHistoryStartKey(hiveId))
                        .remove(InspectionFormDraftStore.legacyKey(hiveId))
                }
            }
            formDraftEditor.apply()

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
    /** Observe the selected hive row so local edits and incoming cloud updates refresh its detail UI. */
    fun observeHive(hiveId: Long): Flow<Hive?> =
        _database.flatMapLatest { it.hives().observeById(hiveId) }.map { it?.let(::toHive) }
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

    fun observeWorkspaceActionSettings(scopeKey: String): Flow<WorkspaceActionSettingsEntity?> =
        _database.flatMapLatest { it.workspaceActionSettings().observe(scopeKey) }

    suspend fun getWorkspaceActionSettings(scopeKey: String): WorkspaceActionSettingsEntity? =
        withContext(Dispatchers.IO) { db.workspaceActionSettings().get(scopeKey) }

    /** Save the local setting first. Signed-in changes remain pending until server sync confirms them. */
    suspend fun saveWorkspaceActionSettings(
        scopeKey: String,
        workspaceId: String?,
        ownerUserId: String?,
        harvestQuantityMode: HarvestQuantityMode,
        updatedBy: String?
    ): WorkspaceActionSettingsEntity = withContext(Dispatchers.IO) {
        checkWritable()
        require(scopeKey == workspaceActionSettingsScopeKey(workspaceId, ownerUserId)) {
            "The action settings scope does not match the selected workspace."
        }
        val cleanWorkspaceId = workspaceId?.trim()?.takeIf { it.isNotEmpty() }
        val entity = WorkspaceActionSettingsEntity(
            scopeKey = scopeKey,
            workspaceId = cleanWorkspaceId,
            ownerUserId = if (cleanWorkspaceId == null) {
                ownerUserId?.trim()?.takeIf { it.isNotEmpty() }
            } else null,
            harvestQuantityMode = harvestQuantityMode.storageValue,
            updatedAt = System.currentTimeMillis(),
            updatedBy = updatedBy?.trim()?.takeIf { it.isNotEmpty() },
            syncState = if (updatedBy.isNullOrBlank()) "local" else "pending",
            lastError = null
        )
        db.withTransaction { db.workspaceActionSettings().upsert(entity) }
        entity
    }

    /**
     * Apply a server snapshot unless a newer offline edit is waiting to upload.
     * The server timestamp is authoritative after a successful upsert.
     */
    suspend fun applyRemoteWorkspaceActionSettings(
        remote: WorkspaceActionSettingsEntity,
        preservePendingLocal: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val activeDb = db
        activeDb.withTransaction {
            val current = activeDb.workspaceActionSettings().get(remote.scopeKey)
            if (
                preservePendingLocal &&
                current != null &&
                current.syncState in setOf("pending", "local") &&
                current.updatedAt > remote.updatedAt
            ) {
                false
            } else {
                activeDb.workspaceActionSettings().upsert(
                    remote.copy(
                        harvestQuantityMode = HarvestQuantityMode.fromStorage(remote.harvestQuantityMode).storageValue,
                        syncState = "synced",
                        lastError = null
                    )
                )
                true
            }
        }
    }

    suspend fun setWorkspaceActionSettingsSyncState(
        scopeKey: String,
        state: String,
        error: String? = null
    ) = withContext(Dispatchers.IO) {
        db.workspaceActionSettings().setSyncState(scopeKey, state, error)
    }

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
            val quotaLimits = activeQuotaLimits
            val apiaryLimit = quotaLimits.apiaryLimit
            val currentApiaryCount = existingApiaries.count { !it.deleted }
            require(currentApiaryCount + cleanApiaryNames.size <= apiaryLimit) {
                BeeKeepEntitlements.apiaryLimitMessage(quotaLimits.plan)
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
            val hiveLimit = quotaLimits.hiveLimit
            require(requestedHiveCount == 0 || currentHiveCount + requestedHiveCount <= hiveLimit) {
                BeeKeepEntitlements.hiveLimitMessage(quotaLimits.plan, hiveLimit)
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
            val quotaLimits = activeQuotaLimits
            require(currentHiveCount < quotaLimits.hiveLimit) {
                BeeKeepEntitlements.hiveLimitMessage(quotaLimits.plan, quotaLimits.hiveLimit)
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
            val hive = db.hives().get(hiveId) ?: throw IllegalArgumentException("Hive not found.")
            val deletionTime = System.currentTimeMillis()
            enqueueHiveHistoryDeletions(hiveId, deletionTime)
            // Keep the last known Hive fields in the tombstone so the server trigger can
            // describe the deletion accurately without needing the deleted local row.
            enqueue(
                "hive",
                hiveId,
                "delete",
                hivePayload(hive.copy(updatedAt = deletionTime, deleted = true))
            )
            permanentlyDeleteHiveLocal(hiveId)
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
        // In-progress inspection fields and the live field-note buffer are separate cloud documents.
        InspectionFormDraftStore.markDeleted(inspectionFormDraftPrefs, _activeWorkspaceId.value, hiveId)
        val formDraftKey = InspectionFormDraftStore.key(_activeWorkspaceId.value, hiveId)
        val formDraftTombstone = InspectionFormDraftStore.readRaw(
            inspectionFormDraftPrefs, _activeWorkspaceId.value, hiveId
        ) ?: tombstone(hiveId)
        enqueue("inspection_form_draft", hiveId, "delete", formDraftTombstone)
        inspectionFormDraftPrefs.edit()
            .putBoolean(InspectionFormDraftStore.pendingKey(formDraftKey), false)
            .apply()
        enqueue("inspection_note_draft", hiveId, "delete", tombstone(hiveId))
    }

    private suspend fun permanentlyDeleteHiveLocal(hiveId: Long) {
        // A permanent parent deletion overrides any pending child draft on this device.
        // The local delete path queues draft tombstones before reaching this method.
        forceClearLocalInspectionNoteDraft(hiveId)
        InspectionFormDraftStore.clearForDeletedHive(
            inspectionFormDraftPrefs, _activeWorkspaceId.value, hiveId
        )
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
                enqueue(
                    "hive",
                    hive.id,
                    "delete",
                    hivePayload(hive.copy(updatedAt = deletionTime, deleted = true))
                )
                permanentlyDeleteHiveLocal(hive.id)
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
                // Clear a legacy cached UID without pretending the hive profile changed.
                // The UID history/tombstone below is the only shared mutation.
                db.hives().refreshTagCache(cachedHive.id)
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
                // This is a tag-cache-only change. The assignment ledger is authoritative;
                // do not publish a possibly stale full hive-profile snapshot.
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
            // A tag assignment changes UID -> hiveId, not the hive's profile.
            // Sync the ledger only; each client derives its tag cache from the ledger.
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

            // Only the assignment ledger is shared. The cached tag UID is derived locally;
            // do not advance the hive profile timestamp for a tag-only change.
            db.hives().refreshTagCache(hiveId)

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
            val previousInspection = db.inspections().get(i.id)
            val inspectionActionAt = maxOf(
                System.currentTimeMillis(),
                (previousInspection?.updatedAt ?: 0L) + 1L
            )
            val entity = InspectionEntity(
                id = i.id, hiveId = i.hiveId, createdAt = i.createdAt, strength = i.strength.coerceIn(0, 10),
                queenStatus = i.queenStatus.trim().ifBlank { "Laying" }, miteCount = safeMites, sampleSize = safeSample,
                notes = i.notes.trim(), latitude = i.latitude, longitude = i.longitude,
                emergencyCells = i.emergencyCells.coerceAtLeast(0), supercedureCells = i.supercedureCells.coerceAtLeast(0), swarmCells = i.swarmCells.coerceAtLeast(0),
                eggs = normalizeBroodStoresGauge(i.eggs), openBrood = normalizeBroodStoresGauge(i.openBrood), cappedBrood = normalizeBroodStoresGauge(i.cappedBrood),
                honeyStores = normalizeBroodStoresGauge(i.honeyStores), pollen = normalizeBroodStoresGauge(i.pollen), emptyDrawnComb = normalizeBroodStoresGauge(i.emptyDrawnComb),
                diseaseFlags = i.diseaseFlags.trim(), updatedAt = inspectionActionAt,
                beeCountFrames = safeBeeCountFrames
            )
            db.inspections().upsert(entity)
            // Refresh from the newest saved inspection, not necessarily the one just inserted
            // (a backfilled inspection can be older than the existing latest entry).
            refreshHiveInspectionSummary(i.hiveId, touchUpdatedAt = true)
            val event = ActivityEventEntity(i.id, i.hiveId, i.createdAt, "inspection", "Inspection", "Strength ${ColonyStrength.gradeFromStored(entity.strength)}/5 • ${ColonyStrength.labelFromStored(entity.strength)} • Mites ${String.format(java.util.Locale.US, "%.2f", entity.mitePercent)}%")
            db.events().upsert(event)
            enqueue("inspection", i.id, "upsert", inspectionPayload(entity))
            // Hive strength/queen/mites are a derived cache of inspection history.
            // Other devices recompute that summary when this inspection syncs; publishing
            // a second hive mutation here would create a misleading duplicate Actions row.
            enqueue("event", event.id, "upsert", eventPayload(event))
        }
    }

    suspend fun saveFeeding(f: Feeding) = withContext(Dispatchers.IO) {
        checkWritable()
        require(f.feedType.trim().isNotBlank()) { "Feed type cannot be blank" }
        require(f.amount.isFinite() && f.amount >= 0.0) { "Feeding amount must be a valid non-negative number" }
        require(f.unit.trim().isNotBlank()) { "Feeding unit cannot be blank" }
        db.withTransaction {
            val hive = db.hives().get(f.hiveId) ?: throw IllegalArgumentException("Hive not found.")
            val entity = FeedingEntity(
                id = f.id, hiveId = f.hiveId, createdAt = f.createdAt,
                feedType = f.feedType.trim(), ratio = f.ratio.trim(), amount = f.amount,
                unit = f.unit.trim(), notes = f.notes.trim(), recordUuid = f.recordUuid,
                apiaryId = f.apiaryId ?: hive.apiaryId, batchId = f.batchId,
                occurredAt = f.occurredAt, createdBy = f.createdBy ?: currentAccountUserId(),
                deviceId = f.deviceId ?: currentDeviceId()
            )
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
            val hive = db.hives().get(t.hiveId) ?: throw IllegalArgumentException("Hive not found.")
            val entity = TreatmentEntity(
                id = t.id, hiveId = t.hiveId, createdAt = t.createdAt,
                treatmentType = t.treatmentType.trim(), product = t.product.trim(),
                insertedAt = t.insertedAt, removalAt = t.removalAt,
                withdrawalUntil = t.withdrawalUntil, notes = t.notes.trim(),
                recordUuid = t.recordUuid, apiaryId = t.apiaryId ?: hive.apiaryId,
                batchId = t.batchId, occurredAt = t.occurredAt,
                createdBy = t.createdBy ?: currentAccountUserId(),
                deviceId = t.deviceId ?: currentDeviceId()
            )
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
            val hive = db.hives().get(h.hiveId) ?: throw IllegalArgumentException("Hive not found.")
            val entity = HarvestEntity(
                id = h.id, hiveId = h.hiveId, createdAt = h.createdAt,
                supersPulled = h.supersPulled.coerceAtLeast(0),
                wetHoneyWeight = h.wetHoneyWeight, dryHoneyWeight = h.dryHoneyWeight,
                weightUnit = h.weightUnit.trim(), waxWeight = h.waxWeight,
                propolisWeight = h.propolisWeight, notes = h.notes.trim(),
                recordUuid = h.recordUuid, apiaryId = h.apiaryId ?: hive.apiaryId,
                batchId = h.batchId, occurredAt = h.occurredAt,
                createdBy = h.createdBy ?: currentAccountUserId(),
                deviceId = h.deviceId ?: currentDeviceId()
            )
            db.harvests().upsert(entity)
            val event = ActivityEventEntity(h.id, h.hiveId, h.createdAt, "harvest", "Harvest", "${entity.dryHoneyWeight} ${entity.weightUnit} dry honey")
            db.events().upsert(event)
            enqueue("harvest", h.id, "upsert", harvestPayload(entity))
            enqueue("event", event.id, "upsert", eventPayload(event))
        }
    }


    private suspend fun resolveApiaryBatchTargets(
        database: BeeKeepRoomDb,
        apiaryId: Long,
        apiaryName: String,
        targetHiveIds: List<Long>
    ): List<HiveEntity> {
        require(targetHiveIds.isNotEmpty()) { "Select at least one hive." }
        require(targetHiveIds.distinct().size == targetHiveIds.size) {
            "The selected hive list contains duplicates. Refresh and try again."
        }
        val apiary = database.apiaries().get(apiaryId)
            ?: throw IllegalArgumentException("Apiary not found. Refresh and try again.")
        require(!apiary.deleted && apiary.name.equals(apiaryName.trim(), ignoreCase = true)) {
            "This apiary changed while the action was open. Reopen it and try again."
        }
        val eligibleById = database.hives()
            .forApiaryRename(apiary.id, apiary.name)
            .filter { !it.deleted && it.status == HiveStatus.ACTIVE }
            .associateBy { it.id }
        val missingIds = targetHiveIds.filterNot { it in eligibleById }
        require(missingIds.isEmpty()) {
            "One or more selected hives are no longer active in this apiary. Refresh and try again."
        }
        return targetHiveIds.map { eligibleById.getValue(it) }
            .sortedWith(
                compareBy<HiveEntity> { it.number.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.number.lowercase(java.util.Locale.ROOT) }
                    .thenBy { it.id }
            )
    }

    private fun <T> validateApiaryBatchRecords(
        apiaryId: Long,
        targetHiveIds: List<Long>,
        records: List<T>,
        batchIdOf: (T) -> String?,
        hiveIdOf: (T) -> Long,
        apiaryIdOf: (T) -> Long?,
        recordIdOf: (T) -> Long
    ): String {
        require(records.size == targetHiveIds.size) {
            "Every selected hive must receive exactly one record."
        }
        val recordHiveIds = records.map(hiveIdOf)
        require(recordHiveIds.distinct().size == records.size &&
            recordHiveIds.toSet() == targetHiveIds.toSet()
        ) {
            "The batch records do not match the selected hives."
        }
        require(records.all { apiaryIdOf(it) == apiaryId }) {
            "The batch contains a record for a different apiary."
        }
        val batchIds = records.mapNotNull(batchIdOf).distinct()
        require(batchIds.size == 1 && records.all { !batchIdOf(it).isNullOrBlank() }) {
            "Every record in an apiary action must share one batch identifier."
        }
        val recordIds = records.map(recordIdOf)
        require(recordIds.all { it > 0L } && recordIds.distinct().size == records.size) {
            "The batch contains an invalid or duplicate record identifier."
        }
        return batchIds.single()
    }

    private fun actionBatchDocument(
        entityType: String,
        entityId: Long,
        payload: String,
        updatedAt: Long,
        deviceId: String
    ): JSONObject = JSONObject()
        .put("entity_type", entityType)
        .put("entity_id", entityId)
        .put("payload", payload)
        .put("updated_at", updatedAt)
        .put("deleted", false)
        .put("device_id", deviceId)

    private fun batchEventPayload(
        event: ActivityEventEntity,
        batchId: String,
        apiaryId: Long
    ): String = JSONObject()
        .put("id", event.id)
        .put("hive_id", event.hiveId)
        .put("created_at", event.createdAt)
        .put("updated_at", event.createdAt)
        .put("type", event.type)
        .put("title", event.title)
        .put("detail", event.detail)
        .put("apiary_id", apiaryId)
        .put("batch_id", batchId)
        .toString()

    private suspend fun enqueueActionBatch(
        database: BeeKeepRoomDb,
        batchId: String,
        workspaceId: String?,
        apiaryId: Long,
        apiaryName: String,
        actionType: String,
        documents: JSONArray
    ) {
        checkWritable()
        check(_activeWorkspaceId.value == workspaceId && db === database) {
            "The active workspace changed. Reopen the apiary and try again."
        }
        val payload = JSONObject()
            .put("schema_version", 1)
            .put("action_type", actionType)
            .put("apiary_id", apiaryId)
            .put("apiary_name", apiaryName)
            .put("batch_id", batchId)
            .put("documents", documents)
            .toString()
        database.actionBatchOutbox().enqueue(
            ActionBatchOutboxEntity(
                batchId = batchId,
                workspaceId = workspaceId,
                schemaVersion = 1,
                payload = payload,
                createdAt = System.currentTimeMillis(),
                state = "pending",
                lastError = null
            )
        )
    }

    /**
     * Saves one feeding entry per selected hive in one Room transaction.
     * Every row shares a batch_id, while its own record_uuid and hive_id stay unique.
     */
    suspend fun saveFeedingBatch(
        apiaryId: Long,
        apiaryName: String,
        targetHiveIds: List<Long>,
        records: List<Feeding>
    ): Int = withContext(Dispatchers.IO) {
        checkWritable()
        require(records.all {
            it.feedType.trim().isNotBlank() &&
                it.amount.isFinite() && it.amount > 0.0 &&
                it.unit.trim().isNotBlank()
        }) { "Every feeding record needs a type, a positive amount, and a unit." }

        val workspaceAtStart = _activeWorkspaceId.value
        val database = db
        check(_activeWorkspaceId.value == workspaceAtStart && db === database) {
            "The active workspace changed. Reopen the apiary and try again."
        }
        database.withTransaction {
            checkWritable()
            check(_activeWorkspaceId.value == workspaceAtStart && db === database) {
                "The active workspace changed. Reopen the apiary and try again."
            }
            val targets = resolveApiaryBatchTargets(database, apiaryId, apiaryName, targetHiveIds)
            val batchId = validateApiaryBatchRecords(
                apiaryId, targetHiveIds, records,
                { it.batchId }, { it.hiveId }, { it.apiaryId }, { it.id }
            )
            val byHiveId = records.associateBy { it.hiveId }
            val accountId = currentAccountUserId()
            val deviceId = currentDeviceId()
            val batchDocuments = JSONArray()
            targets.forEach { hive ->
                val record = byHiveId.getValue(hive.id)
                val entity = FeedingEntity(
                    id = record.id,
                    hiveId = hive.id,
                    createdAt = record.createdAt,
                    feedType = record.feedType.trim(),
                    ratio = record.ratio.trim(),
                    amount = record.amount,
                    unit = record.unit.trim(),
                    notes = record.notes.trim(),
                    recordUuid = record.recordUuid,
                    apiaryId = apiaryId,
                    batchId = batchId,
                    occurredAt = record.occurredAt,
                    createdBy = record.createdBy ?: accountId,
                    deviceId = record.deviceId ?: deviceId
                )
                database.feedings().upsert(entity)
                val event = ActivityEventEntity(
                    entity.id, hive.id, entity.createdAt, "feeding", "Feeding",
                    "${entity.amount} ${entity.unit} • ${entity.feedType} ${entity.ratio}"
                )
                database.events().upsert(event)
                batchDocuments.put(actionBatchDocument(
                    "feeding", entity.id, feedingPayload(entity), entity.createdAt, entity.deviceId ?: deviceId
                ))
                batchDocuments.put(actionBatchDocument(
                    "event", event.id, batchEventPayload(event, batchId, apiaryId), entity.createdAt, entity.deviceId ?: deviceId
                ))
            }
            enqueueActionBatch(
                database, batchId, workspaceAtStart, apiaryId, apiaryName, "feed", batchDocuments
            )
            check(_activeWorkspaceId.value == workspaceAtStart && db === database) {
                "The active workspace changed. Reopen the apiary and try again."
            }
            targets.size
        }
    }

    /**
     * Saves one treatment entry per selected hive in one Room transaction.
     */
    suspend fun saveTreatmentBatch(
        apiaryId: Long,
        apiaryName: String,
        targetHiveIds: List<Long>,
        records: List<Treatment>
    ): Int = withContext(Dispatchers.IO) {
        checkWritable()
        require(records.all {
            it.treatmentType.trim().isNotBlank() && it.product.trim().isNotBlank()
        }) { "Every treatment record needs a type and product." }

        val workspaceAtStart = _activeWorkspaceId.value
        val database = db
        check(_activeWorkspaceId.value == workspaceAtStart && db === database) {
            "The active workspace changed. Reopen the apiary and try again."
        }
        database.withTransaction {
            checkWritable()
            check(_activeWorkspaceId.value == workspaceAtStart && db === database) {
                "The active workspace changed. Reopen the apiary and try again."
            }
            val targets = resolveApiaryBatchTargets(database, apiaryId, apiaryName, targetHiveIds)
            val batchId = validateApiaryBatchRecords(
                apiaryId, targetHiveIds, records,
                { it.batchId }, { it.hiveId }, { it.apiaryId }, { it.id }
            )
            val byHiveId = records.associateBy { it.hiveId }
            val accountId = currentAccountUserId()
            val deviceId = currentDeviceId()
            val batchDocuments = JSONArray()
            targets.forEach { hive ->
                val record = byHiveId.getValue(hive.id)
                val entity = TreatmentEntity(
                    id = record.id,
                    hiveId = hive.id,
                    createdAt = record.createdAt,
                    treatmentType = record.treatmentType.trim(),
                    product = record.product.trim(),
                    insertedAt = record.insertedAt,
                    removalAt = record.removalAt,
                    withdrawalUntil = record.withdrawalUntil,
                    notes = record.notes.trim(),
                    recordUuid = record.recordUuid,
                    apiaryId = apiaryId,
                    batchId = batchId,
                    occurredAt = record.occurredAt,
                    createdBy = record.createdBy ?: accountId,
                    deviceId = record.deviceId ?: deviceId
                )
                database.treatments().upsert(entity)
                val event = ActivityEventEntity(
                    entity.id, hive.id, entity.createdAt, "treatment", "Treatment",
                    "${entity.treatmentType} • ${entity.product}"
                )
                database.events().upsert(event)
                batchDocuments.put(actionBatchDocument(
                    "treatment", entity.id, treatmentPayload(entity), entity.createdAt, entity.deviceId ?: deviceId
                ))
                batchDocuments.put(actionBatchDocument(
                    "event", event.id, batchEventPayload(event, batchId, apiaryId), entity.createdAt, entity.deviceId ?: deviceId
                ))
            }
            enqueueActionBatch(
                database, batchId, workspaceAtStart, apiaryId, apiaryName, "treat", batchDocuments
            )
            check(_activeWorkspaceId.value == workspaceAtStart && db === database) {
                "The active workspace changed. Reopen the apiary and try again."
            }
            targets.size
        }
    }

    /**
     * Saves one harvest entry per selected hive in one Room transaction.
     * Quantity allocation is prepared by the ViewModel and then verified as a complete batch here.
     */
    suspend fun saveHarvestBatch(
        apiaryId: Long,
        apiaryName: String,
        targetHiveIds: List<Long>,
        records: List<Harvest>
    ): Int = withContext(Dispatchers.IO) {
        checkWritable()
        require(records.all {
            it.weightUnit.trim().isNotBlank() &&
                it.supersPulled >= 0 &&
                listOf(it.wetHoneyWeight, it.dryHoneyWeight, it.waxWeight, it.propolisWeight)
                    .all { value -> value.isFinite() && value >= 0.0 }
        }) { "Every harvest record must contain valid non-negative quantities and a weight unit." }

        val workspaceAtStart = _activeWorkspaceId.value
        val database = db
        check(_activeWorkspaceId.value == workspaceAtStart && db === database) {
            "The active workspace changed. Reopen the apiary and try again."
        }
        database.withTransaction {
            checkWritable()
            check(_activeWorkspaceId.value == workspaceAtStart && db === database) {
                "The active workspace changed. Reopen the apiary and try again."
            }
            val targets = resolveApiaryBatchTargets(database, apiaryId, apiaryName, targetHiveIds)
            val batchId = validateApiaryBatchRecords(
                apiaryId, targetHiveIds, records,
                { it.batchId }, { it.hiveId }, { it.apiaryId }, { it.id }
            )
            val byHiveId = records.associateBy { it.hiveId }
            val accountId = currentAccountUserId()
            val deviceId = currentDeviceId()
            val batchDocuments = JSONArray()
            targets.forEach { hive ->
                val record = byHiveId.getValue(hive.id)
                val entity = HarvestEntity(
                    id = record.id,
                    hiveId = hive.id,
                    createdAt = record.createdAt,
                    supersPulled = record.supersPulled,
                    wetHoneyWeight = record.wetHoneyWeight,
                    dryHoneyWeight = record.dryHoneyWeight,
                    weightUnit = record.weightUnit.trim(),
                    waxWeight = record.waxWeight,
                    propolisWeight = record.propolisWeight,
                    notes = record.notes.trim(),
                    recordUuid = record.recordUuid,
                    apiaryId = apiaryId,
                    batchId = batchId,
                    occurredAt = record.occurredAt,
                    createdBy = record.createdBy ?: accountId,
                    deviceId = record.deviceId ?: deviceId
                )
                database.harvests().upsert(entity)
                val event = ActivityEventEntity(
                    entity.id, hive.id, entity.createdAt, "harvest", "Harvest",
                    "${entity.dryHoneyWeight} ${entity.weightUnit} dry honey"
                )
                database.events().upsert(event)
                batchDocuments.put(actionBatchDocument(
                    "harvest", entity.id, harvestPayload(entity), entity.createdAt, entity.deviceId ?: deviceId
                ))
                batchDocuments.put(actionBatchDocument(
                    "event", event.id, batchEventPayload(event, batchId, apiaryId), entity.createdAt, entity.deviceId ?: deviceId
                ))
            }
            enqueueActionBatch(
                database, batchId, workspaceAtStart, apiaryId, apiaryName, "harvest", batchDocuments
            )
            check(_activeWorkspaceId.value == workspaceAtStart && db === database) {
                "The active workspace changed. Reopen the apiary and try again."
            }
            targets.size
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
                val quotaLimits = activeQuotaLimits
                require(activeApiaryCount < quotaLimits.apiaryLimit) {
                    BeeKeepEntitlements.apiaryLimitMessage(quotaLimits.plan)
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

    /**
     * Recompute the hive's inspection-derived summary without changing its other profile
     * fields. The optional timestamp bump is for a locally-entered inspection whose
     * summary must be propagated as a normal hive update.
     */
    private suspend fun refreshHiveInspectionSummary(
        hiveId: Long,
        touchUpdatedAt: Boolean = false
    ) {
        val hive = db.hives().get(hiveId) ?: return
        val latest = db.inspections().latest(hiveId) ?: return
        var merged = hive.withInspectionSummary(latest)
        if (touchUpdatedAt) {
            merged = merged.copy(updatedAt = maxOf(System.currentTimeMillis(), hive.updatedAt + 1L))
        }
        if (merged != hive) db.hives().upsert(merged)
    }

    /**
     * Recover unfinished inspection fields that were persisted locally before a process
     * death or before the debounced cloud sync ran. Mirrors the notes-draft recovery path.
     */
    suspend fun queuePendingInspectionFormDrafts(): Int = withContext(Dispatchers.IO) {
        if (_activeWorkspaceReadOnly.value) return@withContext 0
        val workspaceId = _activeWorkspaceId.value
        val prefix = InspectionFormDraftStore.keyPrefix(workspaceId)
        val hiveIds = inspectionFormDraftPrefs.all.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).toLongOrNull() }
            .filter { it > 0L }
            .distinct()
            .toList()
        var queued = 0
        for (hiveId in hiveIds) {
            if (!InspectionFormDraftStore.isPending(inspectionFormDraftPrefs, workspaceId, hiveId)) continue
            val raw = InspectionFormDraftStore.readRaw(inspectionFormDraftPrefs, workspaceId, hiveId) ?: continue
            val payload = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val updatedAt = InspectionFormDraftStore.updatedAt(raw)
            if (updatedAt <= 0L) continue
            payload.put("id", hiveId).put("hive_id", hiveId).put("updated_at", updatedAt).put("updatedAt", updatedAt)
            val operation = if (payload.optBoolean("deleted", false)) "delete" else "upsert"
            enqueue("inspection_form_draft", hiveId, operation, payload.toString())
            inspectionFormDraftPrefs.edit()
                .putBoolean(InspectionFormDraftStore.pendingKey(InspectionFormDraftStore.key(workspaceId, hiveId)), false)
                .apply()
            queued++
        }
        queued
    }

    /**
     * Drop old full-hive writes queued by builds where assigning/removing an NFC tag also
     * bumped the hive profile timestamp. Publishing those rows can replace newer queen,
     * strength, and location details with an outdated whole-profile snapshot.
     */
    internal suspend fun nfcMutationSyncTimestamps(): List<NfcMutationSyncTimestamp> =
        withContext(Dispatchers.IO) {
            val database = db
            buildList {
                database.nfcTagAssignments().allForBackup().forEach { assignment ->
                    if (assignment.assignedAt > 0L) {
                        add(NfcMutationSyncTimestamp(assignment.hiveId, assignment.assignedAt))
                    }
                    assignment.unassignedAt?.takeIf { it > 0L }?.let {
                        add(NfcMutationSyncTimestamp(assignment.hiveId, it))
                    }
                }
                database.events().allForBackup()
                    .filter {
                        it.type.equals("tag", ignoreCase = true) &&
                            it.title.startsWith("NFC tag ", ignoreCase = true)
                    }
                    .forEach { event ->
                        if (event.createdAt > 0L) {
                            add(NfcMutationSyncTimestamp(event.hiveId, event.createdAt))
                        }
                    }
            }
        }

    suspend fun discardLegacyNfcProfileSnapshots(): Int = withContext(Dispatchers.IO) {
        if (_activeWorkspaceReadOnly.value) return@withContext 0

        val database = db
        val mutations = nfcMutationSyncTimestamps()
        var discarded = 0
        database.outbox().allPendingForReset()
            .filter { it.entityType == "hive" && it.operation == "upsert" }
            .forEach { item ->
                val updatedAt = runCatching {
                    JSONObject(item.payload).optLong("updated_at", item.createdAt)
                }.getOrDefault(item.createdAt)
                if (LegacyNfcProfileSnapshotGuard.isLegacyNfcOnlyHiveSnapshot(
                        item.entityId, updatedAt, mutations
                    )
                ) {
                    database.outbox().setState(item.id, "done")
                    discarded++
                }
            }
        discarded
    }

    suspend fun pendingSync(limit: Int = 50): List<SyncOutboxEntity> = db.outbox().pending(limit)
    suspend fun pendingActionBatches(limit: Int = 20): List<ActionBatchOutboxEntity> =
        withContext(Dispatchers.IO) { db.actionBatchOutbox().pending(limit) }

    suspend fun pendingWorkspaceActionChanges(
        workspaceId: String,
        limit: Int = 50
    ): List<WorkspaceActionOutboxEntity> = withContext(Dispatchers.IO) {
        db.workspaceActionOutbox().pending(workspaceId, limit)
    }

    suspend fun completeWorkspaceActionChanges(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        ids.distinct().chunked(500).forEach { batch ->
            db.workspaceActionOutbox().markDone(batch)
        }
    }

    suspend fun markWorkspaceActionChangesError(ids: Collection<Long>, message: String) = withContext(Dispatchers.IO) {
        if (ids.isNotEmpty()) {
            db.workspaceActionOutbox().markPendingWithError(ids.distinct(), message.take(500))
        }
    }

    suspend fun hasPendingWorkspaceActionChanges(workspaceId: String): Boolean =
        withContext(Dispatchers.IO) {
            db.workspaceActionOutbox().pendingCount(workspaceId) > 0
        }

    suspend fun completeActionBatch(batchId: String) = withContext(Dispatchers.IO) {
        val database = db
        database.withTransaction {
            database.actionBatchOutbox().setState(batchId, "done")
            database.actionBatchOutbox().deleteCompleted(batchId)
        }
    }

    suspend fun markActionBatchError(batchId: String, message: String) = withContext(Dispatchers.IO) {
        val database = db
        database.actionBatchOutbox().setState(batchId, "pending", message.take(500))
    }

    suspend fun hasPendingActionBatches(): Boolean = withContext(Dispatchers.IO) {
        db.actionBatchOutbox().pending(1).isNotEmpty()
    }

    suspend fun markSyncDone(id: Long) = db.outbox().setState(id, "done")
    suspend fun markSyncDone(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        ids.distinct().chunked(500).forEach { batch ->
            db.outbox().markDone(batch)
        }
    }
    suspend fun markSyncError(id: Long, message: String) = db.outbox().setState(id, "pending", message.take(500))
    suspend fun pruneSync() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000
        db.outbox().pruneDone(cutoff)
        db.workspaceActionOutbox().pruneDone(cutoff)
    }

    private fun clearLocalInspectionFormDraft(hiveId: Long, deletedAt: Long) {
        InspectionFormDraftStore.clearRemote(
            inspectionFormDraftPrefs, _activeWorkspaceId.value, hiveId, deletedAt
        )
    }

    private fun forceClearLocalInspectionNoteDraft(hiveId: Long) {
        val keys = inspectionNoteDraftKeysForDeletedHive(_activeWorkspaceId.value, hiveId)
        val editor = inspectionNoteDraftPrefs.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }

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

    suspend fun applyCloudDocument(
        doc: CloudDocumentRow,
        ignoreLegacyNfcProfileSnapshot: Boolean = false
    ) = withContext(Dispatchers.IO) {
        applyCloudDocumentInternal(doc, ignoreLegacyNfcProfileSnapshot)
    }

    /**
     * Applies dependency-ordered cloud records in bounded Room transactions.
     *
     * Each chunk is committed atomically, so observers see a coherent batch instead of
     * invalidating and reloading the UI for every individual cloud row. The bounded size
     * avoids holding the database transaction for an entire large workspace download.
     */
    suspend fun applyCloudDocuments(
        documents: List<CloudDocumentRow>,
        ignoreLegacyNfcProfileSnapshotHiveIds: Set<Long> = emptySet(),
        batchSize: Int = 100
    ) = withContext(Dispatchers.IO) {
        require(batchSize in 1..500) { "Cloud apply batch size must be between 1 and 500." }
        documents.chunked(batchSize).forEach { batch ->
            db.withTransaction {
                batch.forEach { document ->
                    applyCloudDocumentInternal(
                        document,
                        ignoreLegacyNfcProfileSnapshot =
                            document.entity_type == "hive" &&
                                document.entity_id in ignoreLegacyNfcProfileSnapshotHiveIds
                    )
                }
            }
        }
    }

    private suspend fun applyCloudDocumentInternal(
        doc: CloudDocumentRow,
        ignoreLegacyNfcProfileSnapshot: Boolean
    ) {
        if (doc.deleted) {
            when (doc.entity_type) {
                "inspection_note_draft" -> clearLocalInspectionNoteDraft(doc.entity_id, doc.updated_at)
                "inspection_form_draft" -> clearLocalInspectionFormDraft(doc.entity_id, doc.updated_at)
                // Permanent cloud tombstones are authoritative even when a device's local
                // clock is ahead. The server prevents these identities from being revived.
                "apiary" -> db.apiaries().get(doc.entity_id)?.let {
                    db.withTransaction { db.apiaries().hardDelete(doc.entity_id) }
                }
                "hive" -> db.hives().get(doc.entity_id)?.let {
                    db.withTransaction { permanentlyDeleteHiveLocal(doc.entity_id) }
                }
                "inspection" -> db.inspections().get(doc.entity_id)?.let { existing ->
                    db.withTransaction { db.inspections().hardDelete(doc.entity_id) }
                    refreshHiveInspectionSummary(existing.hiveId)
                }
                "feeding" -> db.feedings().get(doc.entity_id)?.let {
                    db.withTransaction { db.feedings().hardDelete(doc.entity_id) }
                }
                "treatment" -> db.treatments().get(doc.entity_id)?.let {
                    db.withTransaction { db.treatments().hardDelete(doc.entity_id) }
                }
                "harvest" -> db.harvests().get(doc.entity_id)?.let {
                    db.withTransaction { db.harvests().hardDelete(doc.entity_id) }
                }
                "event" -> db.events().get(doc.entity_id)?.let {
                    db.withTransaction { db.events().hardDelete(doc.entity_id) }
                }
                "nfc_assignment" -> db.nfcTagAssignments().get(doc.entity_id)?.let { assignment ->
                    db.withTransaction {
                        db.nfcTagAssignments().hardDelete(doc.entity_id)
                        db.hives().refreshTagCache(assignment.hiveId)
                    }
                }
            }
            return
        }
        // Older clients published a complete hive profile just to assign/remove its NFC tag.
        // If the batch identifies that legacy snapshot, keep an existing local profile intact;
        // the canonical assignment/event rows are still applied and refresh the tag cache.
        if (doc.entity_type == "hive" && ignoreLegacyNfcProfileSnapshot &&
            db.hives().get(doc.entity_id) != null
        ) return

        val json = runCatching { JSONObject(doc.payload) }.getOrNull() ?: return
        when (doc.entity_type) {
            "inspection_note_draft" -> {
                val workspaceId = _activeWorkspaceId.value
                val key = inspectionNoteDraftKey(workspaceId, doc.entity_id)
                val incomingUpdatedAt = json.optLong("updated_at", doc.updated_at)
                val localUpdatedAt = inspectionNoteDraftPrefs.getLong(inspectionNoteDraftUpdatedAtKey(key), 0L)
                // A local edit may already be persisted but not yet in the Room outbox.
                // Preserve it until the pre-sync recovery path queues it.
                val localPending = inspectionNoteDraftPrefs.getBoolean(
                    inspectionNoteDraftPendingKey(key), false
                )
                if (!shouldApplyInspectionNoteDraft(incomingUpdatedAt, localUpdatedAt, localPending)) return

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
            "inspection_form_draft" -> {
                val workspaceId = _activeWorkspaceId.value
                val hiveId = json.optLong("hive_id", doc.entity_id)
                if (!db.hives().exists(hiveId)) return
                if (InspectionFormDraftStore.isPending(inspectionFormDraftPrefs, workspaceId, hiveId)) return

                val incomingUpdatedAt = json.optLong("updated_at", doc.updated_at)
                val existingRaw = InspectionFormDraftStore.readRaw(inspectionFormDraftPrefs, workspaceId, hiveId)
                if (!CloudConflictPolicy.shouldApplyRemote(
                    incomingUpdatedAt, InspectionFormDraftStore.updatedAt(existingRaw)
                )) return

                // A completed inspection wins over an older unfinished form. A genuinely
                // newer draft may still be restored on another device.
                val latestInspection = db.inspections().latest(hiveId)
                if (latestInspection != null && latestInspection.createdAt >= incomingUpdatedAt) {
                    InspectionFormDraftStore.clearRemote(
                        inspectionFormDraftPrefs, workspaceId, hiveId, latestInspection.createdAt
                    )
                    return
                }
                InspectionFormDraftStore.applyRemote(
                    inspectionFormDraftPrefs, workspaceId, hiveId, doc.payload
                )
            }
            "apiary" -> {
                val existing = db.apiaries().get(doc.entity_id)
                val incomingUpdatedAt = json.optLong("updated_at", doc.updated_at)
                if (existing != null && !CloudConflictPolicy.shouldApplyRemote(incomingUpdatedAt, existing.updatedAt)) return
                db.apiaries().upsert(ApiaryEntity(
                    id = doc.entity_id,
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
                if (existing != null && !CloudConflictPolicy.shouldApplyRemote(incomingUpdatedAt, existing.updatedAt)) return
                val incomingStatusChangedAt = json.optLong("status_changed_at", 0L)
                // Lifecycle (ACTIVE/DEAD) resolves by status_changed_at so a stale
                // offline save can merge field edits without resurrecting the colony.
                val lifecycleWins = existing == null || incomingStatusChangedAt > existing.statusChangedAt
                val status = if (lifecycleWins) json.optString("status", HiveStatus.ACTIVE) else existing.status
                db.hives().upsert(HiveEntity(
                    id = doc.entity_id, number = json.optString("number").trim(),
                    apiaryName = json.optString("apiary").trim().ifBlank { "Unassigned Yard" }, apiaryId = json.longOrNull("apiary_id")?.takeIf { db.apiaries().get(it) != null },
                    queenStatus = json.optString("queen_status", "Laying"),
                    queenMarkColor = json.optString("queen_mark_color"), queenOrigin = json.optString("queen_origin"),
                    queenAgeMonths = json.intOrNull("queen_age_months"), queenTemperament = json.optInt("queen_temperament", 3).coerceIn(1, 5),
                    strength = json.optInt("strength", 0).coerceIn(0, 10), mitePercent = json.optDouble("mite_percent", 0.0).coerceAtLeast(0.0),
                    tagUid = existing?.tagUid ?: json.stringOrNull("tag_uid")?.let(NfcUid::normalize),
                    updatedAt = incomingUpdatedAt,
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
                // Older builds stored the physical UID only in hives.tag_uid. Preserve
                // that cache for truly legacy IDs, but do not synthesize a new active ledger row
                // while syncing the hive: its canonical assignment (including a release
                // tombstone) may still be waiting later in this same cloud batch.
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
                // Refresh the derived cache whenever this hive or its cached UID has ledger
                // history. Otherwise retain the tag cache as a legacy fallback until a tag
                // is explicitly assigned or released.
                val remoteTagUid = json.stringOrNull("tag_uid")?.let(NfcUid::normalize)
                val hiveHasLedgerHistory = db.nfcTagAssignments()
                    .allAssignmentsForHive(doc.entity_id).isNotEmpty()
                val uidHasLedgerHistory = remoteTagUid != null &&
                    db.nfcTagAssignments().allAssignmentsForTag(remoteTagUid).isNotEmpty()
                if (hiveHasLedgerHistory || uidHasLedgerHistory ||
                    status != HiveStatus.ACTIVE || json.optBoolean("deleted", false)
                ) {
                    db.hives().refreshTagCache(doc.entity_id)
                }
                // Inspection-derived strength/queen/mites stay authoritative on this device.
                refreshHiveInspectionSummary(doc.entity_id)
            }
            "nfc_assignment" -> {
                val incoming = NfcTagAssignmentEntity(
                    id = doc.entity_id,
                    tagUid = json.optString("tag_uid"),
                    hiveId = json.optLong("hive_id"),
                    assignedAt = json.optLong("assigned_at", doc.updated_at),
                    unassignedAt = json.longOrNull("unassigned_at")
                )
                if (incoming.tagUid.isNotBlank() && db.hives().exists(incoming.hiveId)) applyAssignment(incoming)
            }
            "inspection" -> {
                val hiveId = json.optLong("hive_id")
                if (!db.hives().exists(hiveId)) return
                val incoming = InspectionEntity(
                    id = doc.entity_id,
                    hiveId = hiveId,
                    createdAt = json.optLong("created_at", doc.updated_at),
                    strength = json.optInt("strength").coerceIn(0, 10),
                    queenStatus = json.optString("queen_status"),
                    miteCount = json.optInt("mite_count").coerceAtLeast(0),
                    sampleSize = json.optInt("sample_size", 1).coerceAtLeast(1),
                    notes = json.optString("notes"),
                    latitude = json.doubleOrNull("latitude"),
                    longitude = json.doubleOrNull("longitude"),
                    emergencyCells = json.optInt("emergency_cells").coerceAtLeast(0),
                    supercedureCells = json.optInt("supercedure_cells").coerceAtLeast(0),
                    swarmCells = json.optInt("swarm_cells").coerceAtLeast(0),
                    eggs = json.optDouble("eggs", 0.0).coerceAtLeast(0.0),
                    openBrood = json.optDouble("open_brood", 0.0).coerceAtLeast(0.0),
                    cappedBrood = json.optDouble("capped_brood", 0.0).coerceAtLeast(0.0),
                    honeyStores = json.optDouble("honey_stores", 0.0).coerceAtLeast(0.0),
                    pollen = json.optDouble("pollen", 0.0).coerceAtLeast(0.0),
                    emptyDrawnComb = json.optDouble("empty_drawn_comb", 0.0).coerceAtLeast(0.0),
                    diseaseFlags = json.optString("disease_flags"),
                    updatedAt = json.optLong("updated_at", doc.updated_at),
                    beeCountFrames = json.doubleOrNull("bee_count_frames")
                        ?.let { (it * 2.0).roundToInt().coerceIn(1, 20) / 2.0 }
                )
                val existing = db.inspections().get(incoming.id)
                if (existing == null || CloudConflictPolicy.shouldApplyRemote(incoming.updatedAt, existing.updatedAt)) {
                    db.inspections().upsert(incoming)
                }
                // Even if this copy was stale and ignored, restore the hive summary
                // from whichever inspection is actually latest in local history.
                refreshHiveInspectionSummary(hiveId)
            }
            "feeding" -> {
                val hiveId = json.optLong("hive_id")
                if (db.hives().exists(hiveId)) {
                    val existing = db.feedings().get(doc.entity_id)
                    val incoming = FeedingEntity(
                        id = doc.entity_id, hiveId = hiveId,
                        createdAt = json.optLong("created_at", doc.updated_at),
                        feedType = json.optString("feed_type"), ratio = json.optString("ratio"),
                        amount = json.optDouble("amount").coerceAtLeast(0.0),
                        unit = json.optString("unit"), notes = json.optString("notes"),
                        recordUuid = json.stringOrNull("record_uuid") ?: existing?.recordUuid ?: UUID.randomUUID().toString(),
                        apiaryId = json.longOrNull("apiary_id") ?: existing?.apiaryId,
                        batchId = json.stringOrNull("batch_id") ?: existing?.batchId,
                        occurredAt = json.optLong("occurred_at", json.optLong("created_at", doc.updated_at)),
                        createdBy = json.stringOrNull("created_by") ?: existing?.createdBy,
                        deviceId = json.stringOrNull("device_id") ?: existing?.deviceId ?: doc.device_id
                    )
                    if (existing == null || CloudConflictPolicy.shouldApplyRemote(incoming.createdAt, existing.createdAt)) {
                        db.feedings().upsert(incoming)
                    }
                }
            }
            "treatment" -> {
                val hiveId = json.optLong("hive_id")
                if (db.hives().exists(hiveId)) {
                    val existing = db.treatments().get(doc.entity_id)
                    val incoming = TreatmentEntity(
                        id = doc.entity_id, hiveId = hiveId,
                        createdAt = json.optLong("created_at", doc.updated_at),
                        treatmentType = json.optString("treatment_type"),
                        product = json.optString("product"),
                        insertedAt = json.longOrNull("inserted_at"),
                        removalAt = json.longOrNull("removal_at"),
                        withdrawalUntil = json.longOrNull("withdrawal_until"),
                        notes = json.optString("notes"),
                        recordUuid = json.stringOrNull("record_uuid") ?: existing?.recordUuid ?: UUID.randomUUID().toString(),
                        apiaryId = json.longOrNull("apiary_id") ?: existing?.apiaryId,
                        batchId = json.stringOrNull("batch_id") ?: existing?.batchId,
                        occurredAt = json.optLong("occurred_at", json.optLong("created_at", doc.updated_at)),
                        createdBy = json.stringOrNull("created_by") ?: existing?.createdBy,
                        deviceId = json.stringOrNull("device_id") ?: existing?.deviceId ?: doc.device_id
                    )
                    if (existing == null || CloudConflictPolicy.shouldApplyRemote(incoming.createdAt, existing.createdAt)) {
                        db.treatments().upsert(incoming)
                    }
                }
            }
            "harvest" -> {
                val hiveId = json.optLong("hive_id")
                if (db.hives().exists(hiveId)) {
                    val existing = db.harvests().get(doc.entity_id)
                    val incoming = HarvestEntity(
                        id = doc.entity_id, hiveId = hiveId,
                        createdAt = json.optLong("created_at", doc.updated_at),
                        supersPulled = json.optInt("supers_pulled").coerceAtLeast(0),
                        wetHoneyWeight = json.optDouble("wet_honey_weight").coerceAtLeast(0.0),
                        dryHoneyWeight = json.optDouble("dry_honey_weight").coerceAtLeast(0.0),
                        weightUnit = json.optString("weight_unit", "lb"),
                        waxWeight = json.optDouble("wax_weight").coerceAtLeast(0.0),
                        propolisWeight = json.optDouble("propolis_weight").coerceAtLeast(0.0),
                        notes = json.optString("notes"),
                        recordUuid = json.stringOrNull("record_uuid") ?: existing?.recordUuid ?: UUID.randomUUID().toString(),
                        apiaryId = json.longOrNull("apiary_id") ?: existing?.apiaryId,
                        batchId = json.stringOrNull("batch_id") ?: existing?.batchId,
                        occurredAt = json.optLong("occurred_at", json.optLong("created_at", doc.updated_at)),
                        createdBy = json.stringOrNull("created_by") ?: existing?.createdBy,
                        deviceId = json.stringOrNull("device_id") ?: existing?.deviceId ?: doc.device_id
                    )
                    if (existing == null || CloudConflictPolicy.shouldApplyRemote(incoming.createdAt, existing.createdAt)) {
                        db.harvests().upsert(incoming)
                    }
                }
            }
            "event" -> {
                val hiveId = json.optLong("hive_id")
                if (db.hives().exists(hiveId)) {
                    val incoming = ActivityEventEntity(
                        doc.entity_id, hiveId, json.optLong("created_at", doc.updated_at),
                        json.optString("type"), json.optString("title"), json.optString("detail")
                    )
                    val existing = db.events().get(doc.entity_id)
                    if (existing == null || CloudConflictPolicy.shouldApplyRemote(incoming.createdAt, existing.createdAt)) {
                        db.events().upsert(incoming)
                    }
                }
            }
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
    suspend fun restoreDataBackupJson(
        raw: String,
        hiveLimit: Int,
        apiaryLimit: Int,
        planName: String
    ): DataBackupRestoreResult = withContext(Dispatchers.IO) {
        checkWritable()
        previewDataBackup(raw)
        val root = JSONObject(raw)
        val records = root.getJSONArray("records")
        val rows = (0 until records.length()).map { records.getJSONObject(it) }
        val currentApiaries = db.apiaries().allForBackup()
        val currentHives = db.hives().allForBackup()

        // Validate the merged result before the first row is applied. This counts logical
        // apiary names and (hive number + apiary) pairs, so restoring an existing record
        // does not consume another slot. The restore is rejected as a whole when it adds
        // records beyond the current plan's limits.
        assessBackupRestoreQuotas(
            currentApiaryNames = currentApiaries.filterNot { it.deleted }.map { it.name },
            importedApiaryNames = rows.filter { it.optString("type") == "apiary" }
                .map { it.getJSONObject("payload") }
                .filterNot { it.optBoolean("deleted", false) }
                .map { it.optString("name") },
            currentHiveKeys = currentHives.filterNot { it.deleted }
                .map { it.number to it.apiaryName },
            importedHiveKeys = rows.filter { it.optString("type") == "hive" }
                .map { it.getJSONObject("payload") }
                .filterNot { it.optBoolean("deleted", false) }
                .map { it.optString("number") to it.optString("apiary") },
            hiveLimit = hiveLimit,
            apiaryLimit = apiaryLimit,
            planName = planName
        )
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
            val existingSameId = db.nfcTagAssignments().get(incoming.id)
            val incomingAlreadyStored = existingSameId != null
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
            // Always reconcile the same assignment ID, including closed incoming rows.
            // A local release is monotonic; an older active payload must not reopen it.
            val merge = NfcAssignmentResolver.merge(
                tagConflicts = if (normalizedUid != null && lifecycleSafeIncoming.isActive) {
                    db.nfcTagAssignments().allActiveAssignments()
                        .filter { NfcUid.matches(it.tagUid, normalizedUid) }
                } else {
                    emptyList()
                },
                hiveConflicts = if (normalizedUid != null && lifecycleSafeIncoming.isActive) {
                    db.nfcTagAssignments().activeAssignmentsForHive(lifecycleSafeIncoming.hiveId)
                } else {
                    emptyList()
                },
                incoming = lifecycleSafeIncoming,
                existingSameId = existingSameId
            )

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
                // Cache-only tag changes stay local. Shared ownership is synchronized by
                // the nfc_assignment document, not by a whole hive-profile snapshot.
                db.hives().refreshTagCache(affectedHiveId)
            }
        }
    }

    private fun checkWritable() {
        if (startupWorkspaceAccessPending.value) {
            _readOnlyWriteAttempt.value = _readOnlyWriteAttempt.value + 1L
            throw IllegalStateException(
                "BeeKeep is verifying access to your selected team workspace. Please try again."
            )
        }
        if (_activeWorkspaceReadOnly.value) {
            _readOnlyWriteAttempt.value = _readOnlyWriteAttempt.value + 1L
            throw IllegalStateException(
                "This team workspace is view-only. Ask a workspace owner to change your role before editing."
            )
        }
    }

    private fun currentAccountUserId(): String? =
        prefs.getString("last_cloud_user_id", null)?.trim()?.takeIf { it.isNotEmpty() }

    private fun currentDeviceId(): String {
        prefs.getString("device_id", null)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        check(prefs.edit().putString("device_id", generated).commit()) {
            "Could not persist the device identifier for action history."
        }
        return generated
    }

    private suspend fun enqueue(type: String, entityId: Long, operation: String, payload: String) {
        // Keep the coalescing document outbox for latest-state sync, but append an
        // immutable action snapshot for every tracked team mutation. Replaying these
        // snapshots in order preserves every offline edit to the same record.
        val database = db
        val workspaceId = _activeWorkspaceId.value
        database.withTransaction {
            // Remote hydration for a viewer may adjust local caches. Never turn that into
            // a pending client write for a read-only role.
            if (_activeWorkspaceReadOnly.value) return@withTransaction

            var queuedPayload = payload
            val tracksWorkspaceAction = !workspaceId.isNullOrBlank() &&
                (type == "apiary" || type == "hive" || (type == "inspection" && operation == "upsert"))
            if (tracksWorkspaceAction) {
                val now = System.currentTimeMillis()
                val payloadTimestamp = runCatching {
                    JSONObject(payload).optLong("updated_at", 0L)
                }.getOrDefault(0L)
                val previousActionAt = database.workspaceActionOutbox().latestActionAt(
                    workspaceId!!, type, entityId
                ) ?: 0L
                val actionAt = maxOf(now, payloadTimestamp, previousActionAt + 1L)
                queuedPayload = JSONObject(payload)
                    .put("updated_at", actionAt)
                    .toString()
                database.workspaceActionOutbox().enqueue(
                    WorkspaceActionOutboxEntity(
                        clientActionId = UUID.randomUUID().toString(),
                        workspaceId = workspaceId,
                        entityType = type,
                        entityId = entityId,
                        operation = operation,
                        payload = queuedPayload,
                        actionAt = actionAt,
                        createdAt = now
                    )
                )
            }

            // Replacing an older pending write and inserting its replacement must be atomic.
            database.outbox().deletePendingForEntity(type, entityId)
            database.outbox().enqueue(
                SyncOutboxEntity(IdGenerator.nextLong(), type, entityId, operation, queuedPayload)
            )
        }
    }

    private fun hivePayload(e: HiveEntity) = JSONObject().put("id",e.id).put("number",e.number).put("apiary",e.apiaryName).put("apiary_id",e.apiaryId).put("queen_status",e.queenStatus).put("queen_mark_color",e.queenMarkColor).put("queen_origin",e.queenOrigin).put("queen_age_months",e.queenAgeMonths).put("queen_temperament",e.queenTemperament).put("strength",e.strength).put("mite_percent",e.mitePercent).put("tag_uid",e.tagUid).put("updated_at",e.updatedAt).put("deleted",e.deleted).put("status",e.status).put("dead_at",e.deadAt).put("status_changed_at",e.statusChangedAt).put("latitude",e.latitude).put("longitude",e.longitude).put("bee_count_frames",e.beeCountFrames).toString()
    private fun nfcAssignmentPayload(e: NfcTagAssignmentEntity) = JSONObject().put("id",e.id).put("tag_uid",e.tagUid).put("hive_id",e.hiveId).put("assigned_at",e.assignedAt).put("unassigned_at",e.unassignedAt).put("updated_at",maxOf(e.assignedAt, e.unassignedAt ?: 0L)).toString()
    private fun apiaryPayload(e: ApiaryEntity) = JSONObject().put("id",e.id).put("name",e.name).put("notes",e.notes).put("latitude",e.latitude).put("longitude",e.longitude).put("forage_notes",e.forageNotes).put("water_notes",e.waterNotes).put("updated_at",e.updatedAt).put("deleted",e.deleted).toString()
    private fun inspectionPayload(e: InspectionEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("strength",e.strength).put("queen_status",e.queenStatus).put("mite_count",e.miteCount).put("sample_size",e.sampleSize).put("notes",e.notes)
        .put("latitude",e.latitude).put("longitude",e.longitude).put("emergency_cells",e.emergencyCells).put("supercedure_cells",e.supercedureCells).put("swarm_cells",e.swarmCells).put("eggs",e.eggs).put("open_brood",e.openBrood).put("capped_brood",e.cappedBrood).put("honey_stores",e.honeyStores).put("pollen",e.pollen).put("empty_drawn_comb",e.emptyDrawnComb).put("disease_flags",e.diseaseFlags).put("bee_count_frames",e.beeCountFrames).put("updated_at",e.updatedAt).toString()
    private fun feedingPayload(e: FeedingEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("record_uuid",e.recordUuid).put("apiary_id",e.apiaryId).put("batch_id",e.batchId).put("occurred_at",e.occurredAt).put("created_by",e.createdBy).put("device_id",e.deviceId).put("feed_type",e.feedType).put("ratio",e.ratio).put("amount",e.amount).put("unit",e.unit).put("notes",e.notes).toString()
    private fun treatmentPayload(e: TreatmentEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("record_uuid",e.recordUuid).put("apiary_id",e.apiaryId).put("batch_id",e.batchId).put("occurred_at",e.occurredAt).put("created_by",e.createdBy).put("device_id",e.deviceId).put("treatment_type",e.treatmentType).put("product",e.product).put("inserted_at",e.insertedAt).put("removal_at",e.removalAt).put("withdrawal_until",e.withdrawalUntil).put("notes",e.notes).toString()
    private fun harvestPayload(e: HarvestEntity) = JSONObject().put("id",e.id).put("hive_id",e.hiveId).put("created_at",e.createdAt).put("updated_at",e.createdAt).put("record_uuid",e.recordUuid).put("apiary_id",e.apiaryId).put("batch_id",e.batchId).put("occurred_at",e.occurredAt).put("created_by",e.createdBy).put("device_id",e.deviceId).put("supers_pulled",e.supersPulled).put("wet_honey_weight",e.wetHoneyWeight).put("dry_honey_weight",e.dryHoneyWeight).put("weight_unit",e.weightUnit).put("wax_weight",e.waxWeight).put("propolis_weight",e.propolisWeight).put("notes",e.notes).toString()
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
    private fun toFeeding(e: FeedingEntity) = Feeding(
        e.id, e.hiveId, e.createdAt, e.feedType, e.ratio, e.amount, e.unit, e.notes,
        e.recordUuid, e.apiaryId, e.batchId, e.occurredAt, e.createdBy, e.deviceId
    )
    private fun toTreatment(e: TreatmentEntity) = Treatment(
        e.id, e.hiveId, e.createdAt, e.treatmentType, e.product, e.insertedAt,
        e.removalAt, e.withdrawalUntil, e.notes,
        e.recordUuid, e.apiaryId, e.batchId, e.occurredAt, e.createdBy, e.deviceId
    )
    private fun toHarvest(e: HarvestEntity) = Harvest(
        e.id, e.hiveId, e.createdAt, e.supersPulled, e.wetHoneyWeight, e.dryHoneyWeight,
        e.weightUnit, e.waxWeight, e.propolisWeight, e.notes,
        e.recordUuid, e.apiaryId, e.batchId, e.occurredAt, e.createdBy, e.deviceId
    )
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