package com.beekeep.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LazySingletonTest {
    @Test
    fun reusesTheFirstCreatedInstance() {
        val singleton = LazySingleton<Any>()
        val factoryCalls = AtomicInteger(0)

        val first = singleton.getOrCreate {
            factoryCalls.incrementAndGet()
            Any()
        }
        val second = singleton.getOrCreate {
            factoryCalls.incrementAndGet()
            Any()
        }

        assertSame(first, second)
        assertEquals(1, factoryCalls.get())
    }

    @Test
    fun concurrentCallersShareOneInstance() {
        val singleton = LazySingleton<Any>()
        val callerCount = 12
        val factoryCalls = AtomicInteger(0)
        val startGate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(callerCount)

        try {
            val results = (1..callerCount).map {
                executor.submit(Callable {
                    check(startGate.await(5, TimeUnit.SECONDS)) {
                        "Test start gate timed out"
                    }
                    singleton.getOrCreate {
                        factoryCalls.incrementAndGet()
                        Thread.sleep(10)
                        Any()
                    }
                })
            }

            startGate.countDown()
            val instances = results.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, factoryCalls.get())
            instances.forEach { assertSame(instances.first(), it) }
        } finally {
            executor.shutdownNow()
        }
    }
}
