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
        // Explicit Unit return: JUnit4 requires @Test methods to return void.
        Unit
    }

    @Test fun alteredOriginalEnvelopeAndUnexpectedFilesAreRejectedBeforeErase() = runBlocking {
        val folder = Files.createTempDirectory("camarodiag-recheck").toFile()
        try {
            val journal = File(folder,"attempt.journal")
            assertTrue(journal.createNewFile())
            val target = EcuProgrammingDriverContract.FirmwareTarget("E39","ECM","OS-A")
            val store = PersistentEcuPhysicalBlockBackup(journal,target,"1G1FA1RXXR0123456")
            val bytes = ByteArray(8) { it.toByte() }
            val region = EcuProgrammingDriverContract.RegionRequest(EcuBackupCoverage.Region.CALIBRATION,0x4000L,8L)
            store.preserve(region,bytes,EcuBenchExpertPlan.sha256Hex(bytes))
            store.requireOriginalsIntact()
            val file = store.directory.listFiles()!!.single()
            val original = file.readBytes()
            file.writeBytes(original.copyOf().also { it[it.lastIndex] = 99 })
            assertTrue(runCatching { store.requireOriginalsIntact() }.isFailure)
            file.writeBytes(original)
            assertTrue(runCatching { store.requireOriginalsIntact() }.isFailure)
            val secondJournal = File(folder,"second.journal")
            assertTrue(secondJournal.createNewFile())
            val second = PersistentEcuPhysicalBlockBackup(secondJournal,target,"1G1FA1RXXR0123456")
            second.preserve(region,bytes,EcuBenchExpertPlan.sha256Hex(bytes))
            second.requireOriginalsIntact()
            val extra = File(second.directory,"unexpected")
            assertTrue(extra.createNewFile())
            assertTrue(runCatching { second.requireOriginalsIntact() }.isFailure)
            assertTrue(extra.delete())
            assertTrue(runCatching { second.requireOriginalsIntact() }.isFailure)
        } finally { folder.deleteRecursively() }
        Unit
    }

    @Test fun multiMegabyteSectorIsDurablyVerifiedWithoutSecondFullImageBuffer() = runBlocking {
        val folder = Files.createTempDirectory("camarodiag-large-ecu-block").toFile()
        try {
            val journal = File(folder, "attempt.journal")
            assertTrue(journal.createNewFile())
            val target = EcuProgrammingDriverContract.FirmwareTarget("E39", "ECM", "OS-A")
            val store = PersistentEcuPhysicalBlockBackup(journal, target, "1G1FA1RXXR0123456")
            val live = ByteArray(2 * 1024 * 1024 + 17) { (it * 37).toByte() }
            val sha = EcuBenchExpertPlan.sha256Hex(live)
            val region = EcuProgrammingDriverContract.RegionRequest(
                EcuBackupCoverage.Region.CALIBRATION, 0x8000L, live.size.toLong()
            )
            store.preserve(region, live, sha)
            val file = store.directory.listFiles().orEmpty().single()
            assertTrue(file.length() > live.size)
            val stored = file.readBytes()
            assertTrue(stored.takeLast(64) == live.takeLast(64))
            assertTrue(stored.takeLast(64).any { it != 0.toByte() })
        } finally { folder.deleteRecursively() }
        Unit
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
        // Explicit Unit return: JUnit4 requires @Test methods to return void.
        Unit
    }
}
