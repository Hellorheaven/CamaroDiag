package com.camarodiag.gen5.data.obd

import java.io.Closeable
import java.io.IOException
import java.io.Writer

/**
 * Unbounded, session-specific console event archive (TX/RX/SYSTEM).
 * This does not replace the raw RX stream archive. Caller supplies a fresh
 * destination per connection; a storage failure invalidates the entire journal.
 */
class CanConsoleEventJournalWriter(private val destination: Writer) : Closeable {
    enum class Direction { TX, RX, SYSTEM }

    private var closed = false
    private var failed = false
    private var count = 0L

    val isComplete: Boolean @Synchronized get() = closed && !failed
    val hasFailed: Boolean @Synchronized get() = failed
    val eventCount: Long @Synchronized get() = count

    init {
        writeOrFail { destination.write("sequence,utc_millis,direction,succeeded,content\n") }
    }

    @Synchronized
    fun append(utcMillis: Long, direction: Direction, succeeded: Boolean, content: String) {
        check(!closed) { "Console event journal closed" }
        check(!failed) { "Console event journal incomplete" }
        require(utcMillis >= 0L)
        check(count < Long.MAX_VALUE)
        val next = count + 1L
        // ECU and adapter text is untrusted: quoted CSV alone does not stop
        // spreadsheet programs from evaluating leading formula operators.
        val trimmed = content.trimStart()
        val formula = trimmed.startsWith('=') || trimmed.startsWith('+') ||
            trimmed.startsWith('-') || trimmed.startsWith('@')
        val safeContent = if (formula) "'$content" else content
        val escaped = safeContent.replace("\r", "\\r").replace("\n", "\\n")
            .replace("\"", "\"\"")
        writeOrFail {
            destination.write("$next,$utcMillis,${direction.name},$succeeded,\"$escaped\"\n")
        }
        count = next
    }

    @Synchronized
    fun flush() {
        check(!closed)
        check(!failed)
        writeOrFail { destination.flush() }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var error: IOException? = null
        try {
            destination.flush()
        } catch (e: IOException) {
            error = e
        }
        try {
            destination.close()
        } catch (e: IOException) {
            if (error == null) error = e else error.addSuppressed(e)
        }
        if (error != null) {
            failed = true
            throw CanCaptureArchiveWriteException(error)
        }
    }

    private inline fun writeOrFail(action: () -> Unit) {
        try {
            action()
        } catch (e: IOException) {
            failed = true
            throw CanCaptureArchiveWriteException(e)
        }
    }
}
