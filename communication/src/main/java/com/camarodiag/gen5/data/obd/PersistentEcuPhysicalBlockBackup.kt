package com.camarodiag.gen5.data.obd

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.DigestInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The original bytes must come from a fresh LIVE ECU read, not from a BIN. */
internal fun interface EcuPhysicalBlockBackup {
    suspend fun preserve(
        region: EcuProgrammingDriverContract.RegionRequest,
        physicalBytes: ByteArray,
        expectedOriginalSha256: String
    )
    suspend fun requireOriginalsIntact() {
        error("Durable backup re-verification unavailable.")
    }
}

/** Only for the in-memory unit-test harness, never for a real registered writer. */
internal class InMemoryEcuPhysicalBlockBackup : EcuPhysicalBlockBackup {
    val blocks = mutableMapOf<Pair<EcuBackupCoverage.Region, Long>, ByteArray>()
    private val digests = mutableMapOf<Pair<EcuBackupCoverage.Region, Long>, String>()
    override suspend fun preserve(
        region: EcuProgrammingDriverContract.RegionRequest,
        physicalBytes: ByteArray,
        expectedOriginalSha256: String
    ) {
        val frozen = physicalBytes.copyOf()
        require(region.length == frozen.size.toLong() &&
            sha256Hex(frozen).equals(expectedOriginalSha256, ignoreCase = true))
        val key = region.region to region.address
        check(blocks.putIfAbsent(key, frozen) == null)
        digests[key] = expectedOriginalSha256.lowercase(java.util.Locale.ROOT)
    }
    override suspend fun requireOriginalsIntact() {
        check(digests.isNotEmpty() && blocks.keys == digests.keys)
        for ((key, expected) in digests) {
            currentCoroutineContext().ensureActive()
            check(sha256Hex(blocks.getValue(key)) == expected)
        }
    }
}

/** A distinct write-through, read-back-verified file per ECU erase sector. */
internal class PersistentEcuPhysicalBlockBackup(
    journalFile: File,
    private val target: EcuProgrammingDriverContract.FirmwareTarget,
    private val vin: String
) : EcuPhysicalBlockBackup {
    val directory = File(journalFile.absolutePath + ".blocks")
    private data class Envelope(val length: Long, val sha256: ByteArray)
    private val envelopes = linkedMapOf<String, Envelope>()
    private var backupInvalidated = false

    init {
        require(journalFile.isFile && journalFile.parentFile?.isDirectory == true)
        require(vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")))
        check(directory.mkdir()) { "A fresh, private physical ECU backup directory is required." }
    }

    override suspend fun preserve(
        region: EcuProgrammingDriverContract.RegionRequest,
        physicalBytes: ByteArray,
        expectedOriginalSha256: String
    ) {
        check(!backupInvalidated) { "Original ECU backup session was invalidated." }
        val physical = physicalBytes.copyOf()
        require(region.address >= 0 && region.length > 0 &&
            region.address <= Long.MAX_VALUE - region.length &&
            region.length == physical.size.toLong() &&
            sha256Hex(physical).equals(expectedOriginalSha256, ignoreCase = true)) {
            "The live ECU sector differs from the intended original data."
        }
        currentCoroutineContext().ensureActive()
        check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
        val file = File(directory,
            region.region.name + "-" + region.address.toString(16) + "-" + region.length + ".ecu-block")
        check(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Refusing to overwrite a physical ECU sector backup."
        }
        // Stream straight into the create-new sector file instead of building
        // a second full serialized image in RAM. The file is never accepted
        // until the synchronous write and independent byte-for-byte readback.
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            DataOutputStream(Channels.newOutputStream(channel)).use { data ->
                data.writeUTF("CAMARODIAG-PHYSICAL-BLOCK-V1")
                data.writeUTF(target.controllerFamily)
                data.writeUTF(target.controllerId)
                data.writeUTF(target.firmwareId)
                data.writeUTF(vin)
                data.writeUTF(region.region.name)
                data.writeLong(region.address)
                data.writeLong(region.length)
                data.writeUTF(expectedOriginalSha256.lowercase())
                data.writeInt(physical.size)
                data.write(physical)
                data.flush()
                channel.force(true)
            }
        }
        // Treat missing, truncated or modified on-disk backups as an erase stop.
        val envelopeHash = MessageDigest.getInstance("SHA-256")
        val envelopeLength = file.length()
        check(envelopeLength > physical.size && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        DataInputStream(DigestInputStream(Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).buffered(), envelopeHash)).use { data ->
            check(data.readUTF() == "CAMARODIAG-PHYSICAL-BLOCK-V1" &&
                data.readUTF() == target.controllerFamily &&
                data.readUTF() == target.controllerId &&
                data.readUTF() == target.firmwareId &&
                data.readUTF() == vin &&
                data.readUTF() == region.region.name &&
                data.readLong() == region.address &&
                data.readLong() == region.length &&
                data.readUTF() == expectedOriginalSha256.lowercase() &&
                data.readInt() == physical.size) {
                "Persisted ECU sector metadata differs from the write target."
            }
            // Verify on-disk contents in bounded chunks. Large erase sectors
            // must not allocate an additional sector-sized readback buffer.
            val buffer = ByteArray(8192)
            var offset = 0
            while (offset < physical.size) {
                currentCoroutineContext().ensureActive()
                val length = minOf(buffer.size, physical.size - offset)
                data.readFully(buffer, 0, length)
                for (i in 0 until length) {
                    check(buffer[i] == physical[offset + i]) {
                        "Persisted ECU block bytes differ from the live capture."
                    }
                }
                offset += length
            }
            check(data.read() == -1 && file.length() == envelopeLength) {
                "Persisted ECU block contains unexpected trailing data."
            }
        }
        check(envelopes.putIfAbsent(file.name, Envelope(envelopeLength, envelopeHash.digest())) == null)
    }

    override suspend fun requireOriginalsIntact() {
        check(!backupInvalidated) { "Original ECU backup session was invalidated." }
        try {
            verifyOriginals()
        } catch (failure: Exception) {
            // Restoring files after a detected change must not revive the old write session.
            backupInvalidated = true
            throw failure
        }
    }

    private suspend fun verifyOriginals() {
        fun requireInventory() {
            check(envelopes.isNotEmpty() &&
                Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                directory.list()?.toSet() == envelopes.keys) { "Original ECU sector inventory changed." }
        }
        requireInventory()
        val buffer = ByteArray(8192)
        for ((name, envelope) in envelopes) {
            currentCoroutineContext().ensureActive()
            val file = File(directory, name)
            check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                file.length() == envelope.length) { "Original ECU sector file changed." }
            val digest = MessageDigest.getInstance("SHA-256")
            DataInputStream(Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).buffered()).use { input ->
                var remaining = envelope.length
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val n = minOf(buffer.size.toLong(), remaining).toInt()
                    input.readFully(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    remaining -= n
                }
                check(input.read() == -1 && file.length() == envelope.length)
            }
            check(MessageDigest.isEqual(digest.digest(), envelope.sha256)) {
                "Original ECU sector envelope changed."
            }
        }
        requireInventory()
        currentCoroutineContext().ensureActive()
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
