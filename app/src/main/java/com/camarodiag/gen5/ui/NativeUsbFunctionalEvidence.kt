package com.camarodiag.gen5.ui

import org.json.JSONObject

/** Results reflect recorded replies, not a claim of physical provenance. */
internal object NativeUsbFunctionalEvidence {
    fun initial(): List<FunctionalTestCheck> = listOf(
        Triple("auth", "Connector authentication", "Authentification du boîtier"),
        Triple("integrity", "Campaign consistency", "Cohérence de la campagne"),
        Triple("transport", "Initial OBD response", "Réponse OBD initiale"),
        Triple("vin", "VIN matches selected vehicle", "VIN conforme au véhicule sélectionné"),
        Triple("pid", "PID inventory", "Inventaire des PID"),
        Triple("dtc3", "Confirmed DTCs", "Défauts confirmés"),
        Triple("dtc7", "Pending DTCs", "Défauts en attente"),
        Triple("dtca", "Permanent DTCs", "Défauts permanents"),
        Triple("freeze", "Freeze-frame trigger", "Déclencheur des données figées"),
        Triple("monitor", "Monitor inventory", "Inventaire des moniteurs"),
        Triple("control", "Control inventory (read only)", "Inventaire des commandes (lecture)"),
        Triple("identity", "Identity inventory", "Inventaire identification"),
        Triple("physical", "Physical provenance", "Provenance physique")
    ).map { (key,en,fr) -> FunctionalTestCheck("native:$key","protocol",en,fr,
        FunctionalTestOutcome.NOT_TESTED,"No verified evidence.","Aucune preuve vérifiée.") }

    fun results(state: FunctionalTestState, report: JSONObject): FunctionalTestState {
        val auth = report.optBoolean("device_credential_authenticated")
        val consistent = auth && report.optBoolean("capture_integrity_verified")
        val summary = report.optJSONObject("summary")
        val vin = report.optString("observed_vin").takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) }
        val matches = consistent && vin != null && vin == state.context?.selectedVin
        val steps = report.optJSONArray("steps")
        fun positive(req: String): Boolean = consistent && steps != null &&
            (0 until steps.length()).any { i -> steps.optJSONObject(i)?.let {
                it.optString("request_hex") == req && it.optString("status") == "PASSED"
            } == true }
        fun flag(name: String) = consistent && summary?.optBoolean(name) == true
        val checks = initial().map { row ->
            val key = row.key.substringAfter(':')
            val passed = when (key) {
                "auth" -> auth
                "integrity" -> consistent
                "transport" -> flag("transport_observed")
                "vin" -> matches
                "pid" -> flag("pid_inventory_complete")
                "dtc3" -> positive("03")
                "dtc7" -> positive("07")
                "dtca" -> positive("0A")
                "freeze" -> flag("freeze_trigger_observed")
                "monitor" -> flag("monitor_inventory_complete")
                "control" -> flag("control_inventory_complete")
                "identity" -> flag("identity_inventory_complete")
                else -> false
            }
            val failed = key == "auth" && !auth || key == "integrity" && !consistent ||
                key == "vin" && consistent && vin != null && !matches
            row.copy(outcome = when {
                passed -> FunctionalTestOutcome.PASSED
                failed -> FunctionalTestOutcome.FAILED
                else -> FunctionalTestOutcome.NOT_TESTED
            }, detailEn = when { passed -> "Recorded evidence verified."; failed -> "Evidence rejected."; else -> row.detailEn },
                detailFr = when { passed -> "Preuve enregistrée vérifiée."; failed -> "Preuve rejetée."; else -> row.detailFr })
        }
        return state.copy(checks=checks,context=state.context?.copy(observedVin=vin),
            nativeUsbEvidence=report.toString())
    }
}
