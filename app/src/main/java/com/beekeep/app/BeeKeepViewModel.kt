package com.beekeep.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.beekeep.app.cloud.CloudSyncScheduler
import com.beekeep.app.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.beekeep.app.nfc.NfcUid
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

class BeeKeepViewModel(
    private val repo: LocalHiveRepository,
    private val appContext: Context
) : ViewModel() {
    private val _hives = MutableStateFlow<List<Hive>>(emptyList())
    val hives: StateFlow<List<Hive>> = _hives
    private val _deadHives = MutableStateFlow<List<Hive>>(emptyList())
    val deadHives: StateFlow<List<Hive>> = _deadHives
    private val _apiaries = MutableStateFlow<List<Apiary>>(emptyList())
    val apiaries: StateFlow<List<Apiary>> = _apiaries
    // Expose only the per-apiary totals needed by the Apiaries screen. MutableStateFlow
    // suppresses equal maps, so hive profile edits do not invalidate that screen.
    private val _apiaryHiveCounts = MutableStateFlow(ApiaryHiveCounts(emptyMap()))
    val apiaryHiveCounts: StateFlow<ApiaryHiveCounts> = _apiaryHiveCounts
    private val _selected = MutableStateFlow<Hive?>(null)
    val selected: StateFlow<Hive?> = _selected
    private val _inspections = MutableStateFlow<List<Inspection>>(emptyList())
    val inspections: StateFlow<List<Inspection>> = _inspections
    private val _feedings = MutableStateFlow<List<Feeding>>(emptyList())
    val feedings: StateFlow<List<Feeding>> = _feedings
    private val _feedingsLoading = MutableStateFlow(false)
    val feedingsLoading: StateFlow<Boolean> = _feedingsLoading
    private val _feedingsError = MutableStateFlow<String?>(null)
    val feedingsError: StateFlow<String?> = _feedingsError
    private val _treatments = MutableStateFlow<List<Treatment>>(emptyList())
    val treatments: StateFlow<List<Treatment>> = _treatments
    private val _treatmentsLoading = MutableStateFlow(false)
    val treatmentsLoading: StateFlow<Boolean> = _treatmentsLoading
    private val _treatmentsError = MutableStateFlow<String?>(null)
    val treatmentsError: StateFlow<String?> = _treatmentsError
    private val _harvests = MutableStateFlow<List<Harvest>>(emptyList())
    val harvests: StateFlow<List<Harvest>> = _harvests
    private val _harvestsLoading = MutableStateFlow(false)
    val harvestsLoading: StateFlow<Boolean> = _harvestsLoading
    private val _harvestsError = MutableStateFlow<String?>(null)
    val harvestsError: StateFlow<String?> = _harvestsError
    private val _events = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val events: StateFlow<List<ActivityEvent>> = _events
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    val activeWorkspaceId: StateFlow<String?> = repo.activeWorkspaceId
    val activeWorkspaceReadOnly: StateFlow<Boolean> = repo.activeWorkspaceReadOnly
    val readOnlyWriteAttempt: StateFlow<Long> = repo.readOnlyWriteAttempt
    private var detailJob: Job? = null
    private var selectionRequestGeneration = 0L
    @Volatile
    private var activeQuotaLimits = BeeKeepQuotaLimits.forPlan(
        BeeKeepEntitlements.currentPlan(appContext),
        BeeKeepEntitlements.hiveLimit(appContext)
    )

    internal fun updateQuotaLimits(limits: BeeKeepQuotaLimits) {
        activeQuotaLimits = limits
        repo.updateQuotaLimits(limits)
    }

    /**
     * Room/outbox is the durable save. Ask WorkManager to push it promptly without
     * turning a successful local write into a failure if work scheduling is unavailable.
     */
    private fun requestCloudSync() {
        runCatching { CloudSyncScheduler.runNow(appContext) }
    }

    private fun countHivesByApiary(hives: List<Hive>): ApiaryHiveCounts =
        ApiaryHiveCounts(hives.groupingBy { it.apiary.lowercase(java.util.Locale.ROOT) }.eachCount())

    init {
        repo.updateQuotaLimits(activeQuotaLimits)
        viewModelScope.launch {
            repo.initialize()
            // Hydrate active and inactive UID assignments before NFC auto-write is permitted.
            _hives.value = repo.observeHives().first()
            _apiaryHiveCounts.value = countHivesByApiary(_hives.value)
            _deadHives.value = repo.observeDeadHives().first()
            _apiaries.value = repo.observeApiaries().first()
            _ready.value = true
            launch {
                repo.observeHives().collect { updatedHives ->
                    _hives.value = updatedHives
                    _apiaryHiveCounts.value = countHivesByApiary(updatedHives)
                }
            }
            launch { repo.observeDeadHives().collect { _deadHives.value = it } }
            launch { repo.observeApiaries().collect { _apiaries.value = it } }
        }
        // Clear any open detail pane when the active dataset changes; numeric local IDs
        // can legitimately overlap between a personal DB and a team's DB.
        viewModelScope.launch {
            var previousWorkspaceId = repo.activeWorkspaceId.value
            repo.activeWorkspaceId.collect { workspaceId ->
                if (workspaceId != previousWorkspaceId) {
                    previousWorkspaceId = workspaceId
                    detailJob?.cancel()
                    _selected.value = null
                    _inspections.value = emptyList()
                    _feedings.value = emptyList()
                    _feedingsLoading.value = false
                    _feedingsError.value = null
                    _treatments.value = emptyList()
                    _treatmentsLoading.value = false
                    _treatmentsError.value = null
                    _harvests.value = emptyList()
                    _harvestsLoading.value = false
                    _harvestsError.value = null
                    _events.value = emptyList()
                }
            }
        }
    }

    fun openHive(id: Long) {
        val requestGeneration = ++selectionRequestGeneration
        viewModelScope.launch {
            val openedHive = repo.getHive(id)
            // If another NFC tap or navigation request arrived while this query was running,
            // only the latest requested hive is allowed to replace the selected record.
            if (requestGeneration != selectionRequestGeneration) return@launch

            detailJob?.cancel()
            _selected.value = openedHive
            _inspections.value = emptyList()
            _feedings.value = emptyList()
            _feedingsLoading.value = openedHive != null
            _feedingsError.value = null
            _treatments.value = emptyList()
            _treatmentsLoading.value = openedHive != null
            _treatmentsError.value = null
            _harvests.value = emptyList()
            _harvestsLoading.value = openedHive != null
            _harvestsError.value = null
            _events.value = emptyList()

            if (openedHive != null) {
                detailJob = launch {
                    // Keep the open profile backed by Room instead of a one-time Hive snapshot.
                    // This immediately reflects local edits and cloud changes applied by sync.
                    launch {
                        repo.observeHive(id).collect { updatedHive ->
                            if (updatedHive != null && _selected.value?.id == id) {
                                _selected.value = updatedHive
                            }
                        }
                    }
                    launch { repo.observeInspections(id).collect { _inspections.value = it } }
                    launch {
                        repo.observeFeedings(id)
                            .catch { failure ->
                                if (_selected.value?.id == id) {
                                    _feedingsError.value = failure.message?.takeIf { it.isNotBlank() }
                                        ?: "Could not load feeding records."
                                    _feedingsLoading.value = false
                                }
                            }
                            .collect { records ->
                                if (_selected.value?.id == id) {
                                    _feedings.value = records
                                    _feedingsError.value = null
                                    _feedingsLoading.value = false
                                }
                            }
                    }
                    launch {
                        repo.observeTreatments(id)
                            .catch { failure ->
                                if (_selected.value?.id == id) {
                                    _treatmentsError.value = failure.message?.takeIf { it.isNotBlank() }
                                        ?: "Could not load treatment records."
                                    _treatmentsLoading.value = false
                                }
                            }
                            .collect { records ->
                                if (_selected.value?.id == id) {
                                    _treatments.value = records
                                    _treatmentsError.value = null
                                    _treatmentsLoading.value = false
                                }
                            }
                    }
                    launch {
                        repo.observeHarvests(id)
                            .catch { failure ->
                                if (_selected.value?.id == id) {
                                    _harvestsError.value = failure.message?.takeIf { it.isNotBlank() }
                                        ?: "Could not load harvest records."
                                    _harvestsLoading.value = false
                                }
                            }
                            .collect { records ->
                                if (_selected.value?.id == id) {
                                    _harvests.value = records
                                    _harvestsError.value = null
                                    _harvestsLoading.value = false
                                }
                            }
                    }
                    launch { repo.observeEvents(id).collect { _events.value = it } }
                }
            }
        }
    }

    suspend fun findHiveByTag(tag: String): Hive? = repo.findHiveByNfc(tag)

    /** Reads the current Room hive list instead of relying on a possibly stale UI StateFlow snapshot. */
    suspend fun getHivesSnapshot(): List<Hive> = repo.observeHives().first()

    suspend fun isNfcTagAmbiguous(uid: String): Boolean = repo.isNfcTagAmbiguous(uid)

    suspend fun getAllKnownNfcTagAssignments(): List<Pair<String?, Long>> =
        repo.getAllKnownNfcTagAssignments()

    suspend fun isNfcTagUnassigned(uid: String): Boolean = repo.isNfcTagUnassigned(uid)

    suspend fun getUnassignedNfcTagUids(): Set<String> = repo.getUnassignedNfcTagUids()

    fun clearHive() {
        // Invalidate in-flight openHive database reads so closing the hive cannot be undone by a late result.
        selectionRequestGeneration++
        detailJob?.cancel()
        _selected.value = null
        _inspections.value = emptyList()
        _feedings.value = emptyList()
        _feedingsLoading.value = false
        _feedingsError.value = null
        _treatments.value = emptyList()
        _treatmentsLoading.value = false
        _treatmentsError.value = null
        _harvests.value = emptyList()
        _harvestsLoading.value = false
        _harvestsError.value = null
        _events.value = emptyList()
    }

    private suspend fun refreshSelectedHiveIfCurrent(hiveId: Long) {
        if (_selected.value?.id != hiveId) return
        val refreshed = runCatching { repo.getHive(hiveId) }.getOrNull() ?: return
        // Read from Room first, then re-check after suspension before publishing to StateFlow.
        if (_selected.value?.id == hiveId) _selected.value = refreshed
    }

    fun createPreMadeSetup(
        hivePrefix: String,
        apiaryNames: List<String>,
        startingHiveNumbers: List<Int>,
        hiveCounts: List<Int>,
        allowExistingWorkspace: Boolean = false,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        val cleanPrefix = hivePrefix.trim()
        val cleanApiaryNames = apiaryNames.map(String::trim)
        if (cleanApiaryNames.isEmpty()) {
            onResult(false, "Create at least one apiary.")
            return
        }
        if (cleanApiaryNames.size != startingHiveNumbers.size ||
            cleanApiaryNames.size != hiveCounts.size
        ) {
            onResult(false, "Each apiary needs its own starting number and hive count.")
            return
        }
        if (cleanApiaryNames.any(String::isBlank)) {
            onResult(false, "Every apiary needs a name.")
            return
        }
        if (cleanApiaryNames.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size != cleanApiaryNames.size) {
            onResult(false, "Each apiary needs a unique name.")
            return
        }
        val quotaLimits = activeQuotaLimits
        val apiaryLimit = quotaLimits.apiaryLimit
        if (apiaryLimit != Int.MAX_VALUE && _apiaries.value.size + cleanApiaryNames.size > apiaryLimit) {
            onResult(false, BeeKeepEntitlements.apiaryLimitMessage(quotaLimits.plan))
            return
        }
        if (startingHiveNumbers.any { it !in 1..999_999 }) {
            onResult(false, "Starting numbers must be between 1 and 999,999.")
            return
        }
        if (hiveCounts.any { it !in 0..9_999 }) {
            onResult(false, "Choose a hive count between 0 and 9,999 per apiary.")
            return
        }
        val lockedIds = BeeKeepEntitlements.lockedHiveIds(appContext)
        val currentHiveCount = BeeKeepEntitlements.countHivesSubjectToFreeLimit(
            (_hives.value + _deadHives.value).map { it.id },
            lockedIds
        )
        val requestedHiveCount = hiveCounts.sum()
        val limit = quotaLimits.hiveLimit
        if (requestedHiveCount > 0 && currentHiveCount + requestedHiveCount > limit) {
            onResult(false, BeeKeepEntitlements.hiveLimitMessage(quotaLimits.plan, limit))
            return
        }
        if (allowExistingWorkspace) {
            val existingNames = _apiaries.value.map { it.name.trim().lowercase(java.util.Locale.ROOT) }.toSet()
            if (cleanApiaryNames.any { it.lowercase(java.util.Locale.ROOT) in existingNames }) {
                onResult(false, "An apiary with that name already exists. Choose a different name.")
                return
            }
        }
        if (startingHiveNumbers.indices.any { i ->
            hiveCounts[i] > 0 &&
                startingHiveNumbers[i].toLong() + hiveCounts[i].toLong() - 1L > 999_999L
        }) {
            onResult(false, "A hive range cannot end above 999,999.")
            return
        }

        val hiveNumbersByApiary = cleanApiaryNames.indices.map { index ->
            val start = startingHiveNumbers[index]
            val count = hiveCounts[index]
            (0 until count).map { offset ->
                val number = (start + offset).toString()
                if (cleanPrefix.isBlank()) number else "$cleanPrefix $number"
            }
        }

        viewModelScope.launch {
            val planAtCreation = quotaLimits.plan
            runCatching {
                repo.createStarterSetup(
                    cleanApiaryNames,
                    hiveNumbersByApiary,
                    allowExistingWorkspace = allowExistingWorkspace
                )
            }
                .onSuccess { createdHiveIds ->
                    requestCloudSync()
                    if (planAtCreation.isPaidSubscription) {
                        BeeKeepEntitlements.rememberProCreatedHives(appContext, createdHiveIds)
                    }
                    onResult(true, null)
                }
                .onFailure { onResult(false, it.message ?: "Could not create the starter apiaries and hives.") }
        }
    }

    fun createHive(number: String, apiary: String, queen: String, strength: Int, tagUid: String? = null, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val clean = number.trim(); if (clean.isBlank()) { onResult(false, "Enter a hive number."); return }
        val cleanApiary = apiary.trim().ifBlank { "Unassigned Yard" }
        val quotaLimits = activeQuotaLimits
        viewModelScope.launch {
            val planAtCreation = quotaLimits.plan
            val lockedIds = BeeKeepEntitlements.lockedHiveIds(appContext)
            val currentHiveCount = BeeKeepEntitlements.countHivesSubjectToFreeLimit(
                (_hives.value + _deadHives.value).map { it.id },
                lockedIds
            )
            val hiveLimit = quotaLimits.hiveLimit
            if (currentHiveCount >= hiveLimit) {
                onResult(false, BeeKeepEntitlements.hiveLimitMessage(planAtCreation, hiveLimit))
                return@launch
            }
            val duplicate = repo.findHiveByNumberAndApiary(clean, cleanApiary)
            if (duplicate != null) {
                onResult(false, "Hive $clean already exists in $cleanApiary.")
                return@launch
            }
            runCatching {
                val hiveId = IdGenerator.nextLong()
                repo.saveHive(Hive(hiveId, clean, cleanApiary, queen, "", "", null, 3, strength.coerceIn(0, 10), 0.0, null))
                hiveId
            }.onSuccess { hiveId ->
                if (planAtCreation.isPaidSubscription) {
                    BeeKeepEntitlements.rememberProCreatedHives(appContext, listOf(hiveId))
                }
                val tagError = tagUid?.takeIf { it.isNotBlank() }?.let { tag ->
                    runCatching { repo.assignNfcTag(hiveId, tag) }.exceptionOrNull()?.message?.let { "Hive created. $it" }
                }
                requestCloudSync()
                onResult(true, tagError)
            }.onFailure { onResult(false, it.message ?: "Could not create the hive.") }
        }
    }

    fun updateHiveLocation(
        hiveId: Long,
        latitude: Double,
        longitude: Double,
        onResult: (String?) -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching { repo.updateHiveLocation(hiveId, latitude, longitude) }
                .onSuccess {
                    requestCloudSync()
                    refreshSelectedHiveIfCurrent(hiveId)
                    onResult(null)
                }
                .onFailure { onResult(it.message ?: "Could not save this hive's GPS location.") }
        }
    }

    fun markHiveDead(hiveId: Long, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            runCatching {
                repo.markHiveDead(hiveId)
                requestCloudSync()
                refreshSelectedHiveIfCurrent(hiveId)
            }.onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not mark the colony dead.") }
        }
    }

    fun restoreHive(hiveId: Long, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            runCatching {
                repo.restoreHive(hiveId)
                requestCloudSync()
                refreshSelectedHiveIfCurrent(hiveId)
            }.onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not restore the colony.") }
        }
    }

    fun deleteHivePermanently(hiveId: Long, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            runCatching {
                repo.deleteHivePermanently(hiveId)
                requestCloudSync()
                if (_selected.value?.id == hiveId) clearHive()
            }.onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not delete the hive.") }
        }
    }

    fun assignTag(uid: String, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        _selected.value?.id?.let { assignTagToHive(it, uid, onResult = onResult) } ?: onResult(false, "No hive is selected.")
    }

    fun assignTagToHive(hiveId: Long, uid: String, reassign: Boolean = false, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val normalized = NfcUid.normalize(uid)
        if (normalized == null) { onResult(false, "The NFC tag has no valid readable UID."); return }
        viewModelScope.launch {
            runCatching {
                repo.assignNfcTag(hiveId, normalized, reassign)
                requestCloudSync()
                refreshSelectedHiveIfCurrent(hiveId)
            }.onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not assign the NFC tag.") }
        }
    }

    fun clearTag() {
        val h = _selected.value ?: return
        clearTagForHive(h.id)
    }

    fun clearTagForHive(
        hiveId: Long,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            runCatching { repo.unassignNfcTag(hiveId) }
                .onSuccess { removed ->
                    if (removed) requestCloudSync()
                    refreshSelectedHiveIfCurrent(hiveId)
                    onResult(
                        removed,
                        if (removed) null else "This hive has no NFC tag assigned to remove."
                    )
                }
                .onFailure { error ->
                    onResult(false, error.message ?: "Could not remove the NFC tag.")
                }
        }
    }

    fun updateQueenProfile(status: String, markColor: String, origin: String, ageMonths: Int?, temperament: Int) {
        val h = _selected.value ?: return
        viewModelScope.launch {
            repo.saveHive(h.copy(queenStatus = status, queenMarkColor = markColor, queenOrigin = origin, queenAgeMonths = ageMonths, queenTemperament = temperament.coerceIn(1, 5)))
            requestCloudSync()
            refreshSelectedHiveIfCurrent(h.id)
        }
    }

    private fun normalizeBroodStoresGauge(value: Double): Double =
        (value.coerceIn(0.0, 10.0) * 2.0).roundToInt() / 2.0

    fun recordBeeCountChange(hiveId: Long, previousFrames: Double, newFrames: Double) {
        if (previousFrames == newFrames || _selected.value?.id != hiveId) return
        val changedAt = System.currentTimeMillis()
        viewModelScope.launch {
            runCatching {
                repo.recordBeeCountChange(hiveId, previousFrames, newFrames, changedAt)
            }.onSuccess { requestCloudSync() }
        }
    }

    suspend fun saveInspection(i: Inspection): Boolean {
        val h = _selected.value ?: return false
        if (i.hiveId != h.id) return false
        val safeSample = i.sampleSize.coerceAtLeast(1)
        val normalized = i.copy(
            createdAt = i.createdAt.coerceAtLeast(1L),
            strength = i.strength.coerceIn(0, 10),
            queenStatus = i.queenStatus.trim().ifBlank { h.queenStatus.ifBlank { "Laying" } },
            miteCount = i.miteCount.coerceIn(0, safeSample),
            sampleSize = safeSample,
            notes = i.notes.trim(),
            emergencyCells = i.emergencyCells.coerceAtLeast(0),
            supercedureCells = i.supercedureCells.coerceAtLeast(0),
            swarmCells = i.swarmCells.coerceAtLeast(0),
            eggs = normalizeBroodStoresGauge(i.eggs),
            openBrood = normalizeBroodStoresGauge(i.openBrood),
            cappedBrood = normalizeBroodStoresGauge(i.cappedBrood),
            honeyStores = normalizeBroodStoresGauge(i.honeyStores),
            pollen = normalizeBroodStoresGauge(i.pollen),
            emptyDrawnComb = normalizeBroodStoresGauge(i.emptyDrawnComb),
            diseaseFlags = i.diseaseFlags.trim()
        )
        return runCatching {
            repo.saveInspection(normalized)
            requestCloudSync()
            refreshSelectedHiveIfCurrent(h.id)
            true
        }.getOrElse { false }
    }

    fun saveFeeding(feedType: String, ratio: String, amount: Double, unit: String, notes: String, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val h = _selected.value
        if (h == null) { onResult(false, "No hive is selected."); return }
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            runCatching { repo.saveFeeding(Feeding(IdGenerator.nextLong(), h.id, now, feedType, ratio, amount, unit, notes)) }
                .onSuccess { requestCloudSync(); onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not save feeding.") }
        }
    }

    fun saveTreatment(treatmentType: String, product: String, durationDays: Int, withdrawalDays: Int, notes: String, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val h = _selected.value
        if (h == null) { onResult(false, "No hive is selected."); return }
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                require(durationDays >= 0) { "Removal days cannot be negative." }
                require(withdrawalDays >= 0) { "Withdrawal days cannot be negative." }
                val removal = if (durationDays > 0) localPlusDays(now, durationDays.toLong()) else null
                val withdrawal = if (removal != null && withdrawalDays > 0) localPlusDays(removal, withdrawalDays.toLong()) else null
                val treatment = Treatment(IdGenerator.nextLong(), h.id, now, treatmentType, product, now, removal, withdrawal, notes)
                repo.saveTreatment(treatment)
            }.onSuccess { requestCloudSync(); onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not save treatment.") }
        }
    }

    fun saveHarvest(supers: Int, wet: Double, dry: Double, unit: String, wax: Double, propolis: Double, notes: String, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val h = _selected.value
        if (h == null) { onResult(false, "No hive is selected."); return }
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            runCatching { repo.saveHarvest(Harvest(IdGenerator.nextLong(), h.id, now, supers, wet, dry, unit, wax, propolis, notes)) }
                .onSuccess { requestCloudSync(); onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not save harvest.") }
        }
    }


    private fun orderedActiveHiveIdsForApiary(apiaryName: String, hiveIds: List<Long>): List<Long> {
        require(hiveIds.isNotEmpty()) { "Select at least one hive." }
        require(hiveIds.distinct().size == hiveIds.size) { "The selected hive list contains duplicates." }

        // Re-check plan limits in the ViewModel as well as the screen. This keeps
        // bulk writes from bypassing the same per-hive access rule used by Hive Detail.
        val quotaOrderedHives = (_hives.value + _deadHives.value)
            .distinctBy { it.id }
            .sortedWith(
                compareBy<Hive> { it.isDead }
                    .thenBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.number.lowercase(java.util.Locale.ROOT) }
                    .thenBy { it.apiary.lowercase(java.util.Locale.ROOT) }
                    .thenBy { it.id }
            )
        val lockedIds = BeeKeepEntitlements.hiveIdsBeyondLimit(
            quotaOrderedHives.map { it.id },
            activeQuotaLimits.hiveLimit
        )
        require(hiveIds.none { it in lockedIds }) {
            "One or more selected hives are locked by the current plan. Renew or upgrade the workspace plan to manage them."
        }

        val requested = hiveIds.toSet()
        val ordered = _hives.value
            .filter { it.id in requested && !it.isDead && it.apiary.equals(apiaryName, ignoreCase = true) }
            .sortedWith(
                compareBy<Hive> { it.number.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it.number.lowercase(java.util.Locale.ROOT) }
                    .thenBy { it.id }
            )
            .map { it.id }
        require(ordered.size == hiveIds.size) {
            "One or more selected hives are no longer active in this apiary. Refresh and try again."
        }
        return ordered
    }

    fun saveFeedingToApiary(
        apiaryId: Long,
        apiaryName: String,
        hiveIds: List<Long>,
        feedType: String,
        ratio: String,
        amount: Double,
        unit: String,
        notes: String,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            runCatching {
                val orderedHiveIds = orderedActiveHiveIdsForApiary(apiaryName, hiveIds)
                val now = System.currentTimeMillis()
                val batchId = UUID.randomUUID().toString()
                val records = orderedHiveIds.map { hiveId ->
                    Feeding(
                        IdGenerator.nextLong(), hiveId, now, feedType, ratio, amount, unit, notes,
                        apiaryId = apiaryId, batchId = batchId, occurredAt = now
                    )
                }
                repo.saveFeedingBatch(apiaryId, apiaryName, orderedHiveIds, records)
            }.onSuccess { count ->
                requestCloudSync()
                onResult(true, "Feeding logged for $count hives.")
            }.onFailure {
                onResult(false, it.message ?: "Could not apply feeding to this apiary.")
            }
        }
    }

    fun saveTreatmentToApiary(
        apiaryId: Long,
        apiaryName: String,
        hiveIds: List<Long>,
        treatmentType: String,
        product: String,
        durationDays: Int,
        withdrawalDays: Int,
        notes: String,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            runCatching {
                require(durationDays >= 0) { "Removal days cannot be negative." }
                require(withdrawalDays >= 0) { "Withdrawal days cannot be negative." }
                val orderedHiveIds = orderedActiveHiveIdsForApiary(apiaryName, hiveIds)
                val now = System.currentTimeMillis()
                val removal = if (durationDays > 0) localPlusDays(now, durationDays.toLong()) else null
                val withdrawal = if (removal != null && withdrawalDays > 0) {
                    localPlusDays(removal, withdrawalDays.toLong())
                } else null
                val batchId = UUID.randomUUID().toString()
                val records = orderedHiveIds.map { hiveId ->
                    Treatment(
                        IdGenerator.nextLong(), hiveId, now, treatmentType, product, now,
                        removal, withdrawal, notes,
                        apiaryId = apiaryId, batchId = batchId, occurredAt = now
                    )
                }
                repo.saveTreatmentBatch(apiaryId, apiaryName, orderedHiveIds, records)
            }.onSuccess { count ->
                requestCloudSync()
                onResult(true, "Treatment logged for $count hives.")
            }.onFailure {
                onResult(false, it.message ?: "Could not apply treatment to this apiary.")
            }
        }
    }

    fun saveHarvestToApiary(
        apiaryId: Long,
        apiaryName: String,
        hiveIds: List<Long>,
        supers: Int,
        wet: Double,
        dry: Double,
        unit: String,
        wax: Double,
        propolis: Double,
        notes: String,
        quantityMode: HarvestQuantityMode,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            runCatching {
                val orderedHiveIds = orderedActiveHiveIdsForApiary(apiaryName, hiveIds)
                val allocations = if (quantityMode == HarvestQuantityMode.APIARY_TOTAL) {
                    allocateApiaryHarvestQuantities(supers, wet, dry, wax, propolis, orderedHiveIds.size)
                } else {
                    List(orderedHiveIds.size) {
                        HarvestQuantityAllocation(supers, wet, dry, wax, propolis)
                    }
                }
                val now = System.currentTimeMillis()
                val batchId = UUID.randomUUID().toString()
                val records = orderedHiveIds.mapIndexed { index, hiveId ->
                    val allocation = allocations[index]
                    Harvest(
                        IdGenerator.nextLong(), hiveId, now,
                        allocation.supersPulled,
                        allocation.wetHoneyWeight,
                        allocation.dryHoneyWeight,
                        unit,
                        allocation.waxWeight,
                        allocation.propolisWeight,
                        notes,
                        apiaryId = apiaryId,
                        batchId = batchId,
                        occurredAt = now
                    )
                }
                repo.saveHarvestBatch(apiaryId, apiaryName, orderedHiveIds, records)
            }.onSuccess { count ->
                requestCloudSync()
                onResult(true, "Harvest logged for $count hives.")
            }.onFailure {
                onResult(false, it.message ?: "Could not apply harvest to this apiary.")
            }
        }
    }

    fun saveApiary(name: String, notes: String, lat: Double?, lon: Double?, forage: String, water: String, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val clean = name.trim()
        if (clean.isBlank()) { onResult(false, "Enter an apiary name."); return }
        val quotaLimits = activeQuotaLimits
        val apiaryLimit = quotaLimits.apiaryLimit
        if (apiaryLimit != Int.MAX_VALUE && _apiaries.value.size >= apiaryLimit) {
            onResult(false, BeeKeepEntitlements.apiaryLimitMessage(quotaLimits.plan))
            return
        }
        viewModelScope.launch {
            runCatching { repo.saveApiary(Apiary(IdGenerator.nextLong(), clean, notes, lat, lon, forage, water)) }
                .onSuccess { requestCloudSync(); onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not save apiary.") }
        }
    }

    fun moveHiveToApiary(hiveId: Long, apiaryName: String, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val target = apiaryName.trim()
        if (target.isBlank()) { onResult(false, "Choose an apiary first."); return }
        viewModelScope.launch {
            runCatching {
                val hive = repo.getHive(hiveId) ?: throw IllegalArgumentException("Hive not found.")
                repo.saveHive(hive.copy(apiary = target))
                requestCloudSync()
                refreshSelectedHiveIfCurrent(hiveId)
            }.onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not move the hive.") }
        }
    }

    fun updateApiary(id: Long, name: String, notes: String, lat: Double?, lon: Double?, forage: String, water: String, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val clean = name.trim()
        if (clean.isBlank()) { onResult(false, "Enter an apiary name."); return }
        viewModelScope.launch {
            val existing = _apiaries.value.firstOrNull { it.id == id }
            if (existing == null) { onResult(false, "Apiary not found. Refresh and try again."); return@launch }
            runCatching { repo.saveApiary(Apiary(id, clean, notes, lat, lon, forage, water)) }
                .onSuccess { requestCloudSync(); onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not update apiary.") }
        }
    }

    fun deleteApiaryPermanently(id: Long, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val deletedApiaryName = _apiaries.value.firstOrNull { it.id == id }?.name
        viewModelScope.launch {
            runCatching { repo.deleteApiaryPermanently(id) }
                .onSuccess {
                    requestCloudSync()
                    if (deletedApiaryName != null &&
                        _selected.value?.apiary?.equals(deletedApiaryName, ignoreCase = true) == true
                    ) {
                        clearHive()
                    }
                    onResult(true, null)
                }
                .onFailure { onResult(false, it.message ?: "Could not delete apiary.") }
        }
    }

    private fun localPlusDays(timestamp: Long, days: Long): Long =
        java.time.Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .plusDays(days)
            .toInstant()
            .toEpochMilli()

    fun createHiveFromApiaryDefaults(number: String) = createHive(number, _apiaries.value.firstOrNull()?.name ?: "Unassigned Yard", "Laying", 5)

    class Factory(private val repo: LocalHiveRepository, private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BeeKeepViewModel(repo, context) as T
    }
}
