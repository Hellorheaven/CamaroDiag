package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

/** No TLS connection or J2534 driver is opened by these tests. */
class J2534BridgeHandshakeAdmissionTest {
    @Test fun onlyActiveNotYetClosedBridgeMaySendPairingAndPublishCapabilities() {
        J2534BridgeHandshakeAdmission.requireCurrent(
            coroutineActive = true, closedByApp = false
        )
        assertThrows(CancellationException::class.java) {
            J2534BridgeHandshakeAdmission.requireCurrent(
                coroutineActive = false, closedByApp = false
            )
        }
        assertThrows(IllegalStateException::class.java) {
            J2534BridgeHandshakeAdmission.requireCurrent(
                coroutineActive = true, closedByApp = true
            )
        }
        // Cancellation takes precedence over a concurrent explicit close.
        assertThrows(CancellationException::class.java) {
            J2534BridgeHandshakeAdmission.requireCurrent(
                coroutineActive = false, closedByApp = true
            )
        }
    }
}
