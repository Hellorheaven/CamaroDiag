package com.camarodiag.gen5.data.obd

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Durable, exact byte-for-byte offline restoration image for a documented memory region.
 *
 * The file is never silently replaced. It contains captured bytes, physical address
 * and ECU identity, but requires a compatible real programmer and OEM memory map to
 * restore to hardware. No ECU write is performed by this class.
 */
object EcuRestoreImageFile {
    private const val MAGIC = "CAMARODIAG-ECU-RESTORE-REGION-V1"
    private const val MAX_REGION_BYTES = 64 * 1024 * 1024

    data class Identity(val vin: String, val controllerId: String, val firmwareId: String) {
        init {
            require(vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")))
            require(controllerId.isNotBlank() && firmwareId.isNotBlank() &&
                controllerId.length <= 256 && firmwareId.length <= 256 &&
                controllerId.none(Char::isISOControl) &&
                firmwareId.none(Char::isISOControl)) {
                "Invalid ECU image identity metadata"
            }
        }
    }

    data class Opened(val identity: Identity, val image: EcuRestoreImage.RegionImage)

    fun saveNew(file: File, identity: Identity, image: EcuRestoreImage.RegionImage) {
        require(image.geometry.length == image.byteCount.toLong() &&
            image.byteCount in 1..MAX_REGION_BYTES) {
            "ECU region image has an invalid length"
        }
        val parent = file.absoluteFile.parentFile
        require(parent?.isDirectory == true) { "Restore image destination does not exist" }
        check(file.createNewFile()) { "Refusing to replace an ECU restoration image" }
        try {
            FileOutputStream(file).use { stream ->
                DataOutputStream(stream).use { data ->
                    data.writeUTF(MAGIC)
                    data.writeUTF(identity.vin)
                    data.writeUTF(identity.controllerId)
                    data.writeUTF(identity.firmwareId)
                    data.writeUTF(image.geometry.region.name)
                    data.writeLong(image.geometry.startAddress)
                    data.writeLong(image.geometry.length)
                    data.writeUTF(image.sha256)
                    data.writeInt(image.byteCount)
                    image.writeTo(data)
                    data.flush()
                    stream.fd.sync()
                }
            }
            val reopened = load(file)
            check(reopened.identity == identity &&
                reopened.image.geometry == image.geometry &&
                reopened.image.sha256 == image.sha256 &&
                reopened.image.bytes().contentEquals(image.bytes())) {
                "ECU restoration image disk verification failed"
            }
        } catch (failure: Exception) {
            // Retain any incomplete file for forensics; never overwrite on retry.
            throw failure
        }
    }

    fun load(file: File): Opened {
        require(file.isFile) { "ECU restoration image is missing" }
        DataInputStream(file.inputStream().buffered()).use { data ->
            require(data.readUTF() == MAGIC) { "Unsupported ECU restoration image format" }
            val identity = Identity(data.readUTF(), data.readUTF(), data.readUTF())
            val region = EcuBackupCoverage.Region.valueOf(data.readUTF())
            val start = data.readLong()
            val length = data.readLong()
            val storedHash = data.readUTF()
            require(storedHash.matches(Regex("[0-9a-f]{64}"))) {
                "Invalid restoration image checksum"
            }
            val byteCount = data.readInt()
            require(length in 1L..MAX_REGION_BYTES.toLong() &&
                byteCount.toLong() == length) { "Invalid restoration image size" }
            val bytes = ByteArray(byteCount)
            data.readFully(bytes)
            require(data.read() == -1) { "Unexpected trailing restoration image bytes" }
            val image = EcuRestoreImage.RegionImage(
                EcuRestoreImage.MemoryRegion(region, start, length), bytes
            )
            require(image.sha256 == storedHash) {
                "ECU restoration image does not match its stored checksum"
            }
            return Opened(identity, image)
        }
    }
}
