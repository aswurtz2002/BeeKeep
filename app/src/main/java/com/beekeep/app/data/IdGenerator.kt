package com.beekeep.app.data

import java.util.concurrent.atomic.AtomicLong

/**
 * Generates collision-resistant local IDs while keeping IDs compatible with Room's Long keys.
 * The millisecond component makes IDs naturally ordered; the sequence prevents same-millisecond collisions.
 */
object IdGenerator {
    private val next = AtomicLong(System.currentTimeMillis() * 1_000L)

    fun nextLong(): Long {
        while (true) {
            val current = next.get()
            val timeBasedFloor = System.currentTimeMillis() * 1_000L
            val candidate = maxOf(current + 1L, timeBasedFloor)
            if (next.compareAndSet(current, candidate)) return candidate
        }
    }
}
