package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EcuRestorePortableBundleTest {
    private val vin = "1G1FA1RXXR0123456"
    private val region = EcuBackupCoverage.Region.CALIBRATION
    private val identity = EcuRestoreImageFile.Identity(vin, "ECM-E39", "observed-osid")

    private fun sourcePackage(root: File): File {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val capture = EcuBackupManifest.Capture(
            region, 0x1000, 4, bytes.copyOf(), bytes.copyOf(),
            EcuBackupManifest.Origin.REPORTED_HARDWARE_READ
        )
        val archive = ByteArrayOutputStream()
        EcuBackupArchive.export(
            archive, listOf(capture), emptySet(), vehicleVin = vin
        )
        val imported = EcuBackupArchive.import(archive.toByteArray().inputStream())
        val folder = File(root, "source")
        EcuRestorePackage.createNew(
            folder, imported,
            listOf(EcuRestoreImage.MemoryRegion(region, 0x1000, 4)),
            identity
        )
        return folder
    }

    @Test fun portableBundleSurvivesZipRoundTripWithIdentityAndPhysicalBytes() {
        val root = Files.createTempDirectory("restore-portable").toFile()
        try {
            val inputPackage = sourcePackage(root)
            val zipped = ByteArrayOutputStream()
            EcuRestorePortableBundle.export(inputPackage, zipped)
            val opened = EcuRestorePortableBundle.importNew(
                zipped.toByteArray().inputStream(), File(root, "reopened")
            )
            assertEquals(identity, opened.identity)
            assertEquals(0x1000L, opened.regions.single().geometry.startAddress)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), opened.regions.single().bytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun truncatedOrExtendedZipCannotBeImportedAsValidEcuImage() {
        val root = Files.createTempDirectory("restore-portable-truncated").toFile()
        try {
            val zipped = ByteArrayOutputStream()
            EcuRestorePortableBundle.export(sourcePackage(root), zipped)
            val complete = zipped.toByteArray()
            assertTrue(runCatching {
                EcuRestorePortableBundle.importNew(
                    complete.copyOf(complete.size - 9).inputStream(),
                    File(root, "truncated")
                )
            }.isFailure)
            assertTrue(runCatching {
                EcuRestorePortableBundle.importNew(
                    complete + byteArrayOf(42), File(root, "appended")
                )
            }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun unexpectedArchivePathsAreNeverExtractedOutsideTarget() {
        val root = Files.createTempDirectory("restore-portable-paths").toFile()
        try {
            val zipBytes = ByteArrayOutputStream()
            ZipOutputStream(zipBytes).use { zip ->
                zip.putNextEntry(ZipEntry("../escape.ecu-image"))
                zip.write(byteArrayOf(1, 2))
                zip.closeEntry()
            }
            assertTrue(runCatching {
                EcuRestorePortableBundle.importNew(
                    zipBytes.toByteArray().inputStream(), File(root, "target")
                )
            }.isFailure)
            assertTrue(!File(root, "escape.ecu-image").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
