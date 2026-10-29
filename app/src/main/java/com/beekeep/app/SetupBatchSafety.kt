package com.beekeep.app

internal object SetupBatchSafety {
    const val CONFIRMATION_THRESHOLD = 50

    fun requiresConfirmation(totalHives: Int): Boolean =
        totalHives > CONFIRMATION_THRESHOLD
}
