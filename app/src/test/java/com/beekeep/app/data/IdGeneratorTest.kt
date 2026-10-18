package com.beekeep.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdGeneratorTest {
    @Test
    fun generatesUniquePositiveIds() {
        val ids = List(20_000) { IdGenerator.nextLong() }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it > 0L })
    }
}
