package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.EcuPhysicalBackupInspection
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/** Versioned portable evidence only. Never creates an image or authorizes a restore. */
internal object PhysicalRecoveryEvidenceJson {
    private fun nullable(value: Any?): Any = value ?: JSONObject.NULL
    fun encode(identity: PhysicalRecoveryIdentity,report: EcuPhysicalBackupInspection.Report,
        exportedAtMillis: Long,version: String): String {
        require(identity.valid && report.verified && report.snapshotSha256 != null &&
            report.journalSha256 != null && report.journalAudit?.valid == true)
        val audit=requireNotNull(report.journalAudit)
        require(report.sectors.size == report.expectedSectors &&
            report.sectors.all { it.envelopeSha256?.matches(Regex("[0-9a-fA-F]{64}")) == true })
        return JSONObject()
            .put("schema","camarodiag-ecu-recovery-inspection-v1")
            .put("appVersion",version).put("exportedAtMillis",exportedAtMillis)
            .put("identity",JSONObject().put("vin",identity.vin).put("family",identity.family)
                .put("controller",identity.controller).put("firmware",identity.firmware))
            .put("verified",report.verified).put("expectedSectors",report.expectedSectors)
            .put("verifiedBytes",report.verifiedBytes)
            .put("snapshotSha256",report.snapshotSha256)
            .put("snapshotAlgorithm","SHA256_JOURNAL_AND_ENVELOPES_V1")
            .put("journalSha256",report.journalSha256)
            .put("journalAudit",JSONObject().put("valid",audit.valid).put("records",audit.records)
                .put("lastStage",nullable(audit.lastStage?.name))
                .put("baselineSectors",audit.baselineSectors).put("verifiedSectors",audit.verifiedSectors)
                .put("finalReadbackSectors",audit.finalReadbackSectors).put("completionRecorded",audit.completionRecorded)
                .put("recoveryDisposition",audit.recoveryDisposition.name))
            .put("problem",report.problem)
            .put("uncertainAddress",nullable(report.uncertainAddress))
            .put("uncertainRegion",nullable(report.uncertainRegion?.name))
            .put("problemAddress",nullable(report.problemAddress))
            .put("journalDisposition",nullable(report.journalDisposition?.name))
            .put("sectors",JSONArray(report.sectors.map { sector ->
                JSONObject().put("region",sector.region.name).put("address",sector.address)
                    .put("length",sector.length).put("sha256",sector.sha256)
                    .put("envelopeSha256",sector.envelopeSha256)
            }))
            .put("physicalEcuConnected",false).put("physicalVehicleVerified",false)
            .put("authenticated",false).put("restoreAuthorized",false)
            .put("sourceRevalidatedBeforePublication",true)
            .toString(2)
    }

    /** Never accept a provider replacing the staged JSON with another valid report. */
    fun requireExactJson(input: InputStream,expected: ByteArray) {
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(expected)
        PhysicalRecoveryFolder.verifyBytes(input,expected.size.toLong(),hash) {}
    }
}
