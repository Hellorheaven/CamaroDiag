package com.camarodiag.gen5.data.obd

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuPhysicalBackupInspectionTest {
    private val target = EcuProgrammingDriverContract.FirmwareTarget("E39", "ECM", "OS-A")
    private val vin = "1G1FA1RXXR0123456"

    @Test fun offlineInspectorMatchesDurablePhysicalBytesToHashChainedJournal() = runBlocking {
        val directory = Files.createTempDirectory("camarodiag-ecu-inspection").toFile()
        try {
            val journalFile = File(directory, "attempt.journal")
            val journal = PersistentEcuWriteJournal(journalFile, target, vin)
            val store = PersistentEcuPhysicalBlockBackup(journalFile, target, vin)
            val regions = listOf(
                EcuProgrammingDriverContract.RegionRequest(
                    EcuBackupCoverage.Region.CALIBRATION, 0x1000, 8),
                EcuProgrammingDriverContract.RegionRequest(
                    EcuBackupCoverage.Region.CALIBRATION, 0x1008, 8)
            )
            val files = mutableListOf<File>()
            for ((index, region) in regions.withIndex()) {
                val bytes = ByteArray(8) { (it + index * 20).toByte() }
                val sha = EcuBenchExpertPlan.sha256Hex(bytes)
                // The write engine persists actual physical bytes before its
                // durable BASELINE_CHECKED journal record.
                store.preserve(region, bytes, sha)
                journal.append(EcuWriteTransaction.Record(
                    EcuWriteTransaction.Stage.BASELINE_CHECKED, region.region,
                    region.address, sha, "a".repeat(64)))
                files += store.directory.listFiles().orEmpty().single {
                    it.name.contains("-${region.address.toString(16)}-")
                }
            }
            val normal = EcuPhysicalBackupInspection.inspect(journalFile, target, vin)
            assertTrue(normal.problem, normal.verified)
            assertEquals(2, normal.expectedSectors)
            assertEquals(2, normal.sectors.size)
            assertEquals(16L, normal.verifiedBytes)

            assertFalse(EcuPhysicalBackupInspection.inspect(
                journalFile, target, "1G1FA1RXXR0123457").verified)
            assertFalse(EcuPhysicalBackupInspection.inspect(
                journalFile, target.copy(firmwareId = "WRONG"), vin).verified)

            val original = files[0].readBytes()
            val changed = original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
            files[0].writeBytes(changed)
            val tampered = EcuPhysicalBackupInspection.inspect(journalFile, target, vin)
            assertFalse(tampered.verified)
            files[0].writeBytes(original)

            val extra = File(store.directory, "unexpected.ecu-block")
            extra.writeText("unexpected")
            assertFalse(EcuPhysicalBackupInspection.inspect(journalFile, target, vin).verified)
            extra.delete()

            val missing = files[1].readBytes()
            files[1].delete()
            assertFalse(EcuPhysicalBackupInspection.inspect(journalFile, target, vin).verified)
            files[1].writeBytes(missing)
            assertTrue(EcuPhysicalBackupInspection.inspect(journalFile, target, vin).verified)

            val journalText = journalFile.readText()
            journalFile.writeText(journalText.replace(
                "BASELINE_CHECKED", "TRANSACTION_COMPLETED"))
            assertFalse(EcuPhysicalBackupInspection.inspect(journalFile, target, vin).verified)
            journalFile.writeText(journalText)
            assertTrue(EcuPhysicalBackupInspection.inspect(journalFile, target, vin).verified)
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun incompleteOrNonexistentJournalIsNeverMistakenForRecoveryProof() {
        val directory = Files.createTempDirectory("camarodiag-ecu-missing").toFile()
        try {
            val missing = File(directory, "missing.journal")
            val report = EcuPhysicalBackupInspection.inspect(missing, target, vin)
            assertFalse(report.verified)
            assertEquals(0, report.verifiedBytes)
            assertTrue(report.sectors.isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
