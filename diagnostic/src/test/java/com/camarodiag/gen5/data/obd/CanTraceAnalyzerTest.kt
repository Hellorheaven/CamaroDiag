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
    fun doesNotCorrelateResponseWhoseTimestampPredatesRequest() {
        val trace = listOf(
            "0.100000 1 7E0 Tx d 8 03 22 F1 90 00 00 00 00",
            "0.050000 1 7E8 Rx d 8 03 7F 22 31 00 00 00 00"
        ).joinToString("\n")
        val report = CanTraceAnalyzer.analyze(trace, "out-of-order-timestamps.asc")

        assertEquals(1, report.exchanges.size)
        assertEquals(UdsExchangeStatus.UNANSWERED, report.exchanges.single().status)
        assertTrue(report.exchanges.single().responses.isEmpty())
    }

    @Test
    fun retainsResponsePendingStatusWhenCaptureEndsAfterNrc78() {
        val trace = listOf(
            "0.000000 1 7E0 Tx d 8 03 22 F1 90 00 00 00 00",
            "0.010000 1 7E8 Rx d 8 03 7F 22 78 00 00 00 00"
        ).joinToString("\n")
        val exchange = CanTraceAnalyzer.analyze(trace, "pending-at-eof.asc").exchanges.single()

        assertEquals(UdsExchangeStatus.RESPONSE_PENDING, exchange.status)
        assertEquals(1, exchange.responses.size)
        assertEquals(0x78, exchange.responses.single().negativeResponseCode)
        assertEquals(10.0, exchange.elapsedMilliseconds!!, 0.001)
    }

    @Test
    fun correlatesStandardObdMode01RequestAndResponse() {
        val trace = listOf(
            "0.000000 1 7E0 Tx d 8 02 01 0C 00 00 00 00 00",
            "0.025000 1 7E8 Rx d 8 04 41 0C 1A F8 00 00 00"
        ).joinToString("\n")
        val report = CanTraceAnalyzer.analyze(trace, "obd-mode01.asc")

        assertEquals(1, report.exchanges.size)
        val exchange = report.exchanges.single()
        assertEquals("ShowCurrentData (OBD-II)", exchange.request.serviceName)
        assertEquals(0x01, exchange.request.service)
        assertEquals(UdsExchangeStatus.COMPLETED, exchange.status)
        assertEquals(0x41, exchange.responses.single().responseService)
        assertEquals(1, report.pidObservations.size)
        assertEquals(1726.0, report.pidObservations.single().value, 0.0)
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
    fun correlatesFinalPositiveResponseAfterNrc78FromSameEcu() {
        val trace = listOf(
            "0.000000 1 7E0 Tx d 8 03 22 F1 90 00 00 00 00",
            "0.010000 1 7E8 Rx d 8 03 7F 22 78 00 00 00 00",
            "0.090000 1 7E8 Rx d 8 04 62 F1 90 01 00 00 00"
        ).joinToString("\n")
        val exchange = CanTraceAnalyzer.analyze(trace, "pending-then-positive.asc").exchanges.single()

        assertEquals(2, exchange.responses.size)
        assertEquals(UdsExchangeStatus.COMPLETED, exchange.status)
        assertEquals(0x62, exchange.responses.last().responseService)
        assertEquals(90.0, exchange.elapsedMilliseconds!!, 0.001)
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
    fun correlatesMode01ResponsesByPidWhenSeveralRequestsArePending() {
        val trace = listOf(
            "0.000000 1 7E0 Tx d 8 02 01 0C 00 00 00 00 00",
            "0.001000 1 7E0 Tx d 8 02 01 0D 00 00 00 00 00",
            "0.025000 1 7E8 Rx d 8 03 41 0D 54 00 00 00 00",
            "0.030000 1 7E8 Rx d 8 03 41 0C 1A F8 00 00 00"
        ).joinToString("\n")
        val report = CanTraceAnalyzer.analyze(trace, "multiple-pids.asc")

        assertEquals(2, report.exchanges.size)
        val byPid = report.exchanges.associateBy { it.request.parameterId }
        assertEquals(setOf(0x0C, 0x0D), byPid.keys)
        assertEquals(UdsExchangeStatus.COMPLETED, byPid[0x0C]?.status)
        assertEquals(UdsExchangeStatus.COMPLETED, byPid[0x0D]?.status)
        assertEquals(0x0C, byPid[0x0C]?.responses?.single()?.parameterId)
        assertEquals(0x0D, byPid[0x0D]?.responses?.single()?.parameterId)
    }

    @Test
    fun recognizesInputOutputControlDidForExchangeCorrelation() {
        val trace = listOf(
            "0.000000 1 7E0 Tx d 8 04 2F F1 90 03 00 00 00",
            "0.025000 1 7E8 Rx d 8 05 6F F1 90 03 AA 00 00"
        ).joinToString("\n")
        val exchange = CanTraceAnalyzer.analyze(trace, "io-control.asc").exchanges.single()

        assertEquals(0xF190, exchange.request.did)
        assertEquals(0xF190, exchange.responses.single().did)
        assertEquals(UdsExchangeStatus.COMPLETED, exchange.status)
    }

    @Test
    fun correlatesDiagnosticSessionResponsesBySubFunctionWhenRequestsOverlap() {
        val trace = listOf(
            "0.000000 1 7E0 Tx d 8 02 10 03 00 00 00 00 00",
            "0.001000 1 7E0 Tx d 8 02 10 02 00 00 00 00 00",
            "0.025000 1 7E8 Rx d 8 02 50 02 00 00 00 00 00",
            "0.030000 1 7E8 Rx d 8 02 50 03 00 00 00 00 00"
        ).joinToString("\n")
        val report = CanTraceAnalyzer.analyze(trace, "overlapping-sessions.asc")

        assertEquals(2, report.exchanges.size)
        val bySubFunction = report.exchanges.associateBy { it.request.subFunction }
        assertEquals(setOf(0x02, 0x03), bySubFunction.keys)
        assertEquals(UdsExchangeStatus.COMPLETED, bySubFunction[0x02]?.status)
        assertEquals(UdsExchangeStatus.COMPLETED, bySubFunction[0x03]?.status)
        assertEquals(0x02, bySubFunction[0x02]?.responses?.single()?.subFunction)
        assertEquals(0x03, bySubFunction[0x03]?.responses?.single()?.subFunction)
    }

    @Test
    fun correlatesRoutineControlResponsesBySubFunctionAndRoutineIdentifier() {
        val trace = listOf(
            "0.000000 1 7E0 Tx d 8 04 31 01 F0 01 00 00 00",
            "0.001000 1 7E0 Tx d 8 04 31 01 F0 02 00 00 00",
            "0.025000 1 7E8 Rx d 8 05 71 01 F0 02 00 00 00",
            "0.030000 1 7E8 Rx d 8 05 71 01 F0 01 00 00 00"
        ).joinToString("\n")
        val report = CanTraceAnalyzer.analyze(trace, "overlapping-routines.asc")

        assertEquals(2, report.exchanges.size)
        val byRoutine = report.exchanges.associateBy { it.request.routineIdentifier }
        assertEquals(setOf(0xF001, 0xF002), byRoutine.keys)
        assertTrue(byRoutine.values.all { it.status == UdsExchangeStatus.COMPLETED })
        assertEquals(0xF001, byRoutine[0xF001]?.responses?.single()?.routineIdentifier)
        assertEquals(0xF002, byRoutine[0xF002]?.responses?.single()?.routineIdentifier)
    }

    @Test
    fun decodesStoredPendingAndPermanentObdTroubleCodesAndIgnoresPadding() {
        val trace = listOf(
            "0.000000 1 7E8 Rx d 8 05 43 01 33 40 23 00 00",
            "0.010000 1 7E8 Rx d 8 03 47 C0 23 00 00 00 00",
            "0.020000 1 7E8 Rx d 8 03 4A 80 45 00 00 00 00"
        ).joinToString("\n")
        val report = CanTraceAnalyzer.analyze(trace, "obd-dtc-modes.asc")

        assertEquals(listOf("P0133", "C0023", "U0023", "B0045"), report.dtcObservations.map { it.code })
        assertEquals(listOf(0x03, 0x03, 0x07, 0x0A), report.dtcObservations.map { it.service })
        assertEquals(listOf(0x01, 0x33), report.dtcObservations.first().rawBytes)
    }

    @Test
    fun doesNotInterpretNonDtcOrNegativeResponsesAsTroubleCodes() {
        val trace = listOf(
            "0.000000 1 7E8 Rx d 8 03 7F 22 31 00 00 00 00",
            "0.010000 1 7E8 Rx d 8 03 41 0C 1A 00 00 00 00"
        ).joinToString("\n")
        val report = CanTraceAnalyzer.analyze(trace, "not-dtc.asc")

        assertTrue(report.dtcObservations.isEmpty())
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
    fun expiresIsoTpMessageWhenConsecutiveFrameArrivesAfterLongTimestampGap() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "0.000000 1 7E8 Rx d 8 10 0F 62 F1 90 AA BB CC",
                "0.800000 1 7E8 Rx d 8 21 01 02 03 04 05 06 07",
                "1.900000 1 7E8 Rx d 8 22 08 09 00 00 00 00 00"
            ).joinToString("\n"),
            "stale-isotp-session.asc"
        )

        assertTrue(report.messages.isEmpty())
        assertEquals(1, report.incompleteIsoTpMessages)
        assertTrue(report.errors.any { it.contains("ISO-TP consecutive frame timed out") })
        assertFalse(report.errors.any { it.contains("consecutive frame has no matching first frame") })
    }

    @Test
    fun refreshesIsoTpConsecutiveFrameTimeoutAfterEachValidFrame() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "0.000000 1 7E8 Rx d 8 10 0F 62 F1 90 AA BB CC",
                "0.800000 1 7E8 Rx d 8 21 01 02 03 04 05 06 07",
                "1.600000 1 7E8 Rx d 8 22 08 09 00 00 00 00 00"
            ).joinToString("\n"),
            "refreshed-isotp-session.asc"
        )

        assertEquals(1, report.messages.size)
        assertEquals(15, report.messages.single().payload.size)
        assertEquals(0, report.incompleteIsoTpMessages)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun singleFrameInterruptsPreviouslyPendingIsoTpMessage() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "7E8 8 10 08 62 F1 90 AA BB CC",
                "7E8 3 02 3E 00",
                "7E8 3 21 DD EE"
            ).joinToString("\n"),
            "interrupted-by-single-frame.txt"
        )

        assertEquals(1, report.messages.size)
        assertEquals(listOf(0x3E, 0x00), report.messages.single().payload)
        assertEquals(1, report.incompleteIsoTpMessages)
        assertTrue(report.errors.any { it.contains("single frame interrupted an unfinished ISO-TP message") })
        assertTrue(report.errors.any { it.contains("consecutive frame has no matching first frame") })
    }

    @Test
    fun malformedFirstFrameInterruptsPreviouslyPendingIsoTpMessage() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "7E8 8 10 08 62 F1 90 AA BB CC",
                "7E8 3 10 00 01",
                "7E8 3 21 DD EE"
            ).joinToString("\n"),
            "interrupted-first-frame.txt"
        )

        assertTrue(report.messages.isEmpty())
        assertEquals(1, report.incompleteIsoTpMessages)
        assertTrue(report.errors.any { it.contains("truncated ISO-TP extended first-frame length") })
        assertTrue(report.errors.any { it.contains("consecutive frame has no matching first frame") })
    }

    @Test
    fun rejectsTruncatedAndExcessiveIsoTpExtendedLengths() {
        val truncated = CanTraceAnalyzer.analyze(
            "can_id,direction,data\n7E8,RX,10 00 00 00",
            "truncated-extended-length.csv"
        )
        val excessive = CanTraceAnalyzer.analyze(
            "can_id,direction,data\n7E8,RX,10 00 00 10 00 01",
            "excessive-extended-length.csv"
        )

        assertTrue(truncated.errors.any { it.contains("truncated ISO-TP extended first-frame length") })
        assertTrue(excessive.errors.any { it.contains("unsupported ISO-TP first-frame length") })
        assertEquals(0, truncated.incompleteIsoTpMessages)
        assertEquals(0, excessive.incompleteIsoTpMessages)
    }

    @Test
    fun reassemblesIsoTpThirtyTwoBitLengthPayloadAcrossSequenceWrap() {
        val payload = listOf(0x62, 0xF1, 0x90) + (3 until 4096).map { it and 0xFF }
        fun encode(bytes: List<Int>): String =
            bytes.joinToString(" ") { value -> value.toString(16).padStart(2, '0') }

        val lines = mutableListOf("can_id,direction,data")
        val firstFrame = listOf(0x10, 0x00, 0x00, 0x00, 0x10, 0x00) + payload.take(58)
        lines += "7E8,RX," + encode(firstFrame)

        var offset = 58
        var sequence = 1
        while (offset < payload.size) {
            val chunk = payload.subList(offset, minOf(offset + 7, payload.size))
            val frame = listOf(0x20 or (sequence and 0x0F)) + chunk + List(7 - chunk.size) { 0x00 }
            lines += "7E8,RX," + encode(frame)
            offset += chunk.size
            sequence = (sequence + 1) and 0x0F
        }

        val report = CanTraceAnalyzer.analyze(lines.joinToString("\n"), "extended-isotp.csv")

        assertEquals(1, report.messages.size)
        assertEquals(payload, report.messages.single().payload)
        assertEquals(0x22, report.messages.single().service)
        assertEquals(0xF190, report.messages.single().did)
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
    fun ignoresTruncatedNegativeUdsResponseWithoutLosingTheValidResponse() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "0.000000 1 7E0 Tx d 8 03 22 F1 90 00 00 00 00",
                "0.005000 1 7E8 Rx d 8 02 7F 22 00 00 00 00 00",
                "0.025000 1 7E8 Rx d 8 04 62 F1 90 AA 00 00 00"
            ).joinToString("\n"),
            "truncated-negative-response.asc"
        )

        assertEquals(1, report.exchanges.size)
        val exchange = report.exchanges.single()
        assertEquals(UdsExchangeStatus.COMPLETED, exchange.status)
        assertEquals(1, exchange.responses.size)
        assertEquals(0x62, exchange.responses.single().responseService)
        assertEquals(null, exchange.responses.single().negativeResponseCode)
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
    fun parsesAllSixtyFourFixedWidthCsvBytesForCanFd() {
        val headers = (listOf("can_id", "dlc") + (0..63).map { "data$it" }).joinToString(",")
        val byteValues = (0..63).map { value -> value.toString(16).padStart(2, '0') }
        val row = (listOf("7E8", "64") + byteValues).joinToString(",")
        val report = CanTraceAnalyzer.analyze("$headers\n$row", "can-fd.csv")

        assertEquals(1, report.frames.size)
        assertEquals((0..63).toList(), report.frames.single().data)
        assertEquals(64, report.frames.single().data.size)
        assertTrue(report.errors.isEmpty())
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
    @Test
    fun decodesAdditionalStandardObdRuntimeDistanceAndFuelPids() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "7E8 5 04 41 1F 01 2C",
                "7E8 5 04 41 21 01 F4",
                "7E8 4 03 41 2F 80",
                "7E8 5 04 41 31 03 E8",
                "7E8 4 03 41 33 64",
                "7E8 5 04 41 4D 00 3C",
                "7E8 5 04 41 4E 00 78"
            ).joinToString("\n"),
            "additional-standard-pids.txt"
        )
        val byPid = report.pidObservations.associateBy { it.pid }

        assertEquals(300.0, byPid[0x1F]?.value!!, 0.0)
        assertEquals("s", byPid[0x1F]?.unit)
        assertEquals(500.0, byPid[0x21]?.value!!, 0.0)
        assertEquals(128.0 * 100.0 / 255.0, byPid[0x2F]?.value!!, 0.0001)
        assertEquals(1000.0, byPid[0x31]?.value!!, 0.0)
        assertEquals(100.0, byPid[0x33]?.value!!, 0.0)
        assertEquals(60.0, byPid[0x4D]?.value!!, 0.0)
        assertEquals(120.0, byPid[0x4E]?.value!!, 0.0)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun decodesStandardCatalystOilAndFuelRatePids() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "7E8 5 04 41 3C 03 E8",
                "7E8 5 04 41 3D 07 D0",
                "7E8 5 04 41 3E 0B B8",
                "7E8 5 04 41 3F 0F A0",
                "7E8 4 03 41 5C 64",
                "7E8 5 04 41 5D 69 80",
                "7E8 5 04 41 5E 01 F4"
            ).joinToString("\n"),
            "catalyst-oil-fuel-pids.txt"
        )
        val byPid = report.pidObservations.associateBy { it.pid }

        assertEquals(60.0, byPid[0x3C]?.value!!, 0.0)
        assertEquals(160.0, byPid[0x3D]?.value!!, 0.0)
        assertEquals(260.0, byPid[0x3E]?.value!!, 0.0)
        assertEquals(360.0, byPid[0x3F]?.value!!, 0.0)
        assertEquals(60.0, byPid[0x5C]?.value!!, 0.0)
        assertEquals(1.0, byPid[0x5D]?.value!!, 0.0)
        assertEquals(25.0, byPid[0x5E]?.value!!, 0.0)
        assertEquals("L/h", byPid[0x5E]?.unit)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun doesNotDecodeAdditionalStandardPidsWhenRequiredBytesAreMissing() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "7E8 4 03 41 1F 01",
                "7E8 4 03 41 5E 10",
                "7E8 4 03 41 3C 01"
            ).joinToString("\n"),
            "truncated-additional-pids.txt"
        )

        assertTrue(report.pidObservations.isEmpty())
        assertEquals(3, report.messages.size)
    }

    @Test
    fun distinguishesThrottleAndAcceleratorPedalPidsAndDecodesEngineTorque() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "7E8 4 03 41 47 80",
                "7E8 4 03 41 48 40",
                "7E8 4 03 41 49 A0",
                "7E8 4 03 41 4A C0",
                "7E8 4 03 41 4B FF",
                "7E8 4 03 41 5A 80",
                "7E8 4 03 41 61 85",
                "7E8 4 03 41 62 7D",
                "7E8 5 04 41 63 01 F4"
            ).joinToString("\n"),
            "throttle-pedal-torque-pids.txt"
        )
        val byPid = report.pidObservations.associateBy { it.pid }

        assertEquals("Absolute throttle position B", byPid[0x47]?.name)
        assertEquals("Absolute throttle position C", byPid[0x48]?.name)
        assertEquals("Accelerator pedal position D", byPid[0x49]?.name)
        assertEquals("Accelerator pedal position E", byPid[0x4A]?.name)
        assertEquals("Accelerator pedal position F", byPid[0x4B]?.name)
        assertEquals("Relative accelerator pedal position", byPid[0x5A]?.name)
        assertEquals(8.0, byPid[0x61]?.value!!, 0.0)
        assertEquals(0.0, byPid[0x62]?.value!!, 0.0)
        assertEquals(500.0, byPid[0x63]?.value!!, 0.0)
        assertEquals("Nm", byPid[0x63]?.unit)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun rejectsMalformedDeclaredCsvDlcInsteadOfInferringLength() {
        val report = CanTraceAnalyzer.analyze(
            "can_id,dlc,data\n7E8,invalid,02 41 0C",
            "invalid-dlc.csv"
        )

        assertTrue(report.frames.isEmpty())
        assertTrue(report.errors.any { it.contains("invalid CSV CAN frame") })
    }

    @Test
    fun rejectsMalformedAndNonFiniteCsvTimestamps() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "timestamp,can_id,direction,data",
                "NaN,7E8,RX,02 41 0C",
                "Infinity,7E8,RX,02 41 0C",
                "not-a-time,7E8,RX,02 41 0C"
            ).joinToString("\n"),
            "invalid-timestamps.csv"
        )

        assertTrue(report.frames.isEmpty())
        assertEquals(3, report.errors.size)
    }

    @Test
    fun allowsAnEmptyOptionalCsvTimestampAsUnknown() {
        val report = CanTraceAnalyzer.analyze(
            "timestamp,can_id,direction,data\n,7E8,RX,02 41 0C",
            "missing-timestamp.csv"
        )

        assertEquals(1, report.frames.size)
        assertEquals(null, report.frames.single().timestampSeconds)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun rejectsNonFiniteAscTimestampsAndAcceptsScientificNotation() {
        val trace = listOf(
            "NaN 1 7E8 Rx d 8 03 41 0C 00 00 00 00",
            "Infinity 1 7E8 Rx d 8 03 41 0C 00 00 00 00",
            "1.25e-3 1 7E8 Rx d 8 03 41 0C 00 00 00 00 00"
        ).joinToString("\n")

        val report = CanTraceAnalyzer.analyze(trace, "timestamp-validation.asc")

        assertEquals(1, report.frames.size)
        assertEquals(0.00125, report.frames.single().timestampSeconds!!, 0.0000001)
        assertEquals(2, report.errors.size)
    }


    @Test
    fun rejectsTraceFramesContainingMoreBytesThanDeclaredDlc() {
        val elm = CanTraceAnalyzer.analyze("7E8 2 02 41 0C", "capture.txt")
        val asc = CanTraceAnalyzer.analyze(
            "0.000000 1 7E8 Rx d 2 02 41 0C",
            "capture.asc"
        )
        val csv = CanTraceAnalyzer.analyze(
            "can_id,dlc,data\n7E8,2,02 41 0C",
            "capture.csv"
        )

        assertTrue(elm.frames.isEmpty())
        assertTrue(asc.frames.isEmpty())
        assertTrue(csv.frames.isEmpty())
        assertTrue(elm.errors.isNotEmpty())
        assertTrue(asc.errors.isNotEmpty())
        assertTrue(csv.errors.isNotEmpty())
    }


    @Test
    fun rejectsFixedWidthCsvBytesBeyondDeclaredDlc() {
        val report = CanTraceAnalyzer.analyze(
            "can_id,dlc,data0,data1,data2\n7E8,2,02,41,0C",
            "fixed-width-extra-byte.csv"
        )

        assertTrue(report.frames.isEmpty())
        assertTrue(report.errors.any { it.contains("invalid CSV CAN frame") })
    }


    @Test
    fun rejectsIsoTpFirstFrameWhenPayloadFitsSingleFrame() {
        val report = CanTraceAnalyzer.analyze(
            "7E8 8 10 06 62 F1 90 41 42 43",
            "invalid-first-frame.txt"
        )

        assertTrue(report.messages.isEmpty())
        assertEquals(0, report.incompleteIsoTpMessages)
        assertTrue(report.errors.any { it.contains("unsupported ISO-TP first-frame length") })
    }

    @Test
    fun acceptsIsoTpFirstFrameWhenPayloadExceedsSingleFrameCapacity() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "7E8 8 10 08 62 F1 90 41 42 43",
                "7E8 8 21 44 00 00 00 00 00 00"
            ).joinToString("\n"),
            "valid-first-frame.txt"
        )

        assertEquals(1, report.messages.size)
        assertEquals(listOf(0x62, 0xF1, 0x90, 0x41, 0x42, 0x43, 0x44, 0x00), report.messages.single().payload)
        assertEquals(0, report.incompleteIsoTpMessages)
    }


    @Test
    fun doesNotCorrelatePhysicalRequestWithUnrelatedEcuResponseId() {
        val trace = listOf(
            "0.000000 1 7E0 Tx d 8 03 22 F1 90 00 00 00 00",
            "0.025000 1 7E9 Rx d 8 07 62 F1 90 31 47 30 31"
        ).joinToString("\n")
        val exchange = CanTraceAnalyzer.analyze(trace, "unrelated-response-id.asc").exchanges.single()

        assertEquals(UdsExchangeStatus.UNANSWERED, exchange.status)
        assertTrue(exchange.responses.isEmpty())
    }

    @Test
    fun correlatesFunctionalObdRequestWithStandardEcuResponseRange() {
        val trace = listOf(
            "0.000000 1 7DF Tx d 8 02 01 0C 00 00 00 00 00",
            "0.025000 1 7E8 Rx d 8 04 41 0C 1A F8 00 00 00"
        ).joinToString("\n")
        val exchange = CanTraceAnalyzer.analyze(trace, "functional-obd-response.asc").exchanges.single()

        assertEquals(UdsExchangeStatus.COMPLETED, exchange.status)
        assertEquals(0x7E8, exchange.responses.single().canId)
    }


    @Test
    fun aggregatesResponsesFromMultipleEcusToOneFunctionalObdRequest() {
        val trace = listOf(
            "0.000000 1 7DF Tx d 8 02 01 0C 00 00 00 00 00",
            "0.025000 1 7E8 Rx d 8 04 41 0C 1A F8 00 00 00",
            "0.030000 1 7E9 Rx d 8 04 41 0C 1A F0 00 00 00"
        ).joinToString("\n")
        val report = CanTraceAnalyzer.analyze(trace, "functional-multiple-ecus.asc")

        assertEquals(1, report.exchanges.size)
        val exchange = report.exchanges.single()
        assertEquals(UdsExchangeStatus.COMPLETED, exchange.status)
        assertEquals(listOf(0x7E8, 0x7E9), exchange.responses.map { it.canId })
        assertEquals(30.0, exchange.elapsedMilliseconds!!, 0.001)
    }


    @Test
    fun discardsIsoTpMessageWhenConsecutiveFrameTimestampMovesBackwards() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "0.500000 1 7E8 Rx d 8 10 0F 62 F1 90 AA BB CC",
                "0.400000 1 7E8 Rx d 8 21 01 02 03 04 05 06 07",
                "0.600000 1 7E8 Rx d 8 22 08 09 00 00 00 00 00"
            ).joinToString("\n"),
            "out-of-order-isotp.asc"
        )

        assertTrue(report.messages.isEmpty())
        assertEquals(1, report.incompleteIsoTpMessages)
        assertTrue(report.errors.any { it.contains("timestamp moved backwards") })
        assertTrue(report.errors.any { it.contains("no matching first frame") })
    }


    @Test
    fun acceptsIsoTpFlowControlWithStandardAndSubMillisecondStmin() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "0.000000 1 7E8 Rx d 8 30 00 00 00 00 00 00 00",
                "0.001000 1 7E8 Rx d 8 31 08 F1 00 00 00 00 00",
                "0.002000 1 7E8 Rx d 8 32 00 F9 00 00 00 00 00"
            ).joinToString("\n"),
            "valid-flow-control.asc"
        )

        assertTrue(report.errors.isEmpty())
        assertTrue(report.messages.isEmpty())
    }

    @Test
    fun reportsMalformedIsoTpFlowControlFrames() {
        val report = CanTraceAnalyzer.analyze(
            listOf(
                "0.000000 1 7E8 Rx d 8 33 00 00 00 00 00 00 00",
                "0.001000 1 7E8 Rx d 8 30 00 80 00 00 00 00 00",
                "0.002000 1 7E8 Rx d 8 30 00 FA 00 00 00 00 00",
                "0.003000 1 7E8 Rx d 2 30 00"
            ).joinToString("\n"),
            "invalid-flow-control.asc"
        )

        assertEquals(4, report.errors.size)
        assertTrue(report.errors.all { it.contains("invalid ISO-TP flow-control frame") })
        assertTrue(report.messages.isEmpty())
    }

    @Test
    fun opensCsvWithBomBeforeCanId() {
        val report = CanTraceAnalyzer.analyze("\uFEFFcan_id,data\n7E8,02 41 00", "capture.csv")
        assertEquals(0x7E8, report.frames.single().canId)
    }
    @Test
    fun readsVectorExtendedIdSuffix() {
        val report = CanTraceAnalyzer.analyze("0.0 1 18DAF110x Rx d 3 02 41 00")
        assertEquals(0x18DAF110, report.frames.single().canId)
    }
    @Test
    fun reordersIndexedBytesAndRejectsMissingOrDuplicateIndices() {
        fun read(columns: String) = CanTraceAnalyzer.analyze("id,$columns\n7E8,41,02,00", "indexed.csv")
        assertEquals(listOf(2, 0x41, 0), read("data1,data0,data2").frames.single().data)
        assertTrue(read("byte1,data1,data2").frames.isEmpty())
        assertTrue(read("byte0,byte2,byte3").frames.isEmpty())
    }
    @Test
    fun readsDecimalCommaTimestampsInSemicolonCsv() {
        val report = CanTraceAnalyzer.analyze("time;id;data\n1,25;7E8;02 41 00", "capture.csv")
        assertEquals(1.25, report.frames.single().timestampSeconds!!, 0.0)
    }
    @Test
    fun rejectsMalformedIndexedCsvBytes() {
        val report = CanTraceAnalyzer.analyze("id,data0\n7E8,+1\n7E8,000\n7E8,0x0F", "bytes.csv")
        assertEquals(listOf(15), report.frames.single().data)
    }
    @Test
    fun ignoresUnterminatedCsvRowAndContinues() {
        val report = CanTraceAnalyzer.analyze("id,data\n7E8,\"02 41 00\n7E9,02 41 01", "capture.csv")
        assertEquals(0x7E9, report.frames.single().canId)
        assertTrue(report.errors.any { it.startsWith("Line 2:") })
    }
}
