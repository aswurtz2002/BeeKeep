package com.beekeep.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Scale
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Divider
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Yard
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.beekeep.app.data.ActivityEvent
import com.beekeep.app.data.Harvest
import com.beekeep.app.data.Hive
import com.beekeep.app.data.Treatment
import java.text.DateFormat
import java.util.Date
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun HiveRow(
    hive: Hive,
    supporting: String? = null,
    compact: Boolean = false,
    locked: Boolean = false,
    onClick: () -> Unit
) {
    // The Apiaries hive list now highlights the colony's strength rather than mite percentage.
    val strengthGrade = ColonyStrength.gradeFromStored(hive.strength)
    val strengthColor = when (strengthGrade) {
        1 -> Color(0xFFB91C1C) // Very Weak: red
        2 -> Color(0xFFEA580C) // Weak: orange
        3 -> Color(0xFFCA8A04) // Moderate: yellow
        4 -> Color(0xFF65A30D) // Strong: light green
        else -> Color(0xFF166534) // Very strong: dark green
    }
    val attention = hive.queenStatus == "Queenless" || strengthGrade <= 2
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 20.dp else 24.dp),
        border = BorderStroke(
            1.dp,
            if (attention) MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outline
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 13.dp, vertical = if (compact) 10.dp else 15.dp)
                    .padding(end = if (locked) 18.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)) {
                    Text(hiveDisplayName(hive.number), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text(hive.apiary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        hive.beeCountFrames?.let { frames ->
                            "Queen ${hive.queenStatus} ~ ${formatBeeCountFrames(frames)} Frames of Bees"
                        } ?: "Queen ${hive.queenStatus}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    supporting?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
                }
                Column(
                    Modifier.padding(horizontal = 4.dp, vertical = if (compact) 2.dp else 4.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        ColonyStrength.labelForGrade(strengthGrade),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = strengthColor,
                        maxLines = 1
                    )
                    Icon(Icons.Rounded.ChevronRight, "Open hive", tint = Color(0xFF78350F))
                }
            }
            if (locked) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = "Locked hive",
                    tint = Color(0xFFB45309),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 7.dp).size(16.dp)
                )
            }
        }
    }
}


@Composable
internal fun LockedHiveScreen(hiveName: String, onBack: () -> Unit, onUpgrade: () -> Unit) {
    BackHandler { onBack() }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = "Hive locked",
                    tint = Color(0xFFB45309),
                    modifier = Modifier.size(42.dp)
                )
                Text("Hive Locked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text(
                    "$hiveName was added while a BeeKeep subscription was active. Renew a paid plan to unlock this hive and its records.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onUpgrade, modifier = Modifier.fillMaxWidth()) {
                    Text("RENEW / VIEW PLANS")
                }
                TextButton(onClick = onBack) { Text("BACK TO HIVES") }
            }
        }
    }
}

