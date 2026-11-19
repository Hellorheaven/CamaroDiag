package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanTraceAnalyzerTest {

    @Test
    fun correlatesUdsRequestAndPositiveResponseByDirectionServiceAndDid() {
        val trace = listOf(
            "0.000000 1 7E0 Tx d 8 03 22 F1 90 00 00 00 00",
            "0.025000 1 7E8 Rx d 8 07 62 F1 90 31 47 30 31"
        ).joinToString("\n")
        val report = CanTraceAnalyzer.analyze(trace, "exchange.asc")

        assertEquals(1, report.exchanges.size)
        val exchange = report.exchanges.single()
        assertEquals(0x7E0, exchange.request.canId)
        assertEquals(1, exchange.responses.size)
        assertEquals(0x7E8, exchange.responses.single().canId)
        assertEquals(UdsExchangeStatus.COMPLETED, exchange.status)
        assertEquals(25.0, exchange.elapsedMilliseconds!!, 0.001)
    }

    @Test
    fun keepsNrcResponsePendingOpenUntilFinalNegativeResponse() {
        val trace = listOf(
            "0.000000 1 7E0 Tx d 8 03 22 F1 90 00 00 00 00",
            "0.010000 1 7E8 Rx d 8 03 7F 22 78 00 00 00 00",
            "0.090000 1 7E8 Rx d 8 03 7F 22 31 00 00 00 00"
        ).joinToString("\n")
        val exchange = CanTraceAnalyzer.analyze(trace, "exchange.asc").exchanges.single()

        assertEquals(2, exchange.responses.size)
        assertEquals(UdsExchangeStatus.COMPLETED, exchange.status)
        assertEquals(90.0, exchange.elapsedMilliseconds!!, 0.001)
        assertEquals(0x31, exchange.responses.last().negativeResponseCode)
    }

    @Test
    fun doesNotGuessUdsCorrelationForUnknownDirectionElmFrames() {
        val report = CanTraceAnalyzer.analyze(
            listOf("7E0 4 03 22 F1 90", "7E8 4 03 62 F1 90").joinToString("\n"),
            "capture.txt"
        )
        assertTrue(report.exchanges.isEmpty())
    }



    @Test
    fun doesNotCompleteDidRequestWhenPositiveResponseOmitsTheDid() {
        val trace = listOf(
            "0.000000 1 7E0 Tx d 8 03 22 F1 90 00 00 00 00",
            "0.025000 1 7E8 Rx d 8 02 62 00 00 00 00 00 00"
        ).joinToString("\n")
        val exchange = CanTraceAnalyzer.analyze(trace, "truncated-did-response.asc").exchanges.single()

        assertEquals(UdsExchangeStatus.UNANSWERED, exchange.status)
        assertTrue(exchange.responses.isEmpty())
    }

    @Test
    fun doesNotAttachAmbiguousNegativeResponseToOneOfSeveralSameServiceRequests() {
        val trace = listOf(
            "0.000000 1 7E0 Tx d 8 03 22 F1 90 00 00 00 00",
            "0.001000 1 7E0 Tx d 8 03 22 F1 91 00 00 00 00",
            "0.025000 1 7E8 Rx d 8 03 7F 22 31 00 00 00 00"
        ).joinToString("\n")
        val report = CanTraceAnalyzer.analyze(trace, "ambiguous.asc")

        assertEquals(2, report.exchanges.size)
        assertTrue(report.exchanges.all { it.status == UdsExchangeStatus.UNANSWERED })
        assertTrue(report.exchanges.all { it.responses.isEmpty() })
    }

    @Test
    fun matchesPositiveResponseToItsDidWhenSeveralSameServiceRequestsArePending() {
        val trace = listOf(
            "0.000000 1 7E0 Tx d 8 03 22 F1 90 00 00 00 00",
            "0.001000 1 7E0 Tx d 8 03 22 F1 91 00 00 00 00",
            "0.025000 1 7E8 Rx d 8 04 62 F1 90 AA 00 00 00"
        ).joinToString("\n")
        val report = CanTraceAnalyzer.analyze(trace, "did-match.asc")

        assertEquals(2, report.exchanges.size)
        val completed = report.exchanges.single { it.status == UdsExchangeStatus.COMPLETED }
        val unanswered = report.exchanges.single { it.status == UdsExchangeStatus.UNANSWERED }
        assertEquals(0xF190, completed.request.did)
        assertEquals(0xF190, completed.responses.single().did)
        assertEquals(0xF191, unanswered.request.did)
        assertTrue(unanswered.responses.isEmpty())
    }

    @Test
    fun decodesCanFdEscapedIsoTpSingleFrameLength() {
        val report = CanTraceAnalyzer.analyze(
            "0.000000 1 7E8 Rx d 12 00 0A 62 F1 90 01 02 03 04 05 00 00",
            "can-fd.asc"
        )

        assertEquals(1, report.messages.size)
        assertEquals(
            listOf(0x62, 0xF1, 0x90, 0x01, 0x02, 0x03, 0x04, 0x05, 0x00, 0x00),
            report.messages.single().payload
        )
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun rejectsTruncatedIsoTpConsecutiveFrameAndDiscardsPartialMessage() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "7E8 8 10 08 62 F1 90 AA BB CC",
                "7E8 1 21"
            ).joinToString("\n"),
            "capture.txt"
        )

        assertTrue(report.messages.isEmpty())
        assertEquals(1, report.incompleteIsoTpMessages)
        assertTrue(report.errors.any { it.contains("truncated ISO-TP consecutive frame") })
    }

    @Test
    fun supportsIsoTpSequenceNumberWrapAfterFifteen() {
        val frames = mutableListOf("7E8 8 10 76 62 F1 90 00 01 02")
        for (sequence in 1..16) {
            val bytes = listOf(0x20 or (sequence and 0x0F)) +
                List(7) { index -> (sequence + index) and 0xFF }
            val encoded = bytes.joinToString(" ") { value ->
                value.toString(16).padStart(2, '0').uppercase()
            }
            frames += "7E8 8 $encoded"
        }
        val report = CanTraceAnalyzer.analyze(frames.joinToString("\n"), "sequence-wrap.txt")

        assertEquals(1, report.messages.size)
        assertEquals(118, report.messages.single().payload.size)
        assertEquals(0x22, report.messages.single().service)
        assertEquals(0, report.incompleteIsoTpMessages)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun reassemblesSameCanIdIsoTpSessionsIndependentlyByDirection() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "can_id,direction,data",
                "7E8,TX,10 08 22 F1 90 AA BB CC",
                "7E8,RX,10 08 62 F1 91 11 22 33",
                "7E8,TX,21 DD EE",
                "7E8,RX,21 44 55"
            ).joinToString("\n"),
            "same-id-directions.csv"
        )

        assertEquals(2, report.messages.size)
        assertEquals(CanTraceDirection.TX, report.messages[0].direction)
        assertEquals(CanTraceDirection.RX, report.messages[1].direction)
        assertEquals(listOf(0x22, 0xF1, 0x90, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE), report.messages[0].payload)
        assertEquals(listOf(0x62, 0xF1, 0x91, 0x11, 0x22, 0x33, 0x44, 0x55), report.messages[1].payload)
        assertTrue(report.errors.isEmpty())
    }

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
    fun decodesFuelTrimAndNarrowBandOxygenSensorPids() {
        val report = CanTraceAnalyzer.analyze(
            listOf("7E8 4 03 41 06 80", "7E8 5 04 41 14 80 40").joinToString("\n"),
            "capture.txt"
        )
        assertEquals(2, report.pidObservations.size)
        assertEquals("Short-term fuel trim Bank 1", report.pidObservations[0].name)
        assertEquals(0.0, report.pidObservations[0].value, 0.0)
        assertEquals("Oxygen sensor 1 voltage", report.pidObservations[1].name)
        assertEquals(0.64, report.pidObservations[1].value, 0.0001)
        assertEquals("V", report.pidObservations[1].unit)
    }

    @Test
    fun decodesWideRangeOxygenSensorAndControlModuleVoltage() {
        val report = CanTraceAnalyzer.analyze(
            listOf("7E8 7 06 41 24 80 00 40 00", "7E8 5 04 41 42 2E E0").joinToString("\n"),
            "capture.txt"
        )
        assertEquals(2, report.pidObservations.size)
        assertEquals("Wide-range oxygen sensor 1 equivalence ratio", report.pidObservations[0].name)
        assertEquals(1.0, report.pidObservations[0].value, 0.0001)
        assertEquals("Control module voltage", report.pidObservations[1].name)
        assertEquals(12.0, report.pidObservations[1].value, 0.0001)
    }

    @Test
    fun keepsTruncatedOxygenSensorResponsesOutOfDecodedValues() {
        val report = CanTraceAnalyzer.analyze("7E8 4 03 41 24 80", "capture.txt")
        assertTrue(report.pidObservations.isEmpty())
        assertEquals(1, report.messages.size)
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
    fun countsPreviousIsoTpSessionWhenASecondFirstFrameReplacesIt() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "7E8 8 10 08 62 F1 90 AA BB CC",
                "7E8 8 10 08 62 F1 91 11 22 33"
            ).joinToString("\n"),
            "replaced-first-frame.txt"
        )

        assertTrue(report.messages.isEmpty())
        assertEquals(2, report.incompleteIsoTpMessages)
        assertTrue(report.errors.any { it.contains("replaced an unfinished ISO-TP message") })
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
    fun reportsIsoTpSequenceMismatchAndDiscardsInvalidPartialMessage() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "7E8 8 10 08 62 F1 90 AA BB CC",
                "7E8 3 22 DD EE"
            ).joinToString("\n"),
            "capture.txt"
        )

        assertTrue(report.messages.isEmpty())
        assertEquals(1, report.incompleteIsoTpMessages)
        assertTrue(report.errors.any { it.contains("ISO-TP sequence mismatch") })
    }

    @Test
    fun recognizesNegativeUdsResponseWithoutInventingDidObservation() {
        val report = CanTraceAnalyzer.analyze(
            "7E8 4 03 7F 22 31",
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

    @Test
    fun parsesFixedWidthCsvByteColumnsUsingDeclaredDlc() {
        val report = CanTraceAnalyzer.analyze(
            "can_id,dlc,data0,data1,data2,data3,data4,data5,data6,data7\n" +
                "7E8,3,02,41,0C,,,,,",
            "capture.csv"
        )

        assertEquals(1, report.frames.size)
        assertEquals(listOf(0x02, 0x41, 0x0C), report.frames.single().data)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun rejectsGapsInsideCsvByteColumns() {
        val report = CanTraceAnalyzer.analyze(
            "can_id,dlc,data0,data1,data2\n7E8,3,02,,0C",
            "capture.csv"
        )

        assertTrue(report.frames.isEmpty())
        assertTrue(report.errors.any { it.contains("invalid CSV CAN frame") })
    }

    @Test
    fun describesStandardNegativeResponseCodesInEnglishAndFrench() {
        assertEquals("requestOutOfRange", UdsNegativeResponse.name(0x31))
        assertEquals("Request out of range", UdsNegativeResponse.description(0x31, "en"))
        assertEquals("Requête hors plage", UdsNegativeResponse.description(0x31, "fr"))
        assertEquals("Request received; response pending", UdsNegativeResponse.description(0x78, "en"))
        assertEquals("Requête reçue ; réponse en attente", UdsNegativeResponse.description(0x78, "fr"))
    }

    @Test
    fun leavesUnknownAndManufacturerSpecificNegativeResponseCodesUnclassified() {
        assertEquals(null, UdsNegativeResponse.name(0xF1))
        assertEquals(
            "Unknown or manufacturer-specific NRC 0xF1",
            UdsNegativeResponse.description(0xF1)
        )
    }

    @Test
    fun decodesIsoTpFlowControlStatusAndSeparationTime() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "7E8 3 30 08 05",
                "7E8 3 31 00 F3",
                "7E8 3 32 04 80",
                "7E8 3 33 01 F0"
            ).joinToString("\n"),
            "capture.txt"
        )

        assertEquals(4, report.flowControlObservations.size)
        assertEquals(IsoTpFlowStatus.CONTINUE_TO_SEND, report.flowControlObservations[0].status)
        assertEquals(8, report.flowControlObservations[0].blockSize)
        assertEquals(5_000, report.flowControlObservations[0].separationTimeMicros)
        assertEquals(IsoTpFlowStatus.WAIT, report.flowControlObservations[1].status)
        assertEquals(300, report.flowControlObservations[1].separationTimeMicros)
        assertEquals(IsoTpFlowStatus.OVERFLOW, report.flowControlObservations[2].status)
        assertEquals(null, report.flowControlObservations[2].separationTimeMicros)
        assertEquals(IsoTpFlowStatus.RESERVED, report.flowControlObservations[3].status)
        assertEquals(null, report.flowControlObservations[3].separationTimeMicros)
        assertTrue(report.messages.isEmpty())
    }
}\n