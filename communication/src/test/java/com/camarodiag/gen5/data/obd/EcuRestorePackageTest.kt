package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class EcuRestorePackageTest {
    private val vin = "1G1FA1RXXR0123456"
    private val identity = EcuRestoreImageFile.Identity(vin, "ECU-E39", "observed-firmware")
    private val flash = EcuBackupCoverage.Region.PROGRAM_FLASH
    private val eeprom = EcuBackupCoverage.Region.EXTERNAL_EEPROM

    private fun capture(region: EcuBackupCoverage.Region, address: Long, bytes: ByteArray) =
        EcuBackupManifest.Capture(
            region, address, bytes.size.toLong(), bytes.copyOf(), bytes.copyOf(),
            EcuBackupManifest.Origin.REPORTED_HARDWARE_READ
        )

    private fun archive(vararg captures: EcuBackupManifest.Capture): EcuBackupArchive.Imported {
        val output = ByteArrayOutputStream()
        EcuBackupArchive.export(output, captures.toList(), emptySet(), vehicleVin = vin)
        return EcuBackupArchive.import(output.toByteArray().inputStream())
    }

    @Test fun newMultiRegionPackageIsDurableAndVerifiable() {
        val root = Files.createTempDirectory("c2-package").toFile()
        try {
            val packageDir = File(root, "full-observed-regions")
            val original = archive(
                capture(flash, 0x1000, byteArrayOf(1, 2)),
                capture(flash, 0x1002, byteArrayOf(3, 4)),
                capture(eeprom, 0x2000, byteArrayOf(5, 6))
            )
            val geometry = listOf(
                EcuRestoreImage.MemoryRegion(flash, 0x1000, 4),
                EcuRestoreImage.MemoryRegion(eeprom, 0x2000, 2)
            )
            val created = EcuRestorePackage.createNew(packageDir, original, geometry, identity)
            val reloaded = EcuRestorePackage.open(packageDir)
            assertEquals(identity, created.identity)
            assertEquals(2, reloaded.regions.size)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), reloaded.regions[0].bytes())
            assertArrayEquals(byteArrayOf(5, 6), reloaded.regions[1].bytes())
            assertTrue(runCatching {
                EcuRestorePackage.createNew(packageDir, original, geometry, identity)
            }.isFailure)
            File(packageDir, "region-1.ecu-image").appendBytes(byteArrayOf(99))
            assertTrue(runCatching { EcuRestorePackage.open(packageDir) }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsIncompleteMemoryMapWithoutPublishingPackage() {
        val root = Files.createTempDirectory("c2-map").toFile()
        try {
            val partialMap = listOf(EcuRestoreImage.MemoryRegion(flash, 0x1000, 2))
            val original = archive(
                capture(flash, 0x1000, byteArrayOf(1, 2)),
                capture(eeprom, 0x2000, byteArrayOf(3, 4))
            )
            val target = File(root, "unmapped-capture")
            assertTrue(runCatching {
                EcuRestorePackage.createNew(target, original, partialMap, identity)
            }.isFailure)
            assertTrue(!target.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsMismatchedVinBeforeCreatingFiles() {
        val root = Files.createTempDirectory("c2-identity").toFile()
        try {
            val original = archive(capture(flash, 0x1000, byteArrayOf(1, 2)))
            val map = listOf(EcuRestoreImage.MemoryRegion(flash, 0x1000, 2))
            val bad = EcuRestoreImageFile.Identity("2G1FA1RXXR0123456", "ECU-E39", "observed")
            val target = File(root, "wrong-vehicle")
            assertTrue(runCatching {
                EcuRestorePackage.createNew(target, original, map, bad)
            }.isFailure)
            assertTrue(!target.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun incompletePackageNeverPassesReopen() {
        val root = Files.createTempDirectory("c2-incomplete").toFile()
        try {
            val unfinished = File(root, "interrupted")
            assertTrue(unfinished.mkdir())
            File(unfinished, "region-0.ecu-image").writeBytes(byteArrayOf(1))
            assertTrue(runCatching { EcuRestorePackage.open(unfinished) }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }
}
