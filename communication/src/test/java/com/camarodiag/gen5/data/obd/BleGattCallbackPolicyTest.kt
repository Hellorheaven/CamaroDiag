package com.camarodiag.gen5.data.obd

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure policy tests: no Android Bluetooth runtime, adapter or vehicle needed. */
class BleGattCallbackPolicyTest {
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    private val notify = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
    private val transmit = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
    private val unrelatedDescriptor = UUID.fromString("00002901-0000-1000-8000-00805F9B34FB")

    @Test fun onlyExactUartNotificationSubscriptionCanCompleteHandshake() {
        assertTrue(BleGattCallbackPolicy.isExpectedNotificationDescriptor(cccd, notify))
        assertFalse(BleGattCallbackPolicy.isExpectedNotificationDescriptor(cccd, transmit))
        assertFalse(BleGattCallbackPolicy.isExpectedNotificationDescriptor(unrelatedDescriptor, notify))
        assertFalse(BleGattCallbackPolicy.isExpectedNotificationDescriptor(null, notify))
        assertFalse(BleGattCallbackPolicy.isExpectedNotificationDescriptor(cccd, null))
    }

    @Test fun outOfRangeBluetoothMtuNeverCreatesAnOversizedBleTransmitChunk() {
        assertEquals(20, BleGattCallbackPolicy.payloadForMtu(23))
        assertEquals(20, BleGattCallbackPolicy.payloadForMtu(22))
        assertEquals(20, BleGattCallbackPolicy.payloadForMtu(0))
        assertEquals(20, BleGattCallbackPolicy.payloadForMtu(-1))
        assertEquals(244, BleGattCallbackPolicy.payloadForMtu(247))
        assertEquals(514, BleGattCallbackPolicy.payloadForMtu(517))
        assertEquals(20, BleGattCallbackPolicy.payloadForMtu(518))
        assertEquals(20, BleGattCallbackPolicy.payloadForMtu(Int.MAX_VALUE))
    }
}
