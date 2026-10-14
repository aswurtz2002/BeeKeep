package com.beekeep.app

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BackupIoTest {
    @Test
    fun readsUtf8WhenInputIsWithinLimit() {
        val result = readUtf8Limited(
            input = ByteArrayInputStream("Hive 🐝".toByteArray(Charsets.UTF_8)),
            maxBytes = 16,
            tooLargeMessage = "too large"
        )
        assertEquals("Hive 🐝", result)
    }

    @Test
    fun acceptsInputExactlyAtLimit() {
        val result = readUtf8Limited(
            input = ByteArrayInputStream("12345".toByteArray(Charsets.UTF_8)),
            maxBytes = 5,
            tooLargeMessage = "too large"
        )
        assertEquals("12345", result)
    }

    @Test
    fun rejectsInputAboveLimit() {
        try {
            readUtf8Limited(
                input = ByteArrayInputStream("123456".toByteArray(Charsets.UTF_8)),
                maxBytes = 5,
                tooLargeMessage = "Backup exceeds its size limit."
            )
            fail("Expected oversized input to be rejected.")
        } catch (error: IllegalArgumentException) {
            assertEquals("Backup exceeds its size limit.", error.message)
        }
    }

    @Test
    fun rejectsNegativeLimit() {
        try {
            readUtf8Limited(
                input = ByteArrayInputStream(byteArrayOf()),
                maxBytes = -1,
                tooLargeMessage = "too large"
            )
            fail("Expected a negative limit to be rejected.")
        } catch (error: IllegalArgumentException) {
            assertEquals("Maximum input size cannot be negative.", error.message)
        }
    }
}
