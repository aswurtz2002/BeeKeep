package com.beekeep.app.ui.calendar

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.beekeep.app.SeasonalPlanner
import com.beekeep.app.data.Hive
import com.beekeep.app.data.Task
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun CalendarScreen(
    tasks: List<Task>,
    hives: List<Hive>,
    padding: PaddingValues,
    onAddTask: (title: String, hiveId: Long?, dueAt: Long, reminderEnabled: Boolean, repeatEveryDays: Long?, onResult: (Boolean, String?) -> Unit) -> Unit,
    onComplete: (Task) -> Unit,
    onBuildSeasonalPlan: (Set<String>, Int) -> Unit
) {
    var displayedMonth by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selectedDateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var addDialog by rememberSaveable { mutableStateOf(false) }
    var seasonalDialog by rememberSaveable { mutableStateOf(false) }
    val displayed = runCatching { YearMonth.parse(displayedMonth) }.getOrElse { YearMonth.now().also { displayedMonth = it.toString() } }
    val selectedDate = runCatching { LocalDate.parse(selectedDateText) }.getOrElse { displayed.atDay(1).also { selectedDateText = it.toString() } }.coerceInto(displayed)
    val monthTasks = tasks.filter { !it.completed && it.dueAt.toLocalDate() inMonth displayed }
    val selectedTasks = tasks.filter { it.dueAt.toLocalDate() == selectedDate }.sortedBy { it.dueAt }
    val context = androidx.compose.ui.platform.LocalContext.current
    var notificationsAllowed by rememberSaveable { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notificationsAllowed = granted }
    } else null

    Column(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Calendar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Schedule hive work and get reminded when it's due.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
        }

        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Smart seasonal plan", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                Text("Create editable inspection and monitoring tasks across the coming months. BeeKeep never starts treatments automatically.", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
                Text("Current planning season: ${SeasonalPlanner.seasonFor(LocalDate.now())}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Button(onClick = { seasonalDialog = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("BUILD SEASONAL PLAN", fontWeight = FontWeight.ExtraBold) }
            }
        }

        if (!notificationsAllowed) {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Notifications, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Column(Modifier.weight(1f)) {
                        Text("Reminders are turned off", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("BeeKeep can schedule them, but Android must allow notifications.", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    if (notificationPermission != null) {
                        TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("ALLOW") }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                val previous = displayed.minusMonths(1)
                displayedMonth = previous.toString()
                selectedDateText = previous.atDay(minOf(selectedDate.dayOfMonth, previous.lengthOfMonth())).toString()
            }) { Icon(Icons.Rounded.ChevronLeft, "Previous month") }
            Text(
                displayed.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )
            IconButton(onClick = {
                val next = displayed.plusMonths(1)
                displayedMonth = next.toString()
                selectedDateText = next.atDay(minOf(selectedDate.dayOfMonth, next.lengthOfMonth())).toString()
            }) { Icon(Icons.Rounded.ChevronRight, "Next month") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val today = LocalDate.now()
                displayedMonth = YearMonth.from(today).toString()
                selectedDateText = today.toString()
            }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.Today, null); Spacer(Modifier.width(6.dp)); Text("TODAY")
            }
            Button(onClick = { addDialog = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("ADD TASK", fontWeight = FontWeight.ExtraBold)
            }
        }

        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
                        Text(day, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val first = displayed.atDay(1)
                val leading = first.dayOfWeek.value % 7
                val cells = (((leading + displayed.lengthOfMonth()) + 6) / 7) * 7
                repeat(cells / 7) { rowIndex ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(7) { col ->
                            val offset = rowIndex * 7 + col - leading
                            if (offset in 0 until displayed.lengthOfMonth()) {
                                val date = displayed.atDay(offset + 1)
                                val isSelected = date == selectedDate
                                val isToday = date == LocalDate.now()
                                val hasTasks = monthTasks.any { it.dueAt.toLocalDate() == date }
                                Box(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(12.dp)).background(when { isSelected -> MaterialTheme.colorScheme.primary; isToday -> MaterialTheme.colorScheme.primaryContainer; else -> MaterialTheme.colorScheme.surface }), contentAlignment = Alignment.Center) {
                                    TextButton(onClick = { selectedDateText = date.toString() }, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(0.dp)) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                            Text(date.dayOfMonth.toString(), fontWeight = if (isToday || isSelected) FontWeight.ExtraBold else FontWeight.Medium, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                            if (hasTasks) Box(Modifier.size(5.dp).clip(CircleShape).background(if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)) else Spacer(Modifier.size(5.dp))
                                        }
                                    }
                                }
                            } else Spacer(Modifier.weight(1f).height(46.dp))
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("${selectedTasks.size} scheduled task${if (selectedTasks.size == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (monthTasks.isNotEmpty()) Text("${monthTasks.size} open this month", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }

        if (selectedTasks.isEmpty()) {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Nothing scheduled", fontWeight = FontWeight.Bold)
                    Text("Add an inspection, mite wash, feeding, or any custom hive task.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { addDialog = true }, shape = RoundedCornerShape(14.dp)) { Text("ADD TASK") }
                }
            }
        } else {
            selectedTasks.forEach { task -> CalendarTaskCard(task, hives, onComplete) }
        }
    }

    if (addDialog) {
        AddCalendarTaskDialog(
            hives = hives,
            initialDate = selectedDate,
            initialReminder = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            onDismiss = { addDialog = false },
            onSave = { title, hiveId, dueAt, reminder, repeatEveryDays ->
                onAddTask(title, hiveId, dueAt, reminder, repeatEveryDays) { success, _ ->
                    if (success) {
                        addDialog = false
                        selectedDateText = dueAt.toLocalDate().toString()
                        displayedMonth = YearMonth.from(dueAt.toLocalDate()).toString()
                    }
                }
            }
        )
    }

    if (seasonalDialog) {
        SeasonalPlanDialog(
            onDismiss = { seasonalDialog = false },
            onBuild = { ids, horizon ->
                onBuildSeasonalPlan(ids, horizon)
                seasonalDialog = false
            }
        )
    }
}

