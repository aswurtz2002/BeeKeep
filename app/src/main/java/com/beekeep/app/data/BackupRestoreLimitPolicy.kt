package com.beekeep.app.data

import java.util.Locale

internal data class BackupRestoreQuotaAssessment(
    val resultingHiveCount: Int,
    val newHiveCount: Int,
    val resultingApiaryCount: Int,
    val newApiaryCount: Int
)

/**
 * Calculates the unique logical records a merge will add. Re-importing records already present
 * (including case-only name differences) does not consume additional quota. Existing accounts
 * already above a new limit can still restore history for existing hives; they cannot add more.
 */
internal fun assessBackupRestoreQuotas(
    currentApiaryNames: Collection<String>,
    importedApiaryNames: Collection<String>,
    currentHiveKeys: Collection<Pair<String, String>>,
    importedHiveKeys: Collection<Pair<String, String>>,
    hiveLimit: Int,
    apiaryLimit: Int,
    planName: String
): BackupRestoreQuotaAssessment {
    require(hiveLimit >= 0) { "Hive limit cannot be negative." }
    require(apiaryLimit >= 0) { "Apiary limit cannot be negative." }

    fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
    fun normalizeHiveKey(key: Pair<String, String>): Pair<String, String> =
        normalize(key.first) to normalize(key.second)

    val currentApiaries = currentApiaryNames.map(::normalize).filter { it.isNotEmpty() }.toSet()
    val importedApiaries = importedApiaryNames.map(::normalize).filter { it.isNotEmpty() }.toSet()
    val resultingApiaries = currentApiaries + importedApiaries
    val newApiaryCount = resultingApiaries.size - currentApiaries.size

    val currentHives = currentHiveKeys.map(::normalizeHiveKey)
        .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toSet()
    val importedHives = importedHiveKeys.map(::normalizeHiveKey)
        .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toSet()
    val resultingHives = currentHives + importedHives
    val newHiveCount = resultingHives.size - currentHives.size

    if (newApiaryCount > 0 && resultingApiaries.size > apiaryLimit) {
        throw IllegalArgumentException(
            "This backup adds $newApiaryCount apiary record(s), which would bring your account to " +
                "${resultingApiaries.size}. The $planName plan allows $apiaryLimit apiaries. " +
                "No records were restored. Remove new apiaries from the backup or upgrade your plan."
        )
    }
    if (newHiveCount > 0 && resultingHives.size > hiveLimit) {
        throw IllegalArgumentException(
            "This backup adds $newHiveCount hive record(s), which would bring your account to " +
                "${resultingHives.size}. The $planName plan allows $hiveLimit hives. " +
                "No records were restored. Remove new hives from the backup or upgrade your plan."
        )
    }

    return BackupRestoreQuotaAssessment(
        resultingHiveCount = resultingHives.size,
        newHiveCount = newHiveCount,
        resultingApiaryCount = resultingApiaries.size,
        newApiaryCount = newApiaryCount
    )
}


/** Team backups use the workspace owner's entitlement, never an invited member's personal plan. */
internal fun canRestoreBackupToWorkspace(
    isPersonalWorkspace: Boolean,
    memberRole: String?
): Boolean = isPersonalWorkspace || memberRole == "owner"
