package com.camaradiag.gen5.data.obd

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

    @Test fun malformedOrUnavailableResultsNeverBecomeMeasurements() {
        assertNull(ObdCanMonitorRecords.support("7F0611", 0))
        assertNull(ObdCanMonitorRecords.support("46008000", 0))
        assertNull(ObdCanMonitorRecords.results("4621802400010000", 0x21))
        assertNull(ObdCanMonitorRecords.results("46228024000100000002", 0x21))
        assertEquals(2, ObdCanMonitorRecords.results(
            "46218024000100000002218124000200000003", 0x21)?.size)
    }
}
