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
import androidx.compose.foundation.text.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Yard
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beekeep.app.ui.theme.BeeKeepAccent

@Composable
internal fun PreMadeHiveSetupScreen(
    vm: BeeKeepViewModel,
    onBack: () -> Unit,
    onComplete: (createdApiaries: Int, createdHives: Int) -> Unit
) {
    var apiaryCount by rememberSaveable { mutableIntStateOf(2) }
    var hivesPerApiary by rememberSaveable { mutableIntStateOf(10) }
    var apiaryPrefix by rememberSaveable { mutableStateOf("Apiary") }
    var hivePrefix by rememberSaveable { mutableStateOf("Hive") }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val totalHives = apiaryCount * hivesPerApiary
    val canAddApiary = apiaryCount < 30 &&
        (apiaryCount + 1).toLong() * hivesPerApiary.toLong() <= 1_000L
    val canAddHive = hivesPerApiary.toLong() * apiaryCount < 1_000L
    val cleanApiaryPrefix = apiaryPrefix.trim()
    val cleanHivePrefix = hivePrefix.trim()
    val apiaryWidth = if (apiaryCount >= 10) apiaryCount.toString().length else 1
    val hiveWidth = if (hivesPerApiary >= 10) hivesPerApiary.toString().length else 1

    BackHandler { if (!isSaving) onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 8.dp, end = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, enabled = !isSaving) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Set up your apiaries",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Build a starter layout",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Choose how many yards and hives to create. BeeKeep will number them for you, and you can rename them later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SetupCountCard(
                    title = "Number of apiaries",
                    subtitle = "Separate yards or locations",
                    count = apiaryCount,
                    decreaseEnabled = apiaryCount > 1 && !isSaving,
                    increaseEnabled = canAddApiary && !isSaving,
                    onDecrease = {
                        apiaryCount = (apiaryCount - 1).coerceAtLeast(1)
                        errorMessage = null
                    },
                    onIncrease = {
                        if (canAddApiary) {
                            apiaryCount += 1
                            errorMessage = null
                        }
                    }
                )

                SetupCountCard(
                    title = "Hives per apiary",
                    subtitle = "The same starting count in each yard",
                    count = hivesPerApiary,
                    decreaseEnabled = hivesPerApiary > 1 && !isSaving,
                    increaseEnabled = canAddHive && !isSaving,
                    onDecrease = {
                        hivesPerApiary = (hivesPerApiary - 1).coerceAtLeast(1)
                        errorMessage = null
                    },
                    onIncrease = {
                        if (canAddHive) {
                            hivesPerApiary += 1
                            errorMessage = null
                        }
                    }
                )

                Text(
                    text = "Naming",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = apiaryPrefix,
                    onValueChange = {
                        apiaryPrefix = it.take(32)
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    label = { Text("Apiary name prefix") },
                    supportingText = { Text("For example: Apiary 1, Apiary 2") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )
                OutlinedTextField(
                    value = hivePrefix,
                    onValueChange = {
                        hivePrefix = it.take(32)
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    label = { Text("Hive name prefix") },
                    supportingText = { Text("For example: Hive 1, Hive 2") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Yard, contentDescription = null, tint = BeeKeepAccent)
                            Text(
                                text = "Preview",
                                modifier = Modifier.padding(start = 8.dp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        (1..minOf(apiaryCount, 2)).forEach { index ->
                            val apiaryName = cleanApiaryPrefix + " " +
                                index.toString().padStart(apiaryWidth, '0')
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = apiaryName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                val hivePreviewCount = minOf(hivesPerApiary, 4)
                                val hiveSamples = (1..hivePreviewCount).joinToString("  ·  ") { hiveIndex ->
                                    cleanHivePrefix + " " + hiveIndex.toString().padStart(hiveWidth, '0')
                                }
                                Text(
                                    text = if (hivesPerApiary > hivePreviewCount) "$hiveSamples  ·  …" else hiveSamples,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                                )
                            }
                        }
                        if (apiaryCount > 2) {
                            Text(
                                text = "+ " + (apiaryCount - 2) + " more apiaries",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = apiaryCount.toString() +
                            (if (apiaryCount == 1) " apiary" else " apiaries") +
                            "  ·  " + totalHives +
                            (if (totalHives == 1) " hive total" else " hives total"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Button(
                        onClick = {
                            if (cleanApiaryPrefix.isBlank()) {
                                errorMessage = "Enter a name prefix for your apiaries."
                            } else if (cleanHivePrefix.isBlank()) {
                                errorMessage = "Enter a name prefix for your hives."
                            } else {
                                isSaving = true
                                errorMessage = null
                                vm.createPreMadeSetup(
                                    apiaryPrefix = cleanApiaryPrefix,
                                    hivePrefix = cleanHivePrefix,
                                    apiaryCount = apiaryCount,
                                    hivesPerApiary = hivesPerApiary
                                ) { success, error ->
                                    isSaving = false
                                    if (success) {
                                        onComplete(apiaryCount, totalHives)
                                    } else {
                                        errorMessage = error ?: "Could not create your setup. Please try again."
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        enabled = !isSaving
                    ) {
                        Text(
                            text = if (isSaving) "Creating your apiaries…" else "Create Apiaries and Hives",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCountCard(
    title: String,
    subtitle: String,
    count: Int,
    decreaseEnabled: Boolean,
    increaseEnabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(
                onClick = onDecrease,
                enabled = decreaseEnabled,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Icon(Icons.Rounded.Remove, contentDescription = "Decrease $title")
            }
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = BeeKeepAccent,
                modifier = Modifier.padding(horizontal = 3.dp)
            )
            IconButton(
                onClick = onIncrease,
                enabled = increaseEnabled,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Increase $title")
            }
        }
    }
}
