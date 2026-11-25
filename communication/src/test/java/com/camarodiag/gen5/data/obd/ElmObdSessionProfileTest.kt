package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ElmObdSessionProfileTest {
    @Test fun protocolNumberMustBeAnUnambiguousAdapterReply() {
        for ((raw, expected) in listOf("A7" to 7, "8" to 8, "ATDPN\rA9\r>" to 9,
            "AA" to 10, "3" to 3, "A" to 10)) {
            assertEquals(expected, ElmObdSessionProfile.parseProtocolNumber(raw))
        }
        for (raw in listOf("0", "A0", "NO DATA", "OK", "A7\rA6", "SEARCHING...\rA7", "B", "?"))
            assertNull(raw, ElmObdSessionProfile.parseProtocolNumber(raw))
    }

    @Test fun allConsoleAndUdsCleanupPreservesExtendedOrSlowCanBaseline() = runBlocking {
        for (protocol in 6..9) {
            val expectedHeader = if (protocol in setOf(7,9)) "ATSH 18DB33F1" else "ATSH 7DF"
            val commands = listOf(CanMonitorProtocolPolicy.restoreCommands(false, protocol),
                CanMonitorProtocolPolicy.restoreCommands(true, protocol),
                ElmUdsPayloadTransport.restoreCommandsFor(protocol),
                StnUdsAdapterRestoration.commandsFor(protocol))
            for (sequence in commands) {
                assertEquals(listOf("ATSP$protocol"), sequence.filter { it.startsWith("ATSP") })
                assertEquals(listOf(expectedHeader), sequence.filter { it.startsWith("ATSH") })
                assertTrue(sequence.contains("ATCAF1"))
            }
            val observed = mutableListOf<String>()
            assertTrue(StnUdsAdapterRestoration.restore(protocol) { observed += it; "OK" }.succeeded)
            assertEquals(commands.last(), observed)
        }
    }

    @Test fun unconfirmedProtocolRestoresAutomaticWithoutInventingCanAddress() {
        val auto = ElmObdSessionProfile.fromObservation(7, false)
        assertEquals(0, auto.protocol)
        assertEquals(auto, ElmObdSessionProfile.fromObservation(3, true))
        assertEquals(9, ElmObdSessionProfile.fromObservation(9, true).protocol)
        assertTrue(auto.restoreCommands().containsAll(listOf("ATD", "ATSP0")))
        assertFalse(auto.restoreCommands().any { it.startsWith("ATSH") })
    }
}
