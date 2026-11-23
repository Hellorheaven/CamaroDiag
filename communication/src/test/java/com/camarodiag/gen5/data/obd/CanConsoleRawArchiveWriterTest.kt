package com.camarodiag.gen5.data.obd

import java.io.IOException
import java.io.StringWriter
import java.io.Writer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CanConsoleRawArchiveWriterTest {
    @Test fun archivedCanBytesAreUnmodified() {
        val sink = StringWriter()
        CanConsoleRawArchiveWriter(sink).use { archive ->
            archive.append("7E8 03 41 0C 1A\r")
            archive.append("\n7E9 03 41 0D 1B")
        }
        assertEquals("7E8 03 41 0C 1A\r\n7E9 03 41 0D 1B", sink.toString())
    }

    @Test fun writeErrorIsNotAnAdapterDisconnect() {
        val sink = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) {
                throw IOException("disk full")
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val error = assertThrows(CanCaptureArchiveWriteException::class.java) {
            CanConsoleRawArchiveWriter(sink).use { it.append("7E8 03 41 0C 1A") }
        }
        assertEquals("disk full", error.cause?.message)
        assertEquals(false, ObdTransportFailurePolicy.isConnectionLoss(error))
    }

    @Test fun closeErrorIsNotAnAdapterDisconnect() {
        val sink = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
            override fun flush() = Unit
            override fun close() { throw IOException("failed to sync archive") }
        }
        val error = assertThrows(CanCaptureArchiveWriteException::class.java) {
            CanConsoleRawArchiveWriter(sink).use { it.append("7E8 03 41 0C 1A") }
        }
        assertEquals("failed to sync archive", error.cause?.message)
        assertEquals(false, ObdTransportFailurePolicy.isConnectionLoss(error))
    }
    @Test fun completedArchiveReportsSuccessAndRejectsLateAppends() {
        val sink = StringWriter()
        val archive = CanConsoleRawArchiveWriter(sink)
        archive.append("7E8 01 41")
        archive.flush()
        assertEquals(false, archive.isComplete)
        archive.close()
        assertEquals(true, archive.isComplete)
        archive.close()
        assertThrows(IllegalStateException::class.java) {
            archive.append("7E8 01 42")
        }
        assertEquals("7E8 01 41", sink.toString())
    }

    @Test fun failedWriteMakesArchivePermanentlyIncomplete() {
        var writes = 0
        val sink = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) {
                writes++
                throw IOException("storage exhausted")
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val archive = CanConsoleRawArchiveWriter(sink)
        assertThrows(CanCaptureArchiveWriteException::class.java) {
            archive.append("7E8 01 41")
        }
        assertEquals(true, archive.hasFailed)
        assertThrows(IllegalStateException::class.java) {
            archive.append("7E8 01 42")
        }
        archive.close()
        assertEquals(false, archive.isComplete)
        assertEquals(1, writes)
    }

    @Test fun failedFlushMakesArchivePermanentlyIncomplete() {
        val sink = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
            override fun flush() { throw IOException("failed to flush") }
            override fun close() = Unit
        }
        val archive = CanConsoleRawArchiveWriter(sink)
        archive.append("7E8 01 41")
        assertThrows(CanCaptureArchiveWriteException::class.java) {
            archive.flush()
        }
        assertThrows(IllegalStateException::class.java) {
            archive.append("7E8 01 42")
        }
        archive.close()
        assertEquals(true, archive.hasFailed)
        assertEquals(false, archive.isComplete)
    }

    @Test fun flushFailureDuringCloseStillClosesSinkAndReportsIncomplete() {
        var actualCloses = 0
        val sink = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
            override fun flush() { throw IOException("flush failed on close") }
            override fun close() { actualCloses++ }
        }
        val archive = CanConsoleRawArchiveWriter(sink)
        archive.append("7E8 01 41")
        val error = assertThrows(CanCaptureArchiveWriteException::class.java) {
            archive.close()
        }
        assertEquals("flush failed on close", error.cause?.message)
        assertEquals(1, actualCloses)
        assertEquals(true, archive.hasFailed)
        assertEquals(false, archive.isComplete)
    }

}
