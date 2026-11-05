package com.beekeep.app.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * Runs a state-changing operation in the owning gateway scope rather than in the UI caller.
 *
 * A UI coroutine may be cancelled as soon as the operation publishes a state that replaces
 * its screen (for example, signing in replaces the login form with the startup gate). Awaiter
 * cancellation must not abandon the operation halfway through a persisted auth/session transition.
 */
internal class GatewayOwnedOperationRunner(
    private val ownerScope: CoroutineScope
) {
    suspend fun <T> run(block: suspend () -> T): T =
        ownerScope.async { block() }.await()
}
