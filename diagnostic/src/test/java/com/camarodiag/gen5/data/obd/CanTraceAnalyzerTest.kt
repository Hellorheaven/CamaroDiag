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
            0.000000 1 7E8 Rx d 8 10 0A 62 F1 90 31 47 30 31
            0.001000 1 7E8 Rx d 8 21 32 33 34 35 36 37 38
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
            0.010,0x7E8,Rx,"06 62 F1 90 31 47 30 31"
        """.trimIndent()

        val report = CanTraceAnalyzer.analyze(trace, "capture.csv")

        assertEquals(2, report.frames.size)
        assertEquals(listOf(0xF190), report.readDidCandidates)
        assertEquals(CanTraceDirection.TX, report.frames.first().direction)
        assertEquals(CanTraceDirection.RX, report.frames.last().direction)
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
        val trace = "0.000000 1 7E8 Rx d 8 10 14 62 F1 90 31 47 30 31"

        val report = CanTraceAnalyzer.analyze(trace, "incomplete.asc")

        assertEquals(1, report.incompleteIsoTpMessages)
        assertTrue(report.messages.isEmpty())
    }
}
