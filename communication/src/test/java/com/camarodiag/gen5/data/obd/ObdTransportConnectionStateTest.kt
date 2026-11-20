package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdTransportConnectionStateTest {
    @Test
    fun bleDisconnectIsReportedAsIoFailureToConnectionMonitor() {
        val error = ObdTransportDisconnectedException("BLE disconnected")
        assertTrue(java.io.IOException::class.java.isAssignableFrom(error.javaClass))
    }

    @Test
    fun classicBluetoothRequiresAnOpenConnectedSocket() {
        assertTrue(ObdTransportConnectionState.isCommandChannelAvailable(
            false, false, "BLUETOOTH", classicSocketConnected = true, classicSocketClosed = false
        ))
        assertFalse(ObdTransportConnectionState.isCommandChannelAvailable(
            false, false, "BLUETOOTH", classicSocketConnected = true, classicSocketClosed = true
        ))
    }

    @Test
    fun bleAvailabilityFollowsCurrentGattSession() {
        assertTrue(ObdTransportConnectionState.isCommandChannelAvailable(
            false, false, "BLE", bleConnected = true
        ))
        assertFalse(ObdTransportConnectionState.isCommandChannelAvailable(
            false, false, "BLE", bleConnected = false
        ))
    }

    @Test
    fun usbAndWifiRequireTheirOwnLiveConnectionState() {
        assertFalse(ObdTransportConnectionState.isCommandChannelAvailable(
            false, false, "USB"
        ))
        assertFalse(ObdTransportConnectionState.isCommandChannelAvailable(
            false, false, "WIFI"
        ))
        assertTrue(ObdTransportConnectionState.isCommandChannelAvailable(
            false, false, "USB", usbConnected = true
        ))
        assertTrue(ObdTransportConnectionState.isCommandChannelAvailable(
            false, false, "WIFI", wifiConnected = true
        ))
        assertFalse(ObdTransportConnectionState.isCommandChannelAvailable(
            false, true, "USB", usbConnected = true
        ))
    }

    @Test
    fun demoAndSimulationNeverExposePhysicalCommandChannel() {
        assertFalse(ObdTransportConnectionState.isCommandChannelAvailable(
            true, false, "USB"
        ))
        assertFalse(ObdTransportConnectionState.isCommandChannelAvailable(
            false, true, "WIFI"
        ))
    }

    @Test
    fun sessionInvalidationRejectsPendingWorkAfterReconnect() {
        val epoch = ObdTransportSessionEpoch()
        val original = epoch.capture()
        epoch.requireCurrent(original)

        epoch.invalidate()
        val replacement = epoch.capture()
        assertTrue(replacement != original)

        val failure = org.junit.Assert.assertThrows(
            ObdTransportDisconnectedException::class.java
        ) { epoch.requireCurrent(original) }
        assertTrue(failure.message.orEmpty().contains("changed"))
        epoch.requireCurrent(replacement)
    }

    @Test
    fun repeatedDisconnectsAdvanceEpochWithoutReusingOldSession() {
        val epoch = ObdTransportSessionEpoch()
        val first = epoch.capture()
        epoch.invalidate()
        val second = epoch.capture()
        epoch.invalidate()
        val third = epoch.capture()

        assertTrue(first < second)
        assertTrue(second < third)
        org.junit.Assert.assertThrows(ObdTransportDisconnectedException::class.java) {
            epoch.requireCurrent(first)
        }
        org.junit.Assert.assertThrows(ObdTransportDisconnectedException::class.java) {
            epoch.requireCurrent(second)
        }
        epoch.requireCurrent(third)
    }

    @Test
    fun unsupportedTransportIsUnavailable() {
        assertFalse(ObdTransportConnectionState.isCommandChannelAvailable(
            false, false, "J2534_BRIDGE"
        ))
    }
}
