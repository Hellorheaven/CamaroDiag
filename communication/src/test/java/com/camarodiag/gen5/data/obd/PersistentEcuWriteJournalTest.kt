package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersistentEcuWriteJournalTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val target = EcuProgrammingDriverContract.FirmwareTarget("E39", "test-ecu", "test-fw")
    private val vin = "2G1FF3D36C9104199"
    private val record = EcuWriteTransaction.Record(
        stage = EcuWriteTransaction.Stage.BASELINE_CHECKED,
        region = EcuBackupCoverage.Region.CALIBRATION,
        address = 0x1000,
        originalSha256 = "a".repeat(64),
        updatedSha256 = "b".repeat(64)
    )

    private fun newJournal(): Pair<File, PersistentEcuWriteJournal> {
        val file = File(temporaryFolder.root, "ecu-write-journal.txt")
        return file to PersistentEcuWriteJournal(file, target, vin)
    }

    @Test fun recordsAreDurableAndHashChainedAcrossEraseBoundary() = runBlocking {
        val (file, journal) = newJournal()
        assertTrue(journal.inspect().valid)
        assertEquals(0, journal.inspect().records)
        journal.append(record)
        journal.append(record.copy(stage = EcuWriteTransaction.Stage.BEFORE_ERASE))
        journal.append(record.copy(stage = EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE))
        val audit = journal.inspect()
        assertTrue(audit.valid)
        assertEquals(3, audit.records)
        assertEquals(EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE, audit.lastStage)
        assertEquals(4, file.readLines().size)
        assertTrue(file.readText().contains(vin))
    }

    @Test fun anyRecordMutationMakesJournalInvalidAndPreventsFurtherWrites() = runBlocking {
        val (file, journal) = newJournal()
        journal.append(record)
        journal.append(record.copy(stage = EcuWriteTransaction.Stage.BEFORE_ERASE))
        file.writeText(file.readText().replace("BEFORE_ERASE", "BASELINE_CHECKED"))
        assertFalse(journal.inspect().valid)
        assertTrue(runCatching { journal.append(record) }.isFailure)
    }

    @Test fun truncatedLastRecordIsDetectedBeforeNextErase() = runBlocking {
        val (file, journal) = newJournal()
        journal.append(record)
        file.writeText(file.readText().dropLast(1))
        assertFalse(journal.inspect().valid)
        assertTrue(runCatching {
            journal.append(record.copy(stage = EcuWriteTransaction.Stage.BEFORE_ERASE))
        }.isFailure)
    }

    @Test fun activeWriterRejectsRollbackToAnOtherwiseValidJournalPrefix() = runBlocking {
        val (file, journal) = newJournal()
        journal.append(record)
        val baselineOnly = file.readBytes()
        journal.append(record.copy(stage = EcuWriteTransaction.Stage.BEFORE_ERASE))
        val completePrefix = file.readBytes()
        file.writeBytes(baselineOnly)
        // The historical prefix is syntactically valid, but it is not the last
        // durable record accepted by this live writer.
        assertTrue(PersistentEcuWriteJournal.inspectExisting(file, target, vin).valid)
        assertTrue(runCatching {
            journal.append(record.copy(stage = EcuWriteTransaction.Stage.BEFORE_ERASE))
        }.isFailure)
        assertTrue(baselineOnly.contentEquals(file.readBytes()))
        // Restoring the file does not reopen a poisoned writer.
        file.writeBytes(completePrefix)
        assertTrue(runCatching {
            journal.append(record.copy(stage = EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE))
        }.isFailure)
        assertTrue(completePrefix.contentEquals(file.readBytes()))
    }

    @Test fun existingTransactionLogCannotBeOverwritten() {
        val (file, _) = newJournal()
        assertTrue(runCatching {
            PersistentEcuWriteJournal(file, target, vin)
        }.isFailure)
    }

    @Test fun disorderedEraseAndVerificationAreRejectedBeforeChangingJournal() = runBlocking {
        val (_, journal) = newJournal()
        val nextSector = record.copy(address = 0x1008)
        journal.append(record)
        journal.append(nextSector)
        val originalCount = journal.inspect().records
        for (illegal in listOf(
            record, // duplicate baseline
            nextSector.copy(stage = EcuWriteTransaction.Stage.BEFORE_ERASE), // skip first sector
            record.copy(stage = EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE) // no erase
        )) {
            assertTrue("Illegal ECU journal stage must be rejected: $illegal",
                runCatching { journal.append(illegal) }.isFailure)
            assertTrue(journal.inspect().valid)
            assertEquals(originalCount, journal.inspect().records)
        }
        journal.append(record.copy(stage = EcuWriteTransaction.Stage.BEFORE_ERASE))
        for (illegal in listOf(
            nextSector.copy(stage = EcuWriteTransaction.Stage.BEFORE_ERASE),
            record.copy(stage = EcuWriteTransaction.Stage.BASELINE_CHECKED, address = 0x1010),
            record.copy(stage = EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE,
                updatedSha256 = "c".repeat(64))
        )) {
            assertTrue(runCatching { journal.append(illegal) }.isFailure)
            assertEquals(originalCount + 1, journal.inspect().records)
        }
        journal.append(record.copy(stage = EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE))
        journal.append(nextSector.copy(stage = EcuWriteTransaction.Stage.BEFORE_ERASE))
        journal.append(nextSector.copy(stage = EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE))
        assertEquals(originalCount + 4, journal.inspect().records)
        assertTrue(journal.inspect().valid)
    }

    @Test fun journalCannotMixMemoryRegionsWithinOneWriteTransaction() = runBlocking {
        val (_, journal) = newJournal()
        journal.append(record)
        val other = record.copy(region = EcuBackupCoverage.Region.DATA_FLASH, address = 0x2000)
        assertTrue(runCatching { journal.append(other) }.isFailure)
        assertEquals(1, journal.inspect().baselineSectors)
        assertTrue(journal.inspect().valid)
        journal.append(record.copy(stage = EcuWriteTransaction.Stage.BEFORE_ERASE))
        assertTrue(runCatching {
            journal.append(other.copy(stage = EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE))
        }.isFailure)
        assertEquals(record.region, journal.inspect().uncertainRegion)
    }

    @Test fun verificationCannotBeAppendedBeforeAnyBaseline() = runBlocking {
        val (_, journal) = newJournal()
        for (stage in listOf(
            EcuWriteTransaction.Stage.BEFORE_ERASE,
            EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE
        )) {
            assertTrue(runCatching { journal.append(record.copy(stage = stage)) }.isFailure)
        }
        assertTrue(journal.inspect().valid)
        assertEquals(0, journal.inspect().records)
    }

    @Test fun invalidSha256OrVinIsRejected() = runBlocking {
        val (_, journal) = newJournal()
        assertTrue(runCatching { journal.append(record.copy(updatedSha256 = "NOT_SHA256")) }.isFailure)
        assertEquals(0, journal.inspect().records)
        assertTrue(runCatching {
            PersistentEcuWriteJournal(
                File(temporaryFolder.root, "invalid-vin.txt"), target, "INVALIDVIN"
            )
        }.isFailure)
    }

    @Test fun restartAuditDistinguishesEachProgrammingInterruptionWithoutMutatingJournal() = runBlocking {
        val (file, journal) = newJournal()
        val next = record.copy(address = 0x1008)
        fun reopened(): PersistentEcuWriteJournal.Audit {
            val before = file.readBytes()
            val audit = PersistentEcuWriteJournal.inspectExisting(file, target, vin)
            assertTrue(before.contentEquals(file.readBytes()))
            return audit
        }

        assertEquals(PersistentEcuWriteJournal.RecoveryDisposition.NO_ERASE_RECORDED,
            reopened().recoveryDisposition)
        journal.append(record)
        journal.append(next)
        assertEquals(2, reopened().baselineSectors)
        assertEquals(PersistentEcuWriteJournal.RecoveryDisposition.NO_ERASE_RECORDED,
            reopened().recoveryDisposition)

        journal.append(record.copy(stage = EcuWriteTransaction.Stage.BEFORE_ERASE))
        val interrupted = reopened()
        assertEquals(PersistentEcuWriteJournal.RecoveryDisposition.ERASE_OR_WRITE_MAY_BE_INCOMPLETE,
            interrupted.recoveryDisposition)
        assertEquals(record.address, interrupted.uncertainAddress)
        assertEquals(record.region, interrupted.uncertainRegion)
        assertEquals(0, interrupted.verifiedSectors)

        journal.append(record.copy(stage = EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE))
        assertEquals(PersistentEcuWriteJournal.RecoveryDisposition.ADDITIONAL_BASELINES_NOT_WRITTEN,
            reopened().recoveryDisposition)
        assertEquals(1, reopened().verifiedSectors)
        assertEquals(null, reopened().uncertainAddress)

        journal.append(next.copy(stage = EcuWriteTransaction.Stage.BEFORE_ERASE))
        assertEquals(next.address, reopened().uncertainAddress)
        journal.append(next.copy(stage = EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE))
        val recorded = reopened()
        assertEquals(PersistentEcuWriteJournal.RecoveryDisposition.ALL_RECORDED_SECTORS_VERIFIED,
            recorded.recoveryDisposition)
        assertEquals(2, recorded.verifiedSectors)
    }

    @Test fun recoveryAuditRejectsIncorrectIdentityOrDamagedJournal() = runBlocking {
        val (file, journal) = newJournal()
        journal.append(record)
        fun assertInvalid(audit: PersistentEcuWriteJournal.Audit) {
            assertFalse(audit.valid)
            assertEquals(PersistentEcuWriteJournal.RecoveryDisposition.INVALID_OR_UNREADABLE,
                audit.recoveryDisposition)
        }
        assertInvalid(PersistentEcuWriteJournal.inspectExisting(file, target, "2G1FF3D36C9104198"))
        assertInvalid(PersistentEcuWriteJournal.inspectExisting(file,
            EcuProgrammingDriverContract.FirmwareTarget("E39", "other-ecu", "test-fw"), vin))
        file.writeText(file.readText().dropLast(1))
        assertInvalid(PersistentEcuWriteJournal.inspectExisting(file, target, vin))
    }

    @Test fun recoveryAuditOfMissingJournalDoesNotCreateAFile() {
        val file = File(temporaryFolder.root, "missing-journal.txt")
        val audit = PersistentEcuWriteJournal.inspectExisting(file, target, vin)
        assertFalse(audit.valid)
        assertFalse(file.exists())
        assertEquals(PersistentEcuWriteJournal.RecoveryDisposition.INVALID_OR_UNREADABLE,
            audit.recoveryDisposition)
    }

    @Test fun finalReadbackAndCompletionMustBeDurableAndOrdered() = runBlocking {
        val (file, journal) = newJournal()
        val next = record.copy(address = 0x1008)
        val finalStage = EcuWriteTransaction.Stage.FINAL_READBACK_VERIFIED
        val completedStage = EcuWriteTransaction.Stage.TRANSACTION_COMPLETED
        journal.append(record)
        journal.append(next)
        for (sector in listOf(record, next)) {
            journal.append(sector.copy(stage = EcuWriteTransaction.Stage.BEFORE_ERASE))
            journal.append(sector.copy(stage = EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE))
        }
        fun audit() = PersistentEcuWriteJournal.inspectExisting(file, target, vin)
        assertEquals(PersistentEcuWriteJournal.RecoveryDisposition.ALL_RECORDED_SECTORS_VERIFIED,
            audit().recoveryDisposition)
        assertTrue(runCatching { journal.append(next.copy(stage = completedStage)) }.isFailure)
        assertTrue(runCatching { journal.append(next.copy(stage = finalStage)) }.isFailure)
        journal.append(record.copy(stage = finalStage))
        assertEquals(1, audit().finalReadbackSectors)
        assertFalse(audit().completionRecorded)
        assertEquals(PersistentEcuWriteJournal.RecoveryDisposition.FINAL_READBACK_INCOMPLETE,
            audit().recoveryDisposition)
        assertTrue(runCatching { journal.append(record.copy(stage = finalStage)) }.isFailure)
        journal.append(next.copy(stage = finalStage))
        assertEquals(2, audit().finalReadbackSectors)
        assertFalse(audit().completionRecorded)
        assertTrue(runCatching { journal.append(record.copy(stage = completedStage)) }.isFailure)
        journal.append(next.copy(stage = completedStage))
        assertTrue(audit().completionRecorded)
        assertEquals(PersistentEcuWriteJournal.RecoveryDisposition.TRANSACTION_COMPLETION_RECORDED,
            audit().recoveryDisposition)
        assertTrue(runCatching { journal.append(next.copy(stage = completedStage)) }.isFailure)
        assertTrue(audit().valid)
    }
}
