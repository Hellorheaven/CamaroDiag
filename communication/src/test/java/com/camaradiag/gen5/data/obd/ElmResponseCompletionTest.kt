package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.SocketTimeoutException

class ElmResponseCompletionTest {
    @Test fun stripsOnlyEchoAndKeepsCommandBytesInsideCalibrationData() {
        val reply = "490601000906FF"
        org.junit.Assert.assertEquals(reply, normalizeElmReply("0906\r\n$reply\r", "0906"))
        org.junit.Assert.assertEquals(reply, normalizeElmReply(reply, "0906"))
    }

    @Test
    fun monitorInterruptIsOneByteWithoutCommandRepeat() {
        org.junit.Assert.assertArrayEquals(byteArrayOf(0x1B), ElmResponseCompletion.wireBytes("\u001B"))
        org.junit.Assert.assertArrayEquals("0100\r".toByteArray(Charsets.US_ASCII),
            ElmResponseCompletion.wireBytes(" 0100 "))
        org.junit.Assert.assertTrue(ElmResponseCompletion.isMonitorInterrupt("\u001B"))
        org.junit.Assert.assertFalse(ElmResponseCompletion.isMonitorInterrupt("ATMA"))
        assertThrows(SocketTimeoutException::class.java) {
            ElmResponseCompletion.requirePrompt("\u001B", streaming = false, promptReceived = false)
        }
    }

    @Test
    fun acceptsOrdinaryCommandWhenPromptWasReceived() {
        ElmResponseCompletion.requirePrompt("010C", streaming = false, promptReceived = true)
    }

    @Test
    fun rejectsOrdinaryCommandWhenPromptWasNotReceived() {
        assertThrows(SocketTimeoutException::class.java) {
            ElmResponseCompletion.requirePrompt("010C", streaming = false, promptReceived = false)
        }
    }

    @Test
    fun receiveCallbackOnPhysicalTxDoesNotWaiveTheElmPrompt() {
        org.junit.Assert.assertTrue(
            ElmResponseCompletion.isUnterminatedMonitorCommand("STM", true)
        )
        org.junit.Assert.assertTrue(
            ElmResponseCompletion.isUnterminatedMonitorCommand("ATMA", true)
        )
        for (command in listOf("03 22 F1 90", "ATSP6", "010C", "STPX")) {
            org.junit.Assert.assertFalse(
                ElmResponseCompletion.isUnterminatedMonitorCommand(command, true)
            )
            assertThrows(SocketTimeoutException::class.java) {
                ElmResponseCompletion.requirePrompt(
                    command,
                    streaming = ElmResponseCompletion.isUnterminatedMonitorCommand(command, true),
                    promptReceived = false
                )
            }
        }
    }

    @Test
    fun allowsStreamingCaptureWithoutNormalPrompt() {
        ElmResponseCompletion.requirePrompt("ATMA", streaming = true, promptReceived = false)
    }

    @Test fun startupDrainsResidualPromptWithoutTransmittingAnything() = kotlinx.coroutines.runBlocking {
        var now = 0L
        var remaining = "OLD RESPONSE\r>".toByteArray()
        var stops = 0
        ElmStartupSynchronization.synchronize(
            available = { remaining.size },
            read = { count -> remaining.copyOfRange(0, count).also { remaining = remaining.drop(count).toByteArray() } },
            interrupt = { stops++ },
            ensureCurrent = {},
            clockMs = { now },
            pause = { now += it }
        )
        org.junit.Assert.assertEquals(0, stops)
        org.junit.Assert.assertTrue(remaining.isEmpty())
        org.junit.Assert.assertTrue(now in 75L..100L)
    }

    @Test fun startupInterruptsAnOldMonitorExactlyOnceAndWaitsForPrompt() = kotlinx.coroutines.runBlocking {
        var now = 0L
        var stops = 0
        var prompt = false
        ElmStartupSynchronization.synchronize(
            available = { if (stops == 0) 256 else if (!prompt) 1 else 0 },
            read = { count ->
                if (stops == 0) ByteArray(count) { 0x41 }
                else { prompt = true; byteArrayOf('>'.code.toByte()) }
            },
            interrupt = { stops++ },
            ensureCurrent = {},
            clockMs = { now },
            pause = { now += it }
        )
        org.junit.Assert.assertEquals(1, stops)
        org.junit.Assert.assertTrue(prompt)
        org.junit.Assert.assertTrue(now >= 75L)
    }

    @Test fun startupFailsWhenInterruptedMonitorNeverReturnsPrompt() = kotlinx.coroutines.runBlocking {
        var now = 0L
        var stops = 0
        try {
            ElmStartupSynchronization.synchronize(
                available = { if (stops == 0) 256 else 0 },
                read = { ByteArray(it) { 0x41 } },
                interrupt = { stops++ },
                ensureCurrent = {},
                clockMs = { now },
                pause = { now += it }
            )
            org.junit.Assert.fail("Missing prompt must fail startup")
        } catch (_: SocketTimeoutException) {
            org.junit.Assert.assertEquals(1, stops)
            org.junit.Assert.assertTrue(now in 2000L..2010L)
        }
    }

    @Test fun startupNeverInterruptsAReplacedSocket() = kotlinx.coroutines.runBlocking {
        var now = 0L
        var stops = 0
        try {
            ElmStartupSynchronization.synchronize(
                available = { 256 },
                read = { ByteArray(it) { 0x41 } },
                interrupt = { stops++ },
                ensureCurrent = { if (now >= 1L) throw java.io.IOException("Replaced") },
                clockMs = { now },
                pause = { now += it }
            )
            org.junit.Assert.fail("Replaced socket must not continue")
        } catch (_: java.io.IOException) {
            org.junit.Assert.assertEquals(0, stops)
        }
    }

    @Test fun responseDeadlineClampsFinalReadAndNeverReturnsAnInfiniteTimeout() {
        var time = 1000L
        val budget = ElmCommandReadBudget(250L) { time }
        org.junit.Assert.assertEquals(100, budget.pollTimeoutMs(100))
        time = 1249L
        org.junit.Assert.assertEquals(1, budget.pollTimeoutMs(100))
        time = 1250L
        org.junit.Assert.assertNull(budget.pollTimeoutMs(100))
        time = 1500L
        org.junit.Assert.assertEquals(0L, budget.remainingMs())
    }

}
