package com.camarodiag.gen5.data.obd

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentEcuPhysicalBlockBackupTest {
    @Test fun liveSectorSavedAsNewFileWithExactAddressAndOriginalBytes() = runBlocking {
        val folder = Files.createTempDirectory("camarodiag-ecu-blocks").toFile()
        try {
            val journal = File(folder, "attempt.journal")
            assertTrue(journal.createNewFile())
            val target = EcuProgrammingDriverContract.FirmwareTarget("E39", "ECM", "OS-A")
            val store = PersistentEcuPhysicalBlockBackup(journal, target, "1G1FA1RXXR0123456")
            val live = ByteArray(8) { it.toByte() }
            val originalSha = EcuBenchExpertPlan.sha256Hex(live)
            val region = EcuProgrammingDriverContract.RegionRequest(
                EcuBackupCoverage.Region.CALIBRATION, 0x4000L, 8L)
            store.preserve(region, live, originalSha)
            val backups = store.directory.listFiles().orEmpty()
            assertEquals(1, backups.size)
            assertEquals("CALIBRATION-4000-8.ecu-block", backups.single().name)
            assertTrue(backups.single().length() > live.size)
            live.fill(0) // persistence does not alias the caller's buffer
            assertFalse(backups.single().readBytes().takeLast(8).all { it == 0.toByte() })
            assertThrows(IllegalStateException::class.java) {
                runBlocking { store.preserve(region, ByteArray(8) { it.toByte() }, originalSha) }
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun wrongLiveBytesOrWrongPhysicalSizeCannotBeArchived() = runBlocking {
        val folder = Files.createTempDirectory("camarodiag-ecu-block-failure").toFile()
        try {
            val journal = File(folder, "attempt.journal")
            assertTrue(journal.createNewFile())
            val target = EcuProgrammingDriverContract.FirmwareTarget("E39", "ECM", "OS-A")
            val store = PersistentEcuPhysicalBlockBackup(journal, target, "1G1FA1RXXR0123456")
            val bytes = ByteArray(8) { it.toByte() }
            val correctHash = EcuBenchExpertPlan.sha256Hex(bytes)
            val region = EcuProgrammingDriverContract.RegionRequest(
                EcuBackupCoverage.Region.CALIBRATION, 0x4000L, 8L)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.preserve(region, ByteArray(8), correctHash) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.preserve(region.copy(length = 4L), bytes, correctHash) }
            }
            assertTrue(store.directory.listFiles().orEmpty().isEmpty())
        } finally { folder.deleteRecursively() }
    }
}
