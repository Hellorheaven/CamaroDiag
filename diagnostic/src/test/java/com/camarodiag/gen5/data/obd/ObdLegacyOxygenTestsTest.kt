package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ObdLegacyOxygenTestsTest {
    @Test fun exactProtocolNumberIsRequired() {
        assertEquals(3, ObdLegacyOxygenTests.protocolNumber("ATDPN\rA3\r>"))
        assertEquals(6, ObdLegacyOxygenTests.protocolNumber("6"))
        assertNull(ObdLegacyOxygenTests.protocolNumber("A0"))
        assertNull(ObdLegacyOxygenTests.protocolNumber("3\r6"))
        assertNull(ObdLegacyOxygenTests.protocolNumber("ISO 9141-2"))
    }

    @Test fun discoversOnlyAnnouncedSensorsAndTids() = runBlocking {
        val commands = mutableListOf<String>()
        val report = ObdLegacyOxygenTests.scan(3, { command, _ ->
            commands += command
            when (command) {
                "0100" -> "410000002000"
                "0113" -> "411301"
                "050001" -> "05 00 01\r45000188000000\r>"
                "050101" -> "4501015A"
                "050501" -> "450501283264"
                else -> error("Unexpected command: $command")
            }
        }, clockMs = { 0L })
        assertEquals(listOf("0100", "0113", "050001", "050101", "050501"), commands)
        assertEquals(setOf(1), report.sensorMasks)
        assertTrue(report.complete)
        assertTrue(report.hasTestResults)
        assertEquals(listOf(0x45, 5, 1, 0x28, 0x32, 0x64),
            report.reads.last().positiveRecords.single())
    }

    @Test fun wrongSensorAndTruncatedRecordsAreNotEvidence() = runBlocking {
        val report = ObdLegacyOxygenTests.scan(5, { command, _ ->
            when (command) {
                "0100" -> "410000002000"
                "0113" -> "411301"
                "050001" -> "45000188000000"
                "050101" -> "4501025A"
                "050501" -> "4505012832"
                else -> error("Unexpected command: $command")
            }
        }, clockMs = { 0L })
        assertFalse(report.complete)
        assertFalse(report.hasTestResults)
        assertTrue(report.reads.takeLast(2).all { it.positiveRecords.isEmpty() })
    }

    @Test fun budgetStopsRequestsWithoutInventingCompletion() = runBlocking {
        var clock = 0L
        val requests = mutableListOf<Pair<String, Long>>()
        val report = ObdLegacyOxygenTests.scan(1, { command, timeout ->
            requests += command to timeout
            clock += timeout
            "410000002000"
        }, budgetMs = 10, clockMs = { clock })
        assertEquals(listOf("0100" to 10L), requests)
        assertFalse(report.complete)
        assertFalse(report.hasTestResults)
    }

    @Test fun unsupportedOptionalBitmapDoesNotTriggerBlindProbing() = runBlocking {
        val commands = mutableListOf<String>()
        val report = ObdLegacyOxygenTests.scan(4, { command, _ ->
            commands += command
            when (command) {
                "0100" -> "410000002000"
                "0113" -> "411301"
                "050001" -> "NO DATA"
                else -> error("Unexpected command: $command")
            }
        }, clockMs = { 0L })
        assertEquals(listOf("0100", "0113", "050001"), commands)
        assertFalse(report.complete)
        assertFalse(report.hasTestResults)
    }
}
