package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

/** No TLS connection or J2534 driver is opened by these tests. */
class J2534BridgeHandshakeAdmissionTest {
    private fun requireCurrent(active: Boolean, closed: Boolean,
        startedAt: Long = 5L, now: Long = 5L
    ) = J2534BridgeHandshakeAdmission.requireCurrent(
        coroutineActive = active, closedByApp = closed,
        capturedCloseEpoch = startedAt, currentCloseEpoch = now
    )

    @Test fun onlyActiveNotYetClosedBridgeMaySendPairingAndPublishCapabilities() {
        requireCurrent(active = true, closed = false)
        assertThrows(CancellationException::class.java) {
            requireCurrent(active = false, closed = false)
        }
        assertThrows(IllegalStateException::class.java) {
            requireCurrent(active = true, closed = true)
        }
        assertThrows(CancellationException::class.java) {
            requireCurrent(active = false, closed = true)
        }
    }

    @Test fun closeThenAnotherConnectCannotResetTheOriginalHandshakePermission() {
        // A new connect() resets its own closed Boolean. A monotonic Close
        // generation is required to catch the older socket finishing last.
        assertThrows(IllegalStateException::class.java) {
            requireCurrent(active = true, closed = false, startedAt = 5L, now = 6L)
        }
        assertThrows(IllegalStateException::class.java) {
            requireCurrent(active = true, closed = false, startedAt = 5L, now = 8L)
        }
        // The same epoch remains authorized, including after async work
        // that did not signal a new explicit Close.
        requireCurrent(active = true, closed = false, startedAt = 6L, now = 6L)
    }
}
