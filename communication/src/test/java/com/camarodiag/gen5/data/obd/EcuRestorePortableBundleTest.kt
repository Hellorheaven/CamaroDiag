package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
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

    @Test fun modifiedSourceDuringExportIsNeverReportedAsVerified() {
        val root = Files.createTempDirectory("restore-changing-source").toFile()
        try {
            val original = sourcePackage(root)
            val zipped = ByteArrayOutputStream()
            var modified = false
            val output = object : OutputStream() {
                override fun write(b: Int) {
                    if (!modified) {
                        modified = true
                        File(original, "index.ecu-package").appendBytes(byteArrayOf(42))
                    }
                    zipped.write(b)
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (!modified) {
                        modified = true
                        File(original, "index.ecu-package").appendBytes(byteArrayOf(42))
                    }
                    zipped.write(b, off, len)
                }
            }
            assertTrue(runCatching {
                EcuRestorePortableBundle.export(original, output)
            }.isFailure)
            assertTrue(modified)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun modifiedRegionDuringExportIsRejectedEvenIfZipCloses() {
        val root = Files.createTempDirectory("restore-changing-region").toFile()
        try {
            val original = sourcePackage(root)
            val zipped = ByteArrayOutputStream()
            var modified = false
            val output = object : OutputStream() {
                override fun write(b: Int) {
                    if (!modified) {
                        modified = true
                        File(original, "region-0.ecu-image").appendBytes(byteArrayOf(99))
                    }
                    zipped.write(b)
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (!modified) {
                        modified = true
                        File(original, "region-0.ecu-image").appendBytes(byteArrayOf(99))
                    }
                    zipped.write(b, off, len)
                }
            }
            assertTrue(runCatching {
                EcuRestorePortableBundle.export(original, output)
            }.isFailure)
            assertTrue(modified)
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
            assertTrue(!File(root, "truncated/index.ecu-package").exists())
            assertTrue(runCatching {
                EcuRestorePackage.open(File(root, "truncated"))
            }.isFailure)
            assertTrue(runCatching {
                EcuRestorePortableBundle.importNew(
                    complete + byteArrayOf(42), File(root, "appended")
                )
            }.isFailure)
            assertTrue(!File(root, "appended/index.ecu-package").exists())
            assertTrue(runCatching {
                EcuRestorePackage.open(File(root, "appended"))
            }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun indexFirstZipStillPublishesOnlyAfterFullValidation() {
        val root = Files.createTempDirectory("restore-index-first").toFile()
        try {
            val original = sourcePackage(root)
            val reordered = ByteArrayOutputStream()
            ZipOutputStream(reordered).use { zip ->
                for (name in listOf("index.ecu-package", "region-0.ecu-image")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(File(original, name).readBytes())
                    zip.closeEntry()
                }
            }
            val imported = EcuRestorePortableBundle.importNew(
                reordered.toByteArray().inputStream(), File(root, "verified")
            )
            assertEquals(identity, imported.identity)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), imported.regions.single().bytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun invalidPackageIndexIsNeverLeftAuthoritativeAfterImportFailure() {
        val root = Files.createTempDirectory("restore-invalid-index").toFile()
        try {
            val zipBytes = ByteArrayOutputStream()
            ZipOutputStream(zipBytes).use { zip ->
                zip.putNextEntry(ZipEntry("index.ecu-package"))
                zip.write(byteArrayOf(1, 2, 3, 4))
                zip.closeEntry()
            }
            val target = File(root, "invalid")
            assertTrue(runCatching {
                EcuRestorePortableBundle.importNew(zipBytes.toByteArray().inputStream(), target)
            }.isFailure)
            assertTrue(!File(target, "index.ecu-package").exists())
            assertTrue(runCatching { EcuRestorePackage.open(target) }.isFailure)
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
