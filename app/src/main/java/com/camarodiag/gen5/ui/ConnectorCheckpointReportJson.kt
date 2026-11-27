package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.CamaroDiagConnectorCheckpoint
import org.json.JSONObject

/** Snapshot report only: there is no private key, transport or write authority. */
internal object ConnectorCheckpointReportJson {
    fun encode(snapshot: CamaroDiagConnectorCheckpoint.Snapshot): String {
        require(snapshot.exportable)
        val inspection = snapshot.inspection
        val record = inspection.record?.let {
            JSONObject().put("stage", it.stage.name).put("sequence", it.sequence)
                .put("transactionHex", it.transactionHex).put("manifestSha256", it.manifestSha256)
        }
        return JSONObject()
            .put("schema", "camarodiag-offline-connector-checkpoint-v1")
            .put("platform", "ANDROID")
            .put("snapshotSha256", snapshot.snapshotSha256)
            .put("expectedTransaction", snapshot.expectedTransaction ?: JSONObject.NULL)
            .put("expectedManifest", snapshot.expectedManifest ?: JSONObject.NULL)
            .put("inspection", JSONObject().put("status", inspection.disposition.name)
                .put("record", record ?: JSONObject.NULL)
                .put("authenticated", false).put("permitsProgramming", false)
                .put("permitsAutomaticRecovery", false))
            .put("authenticated", false).put("physicalVehicleVerified", false)
            .put("permitsProgramming", false).put("permitsAutomaticRecovery", false)
            .toString(2)
    }
}
