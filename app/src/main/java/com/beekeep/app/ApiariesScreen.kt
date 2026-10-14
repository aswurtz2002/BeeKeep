package com.beekeep.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Yard
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beekeep.app.data.Apiary
import com.beekeep.app.data.Hive

@Composable
internal fun ApiariesScreen(
    apiaries: List<Apiary>,
    hives: List<Hive>,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onAddApiary: () -> Unit,
    onEditApiary: (Apiary) -> Unit,
    onOpenMap: (Apiary) -> Unit,
    onOpenApiary: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = apiaries.filter { it.name.contains(query, ignoreCase = true) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        InspectionHoneycombBackground(Modifier.matchParentSize())
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Apiaries", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("${filtered.size} ${if (filtered.size == 1) "apiary" else "apiaries"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onAddApiary, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Add, "Add apiary") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search apiaries", color = Color(0xFF78350F)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Color(0xFF78350F)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = Color(0xFFD97706),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = Color(0xFFD97706)
            )
        )
        Spacer(Modifier.height(10.dp))
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    if (apiaries.isEmpty()) "No apiaries yet" else "No matching apiaries",
                    if (apiaries.isEmpty()) "Create an apiary first, then add hives inside it." else "Try a different apiary name.",
                    if (apiaries.isEmpty()) "ADD APIARY" else null,
                    if (apiaries.isEmpty()) onAddApiary else null
                )
            }
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { apiary ->
                    val count = hives.count { it.apiary.equals(apiary.name, ignoreCase = true) }
                    Card(
                        onClick = { onOpenApiary(apiary.name) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFFFE8C2), modifier = Modifier.size(52.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Yard, "Apiary", tint = Color(0xFF78350F))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(apiary.name, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                                Text("$count ${if (count == 1) "hive" else "hives"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                apiary.notes.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(onClick = { onEditApiary(apiary) }) { Icon(Icons.Rounded.Edit, "Edit apiary ${apiary.name}") }
                                if (apiary.latitude != null && apiary.longitude != null) {
                                    IconButton(onClick = { onOpenMap(apiary) }) {
                                        Icon(Icons.Rounded.LocationOn, "View ${apiary.name} on satellite map")
                                    }
                                } else {
                                    Icon(Icons.Rounded.ChevronRight, "Open apiary", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

