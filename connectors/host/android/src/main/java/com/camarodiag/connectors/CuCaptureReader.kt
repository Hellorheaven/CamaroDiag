package com.camarodiag.connectors

import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.function.BooleanSupplier
import java.util.function.LongSupplier
import java.util.zip.CRC32

/** Bounded serial reader shared by CU capture sessions; never retries a request. */
internal class CuCaptureReader(
    private val link: J1979UsbCapture.Link,
    private val clock: LongSupplier,
    private val protocol: String
) {
    fun now(): Long = clock.asLong

    fun remaining(start: Long, budget: Int): Int {
        val elapsed = now() - start
        if (elapsed < 0 || elapsed >= budget) throw SocketTimeoutException("$protocol deadline exceeded")
        return budget - elapsed.toInt()
    }

    fun current(allowed: BooleanSupplier?) {
        if (allowed != null && !allowed.asBoolean) throw IOException("$protocol session revoked")
    }

    fun exact(
        size: Int, start: Long, budget: Int, raw: OutputStream? = null,
        copy: OutputStream? = null, allowed: BooleanSupplier? = null,
        copyLimit: Long = Long.MAX_VALUE, copied: () -> Long = { 0L }
    ): ByteArray {
        if (size !in 0..8192) throw IOException("$protocol invalid record size")
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            current(allowed)
            val count = link.read(bytes, offset, size - offset, remaining(start, budget))
            if (count <= 0 || count > size - offset) throw IOException("Truncated $protocol capture")
            // Preserve bytes already observed, even when revocation or a timeout follows.
            raw?.write(bytes, offset, count)
            if (copy != null) {
                if (copied() > copyLimit - count) throw IOException("$protocol exceeds capture limit")
                copy.write(bytes, offset, count)
            }
            offset += count
            current(allowed)
            remaining(start, budget)
        }
        return bytes
    }

    companion object {
        fun u(value: Byte): Int = value.toInt() and 255

        fun le16(bytes: ByteArray, offset: Int): Int =
            u(bytes[offset]) or (u(bytes[offset + 1]) shl 8)

        fun le32(bytes: ByteArray, offset: Int): Long =
            u(bytes[offset]).toLong() or (u(bytes[offset + 1]).toLong() shl 8) or
                (u(bytes[offset + 2]).toLong() shl 16) or (u(bytes[offset + 3]).toLong() shl 24)

        fun validCrc(head: ByteArray, tail: ByteArray, length: Int): Boolean {
            if (length < 0 || tail.size != length + 4) return false
            val crc = CRC32()
            crc.update(head)
            crc.update(tail, 0, length)
            return crc.value == le32(tail, length)
        }
    }
}
