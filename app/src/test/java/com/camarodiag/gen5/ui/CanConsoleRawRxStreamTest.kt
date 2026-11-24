package com.camarodiag.gen5.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline RAW CAN RX transport callback tests.
 * No active adapter, CAN bus, ECU, port or physical session is required.
 */
class CanConsoleRawRxStreamTest {
    @Test fun rawConsoleCanPersistMoreThanThreeHundredRxEventsWithoutLosingEarlierOnes() {
        val stream = CanConsoleRawRxStream()
        val records = ArrayList<String>()
        var batches = 0
        repeat(1_200) { index ->
            val canId = if (index % 2 == 0) "7E8" else "7E9"
            val chunk = "$canId 03 41 0C 1A\r\n"
            val batch = stream.append(chunk, 1_700_000_000_000L + index)
            records.addAll(batch.records.map { it.content })
            batches++
            assertEquals(0, batch.discardedOversizedLines)
        }
        records.addAll(stream.finish().records.map { it.content })
        assertEquals(1_200, batches)
        assertEquals(1_200L, stream.recordCount)
        assertEquals(1_200, records.size)
        assertTrue(records.first().contains("ID=7E8"))
        assertTrue(records.last().contains("ID=7E9"))
        assertEquals(600, records.count { "ID=7E8" in it })
        assertEquals(600, records.count { "ID=7E9" in it })
    }

    @Test fun splitRxRecordAndCrLfAcrossBleUsbWifiCallbacksIsRecordedExactlyOnce() {
        val stream = CanConsoleRawRxStream()
        assertTrue(stream.append("7E8 03 41", 100L).records.isEmpty())
        val one = stream.append(" 0C 1A\r", 110L)
        assertEquals(1, one.records.size)
        assertEquals(110L, one.receivedAtMillis)
        assertTrue(one.records.single().content.contains("CAN ID=7E8"))
        assertTrue(stream.append("\n7E9 03 41", 120L).records.isEmpty())
        assertTrue(stream.append(" 0D 1B", 130L).records.isEmpty())
        val tail = stream.finish()
        assertEquals(1, tail.records.size)
        assertEquals(130L, tail.receivedAtMillis)
        assertTrue(tail.records.single().content.contains("CAN ID=7E9"))
        assertEquals(2L, stream.recordCount)
        assertThrows(IllegalStateException::class.java) { stream.finish() }
        assertThrows(IllegalStateException::class.java) { stream.append("hello", 150L) }
    }

    @Test fun oversizedUnterminatedAdapterNoiseCannotBecomeForgedEcuResponse() {
        val stream = CanConsoleRawRxStream()
        val discarded = stream.append(
            "X".repeat(4_097) + "7E8 03 41 0C 1A\n7E9 03 41 0D 1B\n",
            200L
        )
        assertEquals(1, discarded.discardedOversizedLines)
        assertEquals(1, discarded.records.size)
        assertTrue(discarded.records.single().content.contains("ID=7E9"))
        assertFalse(discarded.records.single().content.contains("ID=7E8"))
        assertEquals(0, stream.finish().discardedOversizedLines)
    }

    @Test fun adapterStatusLinesAreSystemNotFakedReceivedCanFrames() {
        val stream = CanConsoleRawRxStream()
        val batch = stream.append("SEARCHING...\nNO DATA\nOK\n", 300L)
        assertEquals(3, batch.records.size)
        assertTrue(batch.records.all { it.direction == "SYSTEM" })
        assertFalse(batch.records[1].succeeded)
        assertEquals(3L, stream.recordCount)
    }
}