@Composable
internal fun ColonyHistoryScreen(
    deadHives: List<Hive>,
    lockedHiveIds: Set<Long> = emptySet(),
    padding: androidx.compose.foundation.layout.PaddingValues,
    onBack: () -> Unit,
    onOpenHive: (Long) -> Unit
) {
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Colony history", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("${deadHives.size} dead ${if (deadHives.size == 1) "colony" else "colonies"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (deadHives.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("No dead colonies", "When a colony dies, mark it dead to keep its history here and release its NFC tag.", null, null)
            }
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(deadHives, key = { it.id }) { hive ->
                    Card(onClick = { onOpenHive(hive.id) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(hiveDisplayName(hive.number), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                                Text(hive.apiary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "Dead: ${hive.deadAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "date unknown"}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (hive.id in lockedHiveIds) {
                                    Icon(
                                        Icons.Rounded.Lock,
                                        contentDescription = "Locked hive",
                                        tint = Color(0xFFB45309),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Icon(Icons.Rounded.ChevronRight, "Open colony history", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmptyState(title: String, message: String, actionLabel: String?, onAction: (() -> Unit)?) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction) { Text(actionLabel, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
internal fun QueenProfileDialog(
    hive: Hive,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int?, Int) -> Unit
) {
    var status by rememberSaveable { mutableStateOf(hive.queenStatus) }
    var mark by rememberSaveable { mutableStateOf(hive.queenMarkColor) }
    var origin by rememberSaveable { mutableStateOf(hive.queenOrigin) }
    var age by rememberSaveable { mutableStateOf(hive.queenAgeMonths?.toString() ?: "") }
    var temperament by rememberSaveable { mutableIntStateOf(hive.queenTemperament.coerceIn(1, 5)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Queen profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status", fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (st in listOf("Laying", "Spotted", "Unspotted", "Queenless", "Virgin")) {
                        FilterChip(status == st, { status = st }, { Text(st) })
                    }
                }
                OutlinedTextField(mark, { mark = it }, Modifier.fillMaxWidth(), label = { Text("Mark color") }, singleLine = true)
                OutlinedTextField(origin, { origin = it }, Modifier.fillMaxWidth(), label = { Text("Origin") }, singleLine = true)
                OutlinedTextField(age, { age = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Age (months)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("Temperament ${temperament}/5", fontWeight = FontWeight.Bold)
                Counter("Temperament", temperament, 1..5) { temperament = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(status, mark, origin, age.toIntOrNull(), temperament) }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@Composable internal fun EventCard(event:ActivityEvent){Card(shape=RoundedCornerShape(16.dp)){Row(Modifier.fillMaxWidth().padding(14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){Icon(Icons.Rounded.Yard,null);Column{Text(event.title,fontWeight=FontWeight.Bold);Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(event.createdAt)),color=MaterialTheme.colorScheme.onSurfaceVariant);if(event.detail.isNotBlank())Text(event.detail)}}}}

internal fun parsePositiveLogAmount(raw: String): Double? {
    val parsed = raw.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return parsed.takeIf { it.isFinite() && it > 0.0 }
}

internal fun parseOptionalNonNegativeLogWeight(raw: String): Double? {
    if (raw.isBlank()) return 0.0
    val parsed = raw.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return parsed.takeIf { it.isFinite() && it >= 0.0 }
}

internal fun parseNonNegativeLogDays(raw: String): Int? =
    raw.trim().toIntOrNull()?.takeIf { it >= 0 }

internal fun hasMeaningfulHarvestRecord(
    supersPulled: Int,
    wetHoney: Double,
    dryHoney: Double,
    wax: Double,
    propolis: Double
): Boolean = supersPulled > 0 || listOf(wetHoney, dryHoney, wax, propolis).any { it > 0.0 }

@Composable
internal fun FeedDialog(
    onDismiss: () -> Unit,
    hiveName: String? = null,
    onSave: (String, String, Double, String, String) -> Unit
) {
    var type by rememberSaveable { mutableStateOf("Syrup") }
    var customType by rememberSaveable { mutableStateOf("") }
    var ratioChoice by rememberSaveable { mutableStateOf("1:1") }
    var customRatio by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableStateOf("gal") }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }

    val typeOptions = listOf("Syrup", "Fondant", "Pollen patty", "Dry sugar", "Water", "Other")
    val unitOptions = when (type) {
        "Fondant", "Pollen patty", "Dry sugar" -> listOf("lb", "kg")
        "Other" -> listOf("L", "gal", "lb", "kg")
        else -> listOf("L", "gal")
    }

    FieldLogDialog(
        title = "Log feeding",
        subtitle = hiveName?.let { "$it · Recorded when saved" } ?: "Record what you gave this colony",
        icon = Icons.Rounded.WaterDrop,
        onDismiss = onDismiss,
        onSave = {
            val parsedAmount = parsePositiveLogAmount(amount)
            val finalType = if (type == "Other") customType.trim() else type
            val finalRatio = if (type == "Syrup") {
                if (ratioChoice == "Custom") customRatio.trim() else ratioChoice
            } else ""
            when {
                finalType.isBlank() -> error = "Choose a feed type or enter a custom type."
                parsedAmount == null -> error = "Enter an amount greater than 0. Use a dot or comma for decimals."
                unit !in unitOptions -> error = "Choose a valid unit."
                type == "Syrup" && finalRatio.isBlank() -> error = "Choose or enter a syrup ratio."
                else -> onSave(finalType, finalRatio, parsedAmount, unit, notes.trim())
            }
        }
    ) {
        LogInfoPanel(
            title = "Feeding entry",
            body = "The save time is recorded automatically. Amounts must be greater than zero."
        )
        LogValidationMessage(error)

        LogFieldSection("What did you feed?") {
            LogChoiceChips(typeOptions, type) { selected ->
                type = selected
                error = ""
                ratioChoice = if (selected == "Syrup") ratioChoice.takeIf { it in listOf("1:1", "2:1", "1:2", "Custom") } ?: "1:1" else ""
                val nextUnits = when (selected) {
                    "Fondant", "Pollen patty", "Dry sugar" -> listOf("lb", "kg")
                    "Other" -> listOf("L", "gal", "lb", "kg")
                    else -> listOf("L", "gal")
                }
                if (unit !in nextUnits) unit = if (selected in listOf("Fondant", "Pollen patty", "Dry sugar")) "lb" else "gal"
            }
            if (type == "Other") {
                OutlinedTextField(
                    value = customType,
                    onValueChange = { customType = it; error = "" },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Feed type") },
                    placeholder = { Text("Enter feed type") },
                    singleLine = true
                )
            }
        }

        if (type == "Syrup") {
            LogFieldSection("Syrup ratio") {
                LogChoiceChips(listOf("1:1", "2:1", "1:2", "Custom"), ratioChoice) {
                    ratioChoice = it
                    error = ""
                }
                if (ratioChoice == "Custom") {
                    OutlinedTextField(
                        value = customRatio,
                        onValueChange = { customRatio = it; error = "" },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom ratio") },
                        placeholder = { Text("e.g. 3:2") },
                        singleLine = true
                    )
                }
            }
        }

        LogFieldSection("How much?") {
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it; error = "" },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Amount") },
                placeholder = { Text("Enter amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )
            Text("Unit", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            LogChoiceChips(unitOptions, unit) { unit = it; error = "" }
        }

        LogFieldSection("Notes (optional)") {
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Notes") },
                placeholder = { Text("Feed source, hive response, or follow-up…") },
                minLines = 2,
                maxLines = 4
            )
        }

    }
}

@Composable
internal fun TreatmentDialog(
    onDismiss: () -> Unit,
    hiveName: String? = null,
    onSave: (String, String, Int, Int, String) -> Unit
) {
    var type by rememberSaveable { mutableStateOf("Mite treatment") }
    var customType by rememberSaveable { mutableStateOf("") }
    var product by rememberSaveable { mutableStateOf("") }
    // Timings are deliberately blank: removal and withdrawal requirements depend on the chosen product's label.
    var removalDays by rememberSaveable { mutableStateOf("") }
    var withdrawalDays by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }

    val typeOptions = listOf("Mite treatment", "Disease treatment", "Medication", "Other")
    val parsedRemovalDays = parseNonNegativeLogDays(removalDays)
    val parsedWithdrawalDays = parseNonNegativeLogDays(withdrawalDays)
    val plannedRemovalDate = parsedRemovalDays?.takeIf { it > 0 }?.let(::logDateAfterDays)
    val plannedWithdrawalDate = if (parsedRemovalDays != null && parsedRemovalDays > 0 &&
        parsedWithdrawalDays != null && parsedWithdrawalDays > 0
    ) {
        logDateAfterDays(parsedRemovalDays + parsedWithdrawalDays)
    } else null

    FieldLogDialog(
        title = "Log treatment",
        subtitle = hiveName?.let { "$it · Record product and follow-up timing" } ?: "Record product and follow-up timing",
        icon = Icons.Rounded.MedicalServices,
        onDismiss = onDismiss,
        onSave = {
            val finalType = if (type == "Other") customType.trim() else type
            when {
                finalType.isBlank() -> error = "Choose a treatment type or enter a custom type."
                product.trim().isBlank() -> error = "Enter the product or treatment name."
                parsedRemovalDays == null -> error = "Enter removal days from the product label, or 0 if no removal reminder applies."
                parsedWithdrawalDays == null -> error = "Enter the additional withdrawal days from the product label, or 0 if none applies."
                parsedRemovalDays == 0 && parsedWithdrawalDays != null && parsedWithdrawalDays > 0 -> error = "An additional withdrawal period needs a removal date. Check the product label."
                else -> onSave(finalType, product.trim(), parsedRemovalDays, parsedWithdrawalDays, notes.trim())
            }
        }
    ) {
        LogInfoPanel(
            title = "Product-label timing",
            body = "Enter the timing specified for the product you are using. BeeKeep tracks the dates you enter; it does not determine a safe treatment schedule.",
            warning = true
        )
        LogValidationMessage(error)

        LogFieldSection("Treatment type") {
            LogChoiceChips(typeOptions, type) {
                type = it
                error = ""
            }
            if (type == "Other") {
                OutlinedTextField(
                    value = customType,
                    onValueChange = { customType = it; error = "" },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Treatment type") },
                    placeholder = { Text("Enter a type") },
                    singleLine = true
                )
            }
            OutlinedTextField(
                value = product,
                onValueChange = { product = it; error = "" },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Product / treatment name") },
                placeholder = { Text("Name exactly as shown on the label") },
                singleLine = true
            )
        }

        LogFieldSection("Removal reminder") {
            OutlinedTextField(
                value = removalDays,
                onValueChange = { removalDays = it.filter(Char::isDigit).take(4); error = "" },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Remove after (days)") },
                supportingText = { Text("Enter 0 if no removal reminder applies.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            when {
                parsedRemovalDays == null -> Text("Removal date will appear when you enter the number of days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                parsedRemovalDays == 0 -> Text("No removal reminder will be scheduled.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LogDatePreview("Planned removal", plannedRemovalDate.orEmpty())
            }
        }

        LogFieldSection("Withdrawal reminder") {
            OutlinedTextField(
                value = withdrawalDays,
                onValueChange = { withdrawalDays = it.filter(Char::isDigit).take(4); error = "" },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Additional days after removal") },
                supportingText = { Text("Enter 0 if no extra withdrawal period applies.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            when {
                parsedWithdrawalDays == null -> Text("Enter the additional interval from the product label.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                parsedWithdrawalDays == 0 -> Text("No additional withdrawal reminder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                plannedWithdrawalDate != null -> LogDatePreview("Withdrawal date", plannedWithdrawalDate)
                parsedRemovalDays == null -> Text("Enter removal days first to calculate the withdrawal date.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                parsedRemovalDays == 0 -> Text("A withdrawal reminder requires a removal date.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LogFieldSection("Notes (optional)") {
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Notes") },
                placeholder = { Text("Placement, batch number, observed response…") },
                minLines = 2,
                maxLines = 4
            )
        }

    }
}

@Composable
internal fun HarvestDialog(
    onDismiss: () -> Unit,
    hiveName: String? = null,
    onSave: (Int, Double, Double, String, Double, Double, String) -> Unit
) {
    var supers by rememberSaveable { mutableIntStateOf(0) }
    var wet by rememberSaveable { mutableStateOf("") }
    var dry by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("lb") }
    var wax by rememberSaveable { mutableStateOf("") }
    var propolis by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }

    val parsedWet = parseOptionalNonNegativeLogWeight(wet)
    val parsedDry = parseOptionalNonNegativeLogWeight(dry)
    val parsedWax = parseOptionalNonNegativeLogWeight(wax)
    val parsedPropolis = parseOptionalNonNegativeLogWeight(propolis)

    FieldLogDialog(
        title = "Log harvest",
        subtitle = hiveName?.let { "$it · Capture supers and recovered products" } ?: "Capture supers and recovered products",
        icon = Icons.Rounded.Scale,
        onDismiss = onDismiss,
        onSave = {
            when {
                parsedWet == null -> error = "Wet honey must be a valid non-negative weight."
                parsedDry == null -> error = "Dry honey must be a valid non-negative weight."
                parsedWax == null -> error = "Wax weight must be a valid non-negative weight."
                parsedPropolis == null -> error = "Propolis weight must be a valid non-negative weight."
                !hasMeaningfulHarvestRecord(supers, parsedWet, parsedDry, parsedWax, parsedPropolis) ->
                    error = "Enter at least one harvest quantity or the number of supers pulled."
                unit !in listOf("lb", "kg") -> error = "Choose a valid weight unit."
                else -> onSave(supers, parsedWet, parsedDry, unit, parsedWax, parsedPropolis, notes.trim())
            }
        }
    ) {
        LogInfoPanel(
            title = "Harvest record",
            body = "Blank weight fields are saved as 0 in the current record format. Enter your measured amounts or a supers count before saving."
        )
        LogValidationMessage(error)

        LogFieldSection("Supers pulled") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(supers.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Text(if (supers == 1) "super removed" else "supers removed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(
                    onClick = { supers = (supers - 1).coerceAtLeast(0); error = "" },
                    enabled = supers > 0,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
                ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                Button(
                    onClick = { supers = (supers + 1).coerceAtMost(999); error = "" },
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
                ) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
        }

        LogFieldSection("Weight unit") {
            LogChoiceChips(listOf("lb", "kg"), unit) { unit = it; error = "" }
        }

        LogFieldSection("Honey") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = wet,
                    onValueChange = { wet = it; error = "" },
                    modifier = Modifier.weight(1f),
                    label = { Text("Wet ($unit)") },
                    placeholder = { Text("Blank = 0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = dry,
                    onValueChange = { dry = it; error = "" },
                    modifier = Modifier.weight(1f),
                    label = { Text("Dry ($unit)") },
                    placeholder = { Text("Blank = 0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }
        }

        LogFieldSection("Other products (optional)") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = wax,
                    onValueChange = { wax = it; error = "" },
                    modifier = Modifier.weight(1f),
                    label = { Text("Wax ($unit)") },
                    placeholder = { Text("Blank = 0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = propolis,
                    onValueChange = { propolis = it; error = "" },
                    modifier = Modifier.weight(1f),
                    label = { Text("Propolis ($unit)") },
                    placeholder = { Text("Blank = 0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }
        }

        LogFieldSection("Notes (optional)") {
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Notes") },
                placeholder = { Text("Harvest conditions, moisture, batch details…") },
                minLines = 2,
                maxLines = 4
            )
        }

    }
}

@Composable
private fun FieldLogDialog(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val configuration = LocalConfiguration.current
    val maxDialogHeight = (configuration.screenHeightDp.dp * 0.88f).coerceAtMost(760.dp)
    val maxFormHeight = (configuration.screenHeightDp.dp * 0.54f).coerceAtMost(470.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 520.dp)
                .heightIn(max = maxDialogHeight),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, end = 10.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(25.dp))
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                }
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxFormHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content
                )
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Text("CANCEL", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1.25f).heightIn(min = 52.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Text("SAVE LOG", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogFieldSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
        content()
    }
}

@Composable
private fun LogChoiceChips(
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelected(option) },
                label = { Text(option) }
            )
        }
    }
}

@Composable
private fun LogInfoPanel(title: String, body: String, warning: Boolean = false) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (warning) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun LogValidationMessage(message: String) {
    if (message.isNotBlank()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Text(
                message,
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun LogDatePreview(label: String, date: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(date, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

private fun logDateAfterDays(days: Int): String =
    LocalDate.now().plusDays(days.toLong()).format(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    )
