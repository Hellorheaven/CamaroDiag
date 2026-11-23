package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.CanConsoleEventJournalWriter
import java.io.File

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
    private var disposed = false
    private var generation = 0L

    @Synchronized
    fun append(events: List<CanConsoleHistoryEntry>) {
        if (events.isEmpty()) return
        check(!disposed) { "Console event journal already disposed" }
        check(!failed) { "Console event journal previously failed" }
        try {
            // A deleted/renamed file can still accept writes through an open
            // descriptor on some filesystems; never keep an unexportable journal.
            if (writer != null) {
                check(archive?.isFile == true) {
                    "Console event journal file disappeared during capture"
                }
            }
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
        if (failed || disposed) return null
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
        !failed && !disposed && archive == snapshot.file && generation == snapshot.generation &&
            writer?.eventCount == snapshot.eventCount && snapshot.file.isFile

    /** Cheap UI capability check; snapshot() performs the final file flush. */
    @Synchronized
    fun isAvailable(): Boolean =
        !failed && !disposed && writer != null && archive?.isFile == true

    @Synchronized
    fun hasFailed(): Boolean = failed || disposed

    @Synchronized
    fun reset() {
        if (disposed) return
        releaseFile()
    }

    /** Irreversible on ViewModel destruction: canceled workers cannot resurrect a file. */
    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        releaseFile()
    }

    private fun releaseFile() {
        val old = archive
        try {
            writer?.close()
        } catch (_: Exception) {
            // Teardown must always discard the prior session even if the
            // wrapped writer fails during flush or close.
        } finally {
            writer = null
            archive = null
            failed = false
            generation++
            old?.delete()
        }
    }
}
