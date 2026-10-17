package com.beekeep.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Yard
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.beekeep.app.ui.theme.BeeKeepAccent
import java.util.Locale

@Composable
internal fun PreMadeHiveSetupScreen(
    vm: BeeKeepViewModel,
    existingApiaryNames: List<String> = emptyList(),
    existingHiveCount: Int = 0,
    addToExistingWorkspace: Boolean = false,
    hiveLimit: Int = BeeKeepEntitlements.FREE_HIVE_LIMIT,
    onBack: () -> Unit,
    onUpgrade: () -> Unit = {},
    onComplete: (createdApiaries: Int, createdHives: Int) -> Unit
) {
    val suggestedApiaryName = remember(existingApiaryNames) {
        val existing = existingApiaryNames.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        var suffix = 1
        while ("apiary $suffix" in existing) suffix++
        "Apiary $suffix"
    }
    var apiaryNames by rememberSaveable(addToExistingWorkspace) {
        mutableStateOf(if (addToExistingWorkspace) listOf(suggestedApiaryName) else listOf("Apiary 1", "Apiary 2"))
    }
    var startNumbers by rememberSaveable(addToExistingWorkspace) {
        mutableStateOf(if (addToExistingWorkspace) listOf("1") else listOf("1", "1"))
    }
    val maximumNewHives = (hiveLimit - if (addToExistingWorkspace) existingHiveCount else 0).coerceAtLeast(0)
    var hiveCounts by rememberSaveable(addToExistingWorkspace) {
        mutableStateOf(if (addToExistingWorkspace) listOf(minOf(10, maximumNewHives).toString()) else listOf("10", "10"))
    }
    var hivePrefix by rememberSaveable(addToExistingWorkspace) { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val totalHives = hiveCounts.sumOf { it.toIntOrNull() ?: 0 }
    val existingApiaryNamesNormalized = remember(existingApiaryNames) {
        existingApiaryNames.map { it.trim().lowercase(Locale.ROOT) }.toSet()
    }
    val canAddApiary = true

    BackHandler { if (!isSaving) onBack() }

    fun updateName(index: Int, value: String) {
        apiaryNames = apiaryNames.mapIndexed { i, old -> if (i == index) value.take(48) else old }
        errorMessage = null
    }

    fun updateStart(index: Int, value: String) {
        startNumbers = startNumbers.mapIndexed { i, old -> if (i == index) value.filter(Char::isDigit).take(6) else old }
        errorMessage = null
    }

    fun updateCount(index: Int, value: String) {
        hiveCounts = hiveCounts.mapIndexed { i, old -> if (i == index) value.filter(Char::isDigit).take(4) else old }
        errorMessage = null
    }

    fun nextAvailableApiaryName(): String {
        val alreadyUsed = (existingApiaryNames + apiaryNames)
            .map { it.trim().lowercase(Locale.ROOT) }
            .toSet()
        var suffix = 1
        while ("apiary $suffix" in alreadyUsed) suffix++
        return "Apiary $suffix"
    }

    fun addApiary() {
        apiaryNames = apiaryNames + nextAvailableApiaryName()
        startNumbers = startNumbers + "1"
        hiveCounts = hiveCounts + ""
        errorMessage = null
    }

    fun removeApiary(index: Int) {
        if (apiaryNames.size <= 1) return
        apiaryNames = apiaryNames.filterIndexed { i, _ -> i != index }
        startNumbers = startNumbers.filterIndexed { i, _ -> i != index }
        hiveCounts = hiveCounts.filterIndexed { i, _ -> i != index }
        errorMessage = null
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 18.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, enabled = !isSaving) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (addToExistingWorkspace) "Add Apiaries and Hives" else "Set up your apiaries",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        if (addToExistingWorkspace) "Add starter colonies without resetting your existing apiaries"
                        else "Customize every yard and hive range",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    if (addToExistingWorkspace) {
                        "Add one or more apiaries and choose how many starter hives each should contain. Your current apiaries and hive records will be kept."
                    } else {
                        "Give each apiary its own name, starting hive number, and hive count. Leave a hive count blank or set it to 0 to create an apiary without starter hives."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hiveLimit != Int.MAX_VALUE) {
                    Text(
                        "Free plan: $existingHiveCount of 150 hives currently used. Apiaries are unlimited.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Hive naming", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = hivePrefix,
                            onValueChange = { hivePrefix = it.take(24); errorMessage = null },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            label = { Text("Hive name prefix (optional)") },
                            supportingText = { Text("Leave blank for numbers only, or enter a prefix such as Hive") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            enabled = !isSaving
                        )
                    }
                }

                Text(
                    (if (addToExistingWorkspace) "New apiaries (" else "Your apiaries (") + apiaryNames.size + ")",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )

                apiaryNames.indices.forEach { index ->
                    val cleanName = apiaryNames[index].trim()
                    val start = startNumbers[index].toIntOrNull()
                    val count = hiveCounts[index].toIntOrNull() ?: 0
                    val end = if (start != null && count > 0) start.toLong() + count.toLong() - 1L else null

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.padding(end = 10.dp)
                                ) {
                                    Icon(Icons.Rounded.Yard, contentDescription = null, tint = BeeKeepAccent, modifier = Modifier.padding(8.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        (if (addToExistingWorkspace) "New apiary " else "Apiary ") + (index + 1),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        if (count == 0) "No starter hives"
                                        else count.toString() + " starter " + (if (count == 1) "hive" else "hives"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (apiaryNames.size > 1) {
                                    IconButton(onClick = { removeApiary(index) }, enabled = !isSaving) {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = "Remove " + if (cleanName.isBlank()) "apiary " + (index + 1) else cleanName
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = apiaryNames[index],
                                onValueChange = { updateName(index, it) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                label = { Text("Apiary name") },
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                                enabled = !isSaving
                            )

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = startNumbers[index],
                                    onValueChange = { updateStart(index, it) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    label = { Text("Starting #") },
                                    supportingText = { Text("First hive number") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    enabled = !isSaving
                                )
                                OutlinedTextField(
                                    value = hiveCounts[index],
                                    onValueChange = { updateCount(index, it) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    label = { Text("Hive count") },
                                    supportingText = { Text("Optional • blank = 0") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    enabled = !isSaving
                                )
                            }

                            val preview = when {
                                count == 0 -> "This apiary will be created empty."
                                start == null -> "Enter a starting number to preview the hive names."
                                end != null && end > 999_999L -> "The last hive number is too large."
                                else -> if (hivePrefix.trim().isBlank()) {
                                    start.toString() + "  →  " + (end ?: start)
                                } else {
                                    hivePrefix.trim() + " " + start + "  →  " + hivePrefix.trim() + " " + (end ?: start)
                                }
                            }
                            Text(
                                preview,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (end != null && end > 999_999L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = { addApiary() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isSaving && canAddApiary
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text("Add another apiary", modifier = Modifier.padding(start = 8.dp))
                }
                errorMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    if (message.contains("Free plan", ignoreCase = true)) {
                        OutlinedButton(onClick = onUpgrade, modifier = Modifier.fillMaxWidth(), enabled = !isSaving) {
                            Text("VIEW PRO & ULTIMATE")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        apiaryNames.size.toString() + (if (apiaryNames.size == 1) " apiary" else " apiaries") +
                            "  ·  " + totalHives + (if (totalHives == 1) " hive" else " hives") + " total",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Button(
                        onClick = {
                            val cleanedNames = apiaryNames.map { it.trim() }
                            val normalizedNames = cleanedNames.map { it.lowercase(Locale.ROOT) }
                            val starts = startNumbers.map { it.toIntOrNull() }
                            val counts = hiveCounts.map { if (it.isBlank()) 0 else it.toIntOrNull() ?: -1 }
                            errorMessage = when {
                                totalHives > maximumNewHives ->
                                    if (hiveLimit == Int.MAX_VALUE) "This batch is too large to process at once. Split it into smaller batches."
                                    else if (addToExistingWorkspace) "You can add only $maximumNewHives more hives before reaching the Free plan's 150-hive limit."
                                    else "The Free plan supports up to 150 hives. Upgrade to BeeKeep Pro or Ultimate for unlimited hives."
                                cleanedNames.any(String::isBlank) -> "Enter a name for every apiary."
                                normalizedNames.distinct().size != normalizedNames.size -> "Each apiary needs a unique name."
                                addToExistingWorkspace && cleanedNames.any { it.lowercase(Locale.ROOT) in existingApiaryNamesNormalized } ->
                                    "An apiary with that name already exists. Choose a different name."
                                starts.any { it?.let { value -> value !in 1..999_999 } ?: true } -> "Starting numbers must be between 1 and 999,999."
                                counts.any { it < 0 || it > 9_999 } -> "Each hive count must be between 0 and 9,999 (or left blank)."
                                starts.indices.any { i ->
                                    starts[i]?.let { start -> counts[i] > 0 && start.toLong() + counts[i].toLong() - 1L > 999_999L } ?: false
                                } -> "A hive range cannot end above 999,999."
                                else -> null
                            }
                            if (errorMessage == null) {
                                isSaving = true
                                vm.createPreMadeSetup(
                                    hivePrefix = hivePrefix.trim(),
                                    apiaryNames = cleanedNames,
                                    startingHiveNumbers = starts.map { it!! },
                                    hiveCounts = counts,
                                    allowExistingWorkspace = addToExistingWorkspace
                                ) { success, error ->
                                    isSaving = false
                                    if (success) onComplete(cleanedNames.size, counts.sum())
                                    else errorMessage = error ?: "Could not create your setup. Please try again."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        enabled = !isSaving
                    ) {
                        Text(
                            when {
                                isSaving && addToExistingWorkspace -> "Adding apiaries…"
                                isSaving -> "Creating your apiaries…"
                                addToExistingWorkspace -> "Add Apiaries and Hives"
                                else -> "Create Apiaries and Hives"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
