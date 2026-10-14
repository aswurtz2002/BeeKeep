package com.beekeep.app

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal const val MAX_BACKUP_FILE_BYTES = 25 * 1024 * 1024

/**
 * Reads a UTF-8 backup while enforcing the size limit during the read, not after
 * an unbounded readBytes() allocation. At most one byte beyond the limit is read.
 */
internal fun readUtf8Limited(
    input: InputStream,
    maxBytes: Int,
    tooLargeMessage: String
): String {
    require(maxBytes >= 0) { "Maximum input size cannot be negative." }
    require(tooLargeMessage.isNotBlank()) { "A size-limit error message is required." }

    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var totalRead = 0L
    val maximum = maxBytes.toLong()

    while (true) {
        val remaining = maximum - totalRead
        val requested = minOf(buffer.size.toLong(), remaining + 1L).toInt()
        val count = input.read(buffer, 0, requested)
        if (count < 0) break
        if (count == 0) continue

        totalRead += count
        if (totalRead > maximum) {
            throw IllegalArgumentException(tooLargeMessage)
        }
        output.write(buffer, 0, count)
    }

    return output.toString(Charsets.UTF_8.name())
}
