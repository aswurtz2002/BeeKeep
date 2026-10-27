package com.beekeep.app

import android.content.Context
import com.beekeep.app.BuildConfig
import java.util.Locale
import kotlin.math.roundToInt

enum class BeeKeepPlan(
    val displayName: String,
    internal val subscriptionDurationMillis: Long = 0L
) {
    FREE("Free"),
    PRO("Pro Monthly", 30L * 24L * 60L * 60L * 1000L),
    PRO_YEARLY("Pro Yearly", 365L * 24L * 60L * 60L * 1000L),
    ULTIMATE("Ultimate Monthly", 30L * 24L * 60L * 60L * 1000L),
    ULTIMATE_YEARLY("Ultimate Yearly", 365L * 24L * 60L * 60L * 1000L);

    /** True for paid plans with a recurring monthly or yearly term, including Ultimate. */
    val isProSubscription: Boolean
        get() = subscriptionDurationMillis > 0L
}

/** Pricing labels are CAD; real charges remain disabled until Google Play Billing is integrated. */
internal object BeeKeepPricing {
    const val PRO_MONTHLY_CAD = 2.99
    const val PRO_YEARLY_CAD = 24.99
    const val ULTIMATE_MONTHLY_CAD = 6.99
    const val ULTIMATE_YEARLY_CAD = 59.99

    val proYearlySavingsCad: Double
        get() = PRO_MONTHLY_CAD * 12 - PRO_YEARLY_CAD

    val proYearlyDiscountPercent: Int
        get() = (proYearlySavingsCad / (PRO_MONTHLY_CAD * 12) * 100).roundToInt()

    val ultimateYearlySavingsCad: Double
        get() = ULTIMATE_MONTHLY_CAD * 12 - ULTIMATE_YEARLY_CAD

    val ultimateYearlyDiscountPercent: Int
        get() = (ultimateYearlySavingsCad / (ULTIMATE_MONTHLY_CAD * 12) * 100).roundToInt()

    // Compatibility aliases for existing Pro-only pricing references.
    val yearlySavingsCad: Double
        get() = proYearlySavingsCad

    val yearlyDiscountPercent: Int
        get() = proYearlyDiscountPercent

    fun formatCad(amount: Double): String = String.format(Locale.CANADA, "$%.2f", amount)
}

/**
 * Plan caps apply to locally-created records. Debug builds simulate plan selection and expiry;
 * release builds remain Free until verified Google Play Billing is integrated.
 */
object BeeKeepEntitlements {
    const val FREE_HIVE_LIMIT = 150
    const val PRO_HIVE_LIMIT = 1_000
    const val PRO_APIARY_LIMIT = 100
    const val ULTIMATE_HIVE_LIMIT = 10_000
    const val ULTIMATE_APIARY_LIMIT = 1_000

    private const val PREFS_NAME = "beekeep_prefs"
    private const val DEBUG_PLAN_KEY = "debug_purchase_plan"
    private const val DEBUG_PRO_EXPIRY_KEY = "debug_pro_expires_at"
    // Keep the existing key to preserve locally simulated subscription history on upgrade.
    private const val PRO_CREATED_HIVE_IDS_KEY = "debug_pro_created_hive_ids"
    private const val CLOUD_HIVE_OVERRIDE_PREFIX = "cloud_hive_limit_override_for_"
    const val MAX_CUSTOM_HIVE_LIMIT = 1_000_000

    @Volatile
    private var activeCloudUserId: String? = null

    @Volatile
    private var activeCloudHiveLimitOverride: Int? = null

    /**
     * Selects the currently authenticated cloud account and restores only that account's
     * previously verified, server-returned quota. Overrides are never read from profile metadata.
     */
    fun activateCloudHiveLimitOverride(context: Context, userId: String?) {
        val cleanUserId = userId?.trim()?.takeIf { it.isNotEmpty() }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (cleanUserId == null) {
            activeCloudUserId = null
            activeCloudHiveLimitOverride = null
            return
        }

        activeCloudUserId = cleanUserId
        val cached = prefs.getInt(CLOUD_HIVE_OVERRIDE_PREFIX + cleanUserId, -1)
            .takeIf { it in PRO_HIVE_LIMIT..MAX_CUSTOM_HIVE_LIMIT }
        activeCloudHiveLimitOverride = cached
    }

