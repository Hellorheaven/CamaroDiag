package com.camarodiag.gen5.data.obd

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.DigestInputStream

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
        val sha256: String,
        val envelopeSha256: String? = null
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
        val uncertainRegion: EcuBackupCoverage.Region? = null,
        val snapshotSha256: String? = null,
        val journalSha256: String? = null,
        val journalAudit: PersistentEcuWriteJournal.Audit? = null
    )

    /** A verified snapshot is comparable only with an independently acquired
     * report. Failed backup inspections do not have a full snapshot and are
     * never silently treated as current, even if the error text matches. */
    fun requireSameVerifiedEvidence(previous: Report, current: Report) {
        check(previous.verified && current.verified &&
            previous.snapshotSha256 != null && previous.journalSha256 != null &&
            previous == current) { "Recovery evidence changed or could not be independently verified." }
    }

    private fun digestHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private const val MAX_JOURNAL_BYTES = 8L * 1024 * 1024
    private const val MAX_BLOCK_BYTES = 64L * 1024 * 1024
    private const val MAX_BLOCK_FILE_BYTES = MAX_BLOCK_BYTES + 65536L

    private data class FileIdentity(val size: Long, val modified: java.nio.file.attribute.FileTime,
        val created: java.nio.file.attribute.FileTime, val key: Any?)

    private fun identity(file: File, directory: Boolean = false): FileIdentity {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(!attributes.isSymbolicLink && (if (directory) attributes.isDirectory else attributes.isRegularFile)) {
            "Unexpected or linked recovery evidence path."
        }
        return FileIdentity(attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(), attributes.fileKey())
    }

    private fun openUnlinked(file: File) =
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).buffered()

    // Same canonical framing as the Windows inspector. This is a fingerprint
    // of the inspected evidence, not a signature or a restore authorization.
    internal fun computeSnapshotDigest(journal: ByteArray, envelopes: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(MessageDigest.getInstance("SHA-256").digest(journal))
        for ((name, hash) in envelopes.toSortedMap()) {
            val encoded = name.toByteArray(StandardCharsets.UTF_8)
            require(encoded.isNotEmpty() && '/' !in name && '\\' !in name && hash.size == 32)
            digest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(encoded.size).array())
            digest.update(encoded)
            digest.update(hash)
        }
        return digest.digest().joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }

    private fun inventory(directory: File, expected: Int): List<File> {
        val result = mutableListOf<File>()
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                check(result.size < expected) { "Unexpected original ECU sector file." }
                result += entry.toFile()
            }
        }
        check(result.size == expected) { "Physical ECU backup count differs from journal." }
        return result
    }

    // Different regions may have different address spaces. Within a region
    // reject overlap, negative addresses and arithmetic overflow without
    // inventing OEM erase geometry or assuming a universal memory map.
    internal fun validateRanges(sectors: List<Sector>) {
        check(sectors.isNotEmpty()) { "No original ECU sector ranges." }
        for (group in sectors.groupBy { it.region }.values) {
            var previousEnd = 0L
            for (sector in group.sortedBy { it.address }) {
                check(sector.address >= previousEnd && sector.length > 0 &&
                    sector.address <= Long.MAX_VALUE - sector.length) {
                    "Original ECU sector ranges overlap or exceed address bounds."
                }
                previousEnd = sector.address + sector.length
            }
        }
    }

    private fun envelopeDigest(file: File, expected: FileIdentity, checkActive: () -> Unit): ByteArray {
        checkActive()
        check(identity(file) == expected && expected.size in 1L..MAX_BLOCK_FILE_BYTES) {
            "Saved sector changed during inspection."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        DataInputStream(openUnlinked(file)).use { input ->
            var remaining = expected.size
            while (remaining > 0) {
                checkActive()
                val count = minOf(remaining, buffer.size.toLong()).toInt()
                input.readFully(buffer, 0, count)
                digest.update(buffer, 0, count)
                remaining -= count
            }
            check(input.read() == -1 && identity(file) == expected) {
                "Saved sector changed during inspection."
            }
        }
        return digest.digest()
    }

    fun inspect(
        journalFile: File,
        target: EcuProgrammingDriverContract.FirmwareTarget,
        vin: String,
        checkActive: () -> Unit = {}
    ): Report {
        var expectedCount = 0
        var current: Long? = null
        var auditedSnapshot: ByteArray? = null
        var auditedIdentity: FileIdentity? = null
        var auditedJournal: PersistentEcuWriteJournal.Audit? = null
        val verified = mutableListOf<Sector>()
        var verifiedBytes = 0L
        return try {
            // Audit exactly the snapshot used to derive the baseline list.
            // A different read of a changing file must never supply the evidence.
            fun readSnapshot(): ByteArray {
                checkActive()
                val length = identity(journalFile).size
                check(length in 1L..MAX_JOURNAL_BYTES) { "Invalid ECU journal size." }
                return ByteArray(length.toInt()).also { data ->
                    DataInputStream(openUnlinked(journalFile)).use { input ->
                        input.readFully(data)
                        check(input.read() == -1) { "ECU journal changed while being inspected." }
                    }
                }
            }
            val journalIdentity = identity(journalFile)
            val data = readSnapshot()
            check(identity(journalFile) == journalIdentity) { "ECU journal changed while being inspected." }
            val audit = PersistentEcuWriteJournal.inspectSnapshot(journalFile, target, vin, data)
            check(audit.valid) { "ECU journal is missing, altered or out of sequence." }
            auditedSnapshot = data
            auditedIdentity = journalIdentity
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
            check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Missing or linked original ECU sector backup directory."
            }
            val directoryIdentity = identity(directory, directory = true)
            val files = inventory(directory, expectedCount)
            val fileIdentities = files.associate { it.name to identity(it) }
            val keys = (fileIdentities.values.map { it.key } + journalIdentity.key).filterNotNull()
            check(keys.size == keys.toSet().size) { "Aliased recovery evidence files." }
            check(identity(directory, directory = true) == directoryIdentity) {
                "Original ECU sector directory changed during enumeration."
            }
            val observed = hashSetOf<Pair<EcuBackupCoverage.Region, Long>>()
            val envelopeHashes = linkedMapOf<String, ByteArray>()
            for (file in files) {
                current = null
                checkActive()
                val originalIdentity = fileIdentities.getValue(file.name)
                check(identity(file) == originalIdentity && originalIdentity.size in 1L..MAX_BLOCK_FILE_BYTES) {
                    "Saved physical ECU block exceeds the supported size."
                }
                val envelopeHash = MessageDigest.getInstance("SHA-256")
                DataInputStream(DigestInputStream(openUnlinked(file), envelopeHash)).use { input ->
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
                    check(size in 1L..MAX_BLOCK_BYTES && address >= 0 &&
                        address <= Long.MAX_VALUE - size &&
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
                    check(identity(file) == originalIdentity) { "Saved sector changed during inspection." }
                    verified += Sector(region, address, size, actualSha)
                    verifiedBytes = Math.addExact(verifiedBytes, size)
                }
                val envelope = envelopeHash.digest()
                envelopeHashes[file.name] = envelope
                verified[verified.lastIndex] = verified.last().copy(envelopeSha256 = digestHex(envelope))
            }
            current = null
            validateRanges(verified)
            check(observed.size == expectedCount) {
                "Not every original ECU sector has a matching physical backup."
            }
            check(data.contentEquals(readSnapshot()) && identity(journalFile) == journalIdentity) {
                "ECU journal changed during physical backup inspection."
            }
            // A previously read sector may have been replaced while another
            // sector was hashed. Recheck the complete directory inventory.
            check(identity(directory, directory = true) == directoryIdentity)
            val finalFiles = inventory(directory, expectedCount)
            check(finalFiles.map { it.name }.toSet() == fileIdentities.keys) {
                "Original ECU sector inventory changed during inspection."
            }
            // Metadata alone is insufficient: bytes can change without
            // modifying the file length or observable timestamps.
            for (file in finalFiles) {
                current = verified.firstOrNull {
                    file.name == "${it.region.name}-${it.address.toString(16)}-${it.length}.ecu-block"
                }?.address
                val currentHash = envelopeDigest(file, fileIdentities.getValue(file.name), checkActive)
                check(MessageDigest.isEqual(envelopeHashes.getValue(file.name), currentHash)) {
                    "Saved ECU sector envelope changed during inspection."
                }
            }
            current = null
            check(identity(directory, directory = true) == directoryIdentity &&
                inventory(directory, expectedCount).map { it.name }.toSet() == fileIdentities.keys &&
                data.contentEquals(readSnapshot()) && identity(journalFile) == journalIdentity) {
                "ECU recovery evidence changed during final inspection."
            }
            checkActive()
            Report(true, expectedCount, verified.sortedWith(compareBy({ it.region.name },
                { it.address })), verifiedBytes, "",
                uncertainAddress = audit.uncertainAddress,
                journalDisposition = audit.recoveryDisposition, uncertainRegion = audit.uncertainRegion,
                snapshotSha256 = computeSnapshotDigest(data, envelopeHashes),
                journalSha256 = digestHex(MessageDigest.getInstance("SHA-256").digest(data)),
                journalAudit = audit)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A bad block does not erase a valid warning in an unchanged journal.
            checkActive()
            val stableAudit = auditedSnapshot?.let { snapshot ->
                try {
                    val before = identity(journalFile)
                    if (before != auditedIdentity || before.size != snapshot.size.toLong()) null
                    else DataInputStream(openUnlinked(journalFile)).use { input ->
                        val again = ByteArray(snapshot.size)
                        input.readFully(again)
                        checkActive()
                        if (input.read() == -1 && snapshot.contentEquals(again) &&
                            identity(journalFile) == before) auditedJournal else null
                    }
                } catch (cancelled: java.util.concurrent.CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
            }
            Report(false, expectedCount, emptyList(), 0L,
                error.message ?: "ECU recovery evidence cannot be inspected.",
                uncertainAddress = stableAudit?.uncertainAddress,
                journalDisposition = stableAudit?.recoveryDisposition,
                problemAddress = current, uncertainRegion = stableAudit?.uncertainRegion,
                journalSha256 = if (stableAudit != null && auditedSnapshot != null)
                    digestHex(MessageDigest.getInstance("SHA-256").digest(auditedSnapshot!!)) else null,
                journalAudit = stableAudit)
        }
    }
}
