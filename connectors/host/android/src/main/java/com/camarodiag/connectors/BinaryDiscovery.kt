package com.camarodiag.connectors

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.function.LongSupplier
import java.util.zip.CRC32

/** Public CD/1 discovery only; never an ECU command transport. */
class BinaryDiscovery @JvmOverloads constructor(
    private val link: Link,
    private val clockMs: LongSupplier = LongSupplier { System.nanoTime() / 1_000_000L }
) : AutoCloseable {
    interface Link : AutoCloseable {
        @Throws(IOException::class)
        fun read(buffer: ByteArray, offset: Int, count: Int, timeoutMs: Int): Int
        @Throws(IOException::class)
        fun write(buffer: ByteArray, timeoutMs: Int)
        @Throws(IOException::class)
        fun checkNoBufferedInput() {}
        @Throws(IOException::class)
        override fun close()
    }

    private var sequence = 0
    private var closed = false

    private fun remaining(since: Long): Int {
        val elapsed = clockMs.asLong - since
        if (elapsed < 0 || elapsed >= 3000) throw SocketTimeoutException("Discovery response deadline exceeded")
        return 3000 - elapsed.toInt()
    }

    private fun exact(length: Int, since: Long): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = link.read(data, offset, length - offset, remaining(since))
            remaining(since)
            if (count <= 0 || count > length - offset) throw IOException("Truncated discovery response")
            offset += count
        }
        return data
    }

    private fun exchange(op: Int): String {
        if (closed) throw IOException("Closed discovery session")
        val request = byteArrayOf('C'.code.toByte(), 'D'.code.toByte(), 1, op.toByte(),
            sequence.toByte(), (sequence ushr 8).toByte(), 0, 0, 0, 0, 0, 0)
        val crc = CRC32().also { it.update(request, 0, 8) }.value
        for (i in 0..3) request[8 + i] = (crc ushr (8 * i)).toByte()
        val since = clockMs.asLong
        link.write(request, remaining(since))
        val head = exact(8, since)
        if (head[0] != 'C'.code.toByte() || head[1] != 'D'.code.toByte() ||
            u(head[2]) != 1 || u(head[3]) != op || CuCaptureReader.le16(head, 4) != sequence)
            throw IOException("Unexpected discovery header")
        val length = CuCaptureReader.le16(head, 6)
        if (length !in 1..244) throw IOException("Invalid reply length")
        val tail = exact(length + 4, since)
        link.checkNoBufferedInput()
        remaining(since)
        if (!CuCaptureReader.validCrc(head, tail, length)) throw IOException("CRC mismatch")
        if ((0 until length).any { u(tail[it]) !in 32..126 }) throw IOException("Nonprintable reply")
        val text = String(tail, 0, length, Charsets.US_ASCII)
        if (text != expected[op - 1]) throw IOException("Unexpected identity or capabilities")
        sequence++
        return text
    }

    @Synchronized @Throws(IOException::class)
    fun verify(): Array<String> {
        if (closed || sequence != 0) throw IOException("Fresh device session required")
        try { return arrayOf(exchange(1), exchange(2), exchange(3)) }
        finally { close() }
    }

    @Synchronized @Throws(IOException::class)
    override fun close() {
        if (!closed) {
            closed = true
            link.close()
        }
    }

    companion object {
        private fun u(b: Byte) = CuWire.unsigned(b)
        private val expected = arrayOf(
            "PONG CamaroDiagConnectors 0.1.0",
            "CAPS hs_can=disabled sw_can=disabled eeprom_read=disabled eeprom_write=disabled ecu_flash=disabled j2534=disabled",
            "STATUS mode=prototype write=disabled"
        )
    }
}
