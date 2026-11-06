package com.beekeep.app

import android.content.Context
import java.util.Locale
import kotlin.math.roundToInt

enum class BeeKeepPlan(
    val displayName: String,
    internal val subscriptionDurationMillis: Long = 0L
) {
    HOBBYIST("Hobbyist"),
    COMMERCIAL("Commercial", 30L * 24L * 60L * 60L * 1000L);

    val isPaidSubscription: Boolean get() = this == COMMERCIAL

    @Deprecated("Use isPaidSubscription")
    val isProSubscription: Boolean get() = isPaidSubscription

    companion object {
        /**
         * Missing, unknown, expired, Free and legacy Pro values resolve to Hobbyist.
         * Legacy Ultimate values resolve to Commercial only after trusted server verification.
         */
        fun fromStoredValue(value: String?): BeeKeepPlan {
            val normalized = value.orEmpty().trim().lowercase(Locale.ROOT)
                .filter(Char::isLetterOrDigit)
            return when (normalized) {
                "commercial", "commercialmonthly", "commercialyearly",
                "ultimate", "ultimatemonthly", "ultimateyearly" -> COMMERCIAL
                "hobbyist", "free", "pro", "promonthly", "proyearly", "" -> HOBBYIST
                else -> HOBBYIST
            }
        }

        // Compatibility aliases are not enum entries and are not active plans.
        @Deprecated("Use HOBBYIST") val FREE: BeeKeepPlan get() = HOBBYIST
        @Deprecated("Legacy Pro resolves to Hobbyist") val PRO: BeeKeepPlan get() = HOBBYIST
        @Deprecated("Legacy Pro resolves to Hobbyist") val PRO_YEARLY: BeeKeepPlan get() = HOBBYIST
        @Deprecated("Use COMMERCIAL") val ULTIMATE: BeeKeepPlan get() = COMMERCIAL
        @Deprecated("Use COMMERCIAL") val ULTIMATE_YEARLY: BeeKeepPlan get() = COMMERCIAL
    }
}

/** Quota limits for the currently selected personal or team dataset. */
internal data class BeeKeepQuotaLimits(
    val plan: BeeKeepPlan,
    val hiveLimit: Int,
    val apiaryLimit: Int
) {
    companion object {
        fun forPlan(plan: BeeKeepPlan, customHiveLimit: Int? = null): BeeKeepQuotaLimits =
            BeeKeepQuotaLimits(
                plan,
                BeeKeepEntitlements.effectiveHiveLimit(plan, customHiveLimit),
                BeeKeepEntitlements.apiaryLimit(plan)
            )

        fun forWorkspaceOwner(
            isActiveCommercial: Boolean,
            effectiveHiveLimit: Int,
            effectiveApiaryLimit: Int
        ): BeeKeepQuotaLimits {
            if (!isActiveCommercial) return forPlan(BeeKeepPlan.HOBBYIST)
            val safeHiveLimit = effectiveHiveLimit.takeIf {
                it in BeeKeepEntitlements.COMMERCIAL_HIVE_LIMIT..BeeKeepEntitlements.MAX_CUSTOM_HIVE_LIMIT
            } ?: BeeKeepEntitlements.COMMERCIAL_HIVE_LIMIT
            val safeApiaryLimit = effectiveApiaryLimit.takeIf {
                it >= BeeKeepEntitlements.COMMERCIAL_APIARY_LIMIT
            } ?: BeeKeepEntitlements.COMMERCIAL_APIARY_LIMIT
            return BeeKeepQuotaLimits(BeeKeepPlan.COMMERCIAL, safeHiveLimit, safeApiaryLimit)
        }
    }
}

/**
 * Determines whether the local dataset has a verified, writable subscription context.
 * The server remains the final quota authority; this prevents stale local caps or role
 * state from making the UI appear writable during a dataset switch.
 */
