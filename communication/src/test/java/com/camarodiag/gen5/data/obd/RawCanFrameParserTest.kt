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
        assertNull(RawCanFrameParser.parse("7E0"))
        assertNull(RawCanFrameParser.parse("7E0 GG"))
        assertNull(RawCanFrameParser.parse("7E0 01 02 03 04 05 06 07 08 09"))
    }

    @Test fun acceptsWhitespaceAndLowercaseHex() {
        val frame = RawCanFrameParser.parse("  7e0   0a   ff  ")!!
        assertArrayEquals(byteArrayOf(0x0A, 0xFF.toByte()), frame.data)
    }
}
