package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertThrows
import org.junit.Test

class UsbSerialWritePolicyTest {
    @Test fun nonemptyUsbSerialCommandsAreAcceptedWithoutFakeDeliveryEvidence() {
        UsbSerialWritePolicy.requireNonEmpty(byteArrayOf(0x41))
        UsbSerialWritePolicy.requireNonEmpty(ByteArray(4096) { 0x7F })
    }

    @Test fun zeroByteCommandNeverReachesTheUsbDriver() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbSerialWritePolicy.requireNonEmpty(byteArrayOf())
        }
    }
}
