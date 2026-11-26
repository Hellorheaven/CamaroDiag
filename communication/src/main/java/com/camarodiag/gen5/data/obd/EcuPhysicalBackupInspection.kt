package com.camarodiag.gen5.data.obd

import java.io.DataInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Read-only inspection of previously persisted physical ECU sector backups.
 *
 * This verifies the hash-chained journal first, then matches every BASELINE_CHECKED
 * sector to an independently read, bounded, original-sector file. It never
 * contacts a vehicle, creates a writer, repairs a file or authorizes a restore.
 */
object EcuPhysicalBackupInspection {
    data class Sector(
        val region: EcuBackupCoverage.Region,
        val address: Long,
        val length: Long,
        val sha256: String
    )

    data class Report(
        val verified: Boolean,
        val expectedSectors: Int,
        val sectors: List<Sector>,
        val verifiedBytes: Long,
        val problem: String,
        val uncertainAddress: Long? = null,
        // A valid physical backup is NOT proof that a programming transaction
        // completed: an erase may still be pending or recovery may be required.
        val journalDisposition: PersistentEcuWriteJournal.RecoveryDisposition? = null,
        val problemAddress: Long? = null,
        val uncertainRegion: EcuBackupCoverage.Region? = null
    )

    private const val MAX_JOURNAL_BYTES = 8L * 1024 * 1024
    private const val MAX_BLOCK_BYTES = 64L * 1024 * 1024
    private const val MAX_BLOCK_FILE_BYTES = MAX_BLOCK_BYTES + 65536L

    fun inspect(
        journalFile: File,
        target: EcuProgrammingDriverContract.FirmwareTarget,
        vin: String,
        checkActive: () -> Unit = {}
    ): Report {
        var expectedCount = 0
        var current: Long? = null
        var auditedSnapshot: ByteArray? = null
        var auditedJournal: PersistentEcuWriteJournal.Audit? = null
        val verified = mutableListOf<Sector>()
        var verifiedBytes = 0L
        return try {
            // Audit exactly the snapshot used to derive the baseline list.
            // A different read of a changing file must never supply the evidence.
            fun readSnapshot(): ByteArray {
                checkActive()
                val length = journalFile.length()
                check(length in 1L..MAX_JOURNAL_BYTES) { "Invalid ECU journal size." }
                return ByteArray(length.toInt()).also { data ->
                    DataInputStream(journalFile.inputStream().buffered()).use { input ->
                        input.readFully(data)
                        check(input.read() == -1) { "ECU journal changed while being inspected." }
                    }
                }
            }
            val data = readSnapshot()
            val audit = PersistentEcuWriteJournal.inspectSnapshot(journalFile, target, vin, data)
            check(audit.valid) { "ECU journal is missing, altered or out of sequence." }
            auditedSnapshot = data
            auditedJournal = audit
            val records = String(data, StandardCharsets.UTF_8).trimEnd('\n').split('\n')
            val expected = linkedMapOf<Pair<EcuBackupCoverage.Region, Long>, String>()
            for (line in records.drop(1)) {
                val columns = line.split('\t')
                check(columns.size == 8) { "Invalid journal baseline record." }
                if (columns[2] == EcuWriteTransaction.Stage.BASELINE_CHECKED.name) {
                    val region = EcuBackupCoverage.Region.valueOf(columns[3])
                    val address = columns[4].toLong()
                    check(expected.putIfAbsent(region to address, columns[5]) == null) {
                        "Duplicate physical ECU baseline."
                    }
                }
            }
            expectedCount = expected.size
            check(expectedCount > 0 && audit.baselineSectors == expectedCount) {
                "No verified original ECU sector baselines."
            }
            val directory = File(journalFile.absolutePath + ".blocks")
            check(directory.isDirectory) { "Missing original ECU sector backup directory." }
            val files = directory.listFiles()
                ?: error("Cannot enumerate persisted original ECU sectors.")
            check(files.size == expectedCount && files.all { it.isFile }) {
                "Physical ECU backup files do not match the journal baselines."
            }
            val observed = hashSetOf<Pair<EcuBackupCoverage.Region, Long>>()
            for (file in files) {
                current = null
                checkActive()
                check(file.length() in 1L..MAX_BLOCK_FILE_BYTES) {
                    "Saved physical ECU block exceeds the supported size."
                }
                DataInputStream(file.inputStream().buffered()).use { input ->
                    check(input.readUTF() == "CAMARODIAG-PHYSICAL-BLOCK-V1") {
                        "Unknown original ECU sector file schema."
                    }
                    check(input.readUTF() == target.controllerFamily &&
                        input.readUTF() == target.controllerId &&
                        input.readUTF() == target.firmwareId &&
                        input.readUTF() == vin
                    ) { "Saved sector VIN or firmware does not match the journal." }
                    val region = EcuBackupCoverage.Region.valueOf(input.readUTF())
                    val address = input.readLong()
                    current = address
                    val size = input.readLong()
                    val originalHash = input.readUTF()
                    val byteLength = input.readInt()
                    check(size in 1L..MAX_BLOCK_BYTES &&
                        byteLength.toLong() == size &&
                        file.name == "${region.name}-${address.toString(16)}-$size.ecu-block"
                    ) { "Saved sector size, address or file name is invalid." }
                    val key = region to address
                    val journalHash = expected[key]
                    check(journalHash != null && originalHash.equals(journalHash, true) &&
                        observed.add(key)
                    ) { "Saved sector is not tied to a unique journal baseline." }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    var remaining = size
                    while (remaining > 0) {
                        checkActive()
                        val count = minOf(remaining, buffer.size.toLong()).toInt()
                        input.readFully(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        remaining -= count
                    }
                    check(input.read() == -1) { "Extra data after the original ECU sector." }
                    val actualSha = digest.digest().joinToString("") {
                        "%02x".format(it.toInt() and 0xff)
                    }
                    check(actualSha.equals(journalHash, true)) {
                        "The physical ECU sector changed after it was saved."
                    }
                    verified += Sector(region, address, size, actualSha)
                    verifiedBytes = Math.addExact(verifiedBytes, size)
                }
            }
            current = null
            check(observed.size == expectedCount) {
                "Not every original ECU sector has a matching physical backup."
            }
            check(data.contentEquals(readSnapshot())) {
                "ECU journal changed during physical backup inspection."
            }
            Report(true, expectedCount, verified.sortedWith(compareBy({ it.region.name },
                { it.address })), verifiedBytes, "",
                uncertainAddress = audit.uncertainAddress,
                journalDisposition = audit.recoveryDisposition, uncertainRegion = audit.uncertainRegion)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A bad block does not erase a valid warning in an unchanged journal.
            checkActive()
            val stableAudit = auditedSnapshot?.let { snapshot ->
                try {
                    if (journalFile.length() != snapshot.size.toLong()) null
                    else DataInputStream(journalFile.inputStream().buffered()).use { input ->
                        val again = ByteArray(snapshot.size)
                        input.readFully(again)
                        if (input.read() == -1 && snapshot.contentEquals(again)) auditedJournal else null
                    }
                } catch (cancelled: java.util.concurrent.CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
            }
            Report(false, expectedCount, emptyList(), 0L,
                error.message ?: "ECU recovery evidence cannot be inspected.",
                uncertainAddress = stableAudit?.uncertainAddress,
                journalDisposition = stableAudit?.recoveryDisposition,
                problemAddress = current, uncertainRegion = stableAudit?.uncertainRegion)
        }
    }
}
