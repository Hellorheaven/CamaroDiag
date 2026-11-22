package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class RawCanFrameParserTest {
    @Test fun parsesElevenBitFrame() {
        val frame = RawCanFrameParser.parse("7E0 02 01 0C")!!
        assertEquals(0x7E0L, frame.canId)
        assertFalse(frame.isExtended)
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x0C), frame.data)
    }

    @Test fun parsesTwentyNineBitFrameAndPrefix() {
        val frame = RawCanFrameParser.parse("0x18DAF110 03 22 F1 90")!!
        assertEquals(0x18DAF110L, frame.canId)
        assertTrue(frame.isExtended)
        assertArrayEquals(byteArrayOf(0x03, 0x22, 0xF1.toByte(), 0x90.toByte()), frame.data)
    }

    @Test fun rejectsInvalidIdentifiersAndPayloads() {
        assertNull(RawCanFrameParser.parse("800 01"))
        assertNull(RawCanFrameParser.parse("20000000 01"))
        assertNull(RawCanFrameParser.parse("7E00 01"))
        assertNull(RawCanFrameParser.parse("18DAF11 01"))
        assertNull(RawCanFrameParser.parse("7E0"))
        assertNull(RawCanFrameParser.parse("7E0 GG"))
        assertNull(RawCanFrameParser.parse("7E0 01 02 03 04 05 06 07 08 09"))
    }

    @Test fun acceptsWhitespaceAndLowercaseHex() {
        val frame = RawCanFrameParser.parse("  7e0   0a   ff  ")!!
        assertArrayEquals(byteArrayOf(0x0A, 0xFF.toByte()), frame.data)
    }

    @Test fun stripsDlcWhenDecodingReceivedAdapterFrames() {
        val frames = RawCanFrameParser.parseReceivedFrames(
            "SEARCHING...\r\n7E8 05 41 0C 1A F8 00\r\n18DAF110 03 7F 22 31"
        )
        assertEquals(2, frames.size)
        assertEquals(0x7E8L, frames[0].canId)
        assertFalse(frames[0].isExtended)
        assertArrayEquals(byteArrayOf(0x41, 0x0C, 0x1A, 0xF8.toByte(), 0x00), frames[0].data)
        assertEquals(0x18DAF110L, frames[1].canId)
        assertTrue(frames[1].isExtended)
        assertArrayEquals(byteArrayOf(0x7F, 0x22, 0x31), frames[1].data)
    }

    @Test fun acceptsSingleDigitElmDlcForStandardAndExtendedIdentifiers() {
        val frames = RawCanFrameParser.parseReceivedFrames(
            "7E8 8 07 62 F1 90 31 47 30 31\r\n18DAF110 3 7F 22 31"
        )
        assertEquals(2, frames.size)
        assertEquals(0x7E8L, frames[0].canId)
        assertArrayEquals(
            byteArrayOf(0x07, 0x62, 0xF1.toByte(), 0x90.toByte(), 0x31, 0x47, 0x30, 0x31),
            frames[0].data
        )
        assertEquals(0x18DAF110L, frames[1].canId)
        assertArrayEquals(byteArrayOf(0x7F, 0x22, 0x31), frames[1].data)
    }

    @Test fun ignoresAdapterStatusAndMalformedReceivedLines() {
        val frames = RawCanFrameParser.parseReceivedFrames(
            "OK\r\nNO DATA\r\n7E8 03 41 0C\r\n7E8 04 41 0C\r\n7E8 09 01 02 03 04 05 06 07 08 09\r\nNOT A FRAME"
        )
        assertTrue(frames.isEmpty())
    }

    @Test fun receivesZeroLengthFramesWithoutAllowingMalformedPayloads() {
        val frames = RawCanFrameParser.parseReceivedFrames(
            "7E8 00\r\n18DAF110 0\r\n7E8 00 FF\r\n7E8 09\r\n800 00"
        )
        assertEquals(2, frames.size)
        assertFalse(frames[0].isExtended)
        assertTrue(frames[1].isExtended)
        assertTrue(frames.all { it.data.isEmpty() })
    }

    @Test fun keepsOnlyFramesWhoseDlcMatchesPayloadLength() {
        val frames = RawCanFrameParser.parseReceivedFrames(
            "7E8 03 41 0C 1A\r\n7E8 02 41 0C 1A\r\n7E8 00"
        )
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(0x41, 0x0C, 0x1A), frames[0].data)
        assertEquals(0x7E8L, frames[1].canId)
        assertTrue(frames[1].data.isEmpty())
    }
    @Test fun obdEthanolConsoleInputCannotBeMisclassifiedAsRawCanTransmission() {
        for (input in listOf("0152", "01 52", "0902", "03", "07", "0A")) {
            assertNull("OBD request must use the diagnostic read channel: $input",
                RawCanFrameParser.parse(input))
            assertNotNull("Read-only OBD command must be recognized: $input",
                SafeObdRequestPolicy.parse(input))
        }
        val physicalFrame = "7E0 02 01 52"
        assertNotNull(RawCanFrameParser.parse(physicalFrame))
        assertNull(SafeObdRequestPolicy.parse(physicalFrame))
    }

    @Test fun rawConsoleAllowsRealReadQueriesButRejectsDiagnosticProgramming() {
        for (safe in listOf(
            "7E0 02 01 0C", "7DF 02 01 52", "7E0 02 09 02",
            "7E0 01 03", "7E0 03 22 F1 90", "7E0 02 3E 00",
            "18DA10F1 03 22 F1 90", "7E0 02 01 0C 00 00"
        )) {
            assertTrue(safe, SafeRawCanTransmitPolicy.allows(
                requireNotNull(RawCanFrameParser.parse(safe))))
        }
        for (unsafe in listOf(
            "7E0 02 04 00", "7E0 02 27 01", "7E0 03 2E F1 90",
            "7E0 02 31 01", "7E0 02 34 00", "7E0 02 36 01",
            "7E0 02 11 01", "7DF 02 14 01", "7E0 02 85 01",
            "7E0 10 14 34 00", "7E0 21 00 00", "7E0 02 01 FF",
            "7E0 02 01 0C FF", "18DA10F1 03 2E F1 90",
            "7E8 02 01 0C", "18DAF110 03 22 F1 90",
            "7DF 03 22 F1 90"
        )) {
            assertFalse(unsafe, SafeRawCanTransmitPolicy.allows(
                requireNotNull(RawCanFrameParser.parse(unsafe))))
        }
    }

    @Test fun nonstandardCanIdsCannotBecomeAnObdWriteLoophole() {
        for (input in listOf(
            "123 03 2E F1 90", "123 02 27 01", "123 02 04 00",
            "456 03 31 01 01", "456 03 34 00 44",
            "18DA20F1 03 2E F1 90", "18DA20F1 03 36 01 42",
            "123 10 14 34 00 11 22", "18DA20F1 10 14 2E F1 90"
        )) {
            assertFalse(input, SafeRawCanTransmitPolicy.allows(
                requireNotNull(RawCanFrameParser.parse(input))))
        }
        // The same bytes on an *unknown* CAN address are not proof of a safe
        // diagnostic request. Only qualified physical diagnostic addressing passes.
        for (input in listOf(
            "123 03 22 F1 90", "123 02 01 0C", "123 55 AA BB",
            "123 08 00 FF", "456 02 01 0C", "00000123 03 22 F1 90"
        )) {
            assertFalse(input, SafeRawCanTransmitPolicy.allows(
                requireNotNull(RawCanFrameParser.parse(input))))
        }
        for (input in listOf(
            "18DA20F1 03 22 F1 90", "7E0 03 22 F1 90",
            "7DF 02 01 0C"
        )) {
            assertTrue(input, SafeRawCanTransmitPolicy.allows(
                requireNotNull(RawCanFrameParser.parse(input))))
        }
    }

}
