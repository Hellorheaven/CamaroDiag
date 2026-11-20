package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanConsoleRecordParserTest {
    @Test
    fun classifiesOnlyValidatedCanFramesAsReceivedTraffic() {
        val records = CanConsoleRecordParser.parse(
            "OK\r\n7E8 05 41 0C 1A F8 00\r\n18DAF110 03 7F 22 31"
        )

        assertEquals(listOf("SYSTEM", "RX", "RX"), records.map { it.direction })
        assertEquals("OK", records.first().content)
        assertEquals("CAN ID=7E8 DLC=5 DATA=41 0C 1A F8 00", records[1].content)
        assertEquals("CAN ID=18DAF110 DLC=3 DATA=7F 22 31", records[2].content)
    }

    @Test
    fun zeroLengthCanFramesAreReceivedRatherThanSystemMessages() {
        val records = CanConsoleRecordParser.parse(
            "7E8 00\r\n18DAF110 0\r\n7E8 00 FF"
        )

        assertEquals(listOf("RX", "RX", "SYSTEM"), records.map { it.direction })
        assertEquals("CAN ID=7E8 DLC=0 DATA=(empty)", records[0].content)
        assertEquals("CAN ID=18DAF110 DLC=0 DATA=(empty)", records[1].content)
    }

    @Test
    fun adapterErrorsRemainSystemEventsAndAreMarkedFailed() {
        val records = CanConsoleRecordParser.parse(
            "NO DATA\r\nCAN ERROR\r\nBUFFER FULL\r\nSEARCHING..."
        )

        assertTrue(records.all { it.direction == "SYSTEM" })
        assertFalse(records[0].succeeded)
        assertFalse(records[1].succeeded)
        assertFalse(records[2].succeeded)
        assertTrue(records[3].succeeded)
    }

    @Test
    fun ignoresEmptyLinesAndStripsMonitorPrompt() {
        val records = CanConsoleRecordParser.parse("\r\n7E8 02 41 0C>\r\n")

        assertEquals(1, records.size)
        assertEquals("RX", records.single().direction)
        assertEquals("CAN ID=7E8 DLC=2 DATA=41 0C", records.single().content)
    }
}
