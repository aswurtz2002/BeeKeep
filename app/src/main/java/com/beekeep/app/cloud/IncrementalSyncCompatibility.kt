package com.beekeep.app.cloud

/**
 * Detects only the known PostgREST "function not found" response for BeeKeep's incremental
 * sync RPCs. Network, permission, decoding, and query failures must remain visible as sync
 * failures rather than silently switching synchronization strategies.
 */
internal object IncrementalSyncCompatibility {
    private val incrementalRpcNames = listOf(
        "beekeep_personal_sync_head",
        "beekeep_personal_sync_changes",
        "beekeep_workspace_sync_head",
        "beekeep_workspace_sync_changes"
    )

    fun isMissingIncrementalSyncRpc(error: Throwable): Boolean {
        val messages = generateSequence(error) { it.cause }
            .flatMap { cause -> sequenceOf(cause.message.orEmpty(), cause.toString()) }
            .joinToString("\n")
            .lowercase()

        val referencesIncrementalRpc = incrementalRpcNames.any { rpcName -> messages.contains(rpcName) }
        if (!referencesIncrementalRpc) return false

        return "pgrst202" in messages ||
            "could not find the function" in messages ||
            ("schema cache" in messages && "function" in messages) ||
            ("function" in messages && "does not exist" in messages)
    }
}
