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
class PersistentEcuWriteJournal(
    private val file: File,
    target: EcuProgrammingDriverContract.FirmwareTarget,
    expectedVin: String
) : EcuWriteTransaction.Journal {
    data class Audit(
        val valid: Boolean,
        val records: Int,
        val lastStage: EcuWriteTransaction.Stage? = null
    )

    private val guard = Any()
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
        check(file.createNewFile()) {
            "Refusing to overwrite or append to an earlier ECU write journal."
        }
        durableAppend((header + "\n").toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun append(record: EcuWriteTransaction.Record) {
        require(record.address >= 0L)
        require(shaPattern.matches(record.originalSha256) &&
            shaPattern.matches(record.updatedSha256)) {
            "Only complete SHA-256 image digests may be recorded."
        }
        synchronized(guard) {
            val audit = inspectLocked()
            check(audit.valid) { "The ECU write journal is missing, damaged, or has been modified." }
            val lines = file.readLines(StandardCharsets.UTF_8)
            val lastHash = if (audit.records == 0) sha256(header) else lines.last().substringAfterLast('\t')
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
            durableAppend(bytes)
        }
    }

    /** Detects truncated records and alterations, including changes to earlier entries. */
    fun inspect(): Audit = synchronized(guard) { inspectLocked() }

    private fun inspectLocked(): Audit {
        if (!file.isFile || file.length() !in 1..MAX_JOURNAL_BYTES) return Audit(false, 0)
        val raw = file.readBytes()
        if (raw.isEmpty() || raw.last() != '\n'.code.toByte()) return Audit(false, 0)
        val lines = raw.toString(StandardCharsets.UTF_8).dropLast(1).split('\n')
        if (lines.firstOrNull() != header) return Audit(false, 0)
        var previousHash = sha256(header)
        var count = 0
        var lastStage: EcuWriteTransaction.Stage? = null
        for (line in lines.drop(1)) {
            val parts = line.split('\t')
            if (parts.size != 8 || parts[0].toIntOrNull() != count + 1 ||
                parts[1].toLongOrNull() == null || parts[4].toLongOrNull() == null ||
                parts[4].toLong() < 0 ||
                !shaPattern.matches(parts[5]) || !shaPattern.matches(parts[6])
            ) return Audit(false, count, lastStage)
            val stage = runCatching { EcuWriteTransaction.Stage.valueOf(parts[2]) }.getOrNull()
                ?: return Audit(false, count, lastStage)
            if (runCatching { EcuBackupCoverage.Region.valueOf(parts[3]) }.isFailure) {
                return Audit(false, count, lastStage)
            }
            val expected = sha256(previousHash + "\t" + parts.take(7).joinToString("\t"))
            if (parts[7] != expected) return Audit(false, count, lastStage)
            previousHash = expected
            count++
            lastStage = stage
        }
        return Audit(true, count, lastStage)
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

    private companion object {
        const val MAX_JOURNAL_BYTES = 8L * 1024 * 1024
    }
}
