package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EcuRestoreImageFileTest {
    private val identity = EcuRestoreImageFile.Identity(
        "1G1FA1RXXR0123456", "ECM-E39", "observed-osid"
    )

    @Test fun savedRestorationRegionSurvivesDiskReloadAndPreservesAbsoluteAddress() {
        val directory = Files.createTempDirectory("ecu-restore-test").toFile()
        try {
            val image = EcuRestoreImage.RegionImage(
                EcuRestoreImage.MemoryRegion(
                    EcuBackupCoverage.Region.PROGRAM_FLASH, 0x4000, 4
                ),
                byteArrayOf(1, 2, 3, 4)
            )
            val file = File(directory, "captured-region.ecu-image")
            EcuRestoreImageFile.saveNew(file, identity, image)
            val result = EcuRestoreImageFile.load(file)
            assertEquals(identity, result.identity)
            assertEquals(image.geometry, result.image.geometry)
            assertEquals(image.sha256, result.image.sha256)
            assertArrayEquals(image.bytes(), result.image.bytes())
            assertTrue(runCatching {
                EcuRestoreImageFile.saveNew(file, identity, image)
            }.isFailure)
        } finally {
            directory.listFiles()?.forEach(File::delete)
            directory.delete()
        }
    }

    @Test fun corruptedOrTruncatedImageCannotBeLoadedForRestore() {
        val directory = Files.createTempDirectory("ecu-restore-corruption").toFile()
        try {
            val image = EcuRestoreImage.RegionImage(
                EcuRestoreImage.MemoryRegion(
                    EcuBackupCoverage.Region.CALIBRATION, 0x100, 3
                ),
                byteArrayOf(8, 9, 10)
            )
            val file = File(directory, "source.ecu-image")
            EcuRestoreImageFile.saveNew(file, identity, image)
            val original = file.readBytes()
            val changed = original.copyOf()
            changed[changed.lastIndex] = (changed.last().toInt() xor 0xff).toByte()
            file.writeBytes(changed)
            assertTrue(runCatching { EcuRestoreImageFile.load(file) }.isFailure)
            file.writeBytes(original.copyOf(original.size - 1))
            assertTrue(runCatching { EcuRestoreImageFile.load(file) }.isFailure)
            file.writeBytes(original + 0x7f.toByte())
            assertTrue(runCatching { EcuRestoreImageFile.load(file) }.isFailure)
        } finally {
            directory.listFiles()?.forEach(File::delete)
            directory.delete()
        }
    }
}
