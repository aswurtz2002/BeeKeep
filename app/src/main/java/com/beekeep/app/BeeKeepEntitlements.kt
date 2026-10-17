package com.beekeep.app

import android.content.Context
import com.beekeep.app.BuildConfig
import java.util.Locale

enum class BeeKeepPlan(
    val displayName: String,
    internal val subscriptionDurationMillis: Long = 0L
) {
    FREE("Free"),
    PRO("Pro Monthly", 30L * 24L * 60L * 60L * 1000L),
    PRO_YEARLY("Pro Yearly", 365L * 24L * 60L * 60L * 1000L),
    ULTIMATE("Ultimate");

    val isProSubscription: Boolean
        get() = subscriptionDurationMillis > 0L
}

/** Pricing labels are CAD; real charges remain disabled until Google Play Billing is integrated. */
internal object BeeKeepPricing {
    const val PRO_MONTHLY_CAD = 2.99
    const val PRO_YEARLY_CAD = 9.99
    const val ULTIMATE_LIFETIME_CAD = 89.99

    val yearlySavingsCad: Double
        get() = PRO_MONTHLY_CAD * 12 - PRO_YEARLY_CAD

    val yearlyDiscountPercent: Int
        get() = ((yearlySavingsCad / (PRO_MONTHLY_CAD * 12)) * 100).toInt()

    fun formatCad(amount: Double): String = String.format(Locale.CANADA, "$%.2f", amount)
}

/**
 * Entitlements used by the app's hive-creation gates.
 *
 * Debug builds simulate monthly and yearly Pro plans locally so expiry and locked-hive behavior
 * can be tested without a billing connection. Release builds deliberately ignore the
 * debug preference until verified Google Play Billing is integrated.
 */
object BeeKeepEntitlements {
    const val FREE_HIVE_LIMIT = 150
    private const val PREFS_NAME = "beekeep_prefs"
    private const val DEBUG_PLAN_KEY = "debug_purchase_plan"
    private const val DEBUG_PRO_EXPIRY_KEY = "debug_pro_expires_at"
    private const val PRO_CREATED_HIVE_IDS_KEY = "debug_pro_created_hive_ids"

    fun currentPlan(context: Context): BeeKeepPlan {
        if (!BuildConfig.DEBUG) return BeeKeepPlan.FREE
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(DEBUG_PLAN_KEY, BeeKeepPlan.FREE.name)
        val plan = runCatching { BeeKeepPlan.valueOf(saved ?: BeeKeepPlan.FREE.name) }
            .getOrDefault(BeeKeepPlan.FREE)

        if (plan.isProSubscription) {
            val now = System.currentTimeMillis()
            val expiresAt = prefs.getLong(DEBUG_PRO_EXPIRY_KEY, 0L)
            when {
                // Existing debug installations may already have Pro selected from the
                // earlier simulator, which had no expiry. Give that selection a full
                // simulated subscription term instead of expiring it immediately after updating.
                expiresAt <= 0L -> prefs.edit()
                    .putLong(DEBUG_PRO_EXPIRY_KEY, now + plan.subscriptionDurationMillis)
                    .apply()
                isProPlanExpired(expiresAt, now) -> {
                    prefs.edit()
                        .putString(DEBUG_PLAN_KEY, BeeKeepPlan.FREE.name)
                        .remove(DEBUG_PRO_EXPIRY_KEY)
                        .apply()
                    return BeeKeepPlan.FREE
                }
            }
        }
        return plan
    }

    fun debugProExpiresAt(context: Context): Long? {
        if (!BuildConfig.DEBUG || !currentPlan(context).isProSubscription) return null
        val expiresAt = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(DEBUG_PRO_EXPIRY_KEY, 0L)
        return expiresAt.takeIf { it > 0L }
    }

    fun setDebugPlan(context: Context, plan: BeeKeepPlan) {
        check(BuildConfig.DEBUG) { "Purchase simulation is only available in debug builds." }
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit().putString(DEBUG_PLAN_KEY, plan.name)
        when (plan) {
            BeeKeepPlan.PRO, BeeKeepPlan.PRO_YEARLY -> editor.putLong(
                DEBUG_PRO_EXPIRY_KEY,
                System.currentTimeMillis() + plan.subscriptionDurationMillis
            )
            // Ultimate is lifetime; Free simulates either a Pro runout or a test reset.
            BeeKeepPlan.FREE, BeeKeepPlan.ULTIMATE -> editor.remove(DEBUG_PRO_EXPIRY_KEY)
        }
        editor.apply()
    }

    /**
     * Remembers only hive IDs created while a simulated Pro subscription was
     * active. Existing Free hives are never included in this set.
     */
    fun rememberProCreatedHives(context: Context, hiveIds: Collection<Long>) {
        if (!BuildConfig.DEBUG || hiveIds.isEmpty()) return
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(PRO_CREATED_HIVE_IDS_KEY, emptySet())
            .orEmpty()
            .toMutableSet()
        ids.addAll(hiveIds.filter { it > 0L }.map(Long::toString))
        prefs.edit().putStringSet(PRO_CREATED_HIVE_IDS_KEY, ids).apply()
    }

    fun proCreatedHiveIds(context: Context): Set<Long> {
        if (!BuildConfig.DEBUG) return emptySet()
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(PRO_CREATED_HIVE_IDS_KEY, emptySet())
            .orEmpty()
            .mapNotNull(String::toLongOrNull)
            .toSet()
    }

    /**
     * Locked hives are hidden from normal access only after the simulated Pro plan
     * expires/resets. Selecting Pro again unlocks them; Ultimate also unlocks them.
     */
    fun lockedHiveIds(context: Context): Set<Long> {
        if (!BuildConfig.DEBUG) return emptySet()
        val plan = currentPlan(context)
        if (plan != BeeKeepPlan.FREE) return emptySet()
        return proCreatedHiveIds(context)
    }

    internal fun countHivesSubjectToFreeLimit(hiveIds: Collection<Long>, lockedIds: Set<Long>): Int =
        hiveIds.count { it !in lockedIds }

    fun hasUnlimitedHives(context: Context): Boolean =
        currentPlan(context) != BeeKeepPlan.FREE

    fun hiveLimit(context: Context): Int =
        if (hasUnlimitedHives(context)) Int.MAX_VALUE else FREE_HIVE_LIMIT

    fun freeLimitMessage(): String =
        "The Free plan supports up to $FREE_HIVE_LIMIT hives. Upgrade to BeeKeep Pro or Ultimate to add more."

    internal fun isProPlanExpired(expiresAtMillis: Long, nowMillis: Long): Boolean =
        expiresAtMillis > 0L && nowMillis >= expiresAtMillis

    internal fun isHiveLockedForPlan(plan: BeeKeepPlan, createdUnderPro: Boolean): Boolean =
        plan == BeeKeepPlan.FREE && createdUnderPro
}
