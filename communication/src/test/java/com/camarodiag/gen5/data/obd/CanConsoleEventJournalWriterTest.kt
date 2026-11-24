package com.camarodiag.gen5.data.obd

import java.io.IOException
import java.io.StringWriter
import java.io.Writer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanConsoleEventJournalWriterTest {
    @Test fun keepsAllEventsBeyondDisplayLimitInOriginalOrder() {
        val output = StringWriter()
        val journal = CanConsoleEventJournalWriter(output)
        repeat(600) { index ->
            val direction = when (index % 3) {
                0 -> CanConsoleEventJournalWriter.Direction.TX
                1 -> CanConsoleEventJournalWriter.Direction.RX
                else -> CanConsoleEventJournalWriter.Direction.SYSTEM
            }
            journal.append(index.toLong(), direction, index % 2 == 0, "event-$index")
        }
        assertEquals(600L, journal.eventCount)
        assertFalse(journal.isComplete)
        journal.close()
        assertTrue(journal.isComplete)
        val lines = output.toString().lines().filter(String::isNotEmpty)
        assertEquals(601, lines.size)
        assertEquals("1,0,TX,PENDING,\"event-0\"", lines[1])
        assertEquals("600,599,SYSTEM,FAILED,\"event-599\"", lines.last())
    }

    @Test fun quotedAndMultilinePayloadsCannotInsertFakeJournalRows() {
        val output = StringWriter()
        val journal = CanConsoleEventJournalWriter(output)
        journal.append(42L, CanConsoleEventJournalWriter.Direction.RX, true, "line1\n\"line2\"\r")
        journal.close()
        val lines = output.toString().lines().filter(String::isNotEmpty)
        assertEquals(2, lines.size)
        assertEquals("1,42,RX,OK,\"line1\\n\"\"line2\"\"\\r\"", lines[1])
    }

    @Test fun spreadsheetFormulaPayloadIsStoredAsPlainText() {
        val output = StringWriter()
        val journal = CanConsoleEventJournalWriter(output)
        journal.append(3L, CanConsoleEventJournalWriter.Direction.RX, true, "=HYPERLINK(\"url\")")
        journal.append(4L, CanConsoleEventJournalWriter.Direction.SYSTEM, false, "  +1")
        journal.close()
        assertTrue(output.toString().contains("\"'=HYPERLINK(\"\"url\"\")\""))
        assertTrue(output.toString().contains("\"'  +1\""))
    }

    @Test fun failureLeavesJournalIncompleteAndPreventsSubsequentAppend() {
        val output = object : Writer() {
            private var writes = 0
            override fun write(cbuf: CharArray, off: Int, len: Int) {
                if (++writes > 1) throw IOException("disk full")
            }
            override fun flush() {}
            override fun close() {}
        }
        val journal = CanConsoleEventJournalWriter(output)
        assertTrue(runCatching {
            journal.append(1L, CanConsoleEventJournalWriter.Direction.TX, true, "request")
        }.isFailure)
        assertTrue(journal.hasFailed)
        assertEquals(0L, journal.eventCount)
        assertTrue(runCatching {
            journal.append(2L, CanConsoleEventJournalWriter.Direction.RX, true, "response")
        }.isFailure)
        journal.close()
        assertFalse(journal.isComplete)
    }

    @Test fun failedFlushIsNeverRetriedDuringJournalTeardown() {
        var flushCalls = 0
        var closeCalls = 0
        val output = object : StringWriter() {
            override fun flush() {
                flushCalls++
                throw IOException("disk full")
            }
            override fun close() {
                closeCalls++
                super.close()
            }
        }
        val journal = CanConsoleEventJournalWriter(output)
        journal.append(1L, CanConsoleEventJournalWriter.Direction.TX, false, "request")
        assertTrue(runCatching { journal.flush() }.isFailure)
        assertTrue(journal.hasFailed)
        journal.close()
        assertEquals(1, flushCalls)
        assertEquals(1, closeCalls)
        assertFalse(journal.isComplete)
    }

    @Test fun closeFailureInvalidatesArchive() {
        val output = object : StringWriter() {
            override fun flush() { throw IOException("flush failed") }
        }
        val journal = CanConsoleEventJournalWriter(output)
        assertTrue(runCatching { journal.close() }.isFailure)
        assertTrue(journal.hasFailed)
        assertFalse(journal.isComplete)
    }
}
