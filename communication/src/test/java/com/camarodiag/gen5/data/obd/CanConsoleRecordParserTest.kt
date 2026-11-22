package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanConsoleRecordParserTest {
    @Test
    fun classifiesOnlyValidatedCanFramesAsReceivedTraffic() {
        val records = CanConsoleRecordParser.parse(
            "OK\r\n7E8 05 41 0C 1A F8 00\r\n18DAF110 03 7F 22 31"
        )

        assertEquals(listOf("SYSTEM", "RX", "RX"), records.map { it.direction })
        assertEquals("OK", records.first().content)
        assertEquals("CAN ID=7E8 DLC=5 DATA=41 0C 1A F8 00", records[1].content)
        assertEquals("CAN ID=18DAF110 DLC=3 DATA=7F 22 31", records[2].content)
    }

    @Test
    fun zeroLengthCanFramesAreReceivedRatherThanSystemMessages() {
        val records = CanConsoleRecordParser.parse(
            "7E8 00\r\n18DAF110 0\r\n7E8 00 FF"
        )

        assertEquals(listOf("RX", "RX", "SYSTEM"), records.map { it.direction })
        assertEquals("CAN ID=7E8 DLC=0 DATA=(empty)", records[0].content)
        assertEquals("CAN ID=18DAF110 DLC=0 DATA=(empty)", records[1].content)
    }

    @Test
    fun adapterErrorsRemainSystemEventsAndAreMarkedFailed() {
        val records = CanConsoleRecordParser.parse(
            "NO DATA\r\nCAN ERROR\r\nBUFFER FULL\r\nSEARCHING..."
        )

        assertTrue(records.all { it.direction == "SYSTEM" })
        assertFalse(records[0].succeeded)
        assertFalse(records[1].succeeded)
        assertFalse(records[2].succeeded)
        assertTrue(records[3].succeeded)
    }

    @Test
    fun ignoresEmptyLinesAndStripsMonitorPrompt() {
        val records = CanConsoleRecordParser.parse("\r\n7E8 02 41 0C>\r\n")

        assertEquals(1, records.size)
        assertEquals("RX", records.single().direction)
        assertEquals("CAN ID=7E8 DLC=2 DATA=41 0C", records.single().content)
    }
    @Test
    fun physicalJ2534ObdReadsDoNotRequireCanMonitorOrRawTransmit() {
        assertTrue(CanConsoleCapabilityPolicy.canSubmit(
            rawCanFrame = false, monitorAvailable = false, canTransmit = false,
            readObdAvailable = true, inputPresent = true, busy = false
        ))
        assertFalse(CanConsoleCapabilityPolicy.canSubmit(
            rawCanFrame = true, monitorAvailable = false, canTransmit = false,
            readObdAvailable = true, inputPresent = true, busy = false
        ))
    }

    @Test
    fun rawCanTransmissionStillNeedsVerifiedMonitorAndTransmitCapability() {
        assertTrue(CanConsoleCapabilityPolicy.canSubmit(
            rawCanFrame = true, monitorAvailable = true, canTransmit = true,
            readObdAvailable = false, inputPresent = true, busy = false
        ))
        assertFalse(CanConsoleCapabilityPolicy.canSubmit(
            rawCanFrame = true, monitorAvailable = true, canTransmit = false,
            readObdAvailable = true, inputPresent = true, busy = false
        ))
        assertFalse(CanConsoleCapabilityPolicy.canSubmit(
            rawCanFrame = false, monitorAvailable = true, canTransmit = true,
            readObdAvailable = false, inputPresent = true, busy = false
        ))
    }

    @Test
    fun emptyOrBusyConsoleCannotSubmitOnEitherTransport() {
        for (raw in listOf(true, false)) {
            assertFalse(CanConsoleCapabilityPolicy.canSubmit(
                raw, true, true, true, false, false
            ))
            assertFalse(CanConsoleCapabilityPolicy.canSubmit(
                raw, true, true, true, true, true
            ))
        }
    }

    @Test fun rawCanAdapterErrorsCannotBeReportedAsSuccessfulTransmissions() {
        for (response in listOf("", "?", "NO DATA", "CAN ERROR",
            "BUS ERROR", "UNABLE TO CONNECT", "BUFFER FULL", "STOPPED", "ERROR")) {
            assertTrue(response, CanConsoleAdapterReplyPolicy.isFailure(response))
        }
        assertFalse(CanConsoleAdapterReplyPolicy.isFailure("OK"))
        assertFalse(CanConsoleAdapterReplyPolicy.isFailure("7E8 03 41 52 80"))
    }

    @Test fun rawCanRequiresARealAdapterAcknowledgementOrValidatedFrame() {
        for (response in listOf(
            "OK", "ok>", "7E8 03 41 0C 1A",
            "18DAF110 03 7F 22 31",
            "SEARCHING...\r\n7E8 03 41 0C 1A"
        )) {
            assertTrue(response, CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(response))
        }
        for (response in listOf(
            "", "?", "SEARCHING...", "ATSP6", "7E8 03 41 0C",
            "NO DATA", "OK\r\nCAN ERROR", "7E8 03 41 0C 1A\nBUS ERROR"
        )) {
            assertFalse(response, CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(response))
        }
    }


    @Test fun elevenBitMonitorKeepsOtherEcUsVisibleWhileRoutingPhysicalResponder() {
        val request = requireNotNull(RawCanFrameParser.parse("7E0 02 01 0C"))
        assertEquals(0x7E8L, CanConsoleEcuRouting.expectedResponder(request))
        val records = CanConsoleRecordParser.parse(
            "7E9 03 41 0C FF\r\n7E8 03 41 0C 1A"
        )
        assertEquals(listOf("RX", "RX"), records.map { it.direction })
        assertTrue(records[0].content.startsWith("CAN ID=7E9"))
        assertTrue(records[1].content.startsWith("CAN ID=7E8"))
        assertFalse(CanConsoleEcuRouting.isSelectedResponder(
            request, requireNotNull(RawCanFrameParser.parse("7E9 03 41 0C 1A"))))
    }

    @Test fun twentyNineBitMonitorKeepsAllEcUsVisibleWithFullCanIdentifiers() {
        val request = requireNotNull(RawCanFrameParser.parse("18DA10F1 03 22 F1 90"))
        assertEquals(0x18DAF110L, CanConsoleEcuRouting.expectedResponder(request))
        val records = CanConsoleRecordParser.parse(
            "18DAF111 03 62 F1 90\r\n7E8 03 62 F1 90\r\n18DAF110 03 62 F1 90",
        )
        assertEquals(listOf("RX", "RX", "RX"), records.map { it.direction })
        assertTrue(records[0].content.contains("ID=18DAF111"))
        assertTrue(records[1].content.contains("ID=7E8"))
        assertEquals("CAN ID=18DAF110 DLC=3 DATA=62 F1 90", records[2].content)
    }

    @Test fun functionalAndUnknownRequestContextsNeverFilterCanPresentation() {
        for (input in listOf("7DF 02 01 0C", "123 02 01 0C", "18DAF110 03 22 F1 90")) {
            val request = requireNotNull(RawCanFrameParser.parse(input))
            assertEquals(null, CanConsoleEcuRouting.expectedResponder(request))
            val records = CanConsoleRecordParser.parse("7E8 03 41 0C 1A")
            assertEquals("RX", records.single().direction)
            assertTrue(records.single().succeeded)
        }
    }

    @Test fun passiveMonitorPreservesMultipleEcUsWithoutAttribution() {
        val records = CanConsoleRecordParser.parse(
            "7E8 03 41 0C 1A\r\n7E9 03 41 0C 1B"
        )
        assertEquals(listOf("RX", "RX"), records.map { it.direction })
        assertTrue(records[0].content.contains("ID=7E8"))
        assertTrue(records[1].content.contains("ID=7E9"))
    }

    @Test fun rawCanAdapterCannotConfirmPhysicalRequestUsingAnotherEcu() {
        val request11 = requireNotNull(RawCanFrameParser.parse("7E0 02 01 0C"))
        assertFalse(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(
            "7E9 04 03 41 0C 1A", request11
        ))
        assertFalse(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(
            "7E8 04 03 41 0D 1A", request11
        ))
        assertTrue(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(
            "7E9 04 03 41 0C 1A\r\n7E8 04 03 41 0C 1A", request11
        ))
        val request29 = requireNotNull(RawCanFrameParser.parse("18DA10F1 03 22 F1 90"))
        assertFalse(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(
            "18DAF111 04 03 62 F1 90", request29
        ))
        assertTrue(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(
            "18DAF110 04 03 62 F1 90", request29
        ))
        assertTrue(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission("OK", request11))
        assertFalse(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission("SEARCHING...", request11))
    }

    @Test fun normalizedElmCommandReplyRemainsAcceptableForRestoration() {
        assertEquals("OK", normalizeElmReply("ATSP6\r\nOK\r\n", "ATSP6"))
        assertTrue(CanConsoleAdapterRestorationPolicy.accepted(
            normalizeElmReply("ATSP6\r\nOK\r\n", "ATSP6")
        ))
        assertFalse(CanConsoleAdapterRestorationPolicy.accepted(
            normalizeElmReply("ATSP6\r\n?\r\n", "ATSP6")
        ))
    }

    @Test fun canAdapterRestorationRequiresExplicitCleanOk() {
        for (reply in listOf("OK", "ok>", "\r\nOK\r\n", "ATSP6\r\nOK")) {
            assertTrue(reply, CanConsoleAdapterRestorationPolicy.accepted(reply))
        }
        for (reply in listOf(
            "", "?", "NO DATA", "BUS ERROR", "OK\r\nCAN ERROR",
            "ATSP6", "SEARCHING...", "7E8 04 03 41 0C 1A"
        )) {
            assertFalse(reply, CanConsoleAdapterRestorationPolicy.accepted(reply))
        }
    }
    @Test fun isoTpAutomaticFlowControlMustBeConfirmedBeforeRawDiagnosticRequest() {
        assertEquals("ATFCSM0", CanConsoleIsotpFlowControlPolicy.RESTORE_AUTOMATIC_MODE)
        assertEquals("ATCFC1", CanConsoleIsotpFlowControlPolicy.ENABLE_AUTOMATIC)
        assertEquals(
            listOf("ATFCSM0", "ATCFC1"),
            CanConsoleIsotpFlowControlPolicy.setupCommands()
        )
        for (reply in listOf("OK", "ok>", "ATFCSM0\r\nOK", "ATCFC1\r\nOK")) {
            assertTrue(reply, CanConsoleIsotpFlowControlPolicy.adapterConfirmed(reply))
        }
        for (reply in listOf(
            "", "?", "NO DATA", "UNABLE TO CONNECT", "CAN ERROR",
            "OK\r\nBUS ERROR", "SEARCHING...", "7E8 08 10 0A 62 F1 90 31 32 33"
        )) {
            assertFalse(reply, CanConsoleIsotpFlowControlPolicy.adapterConfirmed(reply))
        }
        // Passive monitoring does not auto-ack ECU multi-frame requests.
        assertFalse(CanMonitorProtocolPolicy.setupCommands(false, false)
            .contains(CanConsoleIsotpFlowControlPolicy.ENABLE_AUTOMATIC))
        assertFalse(CanMonitorProtocolPolicy.setupCommands(true, true)
            .contains(CanConsoleIsotpFlowControlPolicy.ENABLE_AUTOMATIC))
    }

    @Test fun physicalMonitorModeSelectsExplicitFormatAndNeutralStnFilter() {
        assertEquals("ATSP6", CanMonitorProtocolPolicy.protocolCommand(false))
        assertEquals("ATSP7", CanMonitorProtocolPolicy.protocolCommand(true))
        assertEquals("000", CanMonitorProtocolPolicy.clearFilter(false))
        assertEquals("00000000", CanMonitorProtocolPolicy.clearFilter(true))
        assertFalse(CanConsoleAdapterRestorationPolicy.accepted("?"))
        assertTrue(CanConsoleAdapterRestorationPolicy.accepted("OK"))
    }


    @Test fun adapterAckOnlyNeverBecomesAValidatedEcuReply() {
        val physical = requireNotNull(RawCanFrameParser.parse("7E0 02 01 0C"))
        assertTrue(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission("OK", physical))
        assertFalse(CanConsoleAdapterReplyPolicy.hasMatchedDiagnosticResponse("OK", physical))
        assertFalse(CanConsoleAdapterReplyPolicy.hasMatchedDiagnosticResponse(
            "7E9 04 03 41 0C 1A", physical
        ))
        assertFalse(CanConsoleAdapterReplyPolicy.hasMatchedDiagnosticResponse(
            "7E8 04 03 41 0D 1A", physical
        ))
        assertTrue(CanConsoleAdapterReplyPolicy.hasMatchedDiagnosticResponse(
            "7E8 04 03 41 0C 1A", physical
        ))
        assertTrue(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(
            "OK\r\n7E9 04 03 41 0C 1A", physical
        ))
        assertFalse(CanConsoleAdapterReplyPolicy.hasMatchedDiagnosticResponse(
            "OK\r\n7E9 04 03 41 0C 1A", physical
        ))
        val functional = requireNotNull(RawCanFrameParser.parse("7DF 02 01 0C"))
        assertTrue(CanConsoleAdapterReplyPolicy.hasMatchedDiagnosticResponse(
            "7E9 04 03 41 0C 1B", functional
        ))
    }
    @Test fun passiveCanCaptureMayEndQuietlyButNeverAcceptAdapterFailure() {
        for (reply in listOf("", "   ", "\r\n", "SEARCHING...",
            "7E8 03 41 0C 1A", "7E8 03 41 0C 1A\r\n7E9 03 41 0D 1B"
        )) {
            assertTrue(reply, CanMonitorCompletionPolicy.accepted(reply))
        }
        for (reply in listOf("?", "NO DATA", "CAN ERROR", "BUS ERROR",
            "BUFFER FULL", "UNABLE TO CONNECT", "STOPPED", "ERROR",
            "7E8 03 41 0C 1A\r\nCAN ERROR"
        )) {
            assertFalse(reply, CanMonitorCompletionPolicy.accepted(reply))
        }
    }

    @Test fun monitorErrorsBeyondShortAdapterReplyBufferCannotBeHidden() {
        val tracker = CanMonitorErrorTracker()
        val traffic = (0 until 180).joinToString("\r\n") { "7E8 03 41 0C 1A" } + "\r\n"
        tracker.append(traffic)
        // The physical command's summarized reply can be truncated to 1 KiB.
        assertTrue(traffic.length > 1024)
        assertTrue(CanMonitorCompletionPolicy.accepted(traffic.take(1024)))
        tracker.append("CAN ER")
        tracker.append("ROR")
        assertTrue(tracker.hasFailureAtEnd())
    }

    @Test fun monitorTrackerSupportsQuietBusAndFragmentedValidCanFrames() {
        val quiet = CanMonitorErrorTracker()
        assertFalse(quiet.hasFailureAtEnd())
        val tracker = CanMonitorErrorTracker()
        tracker.append("7E8 03 41")
        tracker.append(" 0C 1A\r")
        tracker.append("\n7E9 03 41 0D 1B")
        assertFalse(tracker.hasFailureAtEnd())
    }

    @Test fun monitorCommandSequencesConfigureAndRestoreOnlySelectedBusFormat() {
        assertEquals(
            listOf("ATSP6", "ATE0", "ATL0", "ATS1", "ATH1", "ATCAF0"),
            CanMonitorProtocolPolicy.setupCommands(can29Bit = false, stn = false)
        )
        assertEquals(
            listOf("ATSP7", "ATE0", "ATL0", "ATS1", "ATH1", "ATCAF0"),
            CanMonitorProtocolPolicy.setupCommands(can29Bit = true, stn = false)
        )
        assertEquals(
            listOf("ATSP7", "ATE0", "ATL0", "ATS1", "ATH1", "ATCAF0",
                "ATCF 00000000", "ATCM 00000000", "STFAC"),
            CanMonitorProtocolPolicy.setupCommands(can29Bit = true, stn = true)
        )
        assertEquals(
            listOf("ATSP6", "ATE0", "ATL0", "ATS1", "ATH1", "ATCAF0",
                "ATCF 000", "ATCM 000", "STFAC"),
            CanMonitorProtocolPolicy.setupCommands(can29Bit = false, stn = true)
        )
        assertEquals(
            listOf("ATSP6", "ATE0", "ATL0", "ATH1", "ATS0", "ATCAF1"),
            CanMonitorProtocolPolicy.restoreCommands(stn = false)
        )
        assertEquals(
            listOf("ATAR", "ATSP6", "ATE0", "ATL0", "ATH1", "ATS0", "ATCAF1"),
            CanMonitorProtocolPolicy.restoreCommands(stn = true)
        )
    }


    @Test fun diagnosticReplyStatusDistinguishesPositiveNegativeAndAdapterOnly() {
        val physical = requireNotNull(RawCanFrameParser.parse("7E0 03 22 F1 90"))
        assertEquals(CanConsoleDiagnosticStatus.NONE,
            CanConsoleAdapterReplyPolicy.diagnosticStatus("OK", physical))
        assertEquals(CanConsoleDiagnosticStatus.NONE,
            CanConsoleAdapterReplyPolicy.diagnosticStatus("7E9 04 03 7F 22 31", physical))
        assertEquals(CanConsoleDiagnosticStatus.NEGATIVE,
            CanConsoleAdapterReplyPolicy.diagnosticStatus("7E8 04 03 7F 22 31", physical))
        assertEquals(CanConsoleDiagnosticStatus.NONE,
            CanConsoleAdapterReplyPolicy.diagnosticStatus("7E8 04 03 7F 22 78", physical))
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE,
            CanConsoleAdapterReplyPolicy.diagnosticStatus("7E8 04 03 62 F1 90", physical))
        val functional = requireNotNull(RawCanFrameParser.parse("7DF 03 22 F1 90"))
        assertEquals(CanConsoleDiagnosticStatus.NEGATIVE,
            CanConsoleAdapterReplyPolicy.diagnosticStatus("7E9 04 03 7F 22 31", functional))
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE,
            CanConsoleAdapterReplyPolicy.diagnosticStatus(
                "7E9 04 03 7F 22 31\r\n7E8 04 03 62 F1 90", functional
            ))
        assertEquals(listOf("RX", "RX"),
            CanConsoleRecordParser.parse(
                "7E9 04 03 7F 22 31\r\n7E8 04 03 62 F1 90"
            ).map { it.direction })
    }
}
