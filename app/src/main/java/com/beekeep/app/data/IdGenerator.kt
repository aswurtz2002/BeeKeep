package com.beekeep.app.data

import java.security.SecureRandom

/**
 * Creates positive, high-entropy Long IDs suitable for records created on multiple devices.
 *
 * Time-and-counter IDs are unique only within a device/process. Team members can create records
 * offline at the same time, so shared data needs IDs that are unlikely to collide across devices.
 */
object IdGenerator {
    private val random = SecureRandom()

    @Synchronized
    fun nextLong(): Long {
        while (true) {
            val candidate = random.nextLong() and Long.MAX_VALUE
            if (candidate != 0L) return candidate
        }
    }
}
