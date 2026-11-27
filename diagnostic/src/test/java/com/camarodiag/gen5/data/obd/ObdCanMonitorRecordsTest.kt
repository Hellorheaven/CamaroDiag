package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ObdCanMonitorRecordsTest {
    @Test fun bitmapAndResultsAreSeparateNamespaces() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { cmd, _ ->
            commands += cmd
            when (cmd) {
                "0600" -> "460080000001"
                "0620" -> "462080000000"
                "0601" -> "00A\r0: 460101240064\r1: 000000C8"
                "0621" -> "46218024000100000002"
                else -> "NO DATA"
            }
        })
        val report = requireNotNull(engine.readMonitorTests())
        assertEquals(listOf("0600","0620","0601","0621"), commands)
        assertTrue(report.contains("MID=01 TID=01 UASID=24 RAW=0064"))
        assertTrue(report.contains("MID=21 TID=80 UASID=24 RAW=0001"))
        assertFalse(report.contains("PARTIAL"))
    }

    @Test fun knownMonitorScalingKeepsRawDataAndDecodesSignedLimits() {
        val signed = requireNotNull(ObdCanMonitorRecords.results("46010190FFFF80000000", 1)).single()
        val value = requireNotNull(signed.scaled())
        assertEquals("ms", value.unit)
        assertEquals(-1.0, value.value, 0.0)
        assertEquals(-32768.0, value.minimum, 0.0)
        assertEquals(0.0, value.maximum, 0.0)
        assertEquals("WITHIN", value.numericRelation)
        assertTrue(signed.rawText().contains("RAW=FFFF"))
        assertTrue(signed.rawText().contains("UNIT=ms"))
        val unknown = requireNotNull(ObdCanMonitorRecords.results("460101FF000100000002", 1)).single()
        assertNull(unknown.scaled())
        assertFalse(unknown.rawText().contains("UNIT="))
        val inverted = ObdCanMonitorRecords.Result(1, 1, 0x24, 2, 3, 1)
        assertEquals("INVERTED_LIMITS", inverted.scaled()?.numericRelation)
        val voltage = ObdCanMonitorRecords.Result(1, 1, 0x0A, 1000, 0, 2000)
        assertEquals(122.0, requireNotNull(voltage.scaled()).value, 1e-10)
    }

    @Test fun malformedOrUnavailableResultsNeverBecomeMeasurements() {
        assertNull(ObdCanMonitorRecords.support("7F0611", 0))
        assertNull(ObdCanMonitorRecords.support("46008000", 0))
        assertNull(ObdCanMonitorRecords.results("4621802400010000", 0x21))
        assertNull(ObdCanMonitorRecords.results("46228024000100000002", 0x21))
        assertEquals(2, ObdCanMonitorRecords.results(
            "46218024000100000002218124000200000003", 0x21)?.size)
    }

    @Test fun requestBudgetKeepsVerifiedResultsWithoutQueryingRemainingMids() = runBlocking {
        var clock = 0L
        val commands = mutableListOf<Pair<String, Long>>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { cmd, timeout ->
            commands += cmd to timeout
            clock += minOf(60L, timeout)
            when (cmd) {
                "0600" -> "4600C0000000"
                "0601" -> "460101240064000000C8"
                else -> error("Unexpected command $cmd")
            }
        })
        val report = requireNotNull(engine.readMonitorTests(100L) { clock })
        assertEquals(listOf("0600" to 100L, "0601" to 40L), commands)
        assertTrue(report.contains("MID=01 TID=01 UASID=24 RAW=0064"))
        assertTrue(report.contains("UNQUERIED_MIDS=02"))
        assertTrue(report.endsWith("PARTIAL"))
    }

    @Test fun emptyBitmapIsDistinctFromUnavailableOrIncompleteDiscovery() = runBlocking {
        suspend fun scan(first: String): String? = Obd2FeatureEngine(ObdCommandChannel { cmd, _ ->
            if (cmd == "0600") first else "NO DATA"
        }).readMonitorTests(100L) { 0L }
        assertEquals("DISCOVERED_MIDS=0", scan("460000000000"))
        assertEquals("DISCOVERED_MIDS=0\nPARTIAL", scan("460000000001"))
        assertNull(scan("NO DATA"))
        assertNull(ObdCanMonitorRecords.support("46E000000001", 0xE0))
        assertNotNull(ObdCanMonitorRecords.support("46E000000000", 0xE0))
    }

    @Test fun budgetExhaustedDuringDiscoveryDoesNotStartAnotherPage() = runBlocking {
        var clock = 0L
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { cmd, timeout ->
            commands += cmd
            clock += timeout
            "460080000001"
        })
        val report = requireNotNull(engine.readMonitorTests(25L) { clock })
        assertEquals(listOf("0600"), commands)
        assertEquals("UNQUERIED_MIDS=01\nPARTIAL", report)
    }

}
