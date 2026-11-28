package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.EcuBackupCoverage
import com.camarodiag.gen5.data.obd.EcuPhysicalBackupInspection
import com.camarodiag.gen5.data.obd.EcuWriteTransaction
import com.camarodiag.gen5.data.obd.PersistentEcuWriteJournal
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class PhysicalRecoveryHistoryTest {
    private val identity = PhysicalRecoveryIdentity("1G1FA1RXXR0123456", "E39", "ECM", "OS-A")
    private val region = EcuBackupCoverage.Region.CALIBRATION
    private val sector = EcuPhysicalBackupInspection.Sector(region, 0x1000, 8,
        "a".repeat(64), "b".repeat(64))
    private val stage = EcuWriteTransaction.Stage.BASELINE_CHECKED
    private val firstAudit = PersistentEcuWriteJournal.Audit(true, 1, stage, baselineSectors = 1)
    private val nextAudit = PersistentEcuWriteJournal.Audit(true, 2, EcuWriteTransaction.Stage.BEFORE_ERASE,
        baselineSectors = 1, uncertainAddress = 0x1000, uncertainRegion = region)
    private val finishedAudit = PersistentEcuWriteJournal.Audit(true, 5,
        EcuWriteTransaction.Stage.TRANSACTION_COMPLETED, baselineSectors = 1,
        verifiedSectors = 1, finalReadbackSectors = 1, completionRecorded = true)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    // The comparison engine receives only snapshots already independently
    // checked by the SAF inspector. These byte sequences test comparison
    // semantics, not the production persistent-journal parser.
    private fun captured(text: String, audit: PersistentEcuWriteJournal.Audit,
                         original: EcuPhysicalBackupInspection.Sector = sector):
        PhysicalRecoveryFolder.Captured {
        val bytes = text.toByteArray()
        val report = EcuPhysicalBackupInspection.Report(
            verified = true, expectedSectors = 1, sectors = listOf(original),
            verifiedBytes = original.length, problem = "",
            uncertainAddress = audit.uncertainAddress, uncertainRegion = audit.uncertainRegion,
            journalDisposition = audit.recoveryDisposition,
            snapshotSha256 = digest(bytes + "envelopes".toByteArray()).uppercase(),
            journalSha256 = digest(bytes), journalAudit = audit)
        return PhysicalRecoveryFolder.Captured("transaction.tsv", bytes, report)
    }

    private val first = captured("header\nbaseline\n", firstAudit)
    private val next = captured("header\nbaseline\nerase\n", nextAudit)
    private val finished = captured("header\nbaseline\nerase\nwrite\nreadback\ncomplete\n", finishedAudit)

    private fun compare(
        rows: List<PhysicalRecoveryFolder.Captured>,
        recheck: ((Int) -> EcuPhysicalBackupInspection.Report)? = null,
        current: () -> Boolean = { true }
    ): PhysicalRecoveryHistory.Report = runBlocking {
        PhysicalRecoveryHistory.compare(identity, rows.size,
            acquire = { rows[it] }, reacquire = { recheck?.invoke(it) ?: rows[it].report }, current = current)
    }

    @Test fun orderedHistoryRetainsRecordedDispositionButNoPhysicalAuthorization() {
        val result = compare(listOf(first, next))
        assertTrue(result.verified)
        assertEquals(2, result.snapshots.size)
        assertEquals(1, result.transitions.single().addedRecords)
        assertEquals(next.journalBytes.size - first.journalBytes.size, result.transitions.single().addedBytes)
        assertEquals(nextAudit.recoveryDisposition, result.latestEvidence?.journalDisposition)
        assertEquals(0x1000L, result.latestEvidence?.uncertainAddress)
        assertFalse(result.physicalVehicleVerified)
        assertFalse(result.restoreAuthorized)
        assertNull(result.problem)
    }

    @Test fun completeTransactionRemainsHistoricalNotAnAuthorization() {
        val result = compare(listOf(first, next, finished))
        assertTrue(result.verified)
        assertEquals(2, result.transitions.size)
        assertTrue(result.latestEvidence?.journalAudit?.completionRecorded == true)
        assertFalse(result.restoreAuthorized)
        val extension = captured(String(finished.journalBytes) + "more\n",
            finishedAudit.copy(records = 6))
        val rejected = compare(listOf(finished, extension))
        assertEquals(PhysicalRecoveryHistory.Problem.TERMINAL_EXTENSION, rejected.problem)
        assertNull(rejected.latestEvidence)
    }

    @Test fun duplicatesReverseForkAndPartialRecordFailClosed() {
        val fork = captured("header\nbaseline\nother\n", nextAudit)
        for (rows in listOf(listOf(first, first), listOf(next, first),
            listOf(next, fork))) {
            val result = compare(rows)
            assertFalse(result.verified)
            assertEquals(PhysicalRecoveryHistory.Problem.NOT_STRICT_EXTENSION, result.problem)
            assertNull(result.latestEvidence)
        }
        val truncated = captured("header\nbaseline\nerase", nextAudit)
        assertEquals(PhysicalRecoveryHistory.Problem.UNVERIFIED_SNAPSHOT,
            compare(listOf(first, truncated)).problem)
    }

    @Test fun changedSectorEnvelopeRejectsEqualOriginalBytes() {
        val changed = captured(String(next.journalBytes), nextAudit,
            sector.copy(envelopeSha256 = "c".repeat(64)))
        val result = compare(listOf(first, changed))
        assertEquals(PhysicalRecoveryHistory.Problem.ORIGINAL_EVIDENCE_CHANGED, result.problem)
        assertFalse(result.verified)
    }

    @Test fun invalidInitialHashAndMissingAuditAreRejected() {
        for (bad in listOf(first.report.copy(journalSha256 = "f".repeat(64)),
            first.report.copy(journalAudit = null),
            first.report.copy(verified = false),
            first.report.copy(sectors = listOf(sector.copy(envelopeSha256 = null))),
            first.report.copy(verifiedBytes = 999))) {
            val result = compare(listOf(first.copy(report = bad), next))
            assertEquals(PhysicalRecoveryHistory.Problem.UNVERIFIED_SNAPSHOT, result.problem)
            assertFalse(result.verified)
        }
    }

    @Test fun independentReinspectionInvalidatesAllEarlierSuccess() {
        val rows = listOf(first, next)
        val result = compare(rows, recheck = { index ->
            if (index == 0) first.report.copy(snapshotSha256 = "E".repeat(64)) else next.report
        })
        assertFalse(result.verified)
        assertEquals(PhysicalRecoveryHistory.Problem.EVIDENCE_CHANGED, result.problem)
        assertEquals(1, result.failedOrdinal)
        assertNull(result.latestEvidence)
    }

    @Test fun selectionSizeAndContextChangesRejectInspection() {
        assertEquals(PhysicalRecoveryHistory.Problem.INVALID_SELECTION, compare(listOf(first)).problem)
        assertEquals(PhysicalRecoveryHistory.Problem.INVALID_SELECTION, compare(List(9) { first }).problem)
        var calls = 0
        val result = compare(listOf(first, next), current = { ++calls < 3 })
        assertFalse(result.verified)
        assertNull(result.latestEvidence)
    }

    @Test fun cancellationPropagatesInsteadOfBecomingAReport() {
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            runBlocking {
                PhysicalRecoveryHistory.compare(identity, 2,
                    acquire = { throw java.util.concurrent.CancellationException() },
                    reacquire = { first.report }, current = { true })
            }
        }
    }

    @Test fun portableExportHasExactSchemaAndFalseSafetyFlags() {
        val result = compare(listOf(first, next))
        val json = JSONObject(PhysicalRecoveryHistoryJson.encode(result))
        assertEquals("camarodiag-offline-recovery-history-v1", json.getString("schema"))
        assertEquals("ANDROID", json.getString("platform"))
        assertTrue(json.getBoolean("ordered_prefix_verified"))
        assertEquals(identity.vin, json.getJSONObject("identity").getString("vin"))
        assertEquals(2, json.getJSONArray("snapshots").length())
        val transition = json.getJSONArray("transitions").getJSONObject(0)
        assertTrue(transition.getBoolean("strict_prefix"))
        assertTrue(transition.getBoolean("original_envelopes_unchanged"))
        assertEquals(1, transition.getInt("added_records"))
        assertEquals("ERASE_OR_WRITE_MAY_BE_INCOMPLETE", json.getString("latest_recorded_disposition"))
        for (flag in listOf("authenticated", "physical_vehicle_verified", "physical_restore_qualified",
            "restore_authorized", "current_ecu_state_verified")) assertFalse(json.getBoolean(flag))
        assertThrows(IllegalArgumentException::class.java) {
            PhysicalRecoveryHistoryJson.encode(compare(listOf(next, first)))
        }
    }

    @Test fun reorderControlsDoNotInferChronology() {
        val rows = listOf("older", "newer", "last")
        assertEquals(listOf("newer", "older", "last"), moved(rows, 0, 1))
        assertEquals(listOf("older", "last", "newer"), moved(rows, 2, -1))
        assertEquals(rows, moved(rows, 0, -1))
        assertEquals(rows, moved(rows, 2, 1))
    }
}
