package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanTraceAnalyzerTest {
    @Test
    fun parsesAscSingleFrameUdsReadDidRequestAndResponse() {
        val trace = """
            date Mon Nov 16 13:00:00.000 2026
            0.000000 1 7E0 Tx d 8 03 22 F1 90 00 00 00 00
            0.001000 1 7E8 Rx d 8 07 62 F1 90 31 47 30 31
        """.trimIndent()

        val report = CanTraceAnalyzer.analyze(trace, "camaro.asc")

        assertEquals(2, report.frames.size)
        assertEquals(2, report.messages.size)
        assertEquals(listOf(0x7E0, 0x7E8), report.observedCanIds)
        assertEquals(listOf(0xF190), report.readDidCandidates)
        assertEquals(0x22, report.messages[0].service)
        assertEquals(0xF190, report.messages[0].did)
        assertEquals(CanTraceDirection.TX, report.messages[0].direction)
        assertEquals(0x62, report.messages[1].responseService)
        assertEquals(0xF190, report.messages[1].did)
        assertTrue(report.didObservations.last().positiveResponse)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun reassemblesIsoTpFirstAndConsecutiveFrames() {
        val trace = """
            0.000000 1 7E8 Rx d 8 10 0A 62 F1 90 31 47 30
            0.001000 1 7E8 Rx d 8 21 31 32 33 34 35 36 37
        """.trimIndent()

        val report = CanTraceAnalyzer.analyze(trace, "multi-frame.asc")

        assertEquals(2, report.frames.size)
        assertEquals(1, report.messages.size)
        assertEquals(listOf(0x62, 0xF1, 0x90, 0x31, 0x47, 0x30, 0x31, 0x32, 0x33, 0x34),
            report.messages.single().payload)
        assertEquals(0xF190, report.messages.single().did)
        assertEquals(0, report.incompleteIsoTpMessages)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun parsesCsvWithQuotedHexPayloadAndExplicitDirection() {
        val trace = """
            timestamp,can_id,direction,data
            0.000,0x7E0,Tx,"03 22 F1 90 00 00 00 00"
            0.010,0x7E8,Rx,"07 62 F1 90 31 47 30 31"
        """.trimIndent()

        val report = CanTraceAnalyzer.analyze(trace, "capture.csv")

        assertEquals(2, report.frames.size)
        assertEquals(listOf(0xF190), report.readDidCandidates)
        assertEquals(CanTraceDirection.TX, report.frames.first().direction)
        assertEquals(CanTraceDirection.RX, report.frames.last().direction)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun parsesHeaderEnabledElm327MonitorLogWithoutInventingDirection() {
        val trace = """
            7E0 04 03 22 F1 90
            7E8 08 07 62 F1 90 31 47 30 31
        """.trimIndent()

        val report = CanTraceAnalyzer.analyze(trace, "elm327-monitor.txt")

        assertEquals(2, report.frames.size)
        assertEquals(listOf(0xF190), report.readDidCandidates)
        assertEquals(CanTraceDirection.UNKNOWN, report.frames.first().direction)
        assertEquals(CanTraceDirection.UNKNOWN, report.frames.last().direction)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun rejectsOutOfSequenceConsecutiveFrameAndDoesNotInventDid() {
        val trace = """
            0.000000 1 7E8 Rx d 8 10 0A 62 F1 90 31 47 30 31
            0.001000 1 7E8 Rx d 8 22 32 33 34 35 36 37 38
        """.trimIndent()

        val report = CanTraceAnalyzer.analyze(trace, "bad-sequence.asc")

        assertTrue(report.messages.isEmpty())
        assertTrue(report.readDidCandidates.isEmpty())
        assertEquals(0, report.incompleteIsoTpMessages)
        assertTrue(report.errors.any { it.contains("sequence mismatch") })
    }

    @Test
    fun reportsWriteDidTrafficSeparatelyFromReadDidCandidates() {
        val trace = """
            0.000000 1 7E0 Tx d 8 03 2E F1 90 00 00 00 00
            0.001000 1 7E8 Rx d 8 03 6E F1 90 00 00 00 00
        """.trimIndent()

        val report = CanTraceAnalyzer.analyze(trace, "write-did.asc")

        assertEquals(2, report.didObservations.size)
        assertTrue(report.readDidCandidates.isEmpty())
        assertEquals(0xF190, report.didObservations.last().did)
        assertTrue(report.didObservations.last().positiveResponse)
    }

    @Test
    fun negativeResponseDoesNotBecomeAFalseDidObservation() {
        val trace = "0.000000 1 7E8 Rx d 8 03 7F 22 31 00 00 00 00"

        val report = CanTraceAnalyzer.analyze(trace, "negative.asc")

        assertEquals(1, report.messages.size)
        assertEquals(0x22, report.messages.single().service)
        assertEquals(0x31, report.messages.single().negativeResponseCode)
        assertEquals(null, report.messages.single().did)
        assertTrue(report.didObservations.isEmpty())
        assertFalse(report.messages.single().serviceName == "Unknown")
    }

    @Test
    fun reportsUnfinishedIsoTpMessageAtEndOfTrace() {
        val trace = "0.000000 1 7E8 Rx d 8 10 14 62 F1 90 31 47 30"

        val report = CanTraceAnalyzer.analyze(trace, "incomplete.asc")

        assertEquals(1, report.incompleteIsoTpMessages)
        assertTrue(report.messages.isEmpty())
    }

    @Test
    fun decodesStandardMode01PidsWithoutGuessingUnknownPids() {
        val trace = """
            0.000000 1 7E8 Rx d 8 03 41 0C 1A F8 00 00 00
            0.001000 1 7E8 Rx d 8 03 41 0D 64 00 00 00 00
            0.002000 1 7E8 Rx d 8 03 41 05 7A 00 00 00 00
            0.003000 1 7E8 Rx d 8 03 41 11 80 00 00 00 00
            0.004000 1 7E8 Rx d 8 03 41 99 01 00 00 00 00
        """.trimIndent()

        val report = CanTraceAnalyzer.analyze(trace, "mode01.asc")

        assertEquals(4, report.pidObservations.size)
        assertEquals(1726.0, report.pidObservations[0].value, 0.0)
        assertEquals("rpm", report.pidObservations[0].unit)
        assertEquals(100.0, report.pidObservations[1].value, 0.0)
        assertEquals("km/h", report.pidObservations[1].unit)
        assertEquals(82.0, report.pidObservations[2].value, 0.0)
        assertEquals(128.0 * 100.0 / 255.0, report.pidObservations[3].value, 0.0001)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun ignoresIncompleteStandardPidPayloads() {
        val trace = "0.000000 1 7E8 Rx d 8 02 41 0C 00 00 00 00 00"
        val report = CanTraceAnalyzer.analyze(trace, "incomplete-pid.asc")
        assertTrue(report.pidObservations.isEmpty())
    }

}
