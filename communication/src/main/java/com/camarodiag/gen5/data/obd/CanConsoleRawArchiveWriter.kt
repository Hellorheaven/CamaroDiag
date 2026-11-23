package com.camarodiag.gen5.data.obd

import java.io.Closeable
import java.io.IOException
import java.io.Writer

/**
 * Archives the unchanged physical CAN stream. Local storage failures are
 * deliberately distinct from transport I/O failures, including at close.
 *
 * An archive that experienced a failed write or flush cannot silently resume
 * and later masquerade as a complete physical capture.
 */
class CanConsoleRawArchiveWriter(private val writer: Writer) : Closeable {
    private var closed = false
    private var failed = false

    /** Any failed operation permanently marks this archive incomplete. */
    val isComplete: Boolean get() = closed && !failed
    val hasFailed: Boolean get() = failed

    fun append(chunk: String) {
        check(!closed) { "CAN archive is already closed" }
        check(!failed) { "CAN archive storage failed; capture is incomplete" }
        try {
            writer.write(chunk)
        } catch (error: IOException) {
            failed = true
            throw CanCaptureArchiveWriteException(error)
        }
    }

    fun flush() {
        check(!closed) { "CAN archive is already closed" }
        check(!failed) { "CAN archive storage failed; capture is incomplete" }
        try {
            writer.flush()
        } catch (error: IOException) {
            failed = true
            throw CanCaptureArchiveWriteException(error)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            writer.close()
        } catch (error: IOException) {
            failed = true
            throw CanCaptureArchiveWriteException(error)
        }
    }
}
