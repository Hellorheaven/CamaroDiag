package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanConsoleStreamFramerTest {
    @Test fun preservesAllCompleteFramesAcrossOversizedChunks() {
        val framer = CanConsoleStreamFramer()
        val lines = (0 until 350).joinToString("\r\n") { n ->
            if (n % 2 == 0) "7E8 03 41 0C 1A" else "7E9 03 41 0D 1B"
        } + "\r\n"
        val batch = framer.append(lines)
        assertEquals(0, batch.discardedLines)
        assertEquals(350, CanConsoleRecordParser.parse(batch.completeLines).count { it.direction == "RX" })
        assertEquals("", framer.drainTrailing().completeLines)
    }

    @Test fun splitsCrLfAndFramesAcrossArbitraryUsbBleWifiBoundaries() {
        val framer = CanConsoleStreamFramer()
        assertEquals("", framer.append("7E8 03 41").completeLines)
        assertEquals("7E8 03 41 0C 1A\n", framer.append(" 0C 1A\r").completeLines)
        assertEquals("", framer.append("\n7E9 03 41 0D").completeLines)
        assertEquals("7E9 03 41 0D 1B\n", framer.append(" 1B\n").completeLines)
        assertEquals(0, framer.drainTrailing().discardedLines)
    }

    @Test fun oversizedPartialFrameCannotBecomeForgedCanResponse() {
        val framer = CanConsoleStreamFramer(maxLineChars = 24)
        val batch = framer.append("X".repeat(25) + "7E8 03 41 0C 1A\n7E9 03 41 0C 1B\n")
        assertEquals(1, batch.discardedLines)
        val parsed = CanConsoleRecordParser.parse(batch.completeLines)
        assertEquals(1, parsed.size)
        assertTrue(parsed.single().content.contains("ID=7E9"))
    }

    @Test fun incompleteOversizedRecordIsReportedAtEndAndReset() {
        val framer = CanConsoleStreamFramer(maxLineChars = 3)
        assertEquals("", framer.append("LONG").completeLines)
        assertEquals(1, framer.drainTrailing().discardedLines)
        assertEquals("OK\n", framer.append("OK\n").completeLines)
    }

    @Test fun incompleteLastFrameIsOnlyReturnedWhenStreamEnds() {
        val framer = CanConsoleStreamFramer()
        assertEquals("", framer.append("7E8 03 41 0C 1A").completeLines)
        assertEquals("7E8 03 41 0C 1A", framer.drainTrailing().completeLines)
        assertEquals("", framer.drainTrailing().completeLines)
    }
}
