package com.beekeep.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Yard
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
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
internal fun HiveRow(hive: Hive, supporting: String? = null, onClick: () -> Unit) {
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
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            1.dp,
            if (attention) MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outline
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(hiveDisplayName(hive.number), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                Text(hive.apiary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Queen ${hive.queenStatus}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                supporting?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
            }
            Column(
                Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
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
    }
}

@Composable
internal fun ColonyHistoryScreen(deadHives: List<Hive>, padding: androidx.compose.foundation.layout.PaddingValues, onBack: () -> Unit, onOpenHive: (Long) -> Unit) {
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
                            Icon(Icons.Rounded.ChevronRight, "Open colony history", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
internal fun FeedDialog(onDismiss: () -> Unit, onSave: (String, String, Double, String, String) -> Unit) {
    var type by rememberSaveable { mutableStateOf("Syrup") }
    var ratio by rememberSaveable { mutableStateOf("1:1") }
    var amount by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableStateOf("gal") }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    SimpleLogDialog("Log Feeding", onDismiss, {
        val value = amount.trim().toDoubleOrNull()
        when {
            type.isBlank() -> error = "Enter a feed type."
            value == null || !value.isFinite() || value < 0 -> error = "Enter a valid non-negative amount."
            unit.isBlank() -> error = "Enter a unit."
            else -> onSave(type.trim(), ratio.trim(), value, unit.trim(), notes.trim())
        }
    }, {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(type, { type = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Feed type") }, singleLine = true)
            OutlinedTextField(ratio, { ratio = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Ratio (optional)") }, singleLine = true)
            OutlinedTextField(amount, { amount = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(unit, { unit = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Unit") }, singleLine = true)
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") })
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
    })
}

@Composable
internal fun TreatmentDialog(onDismiss: () -> Unit, onSave: (String, String, Int, Int, String) -> Unit) {
    var type by rememberSaveable { mutableStateOf("Mite treatment") }
    var product by rememberSaveable { mutableStateOf("") }
    var duration by rememberSaveable { mutableStateOf("42") }
    var withdrawal by rememberSaveable { mutableStateOf("0") }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    SimpleLogDialog("Log Treatment", onDismiss, {
        val removal = duration.trim().toIntOrNull()
        val withdraw = withdrawal.trim().toIntOrNull()
        when {
            type.isBlank() -> error = "Enter a treatment type."
            product.isBlank() -> error = "Enter a product."
            removal == null || removal < 0 -> error = "Removal days must be a non-negative whole number."
            withdraw == null || withdraw < 0 -> error = "Withdrawal days must be a non-negative whole number."
            else -> onSave(type.trim(), product.trim(), removal, withdraw, notes.trim())
        }
    }, {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(type, { type = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Treatment type") }, singleLine = true)
            OutlinedTextField(product, { product = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Product") }, singleLine = true)
            OutlinedTextField(duration, { duration = it.filter(Char::isDigit); error = "" }, Modifier.fillMaxWidth(), label = { Text("Removal in days") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            OutlinedTextField(withdrawal, { withdrawal = it.filter(Char::isDigit); error = "" }, Modifier.fillMaxWidth(), label = { Text("Extra withdrawal days") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") })
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
    })
}

@Composable
internal fun HarvestDialog(onDismiss: () -> Unit, onSave: (Int, Double, Double, String, Double, Double, String) -> Unit) {
    var supers by rememberSaveable { mutableStateOf("0") }
    var wet by rememberSaveable { mutableStateOf("0") }
    var dry by rememberSaveable { mutableStateOf("0") }
    var unit by rememberSaveable { mutableStateOf("lb") }
    var wax by rememberSaveable { mutableStateOf("0") }
    var prop by rememberSaveable { mutableStateOf("0") }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    SimpleLogDialog("Log Harvest", onDismiss, {
        val s = supers.trim().toIntOrNull()
        val w = wet.trim().toDoubleOrNull()
        val d = dry.trim().toDoubleOrNull()
        val wx = wax.trim().toDoubleOrNull()
        val pr = prop.trim().toDoubleOrNull()
        when {
            s == null || s < 0 -> error = "Supers pulled must be a non-negative whole number."
            w == null || !w.isFinite() || w < 0 -> error = "Enter a valid wet honey weight."
            d == null || !d.isFinite() || d < 0 -> error = "Enter a valid dry honey weight."
            unit.isBlank() -> error = "Enter a weight unit."
            wx == null || !wx.isFinite() || wx < 0 -> error = "Enter a valid wax weight."
            pr == null || !pr.isFinite() || pr < 0 -> error = "Enter a valid propolis weight."
            else -> onSave(s, w, d, unit.trim(), wx, pr, notes.trim())
        }
    }, {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(supers, { supers = it.filter(Char::isDigit); error = "" }, Modifier.fillMaxWidth(), label = { Text("Supers pulled") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            OutlinedTextField(wet, { wet = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Wet honey") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(dry, { dry = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Dry honey") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(unit, { unit = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Weight unit") }, singleLine = true)
            OutlinedTextField(wax, { wax = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Wax") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(prop, { prop = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Propolis") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") })
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
    })
}

@Composable private fun SimpleLogDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.ExtraBold) },
        text = { content() },
        confirmButton = { TextButton(onClick = onConfirm) { Text("SAVE", fontWeight = FontWeight.ExtraBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}
