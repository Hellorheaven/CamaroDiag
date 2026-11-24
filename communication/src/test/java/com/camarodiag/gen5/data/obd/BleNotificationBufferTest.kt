package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entirely offline: the receive buffer is exercised without Bluetooth hardware. */
class BleNotificationBufferTest {
    @Test fun queueIsBoundedAndDoesNotSilentlyDiscardEarlierNotifications() {
        val receive = BleNotificationBuffer(capacity = 2)
        assertTrue(receive.offer(byteArrayOf(0x41)))
        assertTrue(receive.offer(byteArrayOf(0x42)))
        assertFalse(receive.offer(byteArrayOf(0x43)))
        assertArrayEquals(byteArrayOf(0x41), receive.packets.tryReceive().getOrThrow())
        assertArrayEquals(byteArrayOf(0x42), receive.packets.tryReceive().getOrThrow())
        assertTrue(receive.packets.tryReceive().isFailure)
    }

    @Test fun offeredDataIsCopiedBecauseAndroidCallbackBuffersCanBeReused() {
        val receive = BleNotificationBuffer(capacity = 1)
        val callbackValue = byteArrayOf(0x31, 0x32)
        assertTrue(receive.offer(callbackValue))
        callbackValue.fill(0)
        assertArrayEquals(byteArrayOf(0x31, 0x32),
            receive.packets.tryReceive().getOrThrow())
    }

    @Test fun rejectsInvalidNotificationLengthsAndAllowsLargestAttPayload() {
        val receive = BleNotificationBuffer(capacity = 2)
        assertFalse(receive.offer(byteArrayOf()))
        assertFalse(receive.offer(ByteArray(515)))
        assertTrue(receive.offer(ByteArray(514) { 0x22 }))
        assertArrayEquals(ByteArray(514) { 0x22 },
            receive.packets.tryReceive().getOrThrow())
    }

    @Test fun retiringSessionClearsOnlyBufferedPacketsAndAllowsFreshSession() {
        val receive = BleNotificationBuffer(capacity = 2)
        assertTrue(receive.offer(byteArrayOf(0x31)))
        receive.clear()
        assertTrue(receive.packets.tryReceive().isFailure)
        assertTrue(receive.offer(byteArrayOf(0x32)))
        assertArrayEquals(byteArrayOf(0x32),
            receive.packets.tryReceive().getOrThrow())
    }
}
