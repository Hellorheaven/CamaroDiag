package com.camarodiag.gen5.data.obd

import java.io.IOException
import java.util.zip.CRC32

/**
 * Experimental public USB binary discovery v1, matching Connectors PR #6.
 * Exactly three read-only requests, no negotiation fallback, no ECU commands.
 * CRC32 is error detection, not authentication or anti-replay security.
 */
object CamaroDiagConnectorBinaryDiscovery {
    private val expectedReplies = listOf(
        "PONG CamaroDiagConnectors 0.1.0",
        "CAPS hs_can=disabled sw_can=disabled eeprom_read=disabled eeprom_write=disabled ecu_flash=disabled j2534=disabled",
        "STATUS mode=prototype write=disabled"
    )
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
    ): CamaroDiagConnectorDiscovery.Report =
        inspectWithClock(channel, ensureActive, System::nanoTime)

    /** Fixed clock for USB timeout tests; discovery never enables vehicle access. */
    internal fun inspectWithClock(
        channel: CamaroDiagConnectorDiscovery.Channel,
        ensureActive: () -> Unit = {},
        clock: () -> Long
    ): CamaroDiagConnectorDiscovery.Report {
        var seq = 0
        fun exchange(op: Int): String {
            ensureActive()
            // The 3-second limit includes the USB write. A blocked or stalled
            // bulk transfer must not silently grant a fresh response deadline.
            val deadline = clock() + TIMEOUT_NANOS
            channel.write(request(op, seq))
            ensureActive()
            if (clock() - deadline >= 0) throw IOException("Connector USB write timed out")
            // Read complete USB packets into a large buffer; a short header-sized
            // USB bulk transfer can otherwise lose the remainder of a packet.
            val buffer = ByteArray(512)
            val frame = ByteArray(HEADER_BYTES + MAX_REPLY_PAYLOAD + TRAILER_BYTES)
            var used = 0
            var expectedLength = -1
            while (clock() - deadline < 0) {
                ensureActive()
                val count = channel.read(buffer, 100)
                if (count < 0 || count > buffer.size || count > frame.size - used)
                    throw IOException("Invalid binary USB read")
                buffer.copyInto(frame, used, 0, count)
                used += count
                if (used >= HEADER_BYTES && expectedLength == -1) {
                    if (u8(frame[0]) != 0x43 || u8(frame[1]) != 0x44 ||
                        u8(frame[2]) != 1 || u8(frame[3]) != op || u16(frame, 4) != seq
                    ) throw IOException("Unexpected binary discovery header")
                    val length = u16(frame, 6)
                    if (length !in 1..MAX_REPLY_PAYLOAD) throw IOException("Invalid binary discovery length")
                    expectedLength = HEADER_BYTES + length + TRAILER_BYTES
                }
                if (expectedLength != -1) {
                    if (used > expectedLength) throw IOException("Unexpected trailing binary USB data")
                    if (used == expectedLength) break
                }
            }
            if (clock() - deadline >= 0 || expectedLength == -1 || used != expectedLength)
                throw IOException("Incomplete binary USB reply")
            val wire = frame.copyOf(used)
            val length = u16(wire, 6)
            val crcOffset = HEADER_BYTES + length
            val expectedCrc = wire.copyOf().also { putCrc(it, crcOffset) }
            if (!wire.contentEquals(expectedCrc)) throw IOException("Invalid binary discovery CRC")
            val payload = wire.copyOfRange(HEADER_BYTES, HEADER_BYTES + length)
            if (payload.any { u8(it) !in 32..126 }) throw IOException("Invalid binary discovery payload")
            val text = payload.toString(Charsets.US_ASCII)
            // Match the canonical fixed replies used by the firmware and both
            // reference native clients; no new or reordered fields in CD/1.
            if (text != expectedReplies[op - 1]) throw IOException("Noncanonical binary discovery reply")
            seq++
            ensureActive()
            return text
        }
        return CamaroDiagConnectorDiscovery.verifyReplies(::exchange, ensureActive)
    }
}
