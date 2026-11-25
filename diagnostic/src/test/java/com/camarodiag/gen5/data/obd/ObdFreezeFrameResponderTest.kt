package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ObdFreezeFrameResponderTest {
    @Test fun frameNumberPidAndExactLengthAreRequired() {
        val good = CamaroUdsProtocol.parseFreezeFrameObservations(
            "18DAF111 05 42 0C 00 1A F8 AA AA", 0x0C)
        assertEquals(listOf(0x1A, 0xF8), good.single().bytes)
        assertEquals("18DAF111", good.single().responder)
        for (bad in listOf("420C011AF8", "420C001A", "420C001AF800",
            "7E8 04 42 0C 00 1A", "420D001AF8", "420C00\r1AF8")) {
            assertTrue(bad, CamaroUdsProtocol.parseFreezeFrameObservations(bad, 0x0C).isEmpty())
        }
    }

    @Test fun oneFreezeFrameNeverBorrowsAnotherEcusValues() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "020200" -> "7E8 05 42 02 00 00 00\r7E9 05 42 02 00 01 71"
                "020000" -> "7E9 07 42 00 00 08 10 00 00\r7E8 07 42 00 00 FF FF FF FE"
                "020500" -> "7E8 04 42 05 00 66"
                "020C00" -> "7E8 05 42 0C 00 12 34\r7E9 05 42 0C 00 1A F8"
                else -> "NO DATA"
            }
        })
        assertEquals("P0171", engine.readFreezeFrameDtc()?.code)
        assertEquals(setOf(5, 12), engine.discoverFreezeFrameSupportedPids())
        assertEquals(mapOf(12 to listOf(0x1A, 0xF8)), engine.readFreezeFrame(setOf(5, 12)))
    }

    @Test fun contradictoryRepliesFromSelectedEcuAreNotMeasurements() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "020200" -> "7E9 05 42 02 00 01 71"
                "020500" -> "7E9 04 42 05 00 66\r7E9 04 42 05 00 77"
                else -> "NO DATA"
            }
        })
        assertNotNull(engine.readFreezeFrameDtc())
        assertTrue(engine.readFreezeFrame(setOf(5)).isEmpty())
    }
}
