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
    @Test fun physicalMonitorModeSelectsExplicitFormatAndNeutralStnFilter() {
        assertEquals("ATSP6", CanMonitorProtocolPolicy.protocolCommand(false))
        assertEquals("ATSP7", CanMonitorProtocolPolicy.protocolCommand(true))
        assertEquals("000", CanMonitorProtocolPolicy.clearFilter(false))
        assertEquals("00000000", CanMonitorProtocolPolicy.clearFilter(true))
        assertFalse(CanConsoleAdapterRestorationPolicy.accepted("?"))
        assertTrue(CanConsoleAdapterRestorationPolicy.accepted("OK"))
    }

}
