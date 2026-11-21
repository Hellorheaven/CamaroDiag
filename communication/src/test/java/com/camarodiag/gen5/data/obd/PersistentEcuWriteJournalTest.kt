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
