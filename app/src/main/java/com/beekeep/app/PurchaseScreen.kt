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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues

private enum class CommercialBillingCycle { MONTHLY, YEARLY }

@Composable
internal fun PurchaseScreen(
    padding: PaddingValues,
    currentPlan: BeeKeepPlan,
    onBack: () -> Unit,
    onChoosePlan: (BeeKeepPlan) -> Unit
) {
    BackHandler { onBack() }
    val debugBuild = BuildConfig.DEBUG
    var selectedBillingCycle by rememberSaveable {
        mutableStateOf(CommercialBillingCycle.MONTHLY)
    }

    Column(
        Modifier.fillMaxSize()
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
                Text("BeeKeep plans", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Start free. Upgrade when your operation grows.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }

        PlanCard(
            title = "Hobbyist",
            price = "FREE",
            billing = "always",
            description = "The default plan for every BeeKeep account.",
            features = listOf("15 hive limit", "3 apiary limit", "Cloud sync"),
            current = false,
            highlighted = false,
            actionLabel = null,
            actionEnabled = false,
            statusLabel = "ALWAYS ACTIVE"
        )

        PlanCard(
            title = "Commercial",
            price = BeeKeepPricing.formatCad(
                when (selectedBillingCycle) {
                    CommercialBillingCycle.MONTHLY -> BeeKeepPricing.COMMERCIAL_MONTHLY_CAD
                    CommercialBillingCycle.YEARLY -> BeeKeepPricing.COMMERCIAL_YEARLY_CAD
                }
            ),
            billing = when (selectedBillingCycle) {
                CommercialBillingCycle.MONTHLY -> "CAD / month"
                CommercialBillingCycle.YEARLY -> "CAD / year"
            },
            description = when (selectedBillingCycle) {
                CommercialBillingCycle.MONTHLY -> "For larger operations. Billed monthly."
                CommercialBillingCycle.YEARLY ->
                    BeeKeepPricing.commercialYearlyDiscountDescription()
            },
            features = listOf(
                "10,000 hive limit",
                "1,000 apiary limit",
                "NFC scanning and tag assignment",
                "Team Workspace",
                "Cloud sync"
            ),
            current = currentPlan == BeeKeepPlan.COMMERCIAL,
            highlighted = true,
            badgeText = "COMMERCIAL",
            actionLabel = when {
                debugBuild && currentPlan == BeeKeepPlan.COMMERCIAL -> "SYNC COMMERCIAL TO CLOUD"
                debugBuild -> "SIMULATE COMMERCIAL"
                currentPlan == BeeKeepPlan.COMMERCIAL -> "COMMERCIAL ACTIVE"
                else -> "PURCHASES COMING SOON"
            },
            actionEnabled = debugBuild,
            billingCycle = selectedBillingCycle,
            onBillingCycleChange = { selectedBillingCycle = it },
            onAction = { onChoosePlan(BeeKeepPlan.COMMERCIAL) }
        )

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
                            "These buttons simulate plan access on this device and BeeKeep-Staging only. No payment is charged. Real billing will be connected separately."
                        } else {
                            "Real purchases are not enabled yet. This screen will not charge you or unlock Commercial in a release build until Google Play Billing is integrated."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (debugBuild && currentPlan == BeeKeepPlan.COMMERCIAL) {
            OutlinedButton(
                onClick = { onChoosePlan(BeeKeepPlan.HOBBYIST) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text("SIMULATE COMMERCIAL RUNOUT") }
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
    statusLabel: String? = null,
    actionLabel: String?,
    actionEnabled: Boolean,
    billingCycle: CommercialBillingCycle? = null,
    onBillingCycleChange: ((CommercialBillingCycle) -> Unit)? = null,
    onAction: (() -> Unit)? = null
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
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, maxLines = 2)
                if (badgeText != null) {
                    Surface(color = Color(0xFFDCFCE7), contentColor = Color(0xFF166534), shape = RoundedCornerShape(50)) {
                        Text(badgeText, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            if (billingCycle != null && onBillingCycleChange != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BillingCycleTab(
                        label = "Monthly",
                        selected = billingCycle == CommercialBillingCycle.MONTHLY,
                        modifier = Modifier.weight(1f),
                        onClick = { onBillingCycleChange(CommercialBillingCycle.MONTHLY) }
                    )
                    BillingCycleTab(
                        label = "Yearly",
                        selected = billingCycle == CommercialBillingCycle.YEARLY,
                        modifier = Modifier.weight(1f),
                        onClick = { onBillingCycleChange(CommercialBillingCycle.YEARLY) }
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(price, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = Color(0xFFD97706), maxLines = 1, softWrap = false)
                Text(billing, modifier = Modifier.padding(bottom = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
            }
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            features.forEach { feature ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.padding(top = 1.dp).size(19.dp))
                    Text(feature, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = actionEnabled, shape = RoundedCornerShape(15.dp)) {
                    Text(actionLabel, fontWeight = FontWeight.ExtraBold)
                }
            }
            if (statusLabel != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(18.dp))
                    Text(statusLabel, color = Color(0xFF15803D), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
                }
            }
            if (current) {
                Text("This is your active plan.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}


@Composable
private fun BillingCycleTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFD97706),
                contentColor = Color.White
            )
        ) {
            Text(label, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(13.dp)
        ) {
            Text(label, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
    }
}
