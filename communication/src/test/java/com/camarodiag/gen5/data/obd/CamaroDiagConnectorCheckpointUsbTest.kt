package com.camarodiag.gen5.data.obd

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CamaroDiagConnectorCheckpointUsbTest {
    private val fixture = "43444a0102000000010000000102030405060708090a0b0c0d0e0f10202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3fa75e21b6"
    private class Channel(private val replies: List<String>) : CamaroDiagConnectorDiscovery.Channel {
        val requests = mutableListOf<String>()
        private var offset = 0
        override fun write(bytes: ByteArray) { requests += bytes.toString(Charsets.US_ASCII) }
        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            if (offset == replies.size) throw IOException("EOF")
            return replies[offset++].toByteArray(Charsets.US_ASCII).let { bytes ->
                bytes.copyInto(buffer)
                bytes.size
            }
        }
    }

    @Test fun fragmentAndSinglePacketProduceTheSameGoldenEvidence() {
        val line = "CDJ/1 " + fixture + "\n"
        for (packets in listOf(listOf(line), line.chunked(31))) {
            val channel = Channel(packets)
            val result = CamaroDiagConnectorCheckpointUsb.capture(channel)
            assertNotNull(result)
            assertEquals(CamaroDiagConnectorCheckpoint.Disposition.RECOVERY_REQUIRED,
                CamaroDiagConnectorCheckpoint.inspect(result).disposition)
            assertEquals(listOf("CDJ/1 READ\n"), channel.requests)
        }
    }

    @Test fun missingRecordAndMalformedRepliesCannotAuthorizeAnything() {
        assertNull(CamaroDiagConnectorCheckpointUsb.capture(Channel(listOf("CDJ/1 NONE\n"))))
        for (line in listOf("CDJ/1 CORRUPT\n", "CDJ/1 NONE\nextra",
            "CDJ/1 " + "00".repeat(64) + "\n", "CDJ/1 " + fixture, "x".repeat(136))) {
            assertTrue(runCatching { CamaroDiagConnectorCheckpointUsb.capture(Channel(listOf(line))) }.isFailure)
        }
    }

    @Test fun staleSessionOrDeadlineRejectsCapture() {
        var calls = 0
        val channel = Channel(listOf("CDJ/1 NONE\n"))
        assertTrue(runCatching {
            CamaroDiagConnectorCheckpointUsb.capture(channel, ensureCurrent = {
                if (++calls >= 3) throw IOException("stale session")
            })
        }.isFailure)
        val clock = listOf(0L, 3_000_000_000L).iterator()
        assertTrue(runCatching {
            CamaroDiagConnectorCheckpointUsb.capture(Channel(listOf("CDJ/1 NONE\n")), clock = { clock.next() })
        }.isFailure)
    }
}
