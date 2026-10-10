package com.beekeep.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.beekeep.app.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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

    init {
        viewModelScope.launch {
            repo.initialize()
            _ready.value = true
            launch { repo.observeHives().collect { _hives.value = it } }
            launch { repo.observeDeadHives().collect { _deadHives.value = it } }
            launch { repo.observeApiaries().collect { _apiaries.value = it } }
        }
    }

    fun openHive(id: Long) {
        viewModelScope.launch {
            detailJob?.cancel()
            _selected.value = repo.getHive(id)
            _inspections.value = emptyList(); _feedings.value = emptyList(); _treatments.value = emptyList(); _harvests.value = emptyList(); _events.value = emptyList(); _photos.value = emptyList()
            if (_selected.value != null) {
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

    fun clearHive() { detailJob?.cancel(); _selected.value = null; _photos.value = emptyList() }

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

    fun markHiveDead(hiveId: Long, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            runCatching {
                repo.markHiveDead(hiveId)
                if (_selected.value?.id == hiveId) _selected.value = repo.getHive(hiveId)
            }.onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not mark the colony dead.") }
        }
    }

    fun restoreHive(hiveId: Long, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            runCatching {
                repo.restoreHive(hiveId)
                if (_selected.value?.id == hiveId) _selected.value = repo.getHive(hiveId)
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
                if (_selected.value?.id == hiveId) _selected.value = repo.getHive(hiveId)
            }.onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not assign the NFC tag.") }
        }
    }

    fun clearTag() {
        val h = _selected.value ?: return
        clearTagForHive(h.id)
    }

    fun clearTagForHive(hiveId: Long) {
        viewModelScope.launch {
            repo.unassignNfcTag(hiveId)
            if (_selected.value?.id == hiveId) _selected.value = repo.getHive(hiveId)
        }
    }

    fun updateQueenProfile(status: String, markColor: String, origin: String, ageMonths: Int?, temperament: Int) {
        val h = _selected.value ?: return
        viewModelScope.launch {
            repo.saveHive(h.copy(queenStatus = status, queenMarkColor = markColor, queenOrigin = origin, queenAgeMonths = ageMonths, queenTemperament = temperament.coerceIn(1, 5)))
            _selected.value = repo.getHive(h.id)
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
            _selected.value = repo.getHive(h.id)
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
                if (_selected.value?.id == hiveId) _selected.value = repo.getHive(hiveId)
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
