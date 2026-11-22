package com.camarodiag.gen5.data.obd

import java.io.IOException
import java.util.zip.CRC32

/**
 * Experimental public USB binary discovery v1, matching Connectors PR #6.
 * Exactly three read-only requests, no negotiation fallback, no ECU commands.
 * CRC32 is error detection, not authentication or anti-replay security.
 */
object CamaroDiagConnectorBinaryDiscovery {
    private const val HEADER_BYTES = 8
    private const val TRAILER_BYTES = 4
    private const val MAX_REPLY_PAYLOAD = 244
    private const val TIMEOUT_NANOS = 3_000_000_000L

    private fun u8(value: Byte) = value.toInt() and 0xff
    private fun u16(bytes: ByteArray, offset: Int) =
        u8(bytes[offset]) or (u8(bytes[offset + 1]) shl 8)
    private fun putCrc(bytes: ByteArray, length: Int) {
        val crc = CRC32().apply { update(bytes, 0, length) }.value
        for (i in 0 until TRAILER_BYTES) bytes[length + i] = (crc ushr (8 * i)).toByte()
    }

    private fun request(op: Int, seq: Int): ByteArray =
        ByteArray(HEADER_BYTES + TRAILER_BYTES).apply {
            this[0] = 0x43
            this[1] = 0x44
            this[2] = 1
            this[3] = op.toByte()
            this[4] = seq.toByte()
            this[5] = (seq ushr 8).toByte()
            putCrc(this, HEADER_BYTES)
        }

    fun inspect(
        channel: CamaroDiagConnectorDiscovery.Channel,
        ensureActive: () -> Unit = {}
    ): CamaroDiagConnectorDiscovery.Report {
        var seq = 0
        fun exchange(op: Int): String {
            ensureActive()
            channel.write(request(op, seq))
            val deadline = System.nanoTime() + TIMEOUT_NANOS
            fun readExact(length: Int): ByteArray {
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length && System.nanoTime() - deadline < 0) {
                    ensureActive()
                    val buffer = ByteArray(length - offset)
                    val count = channel.read(buffer, 100)
                    if (count < 0 || count > buffer.size) throw IOException("Invalid binary USB read")
                    buffer.copyInto(bytes, offset, 0, count)
                    offset += count
                }
                if (offset != length) throw IOException("Incomplete binary USB reply")
                return bytes
            }
            val header = readExact(HEADER_BYTES)
            if (u8(header[0]) != 0x43 || u8(header[1]) != 0x44 ||
                u8(header[2]) != 1 || u8(header[3]) != op || u16(header, 4) != seq
            ) throw IOException("Unexpected binary discovery header")
            val length = u16(header, 6)
            if (length !in 1..MAX_REPLY_PAYLOAD) throw IOException("Invalid binary discovery length")
            val tail = readExact(length + TRAILER_BYTES)
            val wire = header + tail
            val crcOffset = HEADER_BYTES + length
            val expectedCrc = wire.copyOf().also { putCrc(it, crcOffset) }
            if (!wire.contentEquals(expectedCrc)) throw IOException("Invalid binary discovery CRC")
            val payload = tail.copyOfRange(0, length)
            if (payload.any { u8(it) !in 32..126 }) throw IOException("Invalid binary discovery payload")
            seq++
            ensureActive()
            return payload.toString(Charsets.US_ASCII)
        }
        return CamaroDiagConnectorDiscovery.verifyReplies(::exchange, ensureActive)
    }
}
