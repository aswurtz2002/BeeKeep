package com.beekeep.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.beekeep.app.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

class BeeKeepViewModel(
    private val repo: LocalHiveRepository,
    @Suppress("unused") private val appContext: Context
) : ViewModel() {
    private val _hives = MutableStateFlow<List<Hive>>(emptyList())
    val hives: StateFlow<List<Hive>> = _hives
    private val _deadHives = MutableStateFlow<List<Hive>>(emptyList())
    val deadHives: StateFlow<List<Hive>> = _deadHives
    private val _apiaries = MutableStateFlow<List<Apiary>>(emptyList())
    val apiaries: StateFlow<List<Apiary>> = _apiaries
    private val _selected = MutableStateFlow<Hive?>(null)
    val selected: StateFlow<Hive?> = _selected
    private val _inspections = MutableStateFlow<List<Inspection>>(emptyList())
    val inspections: StateFlow<List<Inspection>> = _inspections
    private val _feedings = MutableStateFlow<List<Feeding>>(emptyList())
    val feedings: StateFlow<List<Feeding>> = _feedings
    private val _treatments = MutableStateFlow<List<Treatment>>(emptyList())
    val treatments: StateFlow<List<Treatment>> = _treatments
    private val _harvests = MutableStateFlow<List<Harvest>>(emptyList())
    val harvests: StateFlow<List<Harvest>> = _harvests
    private val _events = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val events: StateFlow<List<ActivityEvent>> = _events
    private val _photos = MutableStateFlow<List<PhotoEntity>>(emptyList())
    val photos: StateFlow<List<PhotoEntity>> = _photos
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private var detailJob: Job? = null
    private var selectionRequestGeneration = 0L

    init {
        viewModelScope.launch {
            repo.initialize()
            // Hydrate active and inactive UID assignments before NFC auto-write is permitted.
            _hives.value = repo.observeHives().first()
            _deadHives.value = repo.observeDeadHives().first()
            _apiaries.value = repo.observeApiaries().first()
            _ready.value = true
            launch { repo.observeHives().collect { _hives.value = it } }
            launch { repo.observeDeadHives().collect { _deadHives.value = it } }
            launch { repo.observeApiaries().collect { _apiaries.value = it } }
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
            _treatments.value = emptyList()
            _harvests.value = emptyList()
            _events.value = emptyList()
            _photos.value = emptyList()

            if (openedHive != null) {
                detailJob = launch {
                    launch { repo.observeInspections(id).collect { _inspections.value = it } }
                    launch { repo.observeFeedings(id).collect { _feedings.value = it } }
                    launch { repo.observeTreatments(id).collect { _treatments.value = it } }
                    launch { repo.observeHarvests(id).collect { _harvests.value = it } }
                    launch { repo.observeEvents(id).collect { _events.value = it } }
                    launch { repo.observePhotos(id).collect { _photos.value = it } }
                }
            }
        }
    }

    suspend fun findHiveByTag(tag: String): Hive? = repo.findHiveByNfc(tag)

    suspend fun isNfcTagUnassigned(uid: String): Boolean = repo.isNfcTagUnassigned(uid)

    suspend fun getUnassignedNfcTagUids(): Set<String> = repo.getUnassignedNfcTagUids()

    fun clearHive() {
        // Invalidate in-flight openHive database reads so closing the hive cannot be undone by a late result.
        selectionRequestGeneration++
        detailJob?.cancel()
        _selected.value = null
        _inspections.value = emptyList()
        _feedings.value = emptyList()
        _treatments.value = emptyList()
        _harvests.value = emptyList()
        _events.value = emptyList()
        _photos.value = emptyList()
    }

    private suspend fun refreshSelectedHiveIfCurrent(hiveId: Long) {
        if (_selected.value?.id != hiveId) return
        val refreshed = runCatching { repo.getHive(hiveId) }.getOrNull() ?: return
        // Read from Room first, then re-check after suspension before publishing to StateFlow.
        if (_selected.value?.id == hiveId) _selected.value = refreshed
    }

    fun createPreMadeSetup(
        apiaryPrefix: String,
        hivePrefix: String,
        apiaryCount: Int,
        hivesPerApiary: Int,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        val cleanApiaryPrefix = apiaryPrefix.trim()
        val cleanHivePrefix = hivePrefix.trim()
        if (cleanApiaryPrefix.isBlank()) {
            onResult(false, "Enter a name prefix for your apiaries.")
            return
        }
        if (cleanHivePrefix.isBlank()) {
            onResult(false, "Enter a name prefix for your hives.")
            return
        }
        if (apiaryCount !in 1..30 || hivesPerApiary < 1 ||
            apiaryCount.toLong() * hivesPerApiary.toLong() > 1_000L
        ) {
            onResult(false, "Choose between 1 and 30 apiaries, with no more than 1,000 hives in total.")
            return
        }

        val apiaryWidth = if (apiaryCount >= 10) apiaryCount.toString().length else 1
        val hiveWidth = if (hivesPerApiary >= 10) hivesPerApiary.toString().length else 1
        val apiaryNames = (1..apiaryCount).map { index ->
            cleanApiaryPrefix + " " + index.toString().padStart(apiaryWidth, '0')
        }
        val hiveNamesByApiary = List(apiaryCount) {
            (1..hivesPerApiary).map { index ->
                cleanHivePrefix + " " + index.toString().padStart(hiveWidth, '0')
            }
        }

        viewModelScope.launch {
            runCatching { repo.createStarterSetup(apiaryNames, hiveNamesByApiary) }
                .onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not create the starter apiaries and hives.") }
        }
    }

    fun createHive(number: String, apiary: String, queen: String, strength: Int, tagUid: String? = null, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val clean = number.trim(); if (clean.isBlank()) { onResult(false, "Enter a hive number."); return }
        val cleanApiary = apiary.trim().ifBlank { "Unassigned Yard" }
        viewModelScope.launch {
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
                val tagError = tagUid?.takeIf { it.isNotBlank() }?.let { tag ->
                    runCatching { repo.assignNfcTag(hiveId, tag) }.exceptionOrNull()?.message?.let { "Hive created. $it" }
                }
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
                refreshSelectedHiveIfCurrent(hiveId)
            }.onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not mark the colony dead.") }
        }
    }

    fun restoreHive(hiveId: Long, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            runCatching {
                repo.restoreHive(hiveId)
                refreshSelectedHiveIfCurrent(hiveId)
            }.onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not restore the colony.") }
        }
    }

    fun deleteHivePermanently(hiveId: Long, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            runCatching {
                repo.deleteHivePermanently(hiveId)
                if (_selected.value?.id == hiveId) clearHive()
            }.onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not delete the hive.") }
        }
    }

    fun assignTag(uid: String, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        _selected.value?.id?.let { assignTagToHive(it, uid, onResult = onResult) } ?: onResult(false, "No hive is selected.")
    }

    fun assignTagToHive(hiveId: Long, uid: String, reassign: Boolean = false, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val normalized = uid.trim().uppercase()
        if (normalized.isBlank()) { onResult(false, "The NFC tag has no readable UID."); return }
        viewModelScope.launch {
            runCatching {
                repo.assignNfcTag(hiveId, normalized, reassign)
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
            refreshSelectedHiveIfCurrent(h.id)
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
            eggs = i.eggs.coerceAtLeast(0),
            openBrood = i.openBrood.coerceAtLeast(0),
            cappedBrood = i.cappedBrood.coerceAtLeast(0),
            honeyStores = i.honeyStores.coerceAtLeast(0),
            pollen = i.pollen.coerceAtLeast(0),
            emptyDrawnComb = i.emptyDrawnComb.coerceAtLeast(0),
            diseaseFlags = i.diseaseFlags.trim()
        )
        return runCatching {
            repo.saveInspection(normalized)
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
                .onSuccess { onResult(true, null) }
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
            }.onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not save treatment.") }
        }
    }

    fun saveHarvest(supers: Int, wet: Double, dry: Double, unit: String, wax: Double, propolis: Double, notes: String, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val h = _selected.value
        if (h == null) { onResult(false, "No hive is selected."); return }
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            runCatching { repo.saveHarvest(Harvest(IdGenerator.nextLong(), h.id, now, supers, wet, dry, unit, wax, propolis, notes)) }
                .onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not save harvest.") }
        }
    }

    fun saveApiary(name: String, notes: String, lat: Double?, lon: Double?, forage: String, water: String, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val clean = name.trim()
        if (clean.isBlank()) { onResult(false, "Enter an apiary name."); return }
        viewModelScope.launch {
            runCatching { repo.saveApiary(Apiary(IdGenerator.nextLong(), clean, notes, lat, lon, forage, water)) }
                .onSuccess { onResult(true, null) }
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
                .onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not update apiary.") }
        }
    }

    fun deleteApiaryPermanently(id: Long, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val deletedApiaryName = _apiaries.value.firstOrNull { it.id == id }?.name
        viewModelScope.launch {
            runCatching { repo.deleteApiaryPermanently(id) }
                .onSuccess {
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
