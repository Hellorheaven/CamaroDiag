package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanTraceAnalyzerTest {

    @Test
    fun parsesElmFramesAndDecodesStandardEngineSpeedPid() {
        val report = CanTraceAnalyzer.analyze(
            "7E8 5 04 41 0C 1A F8",
            "capture.txt"
        )

        assertEquals(1, report.frames.size)
        assertEquals(0x7E8, report.frames.single().canId)
        assertEquals(5, report.frames.single().data.size)
        assertEquals(1, report.pidObservations.size)
        assertEquals(0x0C, report.pidObservations.single().pid)
        assertEquals(1726.0, report.pidObservations.single().value, 0.0)
        assertEquals("rpm", report.pidObservations.single().unit)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun reassemblesIsoTpResponseAndReportsObservedDidWithoutPromotingIt() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "7E8 8 10 08 62 F1 90 AA BB CC",
                "7E8 3 21 DD EE"
            ).joinToString("\n"),
            "capture.txt"
        )

        assertEquals(2, report.frames.size)
        assertEquals(1, report.messages.size)
        assertEquals(
            listOf(0x62, 0xF1, 0x90, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE),
            report.messages.single().payload
        )
        assertEquals(1, report.didObservations.size)
        assertEquals(0xF190, report.didObservations.single().did)
        assertTrue(report.didObservations.single().positiveResponse)
        assertEquals(listOf(0xF190), report.readDidCandidates)
        assertEquals(0, report.incompleteIsoTpMessages)
    }

    @Test
    fun retainsIncompleteIsoTpMessagesAsExplicitIncompleteObservations() {
        val report = CanTraceAnalyzer.analyze("7E8 2 10 08", "capture.txt")

        assertEquals(1, report.frames.size)
        assertTrue(report.messages.isEmpty())
        assertEquals(1, report.incompleteIsoTpMessages)
        assertFalse(report.errors.any { it.contains("sequence mismatch") })
    }

    @Test
    fun rejectsMalformedElmFrameAndReportsParsingError() {
        val report = CanTraceAnalyzer.analyze(
            "7E8 9 10 08 62 F1 90 AA BB CC",
            "capture.txt"
        )

        assertTrue(report.frames.isEmpty())
        assertTrue(report.errors.any { it.contains("invalid ELM327 header/DLC/data record") })
    }

    @Test
    fun reassemblesInterleavedIsoTpMessagesIndependentlyByCanId() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "7E8 8 10 08 62 F1 90 AA BB CC",
                "7E9 8 10 08 62 F1 91 11 22 33",
                "7E8 3 21 DD EE",
                "7E9 3 21 44 55"
            ).joinToString("\n"),
            "capture.txt"
        )

        assertEquals(2, report.messages.size)
        assertEquals(listOf(0x7E8, 0x7E9), report.messages.map { it.canId })
        assertEquals(listOf(0xF190, 0xF191), report.didObservations.map { it.did })
        assertEquals(0, report.incompleteIsoTpMessages)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun reportsIsoTpSequenceMismatchAndKeepsMessageIncomplete() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "7E8 8 10 08 62 F1 90 AA BB CC",
                "7E8 3 22 DD EE"
            ).joinToString("\n"),
            "capture.txt"
        )

        assertTrue(report.messages.isEmpty())
        assertEquals(0, report.incompleteIsoTpMessages)
        assertTrue(report.errors.any { it.contains("ISO-TP sequence mismatch") })
    }

    @Test
    fun recognizesNegativeUdsResponseWithoutInventingDidObservation() {
        val report = CanTraceAnalyzer.analyze(
            "7E8 3 03 7F 22 31",
            "capture.txt"
        )

        assertEquals(1, report.messages.size)
        assertEquals(0x22, report.messages.single().service)
        assertEquals(0x31, report.messages.single().negativeResponseCode)
        assertEquals("ReadDataByIdentifier", report.messages.single().serviceName)
        assertTrue(report.didObservations.isEmpty())
        assertTrue(report.readDidCandidates.isEmpty())
    }

    @Test
    fun rejectsMalformedCsvByteColumnInsteadOfSilentlyDroppingIt() {
        val report = CanTraceAnalyzer.analyze(
            "can_id,data0,data1,data2\n7E8,03,41,GG",
            "capture.csv"
        )

        assertTrue(report.frames.isEmpty())
        assertTrue(report.errors.any { it.contains("invalid CSV CAN frame") })
    }
}
