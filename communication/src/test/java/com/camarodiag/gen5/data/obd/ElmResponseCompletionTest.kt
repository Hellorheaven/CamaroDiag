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
    fun allowsStreamingCaptureWithoutNormalPrompt() {
        ElmResponseCompletion.requirePrompt("ATMA", streaming = true, promptReceived = false)
    }
}
