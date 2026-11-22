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
}
