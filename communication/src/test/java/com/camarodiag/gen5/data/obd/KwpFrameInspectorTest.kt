package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class KwpFrameInspectorTest {
    // Same synthetic wire fixture as Connectors native kline_observer_test.cpp.
    private val short = byteArrayOf(0x82.toByte(), 0xf1.toByte(), 0x12, 0x61, 1, 0xe7.toByte())
    private val extended = byteArrayOf(0x80.toByte(), 0xf1.toByte(), 0x12, 2, 0x61, 1, 0xe7.toByte())

    @Test fun canonicalShortAndExtendedLengthFrames() {
        for (wire in listOf(short, extended)) {
            val result = KwpFrameInspector.inspect(wire, 0xf1, 0x12) as KwpFrameInspector.Inspection.Valid
            assertEquals(0xf1, result.frame.target)
            assertEquals(0x12, result.frame.source)
            assertArrayEquals(byteArrayOf(0x61, 1), result.frame.payload)
        }
    }

    @Test fun wrongAddressCorruptionTruncationAndUnsupportedFormatAreRejected() {
        fun error(wire: ByteArray, target: Int = 0xf1, source: Int = 0x12) =
            (KwpFrameInspector.inspect(wire, target, source) as KwpFrameInspector.Inspection.Invalid).error
        assertEquals(KwpFrameInspector.Error.ADDRESS, error(short, 0x12, 0xf1))
        assertEquals(KwpFrameInspector.Error.ADDRESS, error(short, -1, 0x12))
        assertEquals(KwpFrameInspector.Error.LENGTH, error(short.copyOf(5)))
        assertEquals(KwpFrameInspector.Error.CHECKSUM, error(short.copyOf().also { it[5] = 0 }))
        assertEquals(KwpFrameInspector.Error.FORMAT, error(short.copyOf().also { it[0] = 0xc2.toByte() }))
        assertEquals(KwpFrameInspector.Error.SIZE, error(byteArrayOf()))
    }

    @Test fun maximumPayloadIsBoundedAndCopiedFromInput() {
        val wire = ByteArray(260)
        wire[0] = 0x80.toByte()
        wire[1] = 0xf1.toByte()
        wire[2] = 0x12
        wire[3] = 255.toByte()
        for (i in 4..258) wire[i] = i.toByte()
        wire[259] = wire.take(259).sumOf { it.toInt() and 255 }.toByte()
        val parsed = KwpFrameInspector.inspect(wire, 0xf1, 0x12) as KwpFrameInspector.Inspection.Valid
        assertEquals(255, parsed.frame.payload.size)
        wire[4] = 0
        assertEquals(4, parsed.frame.payload[0].toInt())
        assertEquals(KwpFrameInspector.Inspection.Invalid(KwpFrameInspector.Error.SIZE),
            KwpFrameInspector.inspect(wire.copyOf(261), 0xf1, 0x12))
    }
}
