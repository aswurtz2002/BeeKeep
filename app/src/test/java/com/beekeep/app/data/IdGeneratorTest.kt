package com.beekeep.app.data

import org.junit.Assert.assertTrue
import org.junit.Test

class IdGeneratorTest {
    @Test
    fun generatesUniqueIncreasingIds() {
        val ids = List(2_000) { IdGenerator.nextLong() }
        assertTrue(ids.distinct().size == ids.size)
        assertTrue(ids.zipWithNext().all { (a, b) -> b > a })
    }
}
