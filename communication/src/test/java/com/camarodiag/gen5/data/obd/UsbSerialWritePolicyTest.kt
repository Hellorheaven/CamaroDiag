package com.camarodiag.gen5.data.obd

import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbSerialWritePolicyTest {
    @Test fun exactlyCompleteWritesAreAccepted() {
        UsbSerialWritePolicy.requireComplete(1, 1)
        UsbSerialWritePolicy.requireComplete(4096, 4096)
    }

    @Test fun shortOverlongOrFailedDriverWritesNeverCountAsSuccess() {
        for (accepted in listOf(-1, 0, 1, 3, 5, Int.MAX_VALUE)) {
            assertThrows(IOException::class.java) {
                UsbSerialWritePolicy.requireComplete(accepted, 4)
            }
        }
    }

    @Test fun emptyRequestIsNotAnAuthorizedWrite() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbSerialWritePolicy.requireComplete(0, 0)
        }
    }
}
