package com.beekeep.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues

@Composable
internal fun PurchaseScreen(
    padding: PaddingValues,
    currentPlan: BeeKeepPlan,
    onBack: () -> Unit,
    onChoosePlan: (BeeKeepPlan) -> Unit
) {
    BackHandler { onBack() }
    val debugBuild = BuildConfig.DEBUG

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Upgrade BeeKeep", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Clear limits. More room to grow.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }

        PlanCard(
            title = "BeeKeep Pro Monthly",
            price = BeeKeepPricing.formatCad(BeeKeepPricing.PRO_MONTHLY_CAD),
            billing = "CAD / month",
            description = "More capacity for a growing beekeeping operation.",
            features = listOf("Up to 1,000 hives", "Up to 100 apiaries"),
            current = currentPlan == BeeKeepPlan.PRO,
            highlighted = false,
            actionLabel = when {
                currentPlan == BeeKeepPlan.PRO -> "PRO MONTHLY ACTIVE"
                debugBuild -> "SIMULATE PRO MONTHLY"
                else -> "PURCHASES COMING SOON"
            },
            actionEnabled = debugBuild && currentPlan != BeeKeepPlan.PRO,
            onAction = { onChoosePlan(BeeKeepPlan.PRO) }
        )

        PlanCard(
            title = "BeeKeep Pro Yearly",
            price = BeeKeepPricing.formatCad(BeeKeepPricing.PRO_YEARLY_CAD),
            billing = "CAD / year",
            description = "Billed yearly. Save ${BeeKeepPricing.proYearlyDiscountPercent}% compared with 12 monthly payments.",
            features = listOf("Up to 1,000 hives", "Up to 100 apiaries"),
            current = currentPlan == BeeKeepPlan.PRO_YEARLY,
            highlighted = false,
            badgeText = "${BeeKeepPricing.proYearlyDiscountPercent}% OFF",
            actionLabel = when {
                currentPlan == BeeKeepPlan.PRO_YEARLY -> "PRO YEARLY ACTIVE"
                debugBuild -> "SIMULATE PRO YEARLY"
                else -> "PURCHASES COMING SOON"
            },
            actionEnabled = debugBuild && currentPlan != BeeKeepPlan.PRO_YEARLY,
            onAction = { onChoosePlan(BeeKeepPlan.PRO_YEARLY) }
        )

        PlanCard(
            title = "BeeKeep Ultimate Monthly",
            price = BeeKeepPricing.formatCad(BeeKeepPricing.ULTIMATE_MONTHLY_CAD),
            billing = "CAD / month",
            description = "Built for large operations that need shared workplace access and cloud sync.",
            features = listOf(
                "Up to 10,000 hives",
                "Up to 1,000 apiaries",
                "Shared workplace — only the owner needs Ultimate; invited workers do not need their own subscription",
                "Cloud sync"
            ),
            current = currentPlan == BeeKeepPlan.ULTIMATE,
            highlighted = false,
            actionLabel = when {
                currentPlan == BeeKeepPlan.ULTIMATE -> "ULTIMATE MONTHLY ACTIVE"
                debugBuild -> "SIMULATE ULTIMATE MONTHLY"
                else -> "PURCHASES COMING SOON"
            },
            actionEnabled = debugBuild && currentPlan != BeeKeepPlan.ULTIMATE,
            onAction = { onChoosePlan(BeeKeepPlan.ULTIMATE) }
        )

        PlanCard(
            title = "BeeKeep Ultimate Yearly",
            price = BeeKeepPricing.formatCad(BeeKeepPricing.ULTIMATE_YEARLY_CAD),
            billing = "CAD / year",
            description = "Billed yearly. Save ${BeeKeepPricing.ultimateYearlyDiscountPercent}% compared with 12 monthly payments.",
            features = listOf(
                "Up to 10,000 hives",
                "Up to 1,000 apiaries",
                "Shared workplace — only the owner needs Ultimate; invited workers do not need their own subscription",
                "Cloud sync"
            ),
            current = currentPlan == BeeKeepPlan.ULTIMATE_YEARLY,
            highlighted = true,
            badgeText = "${BeeKeepPricing.ultimateYearlyDiscountPercent}% OFF",
            actionLabel = when {
                currentPlan == BeeKeepPlan.ULTIMATE_YEARLY -> "ULTIMATE YEARLY ACTIVE"
                debugBuild -> "SIMULATE ULTIMATE YEARLY"
                else -> "PURCHASES COMING SOON"
            },
            actionEnabled = debugBuild && currentPlan != BeeKeepPlan.ULTIMATE_YEARLY,
            onAction = { onChoosePlan(BeeKeepPlan.ULTIMATE_YEARLY) }
        )

        if (currentPlan != BeeKeepPlan.FREE) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(25.dp))
                    Spacer(Modifier.size(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Current plan: BeeKeep ${currentPlan.displayName}", fontWeight = FontWeight.Bold)
                        Text("Your plan's hive and apiary limits are applied on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(20.dp))
                Spacer(Modifier.size(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (debugBuild) "Debug purchase testing" else "Purchases not active yet", fontWeight = FontWeight.Bold)
                    Text(
                        if (debugBuild) {
                            "These buttons simulate plan access on this device only. No payment is charged and this is not a real Google Play purchase. Real billing will be connected separately."
                        } else {
                            "Real purchases are not enabled yet. This screen will not charge you or unlock a plan in a release build until Google Play Billing is integrated."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (debugBuild && currentPlan != BeeKeepPlan.FREE) {
            OutlinedButton(
                onClick = { onChoosePlan(BeeKeepPlan.FREE) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(when (currentPlan) {
                    BeeKeepPlan.PRO -> "SIMULATE PRO MONTHLY RUNOUT"
                    BeeKeepPlan.PRO_YEARLY -> "SIMULATE PRO YEARLY RUNOUT"
                    BeeKeepPlan.ULTIMATE -> "SIMULATE ULTIMATE MONTHLY RUNOUT"
                    BeeKeepPlan.ULTIMATE_YEARLY -> "SIMULATE ULTIMATE YEARLY RUNOUT"
                    BeeKeepPlan.FREE -> "RESET TEST PLAN TO FREE"
                })
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PlanCard(
    title: String,
    price: String,
    billing: String,
    description: String,
    features: List<String>,
    current: Boolean,
    highlighted: Boolean,
    badgeText: String? = null,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    val borderColor = if (highlighted) Color(0xFFD97706) else MaterialTheme.colorScheme.outlineVariant
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(if (highlighted) 2.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (highlighted) 3.dp else 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2
                )
                if (badgeText != null) {
                    Surface(color = Color(0xFFDCFCE7), contentColor = Color(0xFF166534), shape = RoundedCornerShape(50)) {
                        Text(badgeText, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(price, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = Color(0xFFD97706), maxLines = 1, softWrap = false)
                Text(billing, modifier = Modifier.padding(bottom = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
            }
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            features.forEach { feature ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.padding(top = 1.dp).size(19.dp))
                    Text(
                        feature,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = actionEnabled,
                shape = RoundedCornerShape(15.dp)
            ) { Text(actionLabel, fontWeight = FontWeight.ExtraBold) }
            if (current) {
                Text("This is your active test plan.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
