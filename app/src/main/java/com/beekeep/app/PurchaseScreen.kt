package com.beekeep.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.sp
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
    val honey = Color(0xFFD97706)
    val cream = Color(0xFFFFFBEB)

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
                Text("More room for every colony", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }

        Surface(
            color = cream,
            contentColor = Color(0xFF78350F),
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 1.dp
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("BUILT FOR GROWING APIARIES", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.1.sp)
                Text("Keep every hive in one place.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("Both paid plans remove the 150-hive Free limit. Apiaries remain unlimited on every plan.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        PlanCard(
            title = "BeeKeep Pro",
            price = "$3.99",
            billing = "CAD / month",
            description = "Unlimited hives with a monthly subscription.",
            current = currentPlan == BeeKeepPlan.PRO,
            highlighted = false,
            actionLabel = when {
                currentPlan == BeeKeepPlan.PRO -> "PRO ACTIVE"
                debugBuild -> "SIMULATE PRO PURCHASE"
                else -> "PURCHASES COMING SOON"
            },
            actionEnabled = debugBuild && currentPlan != BeeKeepPlan.PRO,
            onAction = { onChoosePlan(BeeKeepPlan.PRO) }
        )

        PlanCard(
            title = "BeeKeep Ultimate",
            price = "$29.99",
            billing = "CAD / one-time purchase",
            description = "Unlimited hives for one payment. No recurring subscription.",
            current = currentPlan == BeeKeepPlan.ULTIMATE,
            highlighted = true,
            actionLabel = when {
                currentPlan == BeeKeepPlan.ULTIMATE -> "ULTIMATE ACTIVE"
                debugBuild -> "SIMULATE ULTIMATE PURCHASE"
                else -> "PURCHASES COMING SOON"
            },
            actionEnabled = debugBuild && currentPlan != BeeKeepPlan.ULTIMATE,
            onAction = { onChoosePlan(BeeKeepPlan.ULTIMATE) }
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
                        Text("Unlimited hive creation is enabled on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
            ) { Text("RESET TEST PLAN TO FREE") }
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
    current: Boolean,
    highlighted: Boolean,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    val borderColor = if (highlighted) Color(0xFFD97706) else MaterialTheme.colorScheme.outlineVariant
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(if (highlighted) 2.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (highlighted) 3.dp else 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                if (highlighted) {
                    Surface(color = Color(0xFFFFEDD5), contentColor = Color(0xFF9A3412), shape = RoundedCornerShape(50)) {
                        Text("LIFETIME", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(price, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = Color(0xFFD97706))
                Text(billing, modifier = Modifier.padding(bottom = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(19.dp))
                Text("Unlimited hives", fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(19.dp))
                Text("Unlimited apiaries", fontWeight = FontWeight.SemiBold)
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
