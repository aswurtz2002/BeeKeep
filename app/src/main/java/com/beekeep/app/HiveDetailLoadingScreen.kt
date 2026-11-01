package com.beekeep.app

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Static loading placeholder for the short interval between opening a hive and
 * receiving its current Room-backed profile. It intentionally avoids shimmer
 * and other continuous animation so loading never adds unnecessary field motion.
 */
@Composable
internal fun HiveDetailLoadingScreen() {
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .semantics { contentDescription = "Loading hive details" }
    ) {
        InspectionHoneycombBackground(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(placeholderColor)
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Loading hive…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    HiveLoadingBar(Modifier.fillMaxWidth(0.42f).height(9.dp), placeholderColor)
                }
                HiveLoadingBar(Modifier.size(34.dp).clip(CircleShape), placeholderColor)
            }

            // Main inspection action placeholder.
            HiveLoadingBar(
                Modifier.fillMaxWidth().height(68.dp),
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
            )

            // Feed, treat and harvest actions.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(3) {
                    HiveLoadingBar(
                        Modifier.weight(1f).height(44.dp),
                        placeholderColor
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(3) {
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            HiveLoadingBar(Modifier.fillMaxWidth(0.68f).height(15.dp), placeholderColor)
                            HiveLoadingBar(Modifier.fillMaxWidth(0.48f).height(8.dp), placeholderColor)
                        }
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HiveLoadingBar(Modifier.fillMaxWidth(0.34f).height(16.dp), placeholderColor)
                    HiveLoadingBar(Modifier.fillMaxWidth(0.7f).height(9.dp), placeholderColor)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        repeat(3) {
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                HiveLoadingBar(Modifier.width(26.dp).height(18.dp), placeholderColor)
                                HiveLoadingBar(Modifier.fillMaxWidth(0.72f).height(8.dp), placeholderColor)
                            }
                        }
                    }
                    HiveLoadingBar(Modifier.fillMaxWidth(0.86f).height(8.dp), placeholderColor)
                }
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HiveLoadingBar(Modifier.fillMaxWidth(0.38f).height(14.dp), placeholderColor)
                    HiveLoadingBar(Modifier.fillMaxWidth(0.62f).height(9.dp), placeholderColor)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HiveLoadingBar(Modifier.weight(1f).height(40.dp), placeholderColor)
                        HiveLoadingBar(Modifier.weight(1f).height(40.dp), placeholderColor)
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        HiveLoadingBar(Modifier.fillMaxWidth(0.4f).height(14.dp), placeholderColor)
                        HiveLoadingBar(Modifier.fillMaxWidth(0.68f).height(8.dp), placeholderColor)
                    }
                    HiveLoadingBar(Modifier.width(54.dp).height(28.dp), placeholderColor)
                }
            }
        }
    }
}

@Composable
private fun HiveLoadingBar(
    modifier: Modifier,
    color: androidx.compose.ui.graphics.Color
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
    )
}