@Composable
private fun CalendarTaskCard(task: Task, hives: List<Hive>, onComplete: (Task) -> Unit) {
    val hive = hives.firstOrNull { it.id == task.hiveId }
    val overdue = !task.completed && task.dueAt < System.currentTimeMillis()
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = if (overdue) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(task.title, fontWeight = FontWeight.ExtraBold)
                Text(if (hive == null) "Apiary task" else "Hive ${hive.number}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(task.dueAt.toLocalDateTimeText(), color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                if (overdue) Text("OVERDUE", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
            }
            if (!task.completed) Button(onClick = { onComplete(task) }, shape = RoundedCornerShape(14.dp)) { Text("DONE") }
        }
    }
}

@Composable
private fun AddCalendarTaskDialog(
    hives: List<Hive>,
    initialDate: LocalDate,
    initialReminder: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Long?, Long, Boolean, Long?) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var title by rememberSaveable { mutableStateOf("") }
    var hiveText by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(initialDate.toString()) }
    var time by rememberSaveable { mutableStateOf("09:00") }
    var reminder by rememberSaveable { mutableStateOf(initialReminder) }
    var repeatEveryDays by rememberSaveable { mutableStateOf(0L) }
    var error by rememberSaveable { mutableStateOf("") }
    val localDate = runCatching { LocalDate.parse(date) }.getOrElse { initialDate }
    val localTime = runCatching { LocalTime.parse(time) }.getOrElse { LocalTime.of(9, 0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule Task", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Task") }, singleLine = true)
                OutlinedTextField(hiveText, { hiveText = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Hive # (optional)") }, singleLine = true)
                Text("Quick templates", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (preset in listOf("Inspect hive", "Mite wash", "Check stores", "Feed hive")) { FilterChip(selected = title == preset, onClick = { title = preset }, label = { Text(preset) }) }
                }
                OutlinedButton(onClick = {
                    DatePickerDialog(context, { _, year, month, day -> date = LocalDate.of(year, month + 1, day).toString() }, localDate.year, localDate.monthValue - 1, localDate.dayOfMonth).show()
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("DATE  •  ${localDate.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))}") }
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute -> time = String.format(Locale.US, "%02d:%02d", hour, minute) }, localTime.hour, localTime.minute, false).show()
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("TIME  •  ${localTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))}") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Notifications, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("Remind me", fontWeight = FontWeight.Bold)
                        Text("BeeKeep will alert you when it's due.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = reminder, onCheckedChange = { reminder = it })
                }

                Text("Repeat", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((days, label) in listOf(0L to "Never", 7L to "7 days", 14L to "14 days", 30L to "30 days")) {
                        FilterChip(selected = repeatEveryDays == days, onClick = { repeatEveryDays = days }, label = { Text(label) })
                    }
                }
                if (repeatEveryDays > 0) {
                    Text("Completing this task will schedule its next occurrence automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = title.trim()
                if (clean.isBlank()) { error = "Give this task a name."; return@TextButton }
                val normalizedHiveText = hiveText.trim().removePrefix("#")
                val matches = if (normalizedHiveText.isBlank()) emptyList() else hives.filter { it.number.equals(normalizedHiveText, ignoreCase = true) }
                val hiveId = when {
                    normalizedHiveText.isBlank() -> null
                    matches.isEmpty() -> { error = "Hive #$normalizedHiveText was not found."; return@TextButton }
                    matches.size > 1 -> {
                        error = "Hive #$normalizedHiveText exists in more than one apiary. Open that hive from its record to schedule a task."
                        return@TextButton
                    }
                    else -> matches.single().id
                }
                val dueAt = ZonedDateTime.of(localDate, localTime, ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (dueAt <= System.currentTimeMillis()) { error = "Choose a future date and time."; return@TextButton }
                onSave(clean, hiveId, dueAt, reminder, repeatEveryDays.takeIf { it > 0 })
            }) { Text("SCHEDULE", fontWeight = FontWeight.ExtraBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@Composable
private fun SeasonalPlanDialog(
    onDismiss: () -> Unit,
    onBuild: (Set<String>, Int) -> Unit
) {
    var selected by rememberSaveable { mutableStateOf(SeasonalPlanner.templates.map { it.id }.toSet()) }
    var horizon by rememberSaveable { mutableStateOf(180) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Build seasonal plan", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Choose the planning templates BeeKeep should put on your calendar. They are starting points and can be changed or deleted afterward.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                SeasonalPlanner.templates.forEach { template ->
                    val checked = template.id in selected
                    Card(
                        onClick = { selected = if (checked) selected - template.id else selected + template.id },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(template.title, fontWeight = FontWeight.Bold)
                                Text("Every ${template.everyDays} days • months ${template.months.sorted().joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = checked, onCheckedChange = { selected = if (it) selected + template.id else selected - template.id })
                        }
                    }
                }
                Text("Planning horizon", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(90 to "90 days", 180 to "6 months", 365 to "1 year").forEach { (value, label) ->
                        FilterChip(selected = horizon == value, onClick = { horizon = value }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onBuild(selected, horizon) }, enabled = selected.isNotEmpty()) { Text("BUILD", fontWeight = FontWeight.ExtraBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
private fun Long.toLocalDateTimeText(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
private infix fun LocalDate.inMonth(month: YearMonth): Boolean = YearMonth.from(this) == month
private fun LocalDate.coerceInto(month: YearMonth): LocalDate = if (YearMonth.from(this) == month) this else month.atDay(minOf(dayOfMonth, month.lengthOfMonth()))
