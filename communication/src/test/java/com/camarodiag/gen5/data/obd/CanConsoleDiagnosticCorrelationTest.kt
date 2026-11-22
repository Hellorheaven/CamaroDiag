package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class CanConsoleDiagnosticCorrelationTest {
    private fun frame(value: String) = requireNotNull(RawCanFrameParser.parse(value))

    @Test fun elevenBitPhysicalSingleFrameDoesNotClaimAnotherEcuOrPid() {
        val request = frame("7E0 02 01 0C")
        val correlation = CanConsoleDiagnosticCorrelation(request)
        assertNull(correlation.accept(frame("7E9 03 41 0C 1A"), 0))
        assertNull(correlation.accept(frame("7E8 03 41 0D 1A"), 1))
        assertArrayEquals(byteArrayOf(0x41, 0x0C, 0x1A),
            correlation.accept(frame("7E8 03 41 0C 1A"), 2))
        assertNull(correlation.accept(frame("7E8 03 41 0C 1B"), 3))
        val visible = CanConsoleRecordParser.parse(
            "7E9 03 41 0C 1A\r\n7E8 03 41 0D 1A\r\n7E8 03 41 0C 1A"
        )
        assertEquals(listOf("RX", "RX", "RX"), visible.map { it.direction })
    }

    @Test fun twentyNineBitPhysicalResponseIsBoundToItsEcu() {
        val correlation = CanConsoleDiagnosticCorrelation(frame("18DA10F1 03 22 F1 90"))
        assertNull(correlation.accept(frame("18DAF111 03 62 F1 90"), 0))
        assertNull(correlation.accept(frame("7E8 03 62 F1 90"), 1))
        assertArrayEquals(byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte()),
            correlation.accept(frame("18DAF110 03 62 F1 90"), 2))
    }

    @Test fun multiframeResponseAcceptsOnlyMatchedInOrderSequence() {
        val correlation = CanConsoleDiagnosticCorrelation(frame("7E0 03 22 F1 90"))
        val first = frame("7E8 10 0A 62 F1 90 31 32 33")
        val other = frame("7E9 21 34 35 36 37")
        val last = frame("7E8 21 34 35 36 37")
        assertNull(correlation.accept(first, 10))
        assertNull(correlation.accept(other, 11))
        assertArrayEquals(
            byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte(), 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37),
            correlation.accept(last, 12)
        )
        assertEquals(listOf("RX", "RX", "RX"),
            CanConsoleRecordParser.parse(
                "7E8 08 10 0A 62 F1 90 31 32 33\r\n7E9 05 21 34 35 36 37\r\n7E8 05 21 34 35 36 37"
            ).map { it.direction })
    }

    @Test fun incompleteTimeoutOrOutOfSequenceReplyNeverBecomesDiagnosticResult() {
        val request = frame("7E0 03 22 F1 90")
        val first = frame("7E8 10 0A 62 F1 90 31 32 33")
        val last = frame("7E8 21 34 35 36 37")
        val timed = CanConsoleDiagnosticCorrelation(request, timeoutMs = 100)
        assertNull(timed.accept(first, 1))
        assertNull(timed.accept(last, 101))
        val order = CanConsoleDiagnosticCorrelation(request)
        assertNull(order.accept(first, 1))
        assertNull(order.accept(frame("7E8 22 34 35 36 37"), 2))
        assertNull(order.accept(last, 3))
        val malformed = CanConsoleDiagnosticCorrelation(request)
        assertNull(malformed.accept(frame("7E8 10 00 62 F1 90 31 32 33"), 0))
    }

    @Test fun negativeResponseMustMatchServiceAndSession() {
        val correlation = CanConsoleDiagnosticCorrelation(frame("7E0 03 22 F1 90"))
        assertNull(correlation.accept(frame("7E8 03 7F 19 31"), 1))
        assertArrayEquals(byteArrayOf(0x7F, 0x22, 0x31),
            correlation.accept(frame("7E8 03 7F 22 31"), 2))
        assertNull(correlation.accept(frame("7E8 03 62 F1 90"), 3))
    }

    @Test fun physicalTransportUsesCompleteIsotpPayloadWithoutSuppressingOtherFrames() {
        val request = frame("7E0 03 22 F1 90")
        val batch = "7E9 08 10 0A 62 F1 90 31 32 33\r\n" +
            "7E8 08 10 0A 62 F1 90 31 32 33\r\n" +
            "7E9 05 21 34 35 36 37\r\n" +
            "7E8 05 21 34 35 36 37"
        assertEquals(4, CanConsoleRecordParser.parse(batch).count { it.direction == "RX" })
        assertTrue(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(batch, request))
        assertFalse(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(
            "7E9 08 10 0A 62 F1 90 31 32 33\r\n7E9 05 21 34 35 36 37", request
        ))
        assertFalse(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(
            "7E8 08 10 0A 62 F1 90 31 32 33", request
        ))
    }

    @Test fun functionalBroadcastAcceptsSeparateRespondersButNeverMixesTheirSegments() {
        val functional = frame("7DF 03 22 F1 90")
        val mixed = "7E8 08 10 0A 62 F1 90 31 32 33\r\n" +
            "7E9 05 21 34 35 36 37\r\n" +
            "7E8 05 21 34 35 36 37"
        assertEquals(3, CanConsoleRecordParser.parse(mixed).count { it.direction == "RX" })
        assertTrue(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(mixed, functional))
        assertFalse(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(
            "7E8 08 10 0A 62 F1 90 31 32 33\r\n7E9 05 21 34 35 36 37",
            functional
        ))
        assertTrue(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(
            "7E9 04 03 62 F1 90", functional
        ))
        assertFalse(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(
            "7E9 04 03 62 F1 91", functional
        ))
    }

    @Test fun udsResponsePendingDoesNotCompleteOrHideLaterFinalReply() {
        val request = frame("7E0 03 22 F1 90")
        val correlation = CanConsoleDiagnosticCorrelation(request)
        assertNull(correlation.accept(frame("7E8 03 7F 22 78"), 0))
        assertArrayEquals(byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte()),
            correlation.accept(frame("7E8 03 62 F1 90"), 1))
        assertFalse(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(
            "7E8 04 03 7F 22 78", request
        ))
        assertTrue(CanConsoleAdapterReplyPolicy.hasConfirmedTransmission(
            "7E8 04 03 7F 22 78\r\n7E8 04 03 62 F1 90", request
        ))
    }

    @Test fun unknownOrFunctionalPhysicalContextCannotAcquireOtherEcu() {
        for (request in listOf("7DF 02 01 0C", "123 02 01 0C", "18DAF110 03 22 F1 90")) {
            try {
                CanConsoleDiagnosticCorrelation(frame(request))
                fail("Must reject non-physical or unknown request: $request")
            } catch (_: IllegalArgumentException) {
                // Expected: no selected physical ECU.
            }
        }
    }
}
