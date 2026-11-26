package com.camarodiag.gen5.data.obd

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Crash-auditable, append-only journal for an eventual hardware-validated ECU write.
 *
 * The caller must place [file] in app-private persistent storage (not Downloads
 * or the cache). Each record is physically flushed with FileDescriptor.sync()
 * BEFORE the engine is allowed to erase a sector. A new file is required for
 * every programming attempt: an old or tampered journal is never overwritten.
 *
 * A valid audit is evidence about the transaction log, not proof of a correct
 * ECU flash, OEM checksum, or recoverability.
 */
class PersistentEcuWriteJournal private constructor(
    private val file: File,
    target: EcuProgrammingDriverContract.FirmwareTarget,
    expectedVin: String,
    private val writable: Boolean
) : EcuWriteTransaction.Journal {
    constructor(file: File, target: EcuProgrammingDriverContract.FirmwareTarget, expectedVin: String) :
        this(file, target, expectedVin, true)

    enum class RecoveryDisposition {
        INVALID_OR_UNREADABLE,
        NO_ERASE_RECORDED,
        ERASE_OR_WRITE_MAY_BE_INCOMPLETE,
        ADDITIONAL_BASELINES_NOT_WRITTEN,
        ALL_RECORDED_SECTORS_VERIFIED,
        FINAL_READBACK_INCOMPLETE,
        TRANSACTION_COMPLETION_RECORDED
    }

    data class Audit(
        val valid: Boolean,
        val records: Int,
        val lastStage: EcuWriteTransaction.Stage? = null,
        val baselineSectors: Int = 0,
        val verifiedSectors: Int = 0,
        val uncertainAddress: Long? = null,
        val uncertainRegion: EcuBackupCoverage.Region? = null,
        val finalReadbackSectors: Int = 0,
        val completionRecorded: Boolean = false
    ) {
        /** Journal evidence only, never authorization to retry, restore or program. */
        val recoveryDisposition: RecoveryDisposition
            get() = when {
                !valid -> RecoveryDisposition.INVALID_OR_UNREADABLE
                uncertainAddress != null -> RecoveryDisposition.ERASE_OR_WRITE_MAY_BE_INCOMPLETE
                verifiedSectors == 0 -> RecoveryDisposition.NO_ERASE_RECORDED
                verifiedSectors < baselineSectors -> RecoveryDisposition.ADDITIONAL_BASELINES_NOT_WRITTEN
                completionRecorded -> RecoveryDisposition.TRANSACTION_COMPLETION_RECORDED
                finalReadbackSectors > 0 -> RecoveryDisposition.FINAL_READBACK_INCOMPLETE
                else -> RecoveryDisposition.ALL_RECORDED_SECTORS_VERIFIED
            }
    }

    private val guard = Any()
    private var appendFailed = false
    private var expectedLength = 0L
    private var expectedTailHash = ""
    private val shaPattern = Regex("[0-9a-fA-F]{64}")
    private val header = listOf(
        "CAMARODIAG-ECU-JOURNAL-V1",
        encoded(target.controllerFamily),
        encoded(target.controllerId),
        encoded(target.firmwareId),
        expectedVin
    ).joinToString("\t")

    init {
        require(target.controllerFamily.isNotBlank() &&
            target.controllerId.isNotBlank() && target.firmwareId.isNotBlank()
        ) { "An identified ECU target is required." }
        require(expectedVin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) {
            "A verified, 17-character VIN is required for a write journal."
        }
        require(file.parentFile?.isDirectory == true) {
            "The persistent journal directory must already exist."
        }
        if (writable) {
            check(file.createNewFile()) {
                "Refusing to overwrite or append to an earlier ECU write journal."
            }
            val bytes = (header + "\n").toByteArray(StandardCharsets.UTF_8)
            durableAppend(bytes)
            expectedLength = bytes.size.toLong()
            expectedTailHash = sha256(header)
        }
    }

    override suspend fun append(record: EcuWriteTransaction.Record) {
        check(writable) { "An existing recovery journal is read-only." }
        require(record.address >= 0L)
        require(shaPattern.matches(record.originalSha256) &&
            shaPattern.matches(record.updatedSha256)) {
            "Only complete SHA-256 image digests may be recorded."
        }
        synchronized(guard) {
            check(!appendFailed) { "Earlier journal I/O failed; preserve the journal for assessment." }
            val raw = try {
                readBoundedSnapshot()
            } catch (error: Exception) {
                appendFailed = true
                throw error
            }
            val audit = raw?.let { inspectLocked(raw = it) } ?: Audit(false, 0)
            val lastHash = if (audit.records == 0) sha256(header)
                else raw?.toString(StandardCharsets.UTF_8)?.dropLast(1)?.substringAfterLast('\t')
            if (!audit.valid || raw?.size?.toLong() != expectedLength || lastHash != expectedTailHash) {
                appendFailed = true
                error("The active ECU journal differs from the last durable accepted state.")
            }
            check(inspectLocked(nextRecord = record, raw = raw).valid) {
                "Invalid ECU journal stage transition."
            }
            val payload = listOf(
                audit.records + 1,
                System.currentTimeMillis(),
                record.stage.name,
                record.region.name,
                record.address,
                record.originalSha256.lowercase(),
                record.updatedSha256.lowercase()
            ).joinToString("\t")
            val checksum = sha256("$lastHash\t$payload")
            val bytes = ("$payload\t$checksum\n").toByteArray(StandardCharsets.UTF_8)
            check(file.length() + bytes.size <= MAX_JOURNAL_BYTES) {
                "Journal size limit reached; unsafe to continue ECU write."
            }
            try {
                check(file.length() == expectedLength) { "Journal changed before append." }
                durableAppend(bytes)
                expectedLength += bytes.size
                expectedTailHash = checksum
            } catch (error: Exception) {
                // A failed flush may still have written bytes. Do not retry.
                appendFailed = true
                throw error
            }
        }
    }

    /** Detects truncated records and alterations, including changes to earlier entries. */
    fun inspect(): Audit = synchronized(guard) { inspectLocked() }

    /**
     * Reject impossible state transitions even when record hashes are syntactically
     * valid. All independent baseline reads precede the first erase, and only the
     * next verified sector may be erased. A sector must be independently verified
     * before the next sector can be erased.
     */
    private class SequenceGuard {
        private data class Sector(
            val region: EcuBackupCoverage.Region,
            val address: Long,
            val originalSha256: String,
            val updatedSha256: String
        )

        private val baseline = mutableListOf<Sector>()
        private val completed = mutableSetOf<Sector>()
        private var inFlight: Sector? = null
        private var erasePhase = false
        private var finalReadbacks = 0
        private var completionRecorded = false

        fun audit(records: Int, lastStage: EcuWriteTransaction.Stage?) = Audit(
            valid = true,
            records = records,
            lastStage = lastStage,
            baselineSectors = baseline.size,
            verifiedSectors = completed.size,
            uncertainAddress = inFlight?.address,
            uncertainRegion = inFlight?.region,
            finalReadbackSectors = finalReadbacks,
            completionRecorded = completionRecorded
        )

        fun accept(record: EcuWriteTransaction.Record): Boolean {
            if (completionRecorded) return false
            val sector = Sector(
                record.region, record.address,
                record.originalSha256.lowercase(), record.updatedSha256.lowercase()
            )
            return when (record.stage) {
                EcuWriteTransaction.Stage.BASELINE_CHECKED -> {
                    if (erasePhase || inFlight != null ||
                        baseline.firstOrNull()?.region?.let { sector.region != it } == true || baseline.any {
                        it.region == sector.region && it.address == sector.address
                    } || baseline.lastOrNull()?.address?.let { sector.address <= it } == true) {
                        false
                    } else {
                        baseline += sector
                        true
                    }
                }
                EcuWriteTransaction.Stage.BEFORE_ERASE -> {
                    if (inFlight != null || baseline.getOrNull(completed.size) != sector) {
                        false
                    } else {
                        erasePhase = true
                        inFlight = sector
                        true
                    }
                }
                EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE -> {
                    if (inFlight != sector) {
                        false
                    } else {
                        inFlight = null
                        completed += sector
                        true
                    }
                }
                EcuWriteTransaction.Stage.FINAL_READBACK_VERIFIED -> {
                    if (inFlight != null || baseline.isEmpty() ||
                        completed.size != baseline.size ||
                        baseline.getOrNull(finalReadbacks) != sector) {
                        false
                    } else {
                        finalReadbacks++
                        true
                    }
                }
                EcuWriteTransaction.Stage.TRANSACTION_COMPLETED -> {
                    if (inFlight != null || baseline.isEmpty() ||
                        finalReadbacks != baseline.size || completed.size != baseline.size ||
                        baseline.last() != sector) {
                        false
                    } else {
                        completionRecorded = true
                        true
                    }
                }
            }
        }
    }

    private fun readBoundedSnapshot(): ByteArray? {
        if (!file.isFile) return null
        val length = file.length()
        if (length !in 1..MAX_JOURNAL_BYTES) return null
        return file.inputStream().use { input ->
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count <= 0) return@use null
                offset += count
            }
            if (input.read() != -1) null else bytes
        }
    }

    private fun inspectLocked(
        nextRecord: EcuWriteTransaction.Record? = null,
        raw: ByteArray? = readBoundedSnapshot()
    ): Audit {
        if (raw == null) return Audit(false, 0)
        if (raw.isEmpty() || raw.last() != '\n'.code.toByte()) return Audit(false, 0)
        val lines = raw.toString(StandardCharsets.UTF_8).dropLast(1).split('\n')
        if (lines.firstOrNull() != header) return Audit(false, 0)
        var previousHash = sha256(header)
        var count = 0
        var lastStage: EcuWriteTransaction.Stage? = null
        val sequence = SequenceGuard()
        for (line in lines.drop(1)) {
            val parts = line.split('\t')
            if (parts.size != 8 || parts[0].toIntOrNull() != count + 1 ||
                parts[1].toLongOrNull() == null || parts[4].toLongOrNull() == null ||
                parts[4].toLong() < 0 ||
                !shaPattern.matches(parts[5]) || !shaPattern.matches(parts[6])
            ) return Audit(false, count, lastStage)
            val stage = runCatching { EcuWriteTransaction.Stage.valueOf(parts[2]) }.getOrNull()
                ?: return Audit(false, count, lastStage)
            val region = runCatching { EcuBackupCoverage.Region.valueOf(parts[3]) }.getOrNull()
                ?: return Audit(false, count, lastStage)
            if (!sequence.accept(EcuWriteTransaction.Record(
                stage = stage, region = region, address = parts[4].toLong(),
                originalSha256 = parts[5], updatedSha256 = parts[6]
            ))) return Audit(false, count, lastStage)
            val expected = sha256(previousHash + "\t" + parts.take(7).joinToString("\t"))
            if (parts[7] != expected) return Audit(false, count, lastStage)
            previousHash = expected
            count++
            lastStage = stage
        }
        if (nextRecord != null && !sequence.accept(nextRecord)) {
            return Audit(false, count, lastStage)
        }
        return sequence.audit(count, lastStage)
    }

    private fun durableAppend(bytes: ByteArray) {
        FileOutputStream(file, true).use { stream ->
            stream.write(bytes)
            // If fsync fails, the caller MUST stop before erasing the next sector.
            stream.fd.sync()
        }
    }

    private fun encoded(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            text.toByteArray(StandardCharsets.UTF_8)
        )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        private const val MAX_JOURNAL_BYTES = 8L * 1024 * 1024

        /**
         * Inspect a previous transaction after app/process restart without opening a
         * writer, truncating the file, or contacting any hardware. Identity must
         * be supplied independently; a different target is not accepted.
         */
        /** Verify the exact bytes consumed by a recovery inspector, not a separate file read. */
        internal fun inspectSnapshot(
            file: File,
            target: EcuProgrammingDriverContract.FirmwareTarget,
            expectedVin: String,
            raw: ByteArray
        ): Audit = try {
            if (raw.size.toLong() !in 1L..MAX_JOURNAL_BYTES) Audit(false, 0)
            else PersistentEcuWriteJournal(file, target, expectedVin, false).inspectLocked(raw = raw)
        } catch (_: Exception) {
            Audit(false, 0)
        }

        fun inspectExisting(
            file: File,
            target: EcuProgrammingDriverContract.FirmwareTarget,
            expectedVin: String
        ): Audit = try {
            PersistentEcuWriteJournal(file, target, expectedVin, false).inspect()
        } catch (_: Exception) {
            Audit(false, 0)
        }
    }
}
