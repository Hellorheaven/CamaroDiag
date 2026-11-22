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
            "SEARCHING...\\r\\n7E8 03 41 0C 1A"
        )) {
            assertTrue(response, CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(response))
        }
        for (response in listOf(
            "", "?", "SEARCHING...", "ATSP6", "7E8 03 41 0C",
            "NO DATA", "OK\\r\\nCAN ERROR", "7E8 03 41 0C 1A\\nBUS ERROR"
        )) {
            assertFalse(response, CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(response))
        }
    }


    @Test fun elevenBitPhysicalRequestAcceptsOnlyItsResponder() {
        val request = requireNotNull(RawCanFrameParser.parse("7E0 02 01 0C"))
        assertEquals(0x7E8L, CanConsoleEcuRouting.expectedResponder(request))
        val records = CanConsoleRecordParser.parse(
            "7E9 03 41 0C FF\\r\\n7E8 03 41 0C 1A", request
        )
        assertEquals(listOf("SYSTEM", "RX"), records.map { it.direction })
        assertFalse(records[0].succeeded)
        assertTrue(records[1].content.startsWith("CAN ID=7E8"))
        assertFalse(CanConsoleEcuRouting.isSelectedResponder(
            request, requireNotNull(RawCanFrameParser.parse("7E9 03 41 0C 1A"))))
    }

    @Test fun twentyNineBitPhysicalRequestRejectsOtherEcUsAndWrongAddressWidth() {
        val request = requireNotNull(RawCanFrameParser.parse("18DA10F1 03 22 F1 90"))
        assertEquals(0x18DAF110L, CanConsoleEcuRouting.expectedResponder(request))
        val records = CanConsoleRecordParser.parse(
            "18DAF111 03 62 F1 90\\r\\n7E8 03 62 F1 90\\r\\n18DAF110 03 62 F1 90",
            request
        )
        assertEquals(listOf("SYSTEM", "SYSTEM", "RX"), records.map { it.direction })
        assertFalse(records[0].succeeded)
        assertFalse(records[1].succeeded)
        assertEquals("CAN ID=18DAF110 DLC=3 DATA=62 F1 90", records[2].content)
    }

    @Test fun functionalAndUnmappedRequestsCannotClaimAnUnknownEcu() {
        for (input in listOf("7DF 02 01 0C", "123 02 01 0C", "18DAF110 03 22 F1 90")) {
            val request = requireNotNull(RawCanFrameParser.parse(input))
            assertEquals(null, CanConsoleEcuRouting.expectedResponder(request))
            val records = CanConsoleRecordParser.parse("7E8 03 41 0C 1A", request)
            assertEquals("SYSTEM", records.single().direction)
            assertFalse(records.single().succeeded)
        }
    }

    @Test fun passiveMonitorPreservesMultipleEcUsWithoutAttribution() {
        val records = CanConsoleRecordParser.parse(
            "7E8 03 41 0C 1A\\r\\n7E9 03 41 0C 1B"
        )
        assertEquals(listOf("RX", "RX"), records.map { it.direction })
        assertTrue(records[0].content.contains("ID=7E8"))
        assertTrue(records[1].content.contains("ID=7E9"))
    }
}
