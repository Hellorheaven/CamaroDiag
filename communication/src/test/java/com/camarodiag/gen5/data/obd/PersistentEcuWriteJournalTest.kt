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
}