internal object BeeKeepQuotaAccessPolicy {
    fun canWrite(
        isPersonalDataset: Boolean,
        quotaContextApplied: Boolean,
        ownerCommercialActive: Boolean?,
        memberRole: String?,
        workspaceReadOnly: Boolean
    ): Boolean {
        if (!quotaContextApplied) return false
        if (isPersonalDataset) return true
        return ownerCommercialActive == true &&
            memberRole in setOf("owner", "admin", "member") &&
            !workspaceReadOnly
    }
}

/** Pricing labels are CAD; real charges remain disabled until Google Play Billing is integrated. */
internal object BeeKeepPricing {
    const val COMMERCIAL_MONTHLY_CAD = 4.99
    const val COMMERCIAL_YEARLY_CAD = 29.99

    val commercialYearlySavingsCad: Double
        get() = COMMERCIAL_MONTHLY_CAD * 12 - COMMERCIAL_YEARLY_CAD

    val commercialYearlyDiscountPercent: Int
        get() = (commercialYearlySavingsCad / (COMMERCIAL_MONTHLY_CAD * 12) * 100).roundToInt()

    fun commercialYearlyDiscountDescription(): String =
        "Billed yearly. Save ${commercialYearlyDiscountPercent}% compared with monthly billing."

    @Deprecated("Use COMMERCIAL_MONTHLY_CAD") const val ULTIMATE_MONTHLY_CAD = COMMERCIAL_MONTHLY_CAD
    @Deprecated("Use COMMERCIAL_YEARLY_CAD") const val ULTIMATE_YEARLY_CAD = COMMERCIAL_YEARLY_CAD
    @Deprecated("Use commercialYearlySavingsCad") val ultimateYearlySavingsCad: Double
        get() = commercialYearlySavingsCad
    @Deprecated("Use commercialYearlyDiscountPercent") val ultimateYearlyDiscountPercent: Int
        get() = commercialYearlyDiscountPercent

    fun formatCad(amount: Double): String = String.format(Locale.CANADA, "$%.2f", amount)
}

object BeeKeepEntitlements {
    const val HOBBYIST_HIVE_LIMIT = 15
    const val HOBBYIST_APIARY_LIMIT = 3
    const val COMMERCIAL_HIVE_LIMIT = 10_000
    const val COMMERCIAL_APIARY_LIMIT = 1_000

    @Deprecated("Use HOBBYIST_HIVE_LIMIT") const val FREE_HIVE_LIMIT = HOBBYIST_HIVE_LIMIT
    @Deprecated("Use COMMERCIAL_HIVE_LIMIT") const val PRO_HIVE_LIMIT = COMMERCIAL_HIVE_LIMIT
    @Deprecated("Use COMMERCIAL_APIARY_LIMIT") const val PRO_APIARY_LIMIT = COMMERCIAL_APIARY_LIMIT
    @Deprecated("Use COMMERCIAL_HIVE_LIMIT") const val ULTIMATE_HIVE_LIMIT = COMMERCIAL_HIVE_LIMIT
    @Deprecated("Use COMMERCIAL_APIARY_LIMIT") const val ULTIMATE_APIARY_LIMIT = COMMERCIAL_APIARY_LIMIT

    private const val PREFS_NAME = "beekeep_prefs"
    private const val DEBUG_PLAN_KEY = "debug_purchase_plan"
    private const val DEBUG_PRO_EXPIRY_KEY = "debug_pro_expires_at"
    private const val PRO_CREATED_HIVE_IDS_KEY = "debug_pro_created_hive_ids"
    private const val CLOUD_HIVE_OVERRIDE_PREFIX = "cloud_hive_limit_override_for_"
    private const val CLOUD_PLAN_PREFIX = "cloud_verified_plan_for_"
    const val MAX_CUSTOM_HIVE_LIMIT = 1_000_000

    @Volatile private var activeCloudUserId: String? = null
    @Volatile private var activeCloudHiveLimitOverride: Int? = null
    @Volatile private var activeCloudPlanUserId: String? = null
    @Volatile private var activeCloudPlan: BeeKeepPlan? = null

