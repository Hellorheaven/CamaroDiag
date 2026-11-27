package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.CamaroDiagConnectorCheckpoint
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectorCheckpointReportJsonTest {
    @Test fun exportCarriesPinnedEvidenceAndExplicitNonAuthorization() {
        val bytes = "43444a0102000000010000000102030405060708090a0b0c0d0e0f10202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3fa75e21b6"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val report=CamaroDiagConnectorCheckpoint.capture({bytes.inputStream()})
        val json=JSONObject(ConnectorCheckpointReportJson.encode(report))
        assertEquals(report.snapshotSha256,json.getString("snapshotSha256"))
        assertFalse(json.getBoolean("authenticated"))
        assertFalse(json.getBoolean("physicalVehicleVerified"))
        assertFalse(json.getBoolean("permitsProgramming"))
        assertFalse(json.getBoolean("permitsAutomaticRecovery"))
        assertEquals("RECOVERY_REQUIRED",json.getJSONObject("inspection").getString("status"))
        assertEquals(1L,json.getJSONObject("inspection").getJSONObject("record").getLong("sequence"))
    }
    @Test fun unavailableSnapshotCannotBecomeExport() {
        val report=CamaroDiagConnectorCheckpoint.capture({null})
        assertThrows(IllegalArgumentException::class.java) { ConnectorCheckpointReportJson.encode(report) }
    }
}
