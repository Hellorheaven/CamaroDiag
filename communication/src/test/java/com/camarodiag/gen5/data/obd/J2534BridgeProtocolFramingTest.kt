package com.camarodiag.gen5.data.obd

import java.io.BufferedReader
import java.io.EOFException
import java.io.IOException
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class J2534BridgeProtocolFramingTest {
    private fun reader(value: String) = BufferedReader(StringReader(value))

    @Test fun acceptsLfAndCrLfWithoutConsumingNextFrame() {
        val input = reader("one\r\ntwo\n")
        assertEquals("one", J2534BridgeProtocol.readBoundedLine(input))
        assertEquals("two", J2534BridgeProtocol.readBoundedLine(input))
        assertNull(J2534BridgeProtocol.readBoundedLine(input))
    }

    @Test fun rejectsTruncatedFramesAndEmbeddedCarriageReturns() {
        assertThrows(EOFException::class.java) {
            J2534BridgeProtocol.readBoundedLine(reader("{\"ok\":true}"))
        }
        assertThrows(IOException::class.java) {
            J2534BridgeProtocol.readBoundedLine(reader("a\rb\n"))
        }
        assertThrows(IOException::class.java) {
            J2534BridgeProtocol.readBoundedLine(reader("a\r"))
        }
    }

    @Test fun rejectsRawControlBytesButKeepsEscapedJsonAndUnicode() {
        for (raw in listOf(
            "{\"ok\":true}\u0000\n",
            "{\"ok\":true}\t\n",
            "{\"ok\":true}\u001B\n",
            "{\"ok\":true}\u0001\n"
        )) {
            assertThrows(IOException::class.java) {
                J2534BridgeProtocol.readBoundedLine(reader(raw))
            }
        }
        val legal = "{\"ok\":true,\"adapter\":\"Pont éthanol\",\"text\":\"\\\\u0000\"}"
        assertEquals(legal, J2534BridgeProtocol.readBoundedLine(reader(legal + "\n")))
    }

    @Test fun limitsFrameAndPayloadLengths() {
        val max = "a".repeat(32 * 1024)
        assertEquals(max, J2534BridgeProtocol.readBoundedLine(reader(max + "\n")))
        assertThrows(IllegalStateException::class.java) {
            J2534BridgeProtocol.readBoundedLine(reader(max + "a\n"))
        }
        assertEquals(4091, J2534BridgeProtocol.MAX_PAYLOAD_BYTES)
    }
}