    fun activateCloudHiveLimitOverride(context: Context, userId: String?) {
        val cleanUserId = userId?.trim()?.takeIf { it.isNotEmpty() }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (cleanUserId == null) {
            activeCloudUserId = null
            activeCloudHiveLimitOverride = null
            activeCloudPlanUserId = null
            activeCloudPlan = null
            return
        }
        activeCloudUserId = cleanUserId
        activeCloudHiveLimitOverride = prefs.getInt(CLOUD_HIVE_OVERRIDE_PREFIX + cleanUserId, -1)
            .takeIf { it in COMMERCIAL_HIVE_LIMIT..MAX_CUSTOM_HIVE_LIMIT }
        activeCloudPlanUserId = cleanUserId
        activeCloudPlan = prefs.getString(CLOUD_PLAN_PREFIX + cleanUserId, null)
            ?.let { BeeKeepPlan.fromStoredValue(it) }
    }

    /** Persists a plan returned by the signed-in account's trusted entitlement RPC. */
    fun updateCloudVerifiedPlan(context: Context, userId: String, plan: BeeKeepPlan) {
        val cleanUserId = userId.trim()
        if (cleanUserId.isEmpty()) return
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(CLOUD_PLAN_PREFIX + cleanUserId, plan.name)
            .apply()
        if (activeCloudPlanUserId == cleanUserId) activeCloudPlan = plan
    }

    fun updateCloudHiveLimitOverride(context: Context, userId: String, limit: Int?) {
        val cleanUserId = userId.trim()
        if (cleanUserId.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val validLimit = limit?.takeIf { it in COMMERCIAL_HIVE_LIMIT..MAX_CUSTOM_HIVE_LIMIT }
        val editor = prefs.edit()
        if (validLimit == null) editor.remove(CLOUD_HIVE_OVERRIDE_PREFIX + cleanUserId)
        else editor.putInt(CLOUD_HIVE_OVERRIDE_PREFIX + cleanUserId, validLimit)
        editor.apply()
        if (activeCloudUserId == cleanUserId) activeCloudHiveLimitOverride = validLimit
    }

    fun cachedCloudHiveLimitOverride(context: Context, userId: String): Int? {
        val cleanUserId = userId.trim()
        if (cleanUserId.isEmpty()) return null
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(CLOUD_HIVE_OVERRIDE_PREFIX + cleanUserId, -1)
            .takeIf { it in COMMERCIAL_HIVE_LIMIT..MAX_CUSTOM_HIVE_LIMIT }
    }

    fun currentPlan(context: Context): BeeKeepPlan {
        if (!BuildConfig.DEBUG) return activeCloudPlan ?: BeeKeepPlan.HOBBYIST
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(DEBUG_PLAN_KEY, BeeKeepPlan.HOBBYIST.name)
        val plan = BeeKeepPlan.fromStoredValue(saved)
        if (plan == BeeKeepPlan.COMMERCIAL) {
            val now = System.currentTimeMillis()
            val expiresAt = prefs.getLong(DEBUG_PRO_EXPIRY_KEY, 0L)
            val fallbackTerm = if (saved.orEmpty().contains("year", ignoreCase = true)) {
                365L * 24L * 60L * 60L * 1000L
            } else plan.subscriptionDurationMillis
            when {
                expiresAt <= 0L -> prefs.edit().putLong(DEBUG_PRO_EXPIRY_KEY, now + fallbackTerm).apply()
                isProPlanExpired(expiresAt, now) -> {
                    prefs.edit().putString(DEBUG_PLAN_KEY, BeeKeepPlan.HOBBYIST.name)
                        .remove(DEBUG_PRO_EXPIRY_KEY).apply()
                    return BeeKeepPlan.HOBBYIST
                }
            }
        } else {
            prefs.edit().remove(DEBUG_PRO_EXPIRY_KEY).apply()
        }
        return plan
    }

    fun debugCommercialExpiresAt(context: Context): Long? {
        if (!BuildConfig.DEBUG || currentPlan(context) != BeeKeepPlan.COMMERCIAL) return null
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(DEBUG_PRO_EXPIRY_KEY, 0L).takeIf { it > 0L }
    }

    @Deprecated("Use debugCommercialExpiresAt")
    fun debugProExpiresAt(context: Context): Long? = debugCommercialExpiresAt(context)

    fun setDebugPlan(context: Context, plan: BeeKeepPlan) {
        check(BuildConfig.DEBUG) { "Purchase simulation is only available in debug builds." }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit().putString(DEBUG_PLAN_KEY, plan.name)
        when (plan) {
            BeeKeepPlan.HOBBYIST -> editor.remove(DEBUG_PRO_EXPIRY_KEY)
            BeeKeepPlan.COMMERCIAL -> editor.putLong(
                DEBUG_PRO_EXPIRY_KEY,
                System.currentTimeMillis() + plan.subscriptionDurationMillis
            )
        }
        editor.apply()
    }

    // Historical APIs remain for older callers; paid-created records are never locked after downgrade.
    @Suppress("UNUSED_PARAMETER")
    fun rememberProCreatedHives(context: Context, hiveIds: Collection<Long>) = Unit
    @Suppress("UNUSED_PARAMETER")
    fun proCreatedHiveIds(context: Context): Set<Long> = emptySet()
    @Suppress("UNUSED_PARAMETER")
    fun lockedHiveIds(context: Context): Set<Long> = emptySet()

    /**
     * Returns IDs beyond the current plan allowance, preserving the supplied priority order.
     * Callers provide a stable order (active colonies first, then colony history). Existing
     * records are never deleted; this only controls which hives can be edited over the plan cap.
     */
    internal fun hiveIdsBeyondLimit(orderedHiveIds: Collection<Long>, hiveLimit: Int): Set<Long> {
        if (hiveLimit == Int.MAX_VALUE) return emptySet()
        return orderedHiveIds.distinct().drop(hiveLimit.coerceAtLeast(0)).toSet()
    }
    @Suppress("UNUSED_PARAMETER")
    internal fun countHivesSubjectToFreeLimit(hiveIds: Collection<Long>, lockedIds: Set<Long>): Int = hiveIds.size

    internal fun planFromServerEntitlement(
        planCode: String?,
        baseHiveLimit: Int,
        isActivePaidPlan: Boolean
    ): BeeKeepPlan {
        if (!isActivePaidPlan || baseHiveLimit < COMMERCIAL_HIVE_LIMIT) return BeeKeepPlan.HOBBYIST
        return when (planCode.orEmpty().lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)) {
            "commercial", "commercialmonthly", "commercialyearly",
            "ultimate", "ultimatemonthly", "ultimateyearly" -> BeeKeepPlan.COMMERCIAL
            else -> BeeKeepPlan.HOBBYIST
        }
    }

