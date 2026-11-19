package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun demoAndSimulationNeverExposePhysicalCommandChannel() {
        assertFalse(ObdTransportConnectionState.isCommandChannelAvailable(
            true, false, "USB"
        ))
        assertFalse(ObdTransportConnectionState.isCommandChannelAvailable(
            false, true, "WIFI"
        ))
    }

    @Test
    fun unsupportedTransportIsUnavailable() {
        assertFalse(ObdTransportConnectionState.isCommandChannelAvailable(
            false, false, "J2534_BRIDGE"
        ))
    }
}
