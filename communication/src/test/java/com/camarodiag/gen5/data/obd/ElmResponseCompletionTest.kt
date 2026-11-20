package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.SocketTimeoutException

class ElmResponseCompletionTest {
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
