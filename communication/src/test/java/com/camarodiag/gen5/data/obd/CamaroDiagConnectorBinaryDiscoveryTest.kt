package com.camarodiag.gen5.data.obd

import java.io.IOException
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class CamaroDiagConnectorBinaryDiscoveryTest {
    private val ping = "PONG CamaroDiagConnectors 0.1.0"
    private val caps = "CAPS hs_can=disabled sw_can=disabled eeprom_read=disabled eeprom_write=disabled ecu_flash=disabled j2534=disabled"
    private val status = "STATUS mode=prototype write=disabled"

    private fun frame(op: Int, seq: Int, text: String): ByteArray {
        val p = text.toByteArray(Charsets.US_ASCII)
        val header = byteArrayOf(0x43, 0x44, 1, op.toByte(), seq.toByte(), (seq shr 8).toByte(),
            p.size.toByte(), (p.size shr 8).toByte())
        val raw = header + p
        val crc = CRC32().apply { update(raw) }.value
        return raw + ByteArray(4) { (crc ushr (8 * it)).toByte() }
    }

    private class Channel(private val replies: List<ByteArray>, private val chunkSize: Int = 3) : CamaroDiagConnectorDiscovery.Channel {
        val requests = mutableListOf<ByteArray>()
        private var pending = byteArrayOf()
        override fun write(bytes: ByteArray) {
            check(pending.isEmpty())
            pending = replies[requests.size]
            requests += bytes.copyOf()
        }
        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            val count = minOf(chunkSize, pending.size, buffer.size)
            if (count == 0) return -1
            pending.copyInto(buffer, 0, 0, count)
            pending = pending.copyOfRange(count, pending.size)
            return count
        }
    }

    @Test fun fragmentedBinaryDiscoveryMatchesV1AndNeverAuthorizesVehicleAccess() {
        val c = Channel(listOf(frame(1, 0, ping), frame(2, 1, caps), frame(3, 2, status)))
        val report = CamaroDiagConnectorBinaryDiscovery.inspect(c)
        assertEquals(listOf(ping, caps, status), report.lines)
        assertFalse(report.authenticated)
        assertFalse(report.vehicleAccessAllowed)
        assertFalse(report.toAdapterCapabilities().supportsCanTransmit)
        assertEquals(3, c.requests.size)
        for (index in 0..2) assertArrayEquals(frame(index + 1, index, ""), c.requests[index])
    }

    @Test fun malformedVersionSequenceOpcodeCrcAndLengthStopWithoutFallback() {
        val good = frame(1, 0, ping)
        val failures = listOf(
            good.copyOf().also { it[2] = 2 },
            frame(1, 1, ping),
            frame(2, 0, ping),
            good.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
            frame(1, 0, "x".repeat(245)),
            frame(1, 0, ""),
            good.copyOf(good.size - 1),
            (ping + "\n").toByteArray(Charsets.US_ASCII)
        )
        for (failure in failures) {
            val c = Channel(listOf(failure))
            assertTrue(runCatching { CamaroDiagConnectorBinaryDiscovery.inspect(c) }.exceptionOrNull() is IOException)
            assertEquals(1, c.requests.size)
        }
    }

    @Test fun unsafeCapabilitiesOrStatusCannotReturnAReport() {
        val cases = listOf(
            listOf(frame(1, 0, ping), frame(2, 1, caps.replace("ecu_flash=disabled", "ecu_flash=enabled"))),
            listOf(frame(1, 0, ping), frame(2, 1, caps), frame(3, 2, status.replace("disabled", "enabled"))),
            listOf(frame(1, 0, ping), frame(2, 1, caps + "\t"))
        )
        for (replies in cases) {
            val c = Channel(replies)
            assertTrue(runCatching { CamaroDiagConnectorBinaryDiscovery.inspect(c) }.exceptionOrNull() is IOException)
            assertEquals(replies.size, c.requests.size)
        }
    }

    @Test fun referenceNativeClientsRequireCanonicalCapabilityOrder() {
        val reordered = caps.replace("hs_can=disabled sw_can=disabled", "sw_can=disabled hs_can=disabled")
        val c = Channel(listOf(frame(1, 0, ping), frame(2, 1, reordered)))
        assertTrue(runCatching { CamaroDiagConnectorBinaryDiscovery.inspect(c) }.exceptionOrNull() is IOException)
        assertEquals(2, c.requests.size)
    }

    @Test fun completeUsbPacketAndConcatenatedRepliesAreHandledWithoutTruncation() {
        val all = listOf(frame(1, 0, ping), frame(2, 1, caps), frame(3, 2, status))
        val fullPacket = Channel(all, 512)
        assertEquals(listOf(ping, caps, status), CamaroDiagConnectorBinaryDiscovery.inspect(fullPacket).lines)
        val concatenated = Channel(listOf(all[0] + all[1]), 512)
        assertTrue(runCatching {
            CamaroDiagConnectorBinaryDiscovery.inspect(concatenated)
        }.exceptionOrNull() is IOException)
        assertEquals(1, concatenated.requests.size)
    }

    @Test fun cancellationDoesNotSendAnyRequest() {
        val c = Channel(emptyList())
        val error = runCatching {
            CamaroDiagConnectorBinaryDiscovery.inspect(c) { throw java.util.concurrent.CancellationException() }
        }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
        assertTrue(c.requests.isEmpty())
    }
}
