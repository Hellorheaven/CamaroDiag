package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedBluetoothConnectTest {
    @Test fun successfulConnectionKeepsSocketOpen() = runBlocking {
        val closes = AtomicInteger()
        BoundedBluetoothConnect.open(2_000L, {}, { closes.incrementAndGet() })
        assertEquals(0, closes.get())
    }

    @Test fun blockedConnectionTimesOutAndClosesOnlyItsSocket() = runBlocking {
        val released = CountDownLatch(1)
        val closes = AtomicInteger()
        var timedOut = false
        try {
            BoundedBluetoothConnect.open(100L,
                { released.await(5, TimeUnit.SECONDS); Unit },
                { closes.incrementAndGet(); released.countDown() })
        } catch (_: SocketTimeoutException) {
            timedOut = true
        }
        assertTrue(timedOut)
        assertEquals(1, closes.get())
        assertTrue(released.await(1, TimeUnit.SECONDS))
    }
}
