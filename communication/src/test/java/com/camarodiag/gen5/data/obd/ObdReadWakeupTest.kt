package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdReadWakeupTest {
    @Test
    fun disconnectWakesOnlyTheReaderCurrentlyWaiting() = runBlocking {
        val wakeup = ObdReadWakeup()
        val firstWaiter = wakeup.beginWait()
        val failure = IllegalStateException("BLE disconnected")

        wakeup.signal(failure)

        assertTrue(firstWaiter.isCompleted)
        assertSame(failure, firstWaiter.await())
        wakeup.endWait(firstWaiter)

        val nextWaiter = wakeup.beginWait()
        assertFalse(nextWaiter.isCompleted)
        wakeup.endWait(nextWaiter)
    }

    @Test
    fun endingAnOlderWaitDoesNotClearAReplacementWaiter() = runBlocking {
        val wakeup = ObdReadWakeup()
        val olderWaiter = wakeup.beginWait()
        val currentWaiter = wakeup.beginWait()
        val failure = IllegalStateException("Current BLE session disconnected")

        wakeup.endWait(olderWaiter)
        wakeup.signal(failure)

        assertFalse(olderWaiter.isCompleted)
        assertTrue(currentWaiter.isCompleted)
        assertSame(failure, currentWaiter.await())
        wakeup.endWait(currentWaiter)
    }
}
