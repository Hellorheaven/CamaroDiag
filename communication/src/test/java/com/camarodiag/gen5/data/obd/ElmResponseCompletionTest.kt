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
}
