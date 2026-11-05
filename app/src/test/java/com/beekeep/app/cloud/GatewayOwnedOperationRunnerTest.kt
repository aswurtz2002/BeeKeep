package com.beekeep.app.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayOwnedOperationRunnerTest {
    @Test
    fun operationContinuesWhenUiAwaiterIsCancelled() = runBlocking {
        val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runner = GatewayOwnedOperationRunner(gatewayScope)
            val started = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Boolean>()

            val uiCaller = launch {
                runner.run {
                    started.complete(Unit)
                    delay(50)
                    finished.complete(true)
                }
            }

            started.await()
            uiCaller.cancelAndJoin()

            assertTrue(withTimeout(2_000) { finished.await() })
        } finally {
            gatewayScope.cancel()
        }
    }
}
