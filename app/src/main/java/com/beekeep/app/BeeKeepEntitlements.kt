package com.beekeep.app

import android.content.Context
import com.beekeep.app.BuildConfig

enum class BeeKeepPlan(val displayName: String) {
    FREE("Free"),
    PRO("Pro"),
    ULTIMATE("Ultimate")
}

/**
 * Entitlements used by the app's hive-creation gates.
 *
 * Debug builds can simulate a purchase locally for testing. Release builds deliberately
 * ignore this debug preference until verified Google Play Billing is integrated.
 */
object BeeKeepEntitlements {
    const val FREE_HIVE_LIMIT = 150
    private const val PREFS_NAME = "beekeep_prefs"
    private const val DEBUG_PLAN_KEY = "debug_purchase_plan"

    fun currentPlan(context: Context): BeeKeepPlan {
        if (!BuildConfig.DEBUG) return BeeKeepPlan.FREE
        val saved = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(DEBUG_PLAN_KEY, BeeKeepPlan.FREE.name)
        return runCatching { BeeKeepPlan.valueOf(saved ?: BeeKeepPlan.FREE.name) }
            .getOrDefault(BeeKeepPlan.FREE)
    }

    fun setDebugPlan(context: Context, plan: BeeKeepPlan) {
        check(BuildConfig.DEBUG) { "Purchase simulation is only available in debug builds." }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(DEBUG_PLAN_KEY, plan.name)
            .apply()
    }

    fun hasUnlimitedHives(context: Context): Boolean =
        currentPlan(context) != BeeKeepPlan.FREE

    fun hiveLimit(context: Context): Int =
        if (hasUnlimitedHives(context)) Int.MAX_VALUE else FREE_HIVE_LIMIT

    fun freeLimitMessage(): String =
        "The Free plan supports up to $FREE_HIVE_LIMIT hives. Upgrade to BeeKeep Pro or Ultimate to add more."
}
