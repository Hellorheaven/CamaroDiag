package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EthanolContentPidDecoderTest {
    @Test fun decodesValidMode01Pid52Only() {
        assertEquals(0.0, EthanolContentPidDecoder.decode(byteArrayOf(0x41, 0x52, 0x00))!!, 0.001)
        assertEquals(100.0, EthanolContentPidDecoder.decode(byteArrayOf(0x41, 0x52, 0xFF.toByte()))!!, 0.001)
        assertEquals(50.196, EthanolContentPidDecoder.decode(byteArrayOf(0x41, 0x52, 0x80.toByte()))!!, 0.01)
        assertNull(EthanolContentPidDecoder.decode(byteArrayOf(0x41, 0x53, 0x80.toByte())))
        assertNull(EthanolContentPidDecoder.decode(byteArrayOf(0x7F, 0x01, 0x12)))
        assertNull(EthanolContentPidDecoder.decode(byteArrayOf(0x41, 0x52)))
        assertNull(EthanolContentPidDecoder.decode(byteArrayOf(0x41, 0x52, 0x80.toByte(), 0x00)))
    }

    @Test fun checksSupportedPidBitWithoutAssumingFlexFuelCapability() {
        assertTrue(EthanolContentPidDecoder.isSupported(byteArrayOf(0x41, 0x40, 0, 0, 0x40, 0)))
        assertFalse(EthanolContentPidDecoder.isSupported(byteArrayOf(0x41, 0x40, 0, 0, 0, 0)))
        assertFalse(EthanolContentPidDecoder.isSupported(byteArrayOf(0x41, 0x20, 0, 0, 0x40, 0)))
        assertFalse(EthanolContentPidDecoder.isSupported(byteArrayOf(0x41, 0x40, 0x40, 0)))
    }
}