    /**
     * Selects the plan that gates app features.
     *
     * A server-verified Commercial entitlement (including an admin grant) always takes
     * precedence over the local debug purchase simulator. Debug simulation remains available
     * when the server has not granted Commercial; release builds never trust local simulation.
     */
    internal fun planForAppFeatures(
        isDebugBuild: Boolean,
        localPlan: BeeKeepPlan,
        serverVerifiedPlan: BeeKeepPlan?
    ): BeeKeepPlan = when {
        serverVerifiedPlan == BeeKeepPlan.COMMERCIAL -> BeeKeepPlan.COMMERCIAL
        isDebugBuild -> localPlan
        else -> serverVerifiedPlan ?: BeeKeepPlan.HOBBYIST
    }

    private fun standardHiveLimit(plan: BeeKeepPlan): Int = when (plan) {
        BeeKeepPlan.HOBBYIST -> HOBBYIST_HIVE_LIMIT
        BeeKeepPlan.COMMERCIAL -> COMMERCIAL_HIVE_LIMIT
    }

    internal fun effectiveHiveLimit(plan: BeeKeepPlan, customLimit: Int?): Int {
        val standardLimit = standardHiveLimit(plan)
        if (plan == BeeKeepPlan.HOBBYIST) return standardLimit
        val validCustomLimit = customLimit?.takeIf { it in standardLimit..MAX_CUSTOM_HIVE_LIMIT }
            ?: return standardLimit
        return maxOf(standardLimit, validCustomLimit)
    }

