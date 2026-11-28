package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.EcuBackupCoverage
import com.camarodiag.gen5.data.obd.EcuPhysicalBackupInspection
import com.camarodiag.gen5.data.obd.PersistentEcuWriteJournal
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhysicalRecoveryEvidenceJsonTest {
    private val identity=PhysicalRecoveryIdentity("1G1FA1RXXR0123456","E39","ECM","OS-A")
    private val audit=PersistentEcuWriteJournal.Audit(true,1,baselineSectors=1)
    private val sector=EcuPhysicalBackupInspection.Sector(EcuBackupCoverage.Region.CALIBRATION,0x1000,8,
        "a".repeat(64),"b".repeat(64))
    private val report=EcuPhysicalBackupInspection.Report(true,1,listOf(sector),8,"",
        journalDisposition=audit.recoveryDisposition,snapshotSha256="C".repeat(64),
        journalSha256="d".repeat(64),journalAudit=audit)

    @Test fun completeEvidenceIncludesJournalAndSectorEnvelopeFingerprints() {
        val json=JSONObject(PhysicalRecoveryEvidenceJson.encode(identity,report,1000,"test"))
        assertEquals(report.journalSha256,json.getString("journalSha256"))
        assertEquals(report.snapshotSha256,json.getString("snapshotSha256"))
        assertEquals(1,json.getJSONObject("journalAudit").getInt("baselineSectors"))
        assertEquals(sector.envelopeSha256,json.getJSONArray("sectors").getJSONObject(0).getString("envelopeSha256"))
        assertFalse(json.getBoolean("restoreAuthorized"))
        assertFalse(json.getBoolean("physicalVehicleVerified"))
        assertFalse(json.getBoolean("authenticated"))
    }
    @Test fun incompleteEvidenceCannotBeExportedAsVerified() {
        for(bad in listOf(report.copy(verified=false),report.copy(journalSha256=null),
            report.copy(snapshotSha256=null),report.copy(journalAudit=null),report.copy(expectedSectors=2),
            report.copy(sectors=listOf(sector.copy(envelopeSha256=null))))) {
            assertThrows(IllegalArgumentException::class.java) {
                PhysicalRecoveryEvidenceJson.encode(identity,bad,1000,"test")
            }
        }
    }
    @Test fun providerReadbackMustBeExactlyTheGeneratedReport() {
        val bytes=PhysicalRecoveryEvidenceJson.encode(identity,report,1000,"test").toByteArray()
        PhysicalRecoveryEvidenceJson.requireExactJson(bytes.inputStream(),bytes)
        for(bad in listOf(bytes.copyOf(bytes.size-1),bytes+0,
            bytes.copyOf().also { it[4]=(it[4]+1).toByte() })) {
            assertThrows(IllegalStateException::class.java) {
                PhysicalRecoveryEvidenceJson.requireExactJson(bad.inputStream(),bytes)
            }
        }
    }
}
