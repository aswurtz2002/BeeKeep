package com.beekeep.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.beekeep.app.data.*
import com.beekeep.app.notifications.ReminderScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

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
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks
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
    private val _allInspections = MutableStateFlow<List<Inspection>>(emptyList())
    val allInspections: StateFlow<List<Inspection>> = _allInspections
    private val _allFeedings = MutableStateFlow<List<Feeding>>(emptyList())
    val allFeedings: StateFlow<List<Feeding>> = _allFeedings
    private val _allTreatments = MutableStateFlow<List<Treatment>>(emptyList())
    val allTreatments: StateFlow<List<Treatment>> = _allTreatments
    private val _allHarvests = MutableStateFlow<List<Harvest>>(emptyList())
    val allHarvests: StateFlow<List<Harvest>> = _allHarvests

    private var detailJob: Job? = null
    private val scheduledReminderFingerprints = mutableMapOf<Long, String>()

    init {
        viewModelScope.launch {
            repo.initialize()
            _ready.value = true
            launch { repo.observeHives().collect { _hives.value = it } }
            launch { repo.observeDeadHives().collect { _deadHives.value = it } }
            launch { repo.observeApiaries().collect { _apiaries.value = it } }
            launch { repo.observeTasks().collect { tasks ->
                _tasks.value = tasks
                syncReminderSchedules(tasks)
            } }
            launch { repo.observeAllInspections().collect { _allInspections.value = it } }
            launch { repo.observeAllFeedings().collect { _allFeedings.value = it } }
            launch { repo.observeAllTreatments().collect { _allTreatments.value = it } }
            launch { repo.observeAllHarvests().collect { _allHarvests.value = it } }
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
            if (normalized.queenStatus == "Queenless") addReminder(h.id, "Check Hive ${h.number}: queenless follow-up", 1, "queenless")
            if (normalized.emergencyCells + normalized.supercedureCells + normalized.swarmCells > 0) addReminder(h.id, "Check Hive ${h.number}: queen-cell outcome / egg laying", 14, "queen_cells")
            if (normalized.mitePercent >= 3.0) addReminder(h.id, "Review Hive ${h.number}: mites ${"%.2f".format(normalized.mitePercent)}%", 1, "mites")
            if (normalized.diseaseFlags.isNotBlank()) addReminder(h.id, "Review Hive ${h.number}: disease/pest flags", 1, "health")
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
                if (durationDays > 0) addReminder(h.id, "Remove ${product.trim()} from Hive ${h.number}", durationDays.toLong(), "treatment_removal")
                if (withdrawal != null) addReminder(h.id, "Honey withdrawal period ends: Hive ${h.number}", durationDays.toLong() + withdrawalDays.toLong(), "withdrawal")
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

    private fun localPlusDays(timestamp: Long, days: Long): Long =
        java.time.Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .plusDays(days)
            .toInstant()
            .toEpochMilli()

    fun createScheduledTask(
        title: String,
        hiveId: Long?,
        dueAt: Long,
        reminderEnabled: Boolean = true,
        repeatEveryDays: Long? = null,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        val clean = title.trim()
        if (clean.isBlank()) { onResult(false, "Give this task a name."); return }
        if (dueAt <= 0L) { onResult(false, "Choose a valid date and time."); return }
        viewModelScope.launch {
            runCatching {
                val kind = repeatEveryDays?.takeIf { it > 0 }?.let(SeasonalPlanner::recurringKind) ?: "calendar"
                val task = Task(IdGenerator.nextLong(), hiveId, clean, dueAt, false, kind, reminderEnabled)
                repo.saveTask(task)
                if (reminderEnabled) ReminderScheduler.schedule(appContext, task.id, task.title, task.dueAt)
            }.onSuccess { onResult(true, null) }
                .onFailure { onResult(false, it.message ?: "Could not schedule task.") }
        }
    }

    fun buildSeasonalPlan(templateIds: Set<String>, daysAhead: Int = 180) {
        if (templateIds.isEmpty()) return
        viewModelScope.launch {
            val start = LocalDate.now().plusDays(1)
            val selected = SeasonalPlanner.templates.filter { it.id in templateIds }
            val existingKeys = _tasks.value.mapTo(mutableSetOf()) { it.kind }
            val generated = buildList {
                for (template in selected) {
                    SeasonalPlanner.generateDates(template, start, daysAhead.coerceIn(30, 730)).forEach { date ->
                        val fingerprint = SeasonalPlanner.fingerprint(template.id, date)
                        if (!existingKeys.add(fingerprint)) return@forEach
                        val dueAt = date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        add(Task(IdGenerator.nextLong(), null, template.title, dueAt, false, fingerprint, true))
                    }
                }
            }
            repo.saveTasks(generated)
            generated.forEach { ReminderScheduler.schedule(appContext, it.id, it.title, it.dueAt) }
        }
    }

    fun addReminder(title: String, days: Long = 0) = addReminder(null, title, days, "manual")
    private fun addReminder(hiveId: Long?, title: String, days: Long, kind: String) {
        viewModelScope.launch {
            val dueAt = localPlusDays(System.currentTimeMillis(), days.coerceAtLeast(0L))
            val duplicateWindow = TimeUnit.DAYS.toMillis(2)
            val duplicate = _tasks.value.any { existing ->
                !existing.completed && existing.hiveId == hiveId && existing.kind == kind &&
                    existing.title == title && kotlin.math.abs(existing.dueAt - dueAt) <= duplicateWindow
            }
            if (duplicate) return@launch
            val task = Task(IdGenerator.nextLong(), hiveId, title, dueAt, false, kind, true)
            repo.saveTask(task); ReminderScheduler.schedule(appContext, task.id, task.title, task.dueAt)
        }
    }

    fun completeTask(task: Task) {
        viewModelScope.launch {
            repo.completeTask(task.id)
            ReminderScheduler.cancel(appContext, task.id)
            scheduledReminderFingerprints.remove(task.id)
            val repeatDays = SeasonalPlanner.recurrenceDays(task.kind)
            if (repeatDays != null) {
                val nextFromOriginal = localPlusDays(task.dueAt, repeatDays)
                val dueAt = maxOf(nextFromOriginal, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5))
                val next = Task(IdGenerator.nextLong(), task.hiveId, task.title, dueAt, false, task.kind, task.reminderEnabled)
                repo.saveTask(next)
                if (task.reminderEnabled) ReminderScheduler.schedule(appContext, next.id, next.title, next.dueAt)
            }
        }
    }

    private fun syncReminderSchedules(tasks: List<Task>) {
        val now = System.currentTimeMillis()
        val activeIds = mutableSetOf<Long>()
        tasks.forEach { task ->
            if (task.completed || !task.reminderEnabled || task.dueAt <= now) {
                ReminderScheduler.cancel(appContext, task.id)
                scheduledReminderFingerprints.remove(task.id)
                return@forEach
            }
            activeIds += task.id
            val fingerprint = "${task.dueAt}|${task.reminderEnabled}|${task.title}"
            if (scheduledReminderFingerprints[task.id] != fingerprint) {
                ReminderScheduler.schedule(appContext, task.id, task.title, task.dueAt)
                scheduledReminderFingerprints[task.id] = fingerprint
            }
        }
        scheduledReminderFingerprints.keys.retainAll(activeIds)
    }

    fun createHiveFromApiaryDefaults(number: String) = createHive(number, _apiaries.value.firstOrNull()?.name ?: "Unassigned Yard", "Laying", 5)

    class Factory(private val repo: LocalHiveRepository, private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BeeKeepViewModel(repo, context) as T
    }
}