    fun hiveLimit(plan: BeeKeepPlan): Int = effectiveHiveLimit(plan, activeCloudHiveLimitOverride)
    fun hiveLimit(plan: BeeKeepPlan, customLimit: Int?): Int =
        effectiveHiveLimit(plan, customLimit ?: activeCloudHiveLimitOverride)
    fun hiveLimit(context: Context): Int = hiveLimit(currentPlan(context))

    fun apiaryLimit(plan: BeeKeepPlan): Int = when (plan) {
        BeeKeepPlan.HOBBYIST -> HOBBYIST_APIARY_LIMIT
        BeeKeepPlan.COMMERCIAL -> COMMERCIAL_APIARY_LIMIT
    }
    fun apiaryLimit(context: Context): Int = apiaryLimit(currentPlan(context))

    /** NFC scanning, tag assignment and fast assignment are Commercial-only features. */
    fun canUseNfc(plan: BeeKeepPlan): Boolean = plan == BeeKeepPlan.COMMERCIAL

    fun canUseNfc(context: Context): Boolean = canUseNfc(currentPlan(context))

    fun hiveLimitMessage(plan: BeeKeepPlan): String = hiveLimitMessage(plan, activeCloudHiveLimitOverride)

    internal fun hiveLimitMessage(plan: BeeKeepPlan, customLimit: Int?): String {
        val standardLimit = standardHiveLimit(plan)
        val effectiveLimit = effectiveHiveLimit(plan, customLimit)
        if (plan == BeeKeepPlan.COMMERCIAL && effectiveLimit > standardLimit) {
            val formattedLimit = String.format(Locale.CANADA, "%,d", effectiveLimit)
            return "Hive limit reached: your custom BeeKeep Commercial hive limit is $formattedLimit. Your existing records are safe; contact BeeKeep support if you need a higher limit."
        }
        return when (plan) {
            BeeKeepPlan.HOBBYIST ->
                "Hive limit reached: the Hobbyist plan has a 15 hive limit. Upgrade to BeeKeep Commercial for a 10,000 hive limit."
            BeeKeepPlan.COMMERCIAL ->
                "Hive limit reached: BeeKeep Commercial has a 10,000 hive limit. Your existing records are safe; contact BeeKeep support if you need a higher limit."
        }
    }

    fun hiveLimitMessage(context: Context): String = hiveLimitMessage(currentPlan(context))

    fun apiaryLimitMessage(plan: BeeKeepPlan): String = when (plan) {
        BeeKeepPlan.HOBBYIST ->
            "Apiary limit reached: the Hobbyist plan has a 3 apiary limit. Upgrade to BeeKeep Commercial for a 1,000 apiary limit."
        BeeKeepPlan.COMMERCIAL ->
            "Apiary limit reached: BeeKeep Commercial has a 1,000 apiary limit. Your existing records are safe; contact BeeKeep support if you need a higher limit."
    }
    fun apiaryLimitMessage(context: Context): String = apiaryLimitMessage(currentPlan(context))

    internal fun isHiveLimitError(message: String?): Boolean =
        message?.contains("Hive limit reached", ignoreCase = true) == true
    internal fun isApiaryLimitError(message: String?): Boolean =
        message?.contains("Apiary limit reached", ignoreCase = true) == true
    fun freeLimitMessage(): String = hiveLimitMessage(BeeKeepPlan.HOBBYIST)
    fun hasUnlimitedHives(context: Context): Boolean = hiveLimit(context) == Int.MAX_VALUE

    internal fun isProPlanExpired(expiresAtMillis: Long, nowMillis: Long): Boolean =
        expiresAtMillis > 0L && nowMillis >= expiresAtMillis

    @Suppress("UNUSED_PARAMETER")
    internal fun isHiveLockedForPlan(plan: BeeKeepPlan, createdUnderPro: Boolean): Boolean = false
}
