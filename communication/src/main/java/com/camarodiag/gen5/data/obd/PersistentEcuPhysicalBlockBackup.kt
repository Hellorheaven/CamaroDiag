package com.camarodiag.gen5.data.obd

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** The original bytes must come from a fresh LIVE ECU read, not from a BIN. */
internal fun interface EcuPhysicalBlockBackup {
    suspend fun preserve(
        region: EcuProgrammingDriverContract.RegionRequest,
        physicalBytes: ByteArray,
        expectedOriginalSha256: String
    )
}

/** Only for the in-memory unit-test harness, never for a real registered writer. */
internal class InMemoryEcuPhysicalBlockBackup : EcuPhysicalBlockBackup {
    val blocks = mutableMapOf<Pair<EcuBackupCoverage.Region, Long>, ByteArray>()
    override suspend fun preserve(
        region: EcuProgrammingDriverContract.RegionRequest,
        physicalBytes: ByteArray,
        expectedOriginalSha256: String
    ) {
        require(region.length == physicalBytes.size.toLong() &&
            sha256Hex(physicalBytes).equals(expectedOriginalSha256, ignoreCase = true))
        check(blocks.putIfAbsent(region.region to region.address, physicalBytes.copyOf()) == null)
    }
}

/** A distinct write-through, read-back-verified file per ECU erase sector. */
internal class PersistentEcuPhysicalBlockBackup(
    journalFile: File,
    private val target: EcuProgrammingDriverContract.FirmwareTarget,
    private val vin: String
) : EcuPhysicalBlockBackup {
    val directory = File(journalFile.absolutePath + ".blocks")

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
        require(region.address >= 0 && region.length > 0 &&
            region.length == physicalBytes.size.toLong() &&
            sha256Hex(physicalBytes).equals(expectedOriginalSha256, ignoreCase = true)) {
            "The live ECU sector differs from the intended original data."
        }
        val physical = physicalBytes.copyOf()
        val file = File(directory,
            region.region.name + "-" + region.address.toString(16) + "-" + region.length + ".ecu-block")
        check(file.createNewFile()) { "Refusing to overwrite a physical ECU sector backup." }
        val encoded = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
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
            }
            output.toByteArray()
        }
        FileOutputStream(file, true).use { stream ->
            stream.write(encoded)
            stream.fd.sync()
        }
        // Treat missing, truncated or modified on-disk backups as an erase stop.
        DataInputStream(file.inputStream().buffered()).use { data ->
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
            val persisted = ByteArray(physical.size)
            data.readFully(persisted)
            check(data.read() == -1 && persisted.contentEquals(physical)) {
                "Persisted ECU block bytes differ from the live capture."
            }
        }
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
