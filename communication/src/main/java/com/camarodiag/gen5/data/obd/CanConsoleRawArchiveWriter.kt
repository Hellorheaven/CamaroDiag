package com.camarodiag.gen5.data.obd

import java.io.Closeable
import java.io.IOException
import java.io.Writer

/**
 * Archives the unchanged physical CAN stream. Local storage failures are
 * deliberately distinct from transport I/O failures, including at close.
 */
class CanConsoleRawArchiveWriter(private val writer: Writer) : Closeable {
    fun append(chunk: String) {
        try { writer.write(chunk) }
        catch (error: IOException) { throw CanCaptureArchiveWriteException(error) }
    }

    override fun close() {
        try { writer.close() }
        catch (error: IOException) { throw CanCaptureArchiveWriteException(error) }
    }
}
