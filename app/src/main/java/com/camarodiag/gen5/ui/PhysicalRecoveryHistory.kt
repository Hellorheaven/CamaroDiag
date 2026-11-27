package com.camarodiag.gen5.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.camarodiag.gen5.data.obd.EcuPhysicalBackupInspection
import com.camarodiag.gen5.data.obd.PersistentEcuWriteJournal
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

/**
 * Compare explicitly ordered, independently inspected Android evidence trees.
 * No device connection, reconstruction, journal repair or restore authorization.
 * SAF does not provide an atomic multi-document snapshot or provenance proof.
 */
object PhysicalRecoveryHistory {
    const val MAX_SNAPSHOTS = 8
    private const val MAX_JOURNAL = 8 * 1024 * 1024
    private const val MAX_COMBINED = 64L * 1024 * 1024

    enum class Problem {
        INVALID_SELECTION, UNVERIFIED_SNAPSHOT, ORIGINAL_EVIDENCE_CHANGED,
        NOT_STRICT_EXTENSION, TERMINAL_EXTENSION, EVIDENCE_CHANGED, UNAVAILABLE
    }

    data class Snapshot(
        val ordinal: Int,
        val journalName: String,
        val journalBytes: Int,
        val evidence: EcuPhysicalBackupInspection.Report
    )

    data class Transition(
        val fromOrdinal: Int,
        val toOrdinal: Int,
        val addedRecords: Int,
        val addedBytes: Int,
        val previousDisposition: PersistentEcuWriteJournal.RecoveryDisposition,
        val recordedDisposition: PersistentEcuWriteJournal.RecoveryDisposition
    )

    data class Report(
        val identity: PhysicalRecoveryIdentity,
        val verified: Boolean,
        val snapshots: List<Snapshot>,
        val transitions: List<Transition>,
        val problem: Problem? = null,
        val failedOrdinal: Int? = null
    ) {
        val latestEvidence: EcuPhysicalBackupInspection.Report?
            get() = if (verified) snapshots.lastOrNull()?.evidence else null
        val physicalVehicleVerified: Boolean get() = false
        val restoreAuthorized: Boolean get() = false
    }

    private class Rejected(val problem: Problem) : IllegalStateException(problem.name)
    private fun demand(condition: Boolean, problem: Problem) {
        if (!condition) throw Rejected(problem)
    }

