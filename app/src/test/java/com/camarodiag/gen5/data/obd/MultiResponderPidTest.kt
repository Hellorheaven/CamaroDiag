package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MultiResponderPidTest {
    @Test fun discoversMultipleRespondersWithoutBorrowingContinuationPages() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "0100" -> "7E8 06 41 00 00 10 00 00\r7E9 06 41 00 00 08 00 01"
                "0120" -> "7E8 06 41 20 FF FF FF FF\r7E9 06 41 20 00 00 00 01"
                "0140" -> "7E9 06 41 40 00 00 00 20"
                else -> "NO DATA"
            }
        })
        val result = engine.discoverPidAvailability()
        assertEquals(setOf(0x0C, 0x0D, 0x5B), result.supported)
        assertNull(result.firstUnresolvedPid)
    }

    @Test fun missingContinuationForOneResponderIsNotCompleteEvidence() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "0100" -> "7E8 06 41 00 00 10 00 01\r7E9 06 41 00 00 08 00 01"
                "0120" -> "7E9 06 41 20 80 00 00 00"
                else -> "NO DATA"
            }
        })
        val result = engine.discoverPidAvailability()
        assertEquals(setOf(0x0C, 0x0D, 0x21), result.supported)
        assertEquals(0x21, result.firstUnresolvedPid)
    }

    @Test fun genericLiveSourceStaysStableAcrossResponseOrderAndMissingReplies() {
        val sources = StableLivePidSources()
        val first = CamaroUdsProtocol.parsePidObservations("7E9 03 41 0D 32", "0D")
        assertEquals("7E9", sources.select(0x0D, first)?.responder)
        val mixed = CamaroUdsProtocol.parsePidObservations(
            "7E8 03 41 0D 99\r7E9 03 41 0D 33", "0D")
        assertEquals(listOf(0x33), sources.select(0x0D, mixed)?.bytes)
        assertNull(sources.select(0x0D, mixed.filter { it.responder == "7E8" }))
        assertNull(sources.select(0x0D, CamaroUdsProtocol.parsePidObservations(
            "7E9 03 41 0D 33\r7E9 03 41 0D 34", "0D")))
        assertEquals("18DAF111", sources.select(0x5B, CamaroUdsProtocol.parsePidObservations(
            "18DAF111 03 41 5B 80", "5B"))?.responder)
        assertNull(CamaroUdsProtocol.parsePidBytes("18DAF111 04 41 5B 80 00", "5B"))
    }
}
