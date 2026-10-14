package com.beekeep.app

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beekeep.app.data.Apiary
import com.beekeep.app.data.Hive
import com.beekeep.app.ui.theme.BeeKeepAccent

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier, supporting: String? = null) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1
            )
            Text(
                title,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium
            )
            supporting?.let {
                Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HoneycombScanButton(scanning: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "homeScanPulse")
    val ringScale by pulse.animateFloat(
        0.84f, 1.27f,
        infiniteRepeatable(tween(950, easing = androidx.compose.animation.core.LinearOutSlowInEasing)),
        label = "homeScanRingScale"
    )
    val ringAlpha by pulse.animateFloat(
        0.92f, 0f,
        infiniteRepeatable(tween(950, easing = androidx.compose.animation.core.LinearOutSlowInEasing)),
        label = "homeScanRingAlpha"
    )
    val iconScale by pulse.animateFloat(
        1f, 1.045f,
        infiniteRepeatable(
            animation = tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "homeScanIconScale"
    )
    val imageBitmap = ImageBitmap.imageResource(id = R.drawable.scan_honeycomb)
    val croppedPainter = remember(imageBitmap) {
        // Crop to the honeycomb's bounds, excluding the detached oval shadow at the bottom.
        BitmapPainter(
            image = imageBitmap,
            srcOffset = IntOffset(5, 0),
            srcSize = IntSize(
                width = minOf(234, imageBitmap.width - 5),
                height = minOf(228, imageBitmap.height)
            )
        )
    }

    // The pulse is drawn from the transparent artwork itself, so no square outline/ripple appears.
    Box(
        modifier = Modifier
            .width(220.dp)
            .height(226.dp)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (scanning) {
            // Bright gold silhouette expands beyond the artwork and fades to create a clear pulse.
            Image(
                painter = croppedPainter,
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(0xFFFFC928)),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = ringScale
                        scaleY = ringScale
                        alpha = ringAlpha
                    }
            )
        }

        Image(
            painter = croppedPainter,
            contentDescription = if (scanning) "Scanning for a hive tag" else "Scan for a hive tag",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = if (scanning) iconScale else 1f
                    scaleX = scale
                    scaleY = scale
                }
        )
    }
}

@Composable
internal fun HomeScreen(
    hives: List<Hive>,
    apiaries: List<Apiary>,
    padding: androidx.compose.foundation.layout.PaddingValues,
    scanning: Boolean,
    onScan: () -> Unit,
    onCancelScan: () -> Unit
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(padding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            // Integrated header: the cream background stays visible, with an ambient
            // amber glow and barely-there honeycomb watermark for depth.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(142.dp)
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(
                                BeeKeepAccent.copy(alpha = 0.14f),
                                BeeKeepAccent.copy(alpha = 0.055f),
                                Color.Transparent
                            ),
                            center = Offset(w * 0.78f, h * 0.46f),
                            radius = w * 0.56f
                        )
                    )

                    // Fine outline cells are deliberately faint, acting as a watermark.
                    val radius = 12.dp.toPx()
                    val halfHeight = radius * 0.8660254f
                    val columnStep = radius * 1.7320508f
                    val rowStep = radius * 1.5f
                    val lineColor = BeeKeepAccent.copy(alpha = 0.055f)
                    var row = -1
                    while (row * rowStep < h + radius) {
                        val cy = radius + row * rowStep
                        val offset = if (row % 2 == 0) 0f else columnStep / 2f
                        var col = 5
                        while (col * columnStep + offset < w + radius) {
                            val cx = col * columnStep + offset
                            val hex = Path().apply {
                                moveTo(cx + radius, cy)
                                lineTo(cx + radius / 2f, cy + halfHeight)
                                lineTo(cx - radius / 2f, cy + halfHeight)
                                lineTo(cx - radius, cy)
                                lineTo(cx - radius / 2f, cy - halfHeight)
                                lineTo(cx + radius / 2f, cy - halfHeight)
                                close()
                            }
                            drawPath(hex, lineColor, style = Stroke(width = 1.dp.toPx()))
                            col++
                        }
                        row++
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 10.dp)
                        .padding(end = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "BeeKeep",
                            style = MaterialTheme.typography.headlineLarge,
                            color = BeeKeepAccent,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = "APIARY DASHBOARD",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF524B45),
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.25.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.beekeep_header_logo),
                        contentDescription = "BeeKeep apiary health logo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(width = 88.dp, height = 98.dp)
                    )
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard("Hives", hives.size.toString(), Modifier.weight(1f))
                MetricCard("Apiaries", apiaries.size.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 30.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                HoneycombScanButton(scanning = scanning, onClick = onScan)
            }
        }
    }
}

