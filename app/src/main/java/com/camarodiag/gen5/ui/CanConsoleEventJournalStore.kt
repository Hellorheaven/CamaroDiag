package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.CanConsoleEventJournalWriter
import java.io.File
import java.io.IOException

/**
 * Private, per-console-session on-disk event journal.
 *
 * A new journal is created lazily on the first event. Snapshot is valid only
 * while the generation/count remain unchanged; the export worker must recheck
 * both before publishing an output. Clear/account/vehicle changes discard it.
 */
internal class CanConsoleEventJournalStore(private val directory: File) {
    data class Snapshot(val file: File, val generation: Long, val eventCount: Long)

    private var archive: File? = null
    private var writer: CanConsoleEventJournalWriter? = null
    private var failed = false
    private var generation = 0L

    @Synchronized
    fun append(events: List<CanConsoleHistoryEntry>) {
        if (events.isEmpty()) return
        check(!failed) { "Console event journal previously failed" }
        try {
            if (writer == null) {
                val file = File.createTempFile("camarodiag-console-events-", ".csv", directory)
                try {
                    writer = CanConsoleEventJournalWriter(file.bufferedWriter(Charsets.UTF_8))
                    archive = file
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
            }
            val target = checkNotNull(writer)
            for (event in events) {
                val direction = CanConsoleEventJournalWriter.Direction.valueOf(event.direction)
                target.append(event.timestampMillis, direction, event.succeeded, event.content)
            }
            // Do not retain the only event copy in a volatile in-memory writer buffer.
            target.flush()
            generation++
        } catch (error: Exception) {
            failed = true
            throw error
        }
    }

    @Synchronized
    fun snapshot(): Snapshot? {
        if (failed) return null
        val target = writer ?: return null
        try {
            target.flush()
        } catch (error: Exception) {
            failed = true
            return null
        }
        val file = archive?.takeIf { it.isFile } ?: run {
            failed = true
            return null
        }
        return Snapshot(file, generation, target.eventCount)
    }

    @Synchronized
    fun isCurrent(snapshot: Snapshot): Boolean =
        !failed && archive == snapshot.file && generation == snapshot.generation &&
            writer?.eventCount == snapshot.eventCount && snapshot.file.isFile

    @Synchronized
    fun hasFailed(): Boolean = failed

    @Synchronized
    fun reset() {
        val old = archive
        try {
            writer?.close()
        } catch (_: IOException) {
            // Reset never publishes the old journal, even after close failure.
        } catch (_: IllegalStateException) {
            // The previous journal already failed and cannot be reused.
        } finally {
            writer = null
            archive = null
            failed = false
            generation++
            old?.delete()
        }
    }
}
