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
            val baselines = mutableListOf<EcuWriteTransaction.Record>()
            for ((index, region) in regions.withIndex()) {
                val bytes = ByteArray(8) { (it + index * 20).toByte() }
                val sha = EcuBenchExpertPlan.sha256Hex(bytes)
                // The write engine persists actual physical bytes before its
                // durable BASELINE_CHECKED journal record.
                store.preserve(region, bytes, sha)
                val baseline = EcuWriteTransaction.Record(
                    EcuWriteTransaction.Stage.BASELINE_CHECKED, region.region,
                    region.address, sha, "a".repeat(64))
                journal.append(baseline)
                baselines += baseline
                files += store.directory.listFiles().orEmpty().single {
                    it.name.contains("-${region.address.toString(16)}-")
                }
            }
            val normal = EcuPhysicalBackupInspection.inspect(journalFile, target, vin)
            assertTrue(normal.problem, normal.verified)
            assertEquals(2, normal.expectedSectors)
            assertEquals(2, normal.sectors.size)
            assertEquals(16L, normal.verifiedBytes)

            // Cancellation at the last possible checkpoint must not publish
            // an already computed but no longer authorized success.
            var totalChecks = 0
            assertTrue(EcuPhysicalBackupInspection.inspect(journalFile, target, vin) { totalChecks++ }.verified)
            var finalChecks = 0
            try {
                EcuPhysicalBackupInspection.inspect(journalFile, target, vin) {
                    if (++finalChecks == totalChecks)
                        throw java.util.concurrent.CancellationException("cancel before publication")
                }
                throw AssertionError("Final inspection cancellation must propagate")
            } catch (_: java.util.concurrent.CancellationException) {
                assertEquals(totalChecks, finalChecks)
            }

            assertEquals(
                PersistentEcuWriteJournal.RecoveryDisposition.NO_ERASE_RECORDED,
                normal.journalDisposition)

            assertFalse(EcuPhysicalBackupInspection.inspect(
                journalFile, target, "1G1FA1RXXR0123457").verified)
            assertFalse(EcuPhysicalBackupInspection.inspect(
                journalFile, target.copy(firmwareId = "WRONG"), vin).verified)

            // Change a previously inspected file immediately before the
            // final journal reread. No stale successful report is acceptable.
            val originalStamp = files[0].lastModified()
            var activeChecks = 0
            val modifiedDuringInspection = EcuPhysicalBackupInspection.inspect(journalFile, target, vin) {
                if (++activeChecks == 6) {
                    check(files[0].setLastModified(originalStamp + 60_000L))
                }
            }
            assertFalse(modifiedDuringInspection.verified)
            assertTrue(modifiedDuringInspection.sectors.isEmpty())
            val original = files[0].readBytes()
            val changed = original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
            // Restore the same modification timestamp and byte count.
            // A stale success must be impossible even when the identity
            // metadata is unchanged on the filesystem.
            val sameStamp = Files.getLastModifiedTime(files[0].toPath())
            var contentChecks = 0
            val hiddenChange = EcuPhysicalBackupInspection.inspect(journalFile, target, vin) {
                if (++contentChecks == 6) {
                    files[0].writeBytes(changed)
                    Files.setLastModifiedTime(files[0].toPath(), sameStamp)
                }
            }
            assertFalse(hiddenChange.verified)
            assertTrue(hiddenChange.sectors.isEmpty())
            files[0].writeBytes(original)
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

            // The original physical sectors remain intact even when the live
            // controller may be mid-erase. Never confuse their integrity
            // with a completed or recoverable ECU transaction.
            journal.append(baselines.first().copy(
                stage = EcuWriteTransaction.Stage.BEFORE_ERASE))
            val interrupted = EcuPhysicalBackupInspection.inspect(journalFile, target, vin)
            assertTrue(interrupted.verified)
            assertEquals(
                PersistentEcuWriteJournal.RecoveryDisposition.ERASE_OR_WRITE_MAY_BE_INCOMPLETE,
                interrupted.journalDisposition)
            assertEquals(0x1000L, interrupted.uncertainAddress)
            val secondOriginal = files[1].readBytes()
            files[1].writeBytes(secondOriginal.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
            val corrupted = EcuPhysicalBackupInspection.inspect(journalFile, target, vin)
            assertFalse(corrupted.verified)
            assertEquals(0x1000L, corrupted.uncertainAddress)
            assertEquals(0x1008L, corrupted.problemAddress)
            assertEquals(EcuBackupCoverage.Region.CALIBRATION, corrupted.uncertainRegion)
            assertEquals(PersistentEcuWriteJournal.RecoveryDisposition.ERASE_OR_WRITE_MAY_BE_INCOMPLETE,
                corrupted.journalDisposition)
            assertTrue(corrupted.sectors.isEmpty())
            files[1].writeBytes(secondOriginal)
            var checks = 0
            try {
                EcuPhysicalBackupInspection.inspect(journalFile, target, vin) {
                    if (++checks == 3) throw java.util.concurrent.CancellationException("cancelled")
                }
                throw AssertionError("Cancellation must propagate, not be reported as failed integrity")
            } catch (_: java.util.concurrent.CancellationException) {
                assertEquals(3, checks)
            }
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun auditUsesTheSuppliedSnapshotNotAnotherValidFileRead() = runBlocking {
        val directory = Files.createTempDirectory("camarodiag-ecu-snapshot").toFile()
        try {
            val file = File(directory, "attempt.journal")
            val journal = PersistentEcuWriteJournal(file, target, vin)
            val before = file.readBytes()
            journal.append(EcuWriteTransaction.Record(
                EcuWriteTransaction.Stage.BASELINE_CHECKED, EcuBackupCoverage.Region.CALIBRATION,
                0x1000, "a".repeat(64), "b".repeat(64)))
            val after = file.readBytes()
            assertEquals(0, PersistentEcuWriteJournal.inspectSnapshot(file, target, vin, before).baselineSectors)
            assertEquals(1, PersistentEcuWriteJournal.inspectSnapshot(file, target, vin, after).baselineSectors)
            assertFalse(PersistentEcuWriteJournal.inspectSnapshot(file, target, vin,
                after.copyOf(after.size - 1)).valid)
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun overlappingAndOverflowingSectorRangesAreRejected() {
        val region = EcuBackupCoverage.Region.CALIBRATION
        fun sector(address: Long, length: Long) = EcuPhysicalBackupInspection.Sector(
            region, address, length, "a".repeat(64))
        EcuPhysicalBackupInspection.validateRanges(listOf(sector(16, 8), sector(8, 8)))
        for (invalid in listOf(
            listOf(sector(8, 8), sector(15, 8)),
            listOf(sector(-1, 8)),
            listOf(sector(Long.MAX_VALUE - 3, 8)),
            listOf(sector(0, 0))
        )) {
            assertTrue(runCatching { EcuPhysicalBackupInspection.validateRanges(invalid) }.isFailure)
        }
    }

    @Test fun offlineInspectorRejectsOverlappingIndividuallyVerifiedSectors() = runBlocking {
        val dir = Files.createTempDirectory("camarodiag-ecu-overlap").toFile()
        try {
            val file = File(dir, "attempt.journal")
            val journal = PersistentEcuWriteJournal(file, target, vin)
            val store = PersistentEcuPhysicalBlockBackup(file, target, vin)
            for (address in listOf(0x1000L, 0x1004L)) {
                val region = EcuProgrammingDriverContract.RegionRequest(
                    EcuBackupCoverage.Region.CALIBRATION, address, 8)
                val bytes = ByteArray(8) { it.toByte() }
                val sha = EcuBenchExpertPlan.sha256Hex(bytes)
                store.preserve(region, bytes, sha)
                journal.append(EcuWriteTransaction.Record(
                    EcuWriteTransaction.Stage.BASELINE_CHECKED, region.region,
                    address, sha, "a".repeat(64)))
            }
            val report = EcuPhysicalBackupInspection.inspect(file, target, vin)
            assertFalse(report.verified)
            assertTrue(report.sectors.isEmpty())
        } finally { dir.deleteRecursively() }
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
