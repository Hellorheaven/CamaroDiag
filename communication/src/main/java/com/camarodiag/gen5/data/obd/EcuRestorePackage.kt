package com.camarodiag.gen5.data.obd

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * An offline collection of region images with an explicit, independently supplied
 * ECU memory map. It is NOT a certification that the map covers the whole ECU,
 * and it never programs a device.
 */
object EcuRestorePackage {
    private const val MAGIC = "CAMARODIAG-ECU-RESTORE-PACKAGE-V1"
    private const val MAX_REGIONS = 128

    data class Contents(
        val identity: EcuRestoreImageFile.Identity,
        val regions: List<EcuRestoreImage.RegionImage>
    )

    /**
     * Creates a new destination directory, writes every physical region image,
     * then publishes the index last. A failed write leaves an incomplete directory
     * which open() refuses to treat as a complete package.
     */
    fun createNew(
        directory: File,
        archive: EcuBackupArchive.Imported,
        map: List<EcuRestoreImage.MemoryRegion>,
        identity: EcuRestoreImageFile.Identity
    ): Contents {
        require(archive.manifest.vehicleVin == identity.vin) {
            "ECU backup VIN differs from restoration target metadata"
        }
        require(archive.captures.all {
            it.origin == EcuBackupManifest.Origin.REPORTED_HARDWARE_READ
        }) { "Offline or simulated captures cannot be labelled physical ECU images" }
        require(map.size in 1..MAX_REGIONS) { "Invalid region count" }
        val images = EcuRestoreImage.assemble(archive, map)
        check(directory.mkdir()) { "ECU image package directory must be new" }
        images.forEachIndexed { index, image ->
            EcuRestoreImageFile.saveNew(
                File(directory, fileName(index)), identity, image
            )
        }
        val manifest = File(directory, "index.ecu-package")
        check(manifest.createNewFile()) { "Image index already exists" }
        FileOutputStream(manifest).use { stream ->
            DataOutputStream(stream).use { data ->
                data.writeUTF(MAGIC)
                data.writeUTF(identity.vin)
                data.writeUTF(identity.controllerId)
                data.writeUTF(identity.firmwareId)
                data.writeInt(images.size)
                images.forEachIndexed { index, image ->
                    data.writeUTF(fileName(index))
                    data.writeUTF(image.geometry.region.name)
                    data.writeLong(image.geometry.startAddress)
                    data.writeLong(image.geometry.length)
                    data.writeUTF(image.sha256)
                }
                data.flush()
                stream.fd.sync()
            }
        }
        val reopened = open(directory)
        check(reopened.identity == identity && reopened.regions.size == images.size &&
            reopened.regions.zip(images).all { (stored, original) ->
                stored.geometry == original.geometry &&
                    stored.bytes().contentEquals(original.bytes())
            }) { "Stored ECU restoration image package failed disk re-verification" }
        return reopened
    }

    fun open(directory: File): Contents {
        require(directory.isDirectory) { "ECU image package directory is missing" }
        val index = File(directory, "index.ecu-package")
        require(index.isFile) { "Incomplete ECU restoration package: missing index" }
        DataInputStream(index.inputStream().buffered()).use { data ->
            require(data.readUTF() == MAGIC) { "Unsupported ECU image package" }
            val identity = EcuRestoreImageFile.Identity(
                data.readUTF(), data.readUTF(), data.readUTF()
            )
            val count = data.readInt()
            require(count in 1..MAX_REGIONS) { "Invalid ECU image package region count" }
            val results = (0 until count).map { indexNumber ->
                val name = data.readUTF()
                require(name == fileName(indexNumber)) { "Unexpected ECU region filename" }
                val region = EcuBackupCoverage.Region.valueOf(data.readUTF())
                val address = data.readLong()
                val length = data.readLong()
                val hash = data.readUTF()
                val saved = EcuRestoreImageFile.load(File(directory, name))
                require(saved.identity == identity &&
                    saved.image.geometry ==
                    EcuRestoreImage.MemoryRegion(region, address, length) &&
                    saved.image.sha256 == hash) {
                    "ECU image package region metadata or bytes differ"
                }
                saved.image
            }
            require(data.read() == -1) { "Unexpected trailing ECU package index bytes" }
            require(directory.listFiles()?.map { it.name }?.toSet() ==
                ((0 until count).map(::fileName) + "index.ecu-package").toSet()) {
                "Unexpected or missing ECU image files"
            }
            val geometry = results.map { it.geometry }
            require(geometry.distinct().size == geometry.size) {
                "Duplicate ECU restore image memory regions"
            }
            val ordered = geometry.sortedWith(
                compareBy<EcuRestoreImage.MemoryRegion>({ it.region.ordinal }, { it.startAddress })
            )
            ordered.zipWithNext().forEach { (a, b) ->
                require(a.region != b.region ||
                    a.startAddress + a.length <= b.startAddress) {
                    "ECU restore image regions overlap"
                }
            }
            return Contents(identity, results)
        }
    }

    private fun fileName(index: Int) = "region-$index.ecu-image"
}
