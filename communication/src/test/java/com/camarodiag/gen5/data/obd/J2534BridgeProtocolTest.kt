package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class J2534BridgeProtocolTest {
    @Test
    fun readsOneJsonLineAndLeavesFollowingResponseAvailable() {
        val input = BufferedReader(StringReader("{\"ok\":true}\\n{\"ok\":false}\\n"))

        assertEquals("{\"ok\":true}", J2534BridgeProtocol.readBoundedLine(input))
        assertEquals("{\"ok\":false}", J2534BridgeProtocol.readBoundedLine(input))
        assertNull(J2534BridgeProtocol.readBoundedLine(input))
    }

    @Test
    fun returnsFinalLineWhenPeerClosesWithoutNewline() {
        val input = BufferedReader(StringReader("{\"ok\":true}"))

        assertEquals("{\"ok\":true}", J2534BridgeProtocol.readBoundedLine(input))
        assertNull(J2534BridgeProtocol.readBoundedLine(input))
    }

    @Test
    fun rejectsOversizedBridgeResponseLine() {
        val input = BufferedReader(StringReader("x".repeat(32 * 1024 + 1) + "\\n"))

        assertThrows(IllegalStateException::class.java) {
            J2534BridgeProtocol.readBoundedLine(input)
        }
    }
}
