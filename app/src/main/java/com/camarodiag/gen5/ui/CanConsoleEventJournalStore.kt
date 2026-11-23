package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.CanConsoleEventJournalWriter
import java.io.File
import java.security.MessageDigest

/**
 * Private, per-console-session on-disk event journal.
 *
 * A new journal is created lazily on the first event. Snapshot is valid only
 * while the generation/count remain unchanged; the export worker must recheck
 * both before publishing an output. Clear/account/vehicle changes discard it.
 */
internal class CanConsoleEventJournalStore(private val directory: File) {
    data class Snapshot(
        val file: File,
        val generation: Long,
        val eventCount: Long,
        val sizeBytes: Long,
        val sha256: String
    )

    private var archive: File? = null
    private var writer: CanConsoleEventJournalWriter? = null
    private var failed = false
    private var disposed = false
    private var generation = 0L
    private var committedSizeBytes = 0L

    @Synchronized
    fun append(events: List<CanConsoleHistoryEntry>) {
        if (events.isEmpty()) return
        check(!disposed) { "Console event journal already disposed" }
        check(!failed) { "Console event journal previously failed" }
        try {
            // A deleted/renamed file can still accept writes through an open
            // descriptor on some filesystems; never keep an unexportable journal.
            if (writer != null) {
                check(archive?.isFile == true &&
                    archive?.length() == committedSizeBytes) {
                    "Console event journal file changed during capture"
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
            committedSizeBytes = checkNotNull(archive).length()
            check(committedSizeBytes > 0L) { "Console journal flush produced an empty file" }
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
        if (committedSizeBytes <= 0L || file.length() != committedSizeBytes) {
            failed = true
            return null
        }
        return try {
            Snapshot(file, generation, target.eventCount, committedSizeBytes, sha256(file))
        } catch (_: Exception) {
            failed = true
            null
        }
    }

    /** The export checks exact bytes, not only file size and event count.
     * A same-size external mutation invalidates the journal permanently.
     */
    @Synchronized
    fun isCurrent(snapshot: Snapshot): Boolean {
        if (failed || disposed || archive != snapshot.file ||
            generation != snapshot.generation ||
            writer?.eventCount != snapshot.eventCount ||
            !snapshot.file.isFile || snapshot.sizeBytes <= 0L ||
            snapshot.sizeBytes != committedSizeBytes ||
            snapshot.file.length() != snapshot.sizeBytes
        ) return false
        return try {
            val valid = sha256(snapshot.file) == snapshot.sha256
            if (!valid) failed = true
            valid
        } catch (_: Exception) {
            failed = true
            false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Cheap UI capability check; snapshot() performs the final file flush. */
    @Synchronized
    fun isAvailable(): Boolean =
        !failed && !disposed && writer != null && committedSizeBytes > 0L &&
            archive?.isFile == true && archive?.length() == committedSizeBytes

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
            committedSizeBytes = 0L
            failed = false
            generation++
            old?.delete()
        }
    }
}
