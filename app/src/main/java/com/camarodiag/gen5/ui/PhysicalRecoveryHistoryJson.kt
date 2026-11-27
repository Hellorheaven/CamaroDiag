package com.camarodiag.gen5.ui

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Portable history shape shared with the offline Python verifier. */
internal object PhysicalRecoveryHistoryJson {
    private fun nullable(value: Any?): Any = value ?: JSONObject.NULL

    private fun identity(value: PhysicalRecoveryIdentity) = JSONObject()
        .put("vin", value.vin)
        .put("family", value.family)
        .put("controller", value.controller)
        .put("firmware", value.firmware)

    private fun snapshot(row: PhysicalRecoveryHistory.Snapshot,
                         target: PhysicalRecoveryIdentity): JSONObject {
        val evidence = row.evidence
        val audit = requireNotNull(evidence.journalAudit)
        require(evidence.verified && audit.valid && evidence.journalSha256 != null &&
            evidence.snapshotSha256 != null && evidence.sectors.size == evidence.expectedSectors)
        return JSONObject()
            .put("ordinal", row.ordinal)
            .put("journal_name", row.journalName)
            .put("journal_sha256", requireNotNull(evidence.journalSha256).lowercase(Locale.ROOT))
            .put("snapshot_sha256", requireNotNull(evidence.snapshotSha256).uppercase(Locale.ROOT))
            .put("verified", true)
            .put("problem", JSONObject.NULL)
            .put("identity", identity(target))
            .put("expected_sectors", evidence.expectedSectors)
            .put("verified_bytes", evidence.verifiedBytes)
            .put("records", audit.records)
            .put("last_stage", nullable(audit.lastStage?.name))
            .put("sequence_valid", audit.valid)
            .put("completion_recorded", audit.completionRecorded)
            .put("recovery_disposition", audit.recoveryDisposition.name)
            .put("uncertain_address", nullable(audit.uncertainAddress))
            .put("uncertain_region", nullable(audit.uncertainRegion?.name))
            .put("written_sectors", audit.verifiedSectors)
            .put("final_readback_sectors", audit.finalReadbackSectors)
    }

    fun encode(report: PhysicalRecoveryHistory.Report): String {
        require(report.verified && report.identity.valid && report.problem == null &&
            report.failedOrdinal == null &&
            report.snapshots.size in 2..PhysicalRecoveryHistory.MAX_SNAPSHOTS &&
            report.transitions.size == report.snapshots.size - 1)
        val last = requireNotNull(report.latestEvidence)
        val audit = requireNotNull(last.journalAudit)
        return JSONObject()
            .put("schema", "camarodiag-offline-recovery-history-v1")
            .put("platform", "ANDROID")
            .put("verified", true)
            .put("ordered_prefix_verified", true)
            .put("identity", identity(report.identity))
            .put("snapshots", JSONArray(report.snapshots.map { snapshot(it, report.identity) }))
            .put("transitions", JSONArray(report.transitions.map { row ->
                JSONObject()
                    .put("from_ordinal", row.fromOrdinal)
                    .put("to_ordinal", row.toOrdinal)
                    .put("strict_prefix", true)
                    .put("added_records", row.addedRecords)
                    .put("added_bytes", row.addedBytes)
                    .put("previous_disposition", row.previousDisposition.name)
                    .put("recorded_disposition", row.recordedDisposition.name)
                    .put("original_envelopes_unchanged", true)
            }))
            .put("latest_recorded_disposition", audit.recoveryDisposition.name)
            .put("latest_recorded_uncertain_address", nullable(audit.uncertainAddress))
            .put("latest_recorded_uncertain_region", nullable(audit.uncertainRegion?.name))
            .put("problem", JSONObject.NULL)
            .put("authenticated", false)
            .put("physical_vehicle_verified", false)
            .put("physical_restore_qualified", false)
            .put("restore_authorized", false)
            .put("current_ecu_state_verified", false)
            .toString(2)
    }
}
