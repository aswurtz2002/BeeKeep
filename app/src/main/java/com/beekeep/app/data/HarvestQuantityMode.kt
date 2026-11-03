package com.beekeep.app.data

enum class HarvestQuantityMode(val storageValue: String) {
    PER_HIVE("per_hive"),
    APIARY_TOTAL("apiary_total");

    companion object {
        /** Unknown or missing settings preserve the existing per-hive interpretation. */
        fun fromStorage(value: String?): HarvestQuantityMode =
            entries.firstOrNull { it.storageValue == value?.trim()?.lowercase() } ?: PER_HIVE
    }
}