    /** Persists a limit fetched from the authenticated account's trusted server RPC. */
    fun updateCloudHiveLimitOverride(context: Context, userId: String, limit: Int?) {
        val cleanUserId = userId.trim()
        if (cleanUserId.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Pro overrides start at the standard Pro quota. The server only returns a
        // custom value for a trusted, currently active paid plan.
        val validLimit = limit?.takeIf { it in PRO_HIVE_LIMIT..MAX_CUSTOM_HIVE_LIMIT }
        val editor = prefs.edit()
        if (validLimit == null) {
            editor.remove(CLOUD_HIVE_OVERRIDE_PREFIX + cleanUserId)
        } else {
            editor.putInt(CLOUD_HIVE_OVERRIDE_PREFIX + cleanUserId, validLimit)
        }
        editor.apply()
        if (activeCloudUserId == cleanUserId) activeCloudHiveLimitOverride = validLimit
    }

    fun cachedCloudHiveLimitOverride(context: Context, userId: String): Int? {
        val cleanUserId = userId.trim()
        if (cleanUserId.isEmpty()) return null
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(CLOUD_HIVE_OVERRIDE_PREFIX + cleanUserId, -1)
            .takeIf { it in PRO_HIVE_LIMIT..MAX_CUSTOM_HIVE_LIMIT }
    }


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
                // Existing simulator selections without an expiry get a full subscription term.
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
            BeeKeepPlan.PRO, BeeKeepPlan.PRO_YEARLY,
            BeeKeepPlan.ULTIMATE, BeeKeepPlan.ULTIMATE_YEARLY -> editor.putLong(
                DEBUG_PRO_EXPIRY_KEY,
                System.currentTimeMillis() + plan.subscriptionDurationMillis
            )
            BeeKeepPlan.FREE -> editor.remove(DEBUG_PRO_EXPIRY_KEY)
        }
        editor.apply()
    }

    /**
     * Remembers hive IDs created while any simulated paid subscription was active.
     * These records are locked if the subscription later expires or is reset to Free.
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

    /** All active paid plans unlock subscription-created hive records on this device. */
    fun lockedHiveIds(context: Context): Set<Long> {
        if (!BuildConfig.DEBUG) return emptySet()
        val plan = currentPlan(context)
        if (plan != BeeKeepPlan.FREE) return emptySet()
        return proCreatedHiveIds(context)
    }

    internal fun countHivesSubjectToFreeLimit(hiveIds: Collection<Long>, lockedIds: Set<Long>): Int =
        hiveIds.count { it !in lockedIds }

    private fun standardHiveLimit(plan: BeeKeepPlan): Int = when (plan) {
        BeeKeepPlan.FREE -> FREE_HIVE_LIMIT
        BeeKeepPlan.PRO, BeeKeepPlan.PRO_YEARLY -> PRO_HIVE_LIMIT
        BeeKeepPlan.ULTIMATE, BeeKeepPlan.ULTIMATE_YEARLY -> ULTIMATE_HIVE_LIMIT
    }

    internal fun effectiveHiveLimit(plan: BeeKeepPlan, customLimit: Int?): Int {
        val standardLimit = standardHiveLimit(plan)
        // Free can never gain capacity from a stale or incorrectly assigned override.
        if (plan == BeeKeepPlan.FREE) return standardLimit
        val validCustomLimit = customLimit?.takeIf {
            it in standardLimit..MAX_CUSTOM_HIVE_LIMIT
        } ?: return standardLimit
        return maxOf(standardLimit, validCustomLimit)
    }

    fun hiveLimit(plan: BeeKeepPlan): Int =
        effectiveHiveLimit(plan, activeCloudHiveLimitOverride)

    fun hiveLimit(plan: BeeKeepPlan, customLimit: Int?): Int =
        effectiveHiveLimit(plan, customLimit ?: activeCloudHiveLimitOverride)

    /** Uses only the override activated by the authenticated cloud session. */
    fun hiveLimit(context: Context): Int = hiveLimit(currentPlan(context))

    /** Free retains its existing uncapped-apiary behavior; paid plans have explicit caps. */
    fun apiaryLimit(plan: BeeKeepPlan): Int = when (plan) {
        BeeKeepPlan.FREE -> Int.MAX_VALUE
        BeeKeepPlan.PRO, BeeKeepPlan.PRO_YEARLY -> PRO_APIARY_LIMIT
        BeeKeepPlan.ULTIMATE, BeeKeepPlan.ULTIMATE_YEARLY -> ULTIMATE_APIARY_LIMIT
    }

    fun apiaryLimit(context: Context): Int = apiaryLimit(currentPlan(context))

    fun hiveLimitMessage(plan: BeeKeepPlan): String =
        hiveLimitMessage(plan, activeCloudHiveLimitOverride)

    internal fun hiveLimitMessage(plan: BeeKeepPlan, customLimit: Int?): String {
        val standardLimit = standardHiveLimit(plan)
        val effectiveLimit = effectiveHiveLimit(plan, customLimit)
        if (plan != BeeKeepPlan.FREE && effectiveLimit > standardLimit) {
            val formattedLimit = String.format(Locale.CANADA, "%,d", effectiveLimit)
            val tier = when (plan) {
                BeeKeepPlan.FREE -> "Free"
                BeeKeepPlan.PRO, BeeKeepPlan.PRO_YEARLY -> "Pro"
                BeeKeepPlan.ULTIMATE, BeeKeepPlan.ULTIMATE_YEARLY -> "Ultimate"
            }
            return "Hive limit reached: your custom BeeKeep $tier hive limit is $formattedLimit. Your existing records are safe; contact BeeKeep support if you need a higher limit."
        }
        return when (plan) {
            BeeKeepPlan.FREE ->
                "Hive limit reached: the Free plan has a 150 hive limit. Upgrade to BeeKeep Pro (1,000 hive limit) or Ultimate (10,000 hive limit) to add more."
            BeeKeepPlan.PRO, BeeKeepPlan.PRO_YEARLY ->
                "Hive limit reached: BeeKeep Pro has a 1,000 hive limit. Upgrade to BeeKeep Ultimate for a 10,000 hive limit."
            BeeKeepPlan.ULTIMATE, BeeKeepPlan.ULTIMATE_YEARLY ->
                "Hive limit reached: BeeKeep Ultimate has a 10,000 hive limit. Your existing records are safe; contact BeeKeep support if you need a higher limit."
        }
    }

    fun hiveLimitMessage(context: Context): String = hiveLimitMessage(currentPlan(context))

    fun apiaryLimitMessage(plan: BeeKeepPlan): String = when (plan) {
        BeeKeepPlan.FREE -> "The Free plan has no apiary limit."
        BeeKeepPlan.PRO, BeeKeepPlan.PRO_YEARLY ->
            "Apiary limit reached: BeeKeep Pro has a 100 apiary limit. Upgrade to Ultimate for a 1,000 apiary limit."
        BeeKeepPlan.ULTIMATE, BeeKeepPlan.ULTIMATE_YEARLY ->
            "Apiary limit reached: BeeKeep Ultimate has a 1,000 apiary limit. Your existing records are safe; contact BeeKeep support if you need a higher limit."
    }

    fun apiaryLimitMessage(context: Context): String = apiaryLimitMessage(currentPlan(context))

    /** Stable classifications let every plan's limit error reach the same upgrade flow. */
    internal fun isHiveLimitError(message: String?): Boolean =
        message?.contains("Hive limit reached", ignoreCase = true) == true

    internal fun isApiaryLimitError(message: String?): Boolean =
        message?.contains("Apiary limit reached", ignoreCase = true) == true

    /** Legacy accessor retained for callers that specifically need the Free-plan message. */
    fun freeLimitMessage(): String = hiveLimitMessage(BeeKeepPlan.FREE)

    fun hasUnlimitedHives(context: Context): Boolean = hiveLimit(context) == Int.MAX_VALUE

    internal fun isProPlanExpired(expiresAtMillis: Long, nowMillis: Long): Boolean =
        expiresAtMillis > 0L && nowMillis >= expiresAtMillis

    internal fun isHiveLockedForPlan(plan: BeeKeepPlan, createdUnderPro: Boolean): Boolean =
        plan == BeeKeepPlan.FREE && createdUnderPro
}