    private fun digest(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun validHash(value: String?): Boolean =
        value?.matches(Regex("[0-9a-fA-F]{64}")) == true

    private fun validate(snapshot: PhysicalRecoveryFolder.Captured) {
        val report = snapshot.report
        val audit = report.journalAudit
        demand(snapshot.journalBytes.size in 1..MAX_JOURNAL &&
            snapshot.journalBytes.last() == '\n'.code.toByte() &&
            PhysicalRecoveryFolder.safeName(snapshot.journalName) &&
            report.verified && audit?.valid == true &&
            report.journalDisposition == audit.recoveryDisposition &&
            report.uncertainAddress == audit.uncertainAddress &&
            report.uncertainRegion == audit.uncertainRegion &&
            report.expectedSectors > 0 &&
            audit.baselineSectors == report.expectedSectors &&
            report.sectors.size == report.expectedSectors &&
            report.sectors.all { validHash(it.sha256) && validHash(it.envelopeSha256) &&
                it.address >= 0 && it.length > 0 } &&
            report.sectors.map { it.region to it.address }.distinct().size == report.expectedSectors &&
            report.sectors.sumOf { it.length } == report.verifiedBytes &&
            validHash(report.snapshotSha256) &&
            report.journalSha256.equals(digest(snapshot.journalBytes), ignoreCase = true),
            Problem.UNVERIFIED_SNAPSHOT)
    }

    private fun sectorOrder(report: EcuPhysicalBackupInspection.Report) =
        report.sectors.sortedWith(compareBy({ it.region.name }, { it.address }))

    internal fun strictPrefix(earlier: ByteArray, later: ByteArray): Boolean {
        if (earlier.isEmpty() || later.size <= earlier.size ||
            earlier.last() != '\n'.code.toByte() || later.last() != '\n'.code.toByte()) return false
        for (index in earlier.indices) if (earlier[index] != later[index]) return false
        return true
    }

    /**
     * The acquisition callback returns a verified snapshot and its exact journal.
     * The second callback independently rechecks each source after all comparisons.
     * At most two journal byte arrays are retained by the comparison engine.
     */
    internal suspend fun compare(
        identity: PhysicalRecoveryIdentity,
        count: Int,
        acquire: suspend (Int) -> PhysicalRecoveryFolder.Captured,
        reacquire: suspend (Int) -> EcuPhysicalBackupInspection.Report,
        current: () -> Boolean
    ): Report {
        val scope = currentCoroutineContext()
        fun active() {
            scope.ensureActive()
            check(current())
        }
        val snapshots = mutableListOf<Snapshot>()
        val transitions = mutableListOf<Transition>()
        var ordinal: Int? = null
        var previous: PhysicalRecoveryFolder.Captured? = null
        var total = 0L
        try {
            demand(identity.valid && count in 2..MAX_SNAPSHOTS, Problem.INVALID_SELECTION)
            for (index in 0 until count) {
                ordinal = index + 1
                active()
                val captured = acquire(index)
                active()
                validate(captured)
                total += captured.journalBytes.size
                demand(total <= MAX_COMBINED, Problem.INVALID_SELECTION)
                snapshots += Snapshot(index + 1, captured.journalName,
                    captured.journalBytes.size, captured.report)
                previous?.let { prior ->
                    demand(sectorOrder(snapshots.first().evidence) == sectorOrder(captured.report) &&
                        snapshots.first().evidence.verifiedBytes == captured.report.verifiedBytes,
                        Problem.ORIGINAL_EVIDENCE_CHANGED)
                    demand(strictPrefix(prior.journalBytes, captured.journalBytes),
                        Problem.NOT_STRICT_EXTENSION)
                    val before = checkNotNull(prior.report.journalAudit)
                    val after = checkNotNull(captured.report.journalAudit)
                    demand(!before.completionRecorded, Problem.TERMINAL_EXTENSION)
                    demand(after.records > before.records, Problem.NOT_STRICT_EXTENSION)
                    transitions += Transition(index, index + 1, after.records - before.records,
                        captured.journalBytes.size - prior.journalBytes.size,
                        before.recoveryDisposition, after.recoveryDisposition)
                }
                previous = captured
            }
            for ((index, snapshot) in snapshots.withIndex()) {
                ordinal = index + 1
                active()
                val fresh = reacquire(index)
                active()
                try {
                    EcuPhysicalBackupInspection.requireSameVerifiedEvidence(snapshot.evidence, fresh)
                } catch (_: IllegalStateException) {
                    throw Rejected(Problem.EVIDENCE_CHANGED)
                }
            }
            active()
            return Report(identity, true, snapshots.toList(), transitions.toList())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (rejected: Rejected) {
            return Report(identity, false, snapshots.toList(), transitions.toList(),
                rejected.problem, ordinal)
        } catch (_: Exception) {
            return Report(identity, false, snapshots.toList(), transitions.toList(),
                Problem.UNAVAILABLE, ordinal)
        }
    }

    internal suspend fun inspect(
        resolver: ContentResolver, trees: List<Uri>, cacheDir: File,
        identity: PhysicalRecoveryIdentity, current: () -> Boolean
    ): Report {
        val paths = trees.toList()
        val valid = paths.size in 2..MAX_SNAPSHOTS &&
            runCatching {
                paths.map { uri ->
                    require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
                    requireNotNull(uri.authority) to DocumentsContract.getTreeDocumentId(uri)
                }.distinct().size == paths.size
            }.getOrDefault(false)
        if (!valid) return Report(identity, false, emptyList(), emptyList(), Problem.INVALID_SELECTION)
        return compare(identity, paths.size,
            acquire = { index -> PhysicalRecoveryFolder.capture(resolver, paths[index], cacheDir, identity, current) },
            reacquire = { index -> PhysicalRecoveryFolder.inspect(resolver, paths[index], cacheDir, identity, current) },
            current = current)
    }

    fun requireSameVerifiedEvidence(previous: Report, current: Report) {
        check(previous.verified && current.verified && previous == current) {
            "Recovery history changed or could not be independently verified."
        }
    }
}
