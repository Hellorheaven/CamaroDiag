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

    @Test fun blockedWriteRetiresSocketWithoutReemittingCommand() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closed = AtomicInteger()
        val sends = AtomicInteger()
        try {
            BoundedBluetoothConnect.write(500L, write = {
                sends.incrementAndGet()
                entered.countDown()
                released.await(5, TimeUnit.SECONDS)
            }, close = {
                closed.incrementAndGet()
                released.countDown()
            })
            org.junit.Assert.fail("Timed-out native write must fail")
        } catch (error: ObdTransportDisconnectedException) {
            assertTrue(ObdTransportFailurePolicy.isConnectionLoss(error))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(1, closed.get())
            assertEquals(1, sends.get())
        }
    }

    @Test fun successfulWriteKeepsSocketAndDriverTimeoutRetiresItOnce() = runBlocking {
        val closes = AtomicInteger()
        BoundedBluetoothConnect.write(2000L, {}, { closes.incrementAndGet() })
        assertEquals(0, closes.get())
        try {
            BoundedBluetoothConnect.write(2000L,
                { throw SocketTimeoutException("native driver timeout") },
                { closes.incrementAndGet() })
            org.junit.Assert.fail("Driver timeout must be propagated")
        } catch (error: ObdTransportDisconnectedException) {
            assertTrue(ObdTransportFailurePolicy.isConnectionLoss(error))
            assertEquals(1, closes.get())
        }
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
