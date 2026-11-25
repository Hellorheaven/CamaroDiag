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
