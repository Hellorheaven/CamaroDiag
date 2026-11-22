package com.camarodiag.gen5.ui

import com.camarodiag.gen5.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CanTraceInputTest {
    @Test fun acceptsUtf8AtTheExactByteLimit() = runBlocking {
        val bytes = "véhicule".toByteArray(Charsets.UTF_8)
        assertEquals("véhicule", bytes.inputStream().readCanTraceText(bytes.size))
    }

    @Test fun distinguishesEmptyInputFromOverflowAcrossBuffers() {
        val empty = assertThrows(CanTraceInputException::class.java) {
            runBlocking { byteArrayOf().inputStream().readCanTraceText(8192) }
        }
        assertEquals(R.string.msg_trace_empty, empty.messageRes)
        val overflow = assertThrows(CanTraceInputException::class.java) {
            runBlocking { ByteArray(8193).inputStream().readCanTraceText(8192) }
        }
        assertEquals(R.string.msg_trace_too_large, overflow.messageRes)
    }
}
