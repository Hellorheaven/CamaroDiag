package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class CanConsoleLiveDiagnosticCorrelationTest {
    private fun frame(input: String) = requireNotNull(RawCanFrameParser.parse(input))

    @Test fun genuinePhysicalMultiframeResponseWithinDeadlineIsAccepted() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 03 22 F1 90"), 500)
        correlation.append("7E9 08 10 0A 62 F1 90 31 32 33\r\n", 100)
        correlation.append("7E8 08 10 0A 62 F1 90 31 32 33\r", 110)
        correlation.append("\n7E9 05 21 34 35 36 37\r\n", 120)
        correlation.append("7E8 05 21 34 35 36 37\r\n", 130)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(131))
    }

    @Test fun lateIsoTpContinuationNeverBecomesAnEcuResult() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 03 22 F1 90"), 200)
        correlation.append("7E8 08 10 0A 62 F1 90 31 32 33\r\n", 10)
        correlation.append("7E8 05 21 34 35 36 37\r\n", 210)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(211))
    }

    @Test fun extendedFunctionalBroadcastCorrelatesEachResponderIndependently() {
        val request = frame("18DB33F1 03 22 F1 90")
        val correlation = CanConsoleLiveDiagnosticCorrelation(request)
        correlation.append("18DAF110 08 10 0A 62 F1 90 31 32 33\r\n", 10L)
        correlation.append("18DAF111 05 21 34 35 36 37\r\n", 20L)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(21L))
        correlation.append("18DAF110 05 21 34 35 36 37\r\n", 30L)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(31L))
        assertEquals(3, correlation.recentReceivedRecords().size)
        assertFalse(CanConsoleAdapterReplyPolicy.hasMatchedDiagnosticResponse(
            "18DAF110 08 10 0A 62 F1 90 31 32 33\r\n18DAF111 05 21 34 35 36 37", request))
        assertTrue(CanConsoleAdapterReplyPolicy.hasMatchedDiagnosticResponse(
            "18DAF110 04 03 62 F1 90", request))
    }

    @Test fun rawNonDiagnosticFramesKeepRxWithoutCreatingDiagnosticMatchers() {
        val request = frame("7E0 10 01 02 03 04 05 06 07")
        val correlation = CanConsoleLiveDiagnosticCorrelation(request)
        correlation.append("7E8 04 03 62 F1 90\r\nOK\r\n", 20L)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(21L))
        assertTrue(correlation.hasExplicitAdapterAck())
        assertEquals(2, correlation.recentReceivedRecords().size)
    }

    @Test fun functionalResponsesAreAssembledIndependentlyPerEcu() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7DF 03 22 F1 90"))
        correlation.append("7E8 08 10 0A 62 F1 90 31 32 33\r\n", 100)
        correlation.append("7E9 05 21 34 35 36 37\r\n", 110)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(120))
        val second = CanConsoleLiveDiagnosticCorrelation(frame("7DF 03 22 F1 90"))
        second.append("7E8 08 10 0A 62 F1 90 31 32 33\r\n", 100)
        second.append("7E9 05 21 34 35 36 37\r\n", 110)
        second.append("7E8 05 21 34 35 36 37\r\n", 120)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, second.complete(130))
    }

    @Test fun negativeEcuReplyCannotBePromotedToPositiveStatus() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 03 22 F1 90"))
        correlation.append("OK\r\n7E8 04 03 7F 22 31\r\n", 100)
        assertEquals(CanConsoleDiagnosticStatus.NEGATIVE, correlation.complete(101))
    }

    @Test fun allAdapterFramesAreDecodedBeforeTheUiWindowIsBounded() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        val stream = (0 until 350).joinToString("\r\n") { index ->
            if (index % 2 == 0) "7E8 04 03 41 0C 1A" else "7E9 04 03 41 0C 1B"
        } + "\r\n"
        correlation.append(stream, 50L)
        val recent = correlation.recentReceivedRecords()
        assertEquals(300, recent.size)
        assertEquals(300, recent.count { it.record.direction == "RX" })
        assertEquals("CAN ID=7E8 DLC=4 DATA=03 41 0C 1A", recent.first().record.content)
        assertEquals("CAN ID=7E9 DLC=4 DATA=03 41 0C 1B", recent.last().record.content)
        assertEquals(50L, recent.first().timestampMillis)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(51L))
    }

    @Test fun adapterAckBeyondOneKilobyteIsStillDetectedFromFullStream() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        val chatter = (0 until 120).joinToString("\r\n") {
            "7E9 04 03 41 0D 1A"
        } + "\r\nOK\r\n"
        assertEquals(true, chatter.length > 1024)
        correlation.append(chatter, 200L)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(201L))
        assertEquals(true, correlation.hasExplicitAdapterAck())
        assertEquals(false, correlation.hasAdapterError())
    }

    @Test fun adapterErrorAfterAckOverridesPhysicalTransmissionSuccess() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        correlation.append("OK\r\nCAN ERROR\r\n", 100L)
        correlation.complete(101L)
        assertEquals(true, correlation.hasAdapterError())
        assertEquals(false, correlation.hasExplicitAdapterAck())
    }

    @Test fun independentPhysicalReceiveChunksKeepTheirActualHostTimestamps() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        correlation.append("7E9 04 03 41 0C 1B\r\n", 10L, 1_800_000_000_010L)
        correlation.append("7E8 04 03 41 0C 1A\r\n", 20L, 1_800_000_000_020L)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(30L))
        assertEquals(listOf(1_800_000_000_010L, 1_800_000_000_020L),
            correlation.recentReceivedRecords().map { it.timestampMillis })
        assertEquals(listOf("RX", "RX"),
            correlation.recentReceivedRecords().map { it.record.direction })
    }

    @Test fun failedPhysicalTxKeepsAllObservedEcusWithoutClaimingDiagnosticSuccess() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        correlation.append("7E9 04 03 41 0C 1B\r\n", 50L, 1_800_000_000_050L)
        correlation.append("7E8 04 03 41 0D 1A\r\n", 60L, 1_800_000_000_060L)
        val failure = CanConsolePhysicalExchangeException(
            correlation.recentReceivedRecords(),
            java.net.SocketTimeoutException("ELM prompt timeout")
        )
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(61L))
        assertEquals(2, failure.receivedRecords.size)
        assertEquals(
            listOf("CAN ID=7E9 DLC=4 DATA=03 41 0C 1B", "CAN ID=7E8 DLC=4 DATA=03 41 0D 1A"),
            failure.receivedRecords.map { it.record.content }
        )
        assertEquals(listOf(1_800_000_000_050L, 1_800_000_000_060L),
            failure.receivedRecords.map { it.timestampMillis })
        assertEquals(false, ObdTransportFailurePolicy.isConnectionLoss(failure))
    }

    @Test fun interruptedIsoTpRetainsVisibleForeignRxButNotAValidatedResult() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("18DA10F1 03 22 F1 90"))
        correlation.append("18DAF111 08 10 0A 62 F1 90 31 32 33\r\n", 10L)
        correlation.append("18DAF110 08 10 0A 62 F1 90 31 32 33\r\n", 20L)
        correlation.append("18DAF111 05 21 34 35 36 37\r\n", 30L)
        val failure = CanConsolePhysicalExchangeException(
            correlation.recentReceivedRecords(), java.io.EOFException("connection lost")
        )
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(31L))
        assertEquals(3, failure.receivedRecords.size)
        assertEquals(
            listOf("18DAF111", "18DAF110", "18DAF111"),
            failure.receivedRecords.map { it.record.content.substringAfter("CAN ID=").substringBefore(" ") }
        )
        assertEquals(true, ObdTransportFailurePolicy.isConnectionLoss(failure))
    }

    @Test fun cancelledExchangeCarriesVisibleRxWithoutClaimingSuccess() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        correlation.append("7E9 04 03 41 0C 1B\r\n", 10L, 1_800_000_000_010L)
        val cancellation = CanConsolePhysicalExchangeCancelled(
            correlation.recentReceivedRecords(),
            java.util.concurrent.CancellationException("cancelled by user")
        )
        assertEquals(1, cancellation.receivedRecords.size)
        assertEquals("RX", cancellation.receivedRecords.single().record.direction)
        assertEquals("CAN ID=7E9 DLC=4 DATA=03 41 0C 1B",
            cancellation.receivedRecords.single().record.content)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(11L))
        assertEquals(true,
            java.util.concurrent.CancellationException::class.java.isInstance(cancellation))
        assertEquals(true, cancellation.cause is java.util.concurrent.CancellationException)
    }

    @Test fun adapterErrorDoesNotEraseOtherEcuFramesInFailedExchangeHistory() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        correlation.append("7E9 04 03 41 0C 1B\r\nOK\r\nCAN ERROR\r\n", 100L)
        val failure = CanConsolePhysicalExchangeException(
            correlation.recentReceivedRecords(),
            IllegalStateException("adapter reported CAN ERROR")
        )
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(101L))
        assertEquals(true, correlation.hasAdapterError())
        assertEquals(false, correlation.hasExplicitAdapterAck())
        assertEquals(listOf("RX", "SYSTEM", "SYSTEM"),
            failure.receivedRecords.map { it.record.direction })
        assertEquals("CAN ID=7E9 DLC=4 DATA=03 41 0C 1B",
            failure.receivedRecords.first().record.content)
        assertEquals(false, failure.receivedRecords.last().record.succeeded)
    }

    @Test fun timeoutPreservesFinalCompleteCanLineWithoutCrLf() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        correlation.append("7E9 04 03 41 0C 1B", 100L, 1_800_000_000_100L)
        assertEquals(0, correlation.recentReceivedRecords().size)
        // On timeout the physical manager drains the final fragment before
        // creating the failure; the TX remains failed regardless of RX content.
        correlation.complete(101L, 1_800_000_000_101L)
        val error = CanConsolePhysicalExchangeException(
            correlation.recentReceivedRecords(),
            java.net.SocketTimeoutException("prompt timeout")
        )
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(102L))
        assertEquals(1, error.receivedRecords.size)
        assertEquals("RX", error.receivedRecords.single().record.direction)
        assertEquals("CAN ID=7E9 DLC=4 DATA=03 41 0C 1B",
            error.receivedRecords.single().record.content)
        assertEquals(1_800_000_000_100L, error.receivedRecords.single().timestampMillis)
    }

    @Test fun physicalCanTxConfirmationNeverUsesForeignEcuRxAsSuccess() {
        fun confirmed(
            output: String, status: CanConsoleDiagnosticStatus,
            ack: Boolean = false, error: Boolean = false
        ): Boolean = CanConsolePhysicalTransmissionPolicy.confirmed(output, status, ack, error)

        assertEquals(true, confirmed("OK", CanConsoleDiagnosticStatus.NONE, ack = true))
        // A small command-return buffer may be blank even if the physical RX
        // stream has confirmed an ECU reply or explicit adapter acknowledgement.
        assertEquals(true, confirmed("", CanConsoleDiagnosticStatus.POSITIVE))
        assertEquals(true, confirmed("", CanConsoleDiagnosticStatus.NONE, ack = true))
        assertEquals(false, confirmed("", CanConsoleDiagnosticStatus.NONE))
        assertEquals(false, confirmed("CAN ERROR", CanConsoleDiagnosticStatus.POSITIVE))
        assertEquals(true, confirmed("7E8 04 03 41 0C 1A", CanConsoleDiagnosticStatus.POSITIVE))
        assertEquals(true, confirmed("7E8 04 03 7F 22 31", CanConsoleDiagnosticStatus.NEGATIVE))
        assertEquals(false, confirmed("7E9 04 03 41 0C 1B", CanConsoleDiagnosticStatus.NONE))
        assertEquals(false, confirmed("SEARCHING...", CanConsoleDiagnosticStatus.NONE))
        assertEquals(false, confirmed("NO DATA", CanConsoleDiagnosticStatus.NONE, ack = true))
        assertEquals(false, confirmed("OK\r\nCAN ERROR",
            CanConsoleDiagnosticStatus.POSITIVE, ack = true, error = true))
        // A late CAN ERROR outside a 1024-char reply prefix is authoritative.
        assertEquals(false, confirmed("OK",
            CanConsoleDiagnosticStatus.POSITIVE, ack = true, error = true))
    }

    @Test fun failedExchangeRetainsOnlyBoundedLatestForeignEcuFrames() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        val traffic = (0 until 640).joinToString("\r\n") { index ->
            if (index % 2 == 0) "7E9 04 03 41 0C 1B" else "18DAF111 04 03 62 F1 90"
        } + "\r\n"
        correlation.append(traffic, 100L)
        val error = CanConsolePhysicalExchangeException(
            correlation.recentReceivedRecords(), java.net.SocketTimeoutException("prompt timeout")
        )
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(101L))
        assertEquals(300, error.receivedRecords.size)
        assertEquals(300, error.receivedRecords.count { it.record.direction == "RX" })
        assertEquals("CAN ID=7E9 DLC=4 DATA=03 41 0C 1B",
            error.receivedRecords.first().record.content)
        assertEquals("CAN ID=18DAF111 DLC=4 DATA=03 62 F1 90",
            error.receivedRecords.last().record.content)
    }

    @Test fun wrappedCanTimeoutIsDistinctFromPhysicalAdapterLoss() {
        val direct = CanConsolePhysicalExchangeException(
            emptyList(), java.net.SocketTimeoutException("adapter prompt timeout")
        )
        val nested = CanConsolePhysicalExchangeException(
            emptyList(),
            IllegalStateException("ELM command failed",
                java.net.SocketTimeoutException("response timed out"))
        )
        val disconnected = CanConsolePhysicalExchangeException(
            emptyList(), java.io.EOFException("adapter disconnected")
        )
        assertEquals(true, direct.isResponseTimeout())
        assertEquals(true, nested.isResponseTimeout())
        assertEquals(false, disconnected.isResponseTimeout())
        assertEquals(false, ObdTransportFailurePolicy.isConnectionLoss(direct))
        assertEquals(false, ObdTransportFailurePolicy.isConnectionLoss(nested))
        assertEquals(true, ObdTransportFailurePolicy.isConnectionLoss(disconnected))
    }

    @Test fun lateSingleFrameResponseStaysVisibleButCannotCompleteExpiredPhysicalRequest() {
        val request = frame("7E0 02 01 0C")
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            request, requestStartedAtMs = 1_000L, requestDeadlineMs = 400L
        )
        correlation.append("7E9 04 03 41 0C 1B\r\n", 1_100L)
        correlation.append("7E8 04 03 41 0C 1A\r\n", 1_401L)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(1_402L))
        assertEquals(2, correlation.recentReceivedRecords().size)
        assertEquals(listOf("RX", "RX"),
            correlation.recentReceivedRecords().map { it.record.direction })
    }

    @Test fun lateMultiFrameContinuationCannotCompleteExpiredPhysicalRequest() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("18DA10F1 03 22 F1 90"),
            timeoutMs = 2_000L,
            requestStartedAtMs = 100L,
            requestDeadlineMs = 500L
        )
        correlation.append("18DAF110 08 10 0A 62 F1 90 31 32 33\r\n", 200L)
        correlation.append("18DAF110 05 21 34 35 36 37\r\n", 600L)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(601L))
        assertEquals(2, correlation.recentReceivedRecords().size)
    }

    @Test fun requestDeadlineCoversPhysicalElevenAndTwentyNineBitReplies() {
        val eleven = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requestStartedAtMs = 200L, requestDeadlineMs = 500L
        )
        eleven.append("7E8 04 03 41 0C 1A\r\n", 699L)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, eleven.complete(700L))
        val twentyNine = CanConsoleLiveDiagnosticCorrelation(
            frame("18DA10F1 03 22 F1 90"), requestStartedAtMs = 200L, requestDeadlineMs = 500L
        )
        twentyNine.append("18DAF110 04 03 62 F1 90\r\n", 201L)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, twentyNine.complete(202L))
    }

    @Test fun diagnosticDeadlineRejectsClockReversalAndReinitialization() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), timeoutMs = 100L, requestDeadlineMs = 400L
        )
        correlation.beginRequest(1_000L)
        correlation.append("7E8 04 03 41 0C 1A\r\n", 999L)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(1_001L))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            correlation.beginRequest(1_050L)
        }
    }

    @Test fun untimedAdapterTextCannotBypassExpiredPhysicalSession() {
        val late = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requestStartedAtMs = 100L, requestDeadlineMs = 300L
        )
        late.append("7E9 04 03 41 0C 1B\r\n", 110L)
        late.append("OK\r\n", 400L)
        val diagnostic = late.complete(401L)
        assertEquals(CanConsoleDiagnosticStatus.NONE, diagnostic)
        assertEquals(false, late.hasExplicitAdapterAck())
        assertEquals(false, CanConsolePhysicalTransmissionPolicy.confirmed(
            adapterOutput = "OK", diagnosticStatus = diagnostic,
            hasExplicitAck = late.hasExplicitAdapterAck(), hasAdapterError = late.hasAdapterError()
        ))
        assertEquals(listOf("RX", "SYSTEM"),
            late.recentReceivedRecords().map { it.record.direction })
    }

    @Test fun timelyAdapterAckStillConfirmsPhysicalTransmissionWithoutEcuReply() {
        val timely = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requestStartedAtMs = 100L, requestDeadlineMs = 300L
        )
        timely.append("OK\r\n", 399L)
        assertEquals(CanConsoleDiagnosticStatus.NONE, timely.complete(400L))
        assertEquals(true, timely.hasExplicitAdapterAck())
        assertEquals(true, CanConsolePhysicalTransmissionPolicy.confirmed(
            adapterOutput = "OK", diagnosticStatus = CanConsoleDiagnosticStatus.NONE,
            hasExplicitAck = timely.hasExplicitAdapterAck(), hasAdapterError = timely.hasAdapterError()
        ))
        assertEquals(false, CanConsolePhysicalTransmissionPolicy.confirmed(
            adapterOutput = "OK", diagnosticStatus = CanConsoleDiagnosticStatus.NONE,
            hasExplicitAck = false, hasAdapterError = false
        ))
    }

    @Test fun expiredNegativeAndPendingUdsRepliesRemainRxOnly() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 03 22 F1 90"), requestStartedAtMs = 100L, requestDeadlineMs = 500L
        )
        correlation.append("7E8 04 03 7F 22 78\r\n", 200L)
        correlation.append("7E8 04 03 7F 22 31\r\n", 600L)
        correlation.append("7E8 04 03 62 F1 90\r\n", 601L)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(602L))
        assertEquals(3, correlation.recentReceivedRecords().size)
        assertEquals(3, correlation.recentReceivedRecords().count { it.record.direction == "RX" })
    }

    @Test fun functionalResponderBeforeDeadlineWinsWithoutClaimingLateForeignEcu() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7DF 02 01 0C"), requestStartedAtMs = 10L, requestDeadlineMs = 200L
        )
        correlation.append("7E8 04 03 41 0C 1A\r\n", 209L)
        correlation.append("7E9 04 03 41 0C 1B\r\n", 210L)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(211L))
        assertEquals(2, correlation.recentReceivedRecords().size)
        assertEquals(listOf("CAN ID=7E8 DLC=4 DATA=03 41 0C 1A",
                "CAN ID=7E9 DLC=4 DATA=03 41 0C 1B"),
            correlation.recentReceivedRecords().map { it.record.content })
    }

    @Test fun physicalSessionRejectsPreTxAckAndEcuResponseButKeepsGlobalRx() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requireExplicitStart = true
        )
        correlation.append("OK\r\n7E8 04 03 41 0C 1A\r\n", 900L)
        assertEquals(false, correlation.hasExplicitAdapterAck())
        assertEquals(1, correlation.recentReceivedRecords().count { it.record.direction == "RX" })
        correlation.beginRequest(1_000L)
        correlation.append("7E9 04 03 41 0C 1B\r\n", 1_001L)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(1_002L))
        assertEquals(3, correlation.recentReceivedRecords().size)
        assertEquals(false, CanConsolePhysicalTransmissionPolicy.confirmed(
            adapterOutput = "OK", diagnosticStatus = correlation.complete(1_003L),
            hasExplicitAck = correlation.hasExplicitAdapterAck(),
            hasAdapterError = correlation.hasAdapterError()
        ))
    }

    @Test fun physicalSessionOnlyAcceptsAckOrResponseAfterBeginRequest() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requireExplicitStart = true
        )
        correlation.append("7E8 04 03 41 0C 1A\r\n", 990L)
        correlation.beginRequest(1_000L)
        correlation.append("OK\r\n7E8 04 03 41 0C 1A\r\n", 1_001L)
        assertEquals(true, correlation.hasExplicitAdapterAck())
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(1_002L))
        assertEquals(2, correlation.recentReceivedRecords().count { it.record.direction == "RX" })
    }

    @Test fun partialPreTxFrameCannotBeCompletedByPostTxBytes() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requireExplicitStart = true
        )
        correlation.append("7E8 04 03 41", 990L)
        correlation.beginRequest(1_000L)
        correlation.append(" 0C 1A\r\n", 1_001L)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(1_002L))
        assertEquals(0, correlation.recentReceivedRecords().count { it.record.direction == "RX" })
    }

    @Test fun partialPreTxOkCannotBeReusedAsFreshAdapterAcknowledgement() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requireExplicitStart = true
        )
        correlation.append("OK", 990L)
        correlation.beginRequest(1_000L)
        correlation.append("\r\n7E9 04 03 41 0C 1B\r\n", 1_001L)
        assertEquals(false, correlation.hasExplicitAdapterAck())
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(1_002L))
        assertEquals(1, correlation.recentReceivedRecords().count { it.record.direction == "RX" })
    }


    @Test fun extendedCanIdWithSameNumericValueCannotConfirmStandardPhysicalRequest() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requireExplicitStart = true
        )
        correlation.beginRequest(1_000L)
        // Same numeric identifier, different CAN frame format: not ECU 7E8.
        correlation.append("000007E8 04 03 41 0C 1A\r\n", 1_010L)
        correlation.append("7E9 04 03 41 0C 1B\r\n", 1_020L)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(1_021L))
        assertEquals(2, correlation.recentReceivedRecords().count { it.record.direction == "RX" })
        // A genuine standard-ID ECU 7E8 reply may arrive in separate chunks.
        correlation.append("7E8 04 03 41", 1_030L)
        correlation.append(" 0C 1A\r", 1_040L)
        correlation.append("\n", 1_041L)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(1_042L))
        assertEquals(3, correlation.recentReceivedRecords().count { it.record.direction == "RX" })
    }

    @Test fun trailingRxUsesLastChunkTimestampEvenWhenCommandFinishesAfterDeadline() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requestStartedAtMs = 1_000L,
            requestDeadlineMs = 500L
        )
        correlation.append("7E8 04 03 41", 1_200L, 1_800_000_001_200L)
        correlation.append(" 0C 1A", 1_300L, 1_800_000_001_300L)
        // The adapter returns the command later, but all bytes had been
        // physically received inside the allowed response window.
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(
            1_700L, 1_800_000_001_700L
        ))
        val received = correlation.recentReceivedRecords().single()
        assertEquals("RX", received.record.direction)
        assertEquals(1_800_000_001_300L, received.timestampMillis)
    }

    @Test fun trailingRxActuallyReceivedAfterDeadlineStaysVisibleWithoutConfirmation() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requestStartedAtMs = 1_000L,
            requestDeadlineMs = 500L
        )
        correlation.append("7E8 04 03 41 0C 1A", 1_500L, 1_800_000_001_500L)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(
            1_700L, 1_800_000_001_700L
        ))
        assertEquals(1_800_000_001_500L,
            correlation.recentReceivedRecords().single().timestampMillis)
    }

    @Test fun preTransmitAdapterErrorRemainsInHistoryWithoutPoisoningNewTx() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requireExplicitStart = true
        )
        correlation.append("CAN ERROR\r\n", 990L)
        assertEquals(false, correlation.hasAdapterError())
        assertEquals(false, correlation.hasExplicitAdapterAck())
        assertEquals(listOf("SYSTEM"),
            correlation.recentReceivedRecords().map { it.record.direction })
        assertEquals(false, correlation.recentReceivedRecords().single().record.succeeded)

        correlation.beginRequest(1_000L)
        correlation.append("OK\r\n7E8 04 03 41 0C 1A\r\n", 1_010L)
        val diagnostic = correlation.complete(1_011L)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, diagnostic)
        assertEquals(false, correlation.hasAdapterError())
        assertEquals(true, correlation.hasExplicitAdapterAck())
        assertEquals(true, CanConsolePhysicalTransmissionPolicy.confirmed(
            "OK", diagnostic, correlation.hasExplicitAdapterAck(), correlation.hasAdapterError()
        ))
        assertEquals(listOf("SYSTEM", "SYSTEM", "RX"),
            correlation.recentReceivedRecords().map { it.record.direction })
    }

    @Test fun adapterErrorAfterPhysicalTxAlwaysOverridesEarlierAckAndEcuReply() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requireExplicitStart = true
        )
        correlation.beginRequest(1_000L)
        correlation.append("OK\r\n7E8 04 03 41 0C 1A\r\n", 1_100L)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(1_101L))
        correlation.append("CAN ERROR\r\n", 5_100L)
        assertEquals(true, correlation.hasAdapterError())
        assertEquals(false, correlation.hasExplicitAdapterAck())
        assertEquals(false, CanConsolePhysicalTransmissionPolicy.confirmed(
            "OK", correlation.complete(5_101L),
            correlation.hasExplicitAdapterAck(), correlation.hasAdapterError()
        ))
    }

    @Test fun preStartTimestampCannotPoisonPreinitializedPhysicalSession() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requestStartedAtMs = 1_000L,
            requestDeadlineMs = 500L
        )
        // These bytes are visible but older than the requested physical TX.
        correlation.append("CAN ERROR\r\n", 999L)
        assertEquals(false, correlation.hasAdapterError())
        correlation.append("OK\r\n7E8 04 03 41 0C 1A\r\n", 1_100L)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(1_101L))
        assertEquals(true, correlation.hasExplicitAdapterAck())
        assertEquals(false, correlation.hasAdapterError())
        assertEquals(listOf("SYSTEM", "SYSTEM", "RX"),
            correlation.recentReceivedRecords().map { it.record.direction })
    }

    @Test fun trailingFrameIsProcessedWithoutInventingMissingBytes() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        correlation.append("7E8 04 03 41", 50)
        correlation.append(" 0C 1A", 60)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(61))
        val malformed = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        malformed.append("7E8 05 03 41 0C 1A", 50)
        assertEquals(CanConsoleDiagnosticStatus.NONE, malformed.complete(60))
    }

    @Test fun oversizedPostTxLineOverridesEarlierPhysicalAckAndMatchingEcuReply() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requireExplicitStart = true
        )
        correlation.beginRequest(1_000L)
        correlation.append("OK\r\n7E8 04 03 41 0C 1A\r\n", 1_010L)
        correlation.append("X".repeat(4_097) + "\r\n", 1_020L)
        val status = correlation.complete(1_021L)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, status)
        assertEquals(true, correlation.hasAdapterError())
        assertEquals(false, correlation.hasExplicitAdapterAck())
        assertEquals(false, CanConsolePhysicalTransmissionPolicy.confirmed(
            "OK", status, correlation.hasExplicitAdapterAck(), correlation.hasAdapterError()
        ))
    }

    @Test fun unterminatedOversizedPostTxLineFailsClosedAtCompletion() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requireExplicitStart = true
        )
        correlation.beginRequest(1_000L)
        correlation.append("OK\r\n" + "X".repeat(4_097), 1_010L)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(1_011L))
        assertEquals(true, correlation.hasAdapterError())
        assertEquals(false, correlation.hasExplicitAdapterAck())
    }

    @Test fun oversizedPreTxSetupLineDoesNotPoisonFollowingPhysicalExchange() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(
            frame("7E0 02 01 0C"), requireExplicitStart = true
        )
        correlation.append("X".repeat(4_097) + "\r\n", 990L)
        assertEquals(false, correlation.hasAdapterError())
        correlation.beginRequest(1_000L)
        correlation.append("OK\r\n7E8 04 03 41 0C 1A\r\n", 1_010L)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(1_011L))
        assertEquals(false, correlation.hasAdapterError())
        assertEquals(true, correlation.hasExplicitAdapterAck())
    }
}
