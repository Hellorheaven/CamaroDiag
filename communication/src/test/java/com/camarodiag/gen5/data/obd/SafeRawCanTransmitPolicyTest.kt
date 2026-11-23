package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C6 must not reintroduce a product-specific allowlist of ECU services.
 * The separate OK then YES operator confirmation remains mandatory in UI.
 */
class SafeRawCanTransmitPolicyTest {
    @Test fun validManufacturerSecurityAndProgrammingPayloadsAreNotArtificiallyBlocked() {
        for (text in listOf(
            "7E0 02 27 01",
            "7E0 03 34 00 00",
            "7E0 02 2E F1",
            "18DAF110 02 27 01",
            "18DAF110 04 36 01 00 01"
        )) {
            val frame = RawCanFrameParser.parse(text)
            assertNotNull("Valid Classical CAN payload was rejected by parser: $text", frame)
            assertTrue("C6 must not block an OEM service merely by its bytes: $text",
                SafeRawCanTransmitPolicy.allows(requireNotNull(frame)))
        }
    }

    @Test fun invalidPhysicalCanBoundsAreStillRejected() {
        assertFalse(SafeRawCanTransmitPolicy.allows(
            RawCanFrame(0x800L, isExtended = false, data = byteArrayOf(1))
        ))
        assertFalse(SafeRawCanTransmitPolicy.allows(
            RawCanFrame(0x20000000L, isExtended = true, data = byteArrayOf(1))
        ))
        assertFalse(SafeRawCanTransmitPolicy.allows(
            RawCanFrame(0x7E0L, isExtended = false, data = ByteArray(9))
        ))
    }
}
