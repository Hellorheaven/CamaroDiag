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

    data class Measurement(val mode: Int, val pid: Int, val name: String, val number: Double, val unit: String)
    data class DiagnosticValues(val observedVin: String?, val dtcs: Map<Int, List<String>?>,
        val measurements: List<Measurement>)

    /** Only a coherent authenticated-session report can feed the value display.
     * Missing mode evidence remains null, never an empty fault list. */
    fun diagnosticValues(report: JSONObject): DiagnosticValues? {
        if (report.optBoolean("cancelled") || !report.optBoolean("device_credential_authenticated") ||
            !report.optBoolean("capture_integrity_verified")) return null
        val steps = report.optJSONArray("steps") ?: return null
        val vin = report.optString("observed_vin").takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) }
        val dtcs = linkedMapOf<Int, List<String>?>(3 to null, 7 to null, 10 to null)
        val measured = linkedMapOf<Pair<Int, Int>, Measurement>()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("status") != "PASSED") continue
            val request = step.optString("request_hex")
            if (request in setOf("03","07","0A")) {
                val mode = request.toInt(16)
                val codes = step.optJSONArray("dtc_codes") ?: continue
                val list = (0 until codes.length()).map { codes.optString(it) }
                if (list.all { it.matches(Regex("[PCBU][0-3][0-9A-F]{3}")) }) dtcs[mode] = list
            }
            val value = step.optJSONObject("measurement") ?: continue
            val mode = value.optInt("mode",-1)
            val pid = value.optInt("pid",-1)
            val name = value.optString("name")
            val unit = value.optString("unit")
            val number = value.optDouble("number",Double.NaN)
            if (mode !in setOf(1,2) || pid !in 1..255 || pid%32==0 ||
                request != "%02X%02X%s".format(java.util.Locale.ROOT,mode,pid,if(mode==2) "00" else "") ||
                !number.isFinite() || name.isBlank() || unit.isBlank()) continue
            measured[mode to pid] = Measurement(mode,pid,name,number,unit)
        }
        return DiagnosticValues(vin,dtcs,measured.values.sortedWith(compareBy({ it.mode },{ it.pid })))
    }

    fun results(state: FunctionalTestState, report: JSONObject): FunctionalTestState {
        val auth = report.optBoolean("device_credential_authenticated")
        val consistent = auth && !report.optBoolean("cancelled") && report.optBoolean("capture_integrity_verified")
        val summary = report.optJSONObject("summary")
        val vin = report.optString("observed_vin").takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) }
        val matches = consistent && vin != null && vin == state.context?.selectedVin
        val steps = report.optJSONArray("steps")
        fun positive(req: String): Boolean = consistent && steps != null &&
            (0 until steps.length()).any { i -> steps.optJSONObject(i)?.let {
                it.optString("request_hex") == req && it.optString("status") == "PASSED"
            } == true }
        fun attempted(req: String): Boolean = consistent && steps != null &&
            (0 until steps.length()).any { i -> steps.optJSONObject(i)?.optString("request_hex") == req }
        val dtcRequests = mapOf("dtc3" to "03", "dtc7" to "07", "dtca" to "0A")
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
                key == "vin" && consistent && vin != null && !matches ||
                (dtcRequests[key]?.let { attempted(it) && !positive(it) } == true)
            row.copy(outcome = when {
                passed -> FunctionalTestOutcome.PASSED
                failed -> FunctionalTestOutcome.FAILED
                else -> FunctionalTestOutcome.NOT_TESTED
            }, detailEn = when { passed -> "Recorded evidence verified."; failed -> "Evidence rejected."; else -> row.detailEn },
                detailFr = when { passed -> "Preuve enregistrée vérifiée."; failed -> "Preuve rejetée."; else -> row.detailFr })
        }
        return state.copy(checks=checks,context=state.context?.copy(observedVin=vin.takeIf { consistent }),
            nativeUsbEvidence=report.toString())
    }
}
