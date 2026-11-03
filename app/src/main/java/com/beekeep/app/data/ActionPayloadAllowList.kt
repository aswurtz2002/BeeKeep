package com.beekeep.app.data

/**
 * The only domain fields an apiary-wide action may copy.
 * Identity, timestamps, batch metadata and workspace authorization are generated separately.
 */
internal object ActionPayloadAllowList {
    private val fieldsByType = mapOf(
        "feed" to setOf("feed_type", "ratio", "amount", "unit", "notes"),
        "treat" to setOf("treatment_type", "product", "inserted_at", "removal_at", "withdrawal_until", "notes"),
        "harvest" to setOf("supers_pulled", "wet_honey_weight", "dry_honey_weight", "weight_unit", "wax_weight", "propolis_weight", "notes")
    )

    fun fieldsFor(actionType: String): Set<String> =
        fieldsByType[actionType.trim().lowercase()].orEmpty()

    fun accepts(actionType: String, suppliedFields: Set<String>): Boolean {
        val allowed = fieldsFor(actionType)
        return allowed.isNotEmpty() && suppliedFields.isNotEmpty() && suppliedFields.all { it in allowed }
    }
}
