package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertThrows
import org.junit.Test

class UsbAdapterConnectParametersTest {
    @Test fun acceptsUsbIdsAndSupportedPositiveSerialRatesWithoutInventedLimits() {
        UsbAdapterConnectParameters.requireValid(0, 38400)
        UsbAdapterConnectParameters.requireValid(4242, 115200)
        UsbAdapterConnectParameters.requireValid(Int.MAX_VALUE, 4000000)
    }

    @Test fun invalidRequestIsRejectedBeforeCurrentAdapterTeardown() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbAdapterConnectParameters.requireValid(-1, 38400)
        }
        for (rate in listOf(0, -1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                UsbAdapterConnectParameters.requireValid(7, rate)
            }
        }
    }
}
