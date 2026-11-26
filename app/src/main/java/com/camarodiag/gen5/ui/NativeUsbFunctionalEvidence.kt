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
    data class Monitor(val mid: Int, val tid: Int, val scaling: Int, val value: Int, val minimum: Int, val maximum: Int)
    data class Readiness(val milOn:Boolean,val dtcCount:Int,val compression:Boolean,val monitors:Map<String,Boolean>)
    data class DiagnosticValues(val observedVin: String?, val dtcs: Map<Int, List<String>?>,
        val measurements: List<Measurement>, val monitors: List<Monitor>, val identity: Map<Int,List<String>>, val readiness: Readiness?)

    private fun readinessValue(step: JSONObject): Readiness? {
        if(step.optString("request_hex")!="0101") return null
        val raw=step.optString("response_hex")
        if(!raw.matches(Regex("4101[0-9A-F]{8}"))) return null
        val (a,b,c,d)=raw.drop(4).chunked(2).map { it.toInt(16) }
        val compression=b and 8!=0
        val monitors=linkedMapOf<String,Boolean>()
        fun add(names:List<String?>,support:Int,incomplete:Int) {
            names.forEachIndexed { bit,name ->
                if(name!=null && support and (1 shl bit)!=0) monitors[name]=incomplete and (1 shl bit)==0
            }
        }
        add(listOf("misfire","fuel-system","components"),b,b shr 4)
        add(if(compression) listOf("nmhc-catalyst","nox-aftertreatment",null,"boost-pressure",null,
            "exhaust-gas-sensor","particulate-filter","egr-vvt")
            else listOf("catalyst","heated-catalyst","evaporative-system","secondary-air",null,
                "oxygen-sensor","oxygen-sensor-heater","egr-vvt"),c,d)
        val data=step.optJSONObject("readiness") ?: return null
        val declared=data.optJSONObject("supported_monitors") ?: return null
        val mil=a and 128!=0
        if(data.opt("mil_on")!=mil || data.opt("compression_ignition")!=compression ||
            data.optInt("confirmed_dtc_count",-1)!=(a and 127) ||
            data.optInt("byte_b",-1)!=b || data.optInt("byte_c",-1)!=c || data.optInt("byte_d",-1)!=d ||
            declared.length()!=monitors.size || monitors.any { (key,value)->declared.opt(key)!=value }) return null
        return Readiness(mil,a and 127,compression,monitors.toMap())
    }

    private fun identityValues(step: JSONObject): Pair<Int,List<String>>? {
        val req=step.optString("request_hex")
        if (req !in setOf("0904","0906","090A")) return null
        val type=req.takeLast(2).toInt(16)
        val raw=step.optString("response_hex")
        if (raw.length !in 6..8190 || raw.length%2!=0 || !raw.startsWith("49"+req.takeLast(2)) ||
            !raw.matches(Regex("[0-9A-F]+"))) return null
        val data=raw.chunked(2).map { it.toInt(16) }
        val count=data[2]
        val width=when(type){4->16;6->4;else->20}
        if (count==0 || data.size!=3+count*width) return null
        fun ascii(bytes: List<Int>): String? {
            val clean=bytes.dropLastWhile { it==0 }
            if (clean.isEmpty() || clean.any { it !in 32..126 }) return null
            return clean.map { it.toChar() }.joinToString("")
        }
        val values=when(type){
            4->(0 until count).map { ascii(data.subList(3+it*16,3+(it+1)*16)) ?: return null }
            6->(0 until count).map { raw.substring(6+it*8,6+(it+1)*8) }
            else->{
                if(count!=1 || data[7]!=45) return null
                val acronym=ascii(data.subList(3,7)) ?: return null
                val description=ascii(data.subList(8,23)) ?: return null
                listOf("$acronym-$description")
            }
        }
        val info=step.optJSONObject("vehicle_info") ?: return null
        val declared=info.optJSONArray("values") ?: return null
        if(info.optInt("info_type",-1)!=type || declared.length()!=values.size ||
            values.indices.any { declared.optString(it)!=values[it] }) return null
        return type to values
    }

    /** Only a coherent authenticated-session report can feed the value display.
     * Missing mode evidence remains null, never an empty fault list. */
    fun diagnosticValues(report: JSONObject): DiagnosticValues? {
        if (report.optBoolean("cancelled") || !report.optBoolean("device_credential_authenticated") ||
            !report.optBoolean("capture_integrity_verified")) return null
        val steps = report.optJSONArray("steps") ?: return null
        val vin = report.optString("observed_vin").takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) }
        val dtcs = linkedMapOf<Int, List<String>?>(3 to null, 7 to null, 10 to null)
        val measured = linkedMapOf<Pair<Int, Int>, Measurement>()
        val monitors = mutableListOf<Monitor>()
        val identity=linkedMapOf<Int,List<String>>()
        var readiness:Readiness?=null
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("status") != "PASSED") continue
            val request = step.optString("request_hex")
            readinessValue(step)?.let { readiness=it }
            identityValues(step)?.let { (type,values)->identity[type]=values }
            if (request in setOf("03","07","0A")) {
                val mode = request.toInt(16)
                val codes = step.optJSONArray("dtc_codes") ?: continue
                val list = (0 until codes.length()).map { codes.optString(it) }
                if (list.all { it.matches(Regex("[PCBU][0-3][0-9A-F]{3}")) }) dtcs[mode] = list
            }
            if (request.matches(Regex("06[0-9A-F]{2}"))) {
                val mid = request.takeLast(2).toInt(16)
                if (mid % 32 != 0) {
                    val response = step.optString("response_hex")
                    val encoded = step.optJSONArray("monitor_records")
                    if (encoded != null && response.startsWith("46") &&
                        response.length == 2 + 18 * encoded.length() &&
                        encoded.length() in 1..455 && response.matches(Regex("[0-9A-F]+"))) {
                        val parsed = mutableListOf<Monitor>()
                        for (j in 0 until encoded.length()) {
                            val start = 2 + j * 18
                            val raw = response.substring(start, start + 18)
                            val row = encoded.optJSONObject(j) ?: break
                            val item = Monitor(raw.substring(0,2).toInt(16), raw.substring(2,4).toInt(16),
                                raw.substring(4,6).toInt(16), raw.substring(6,10).toInt(16),
                                raw.substring(10,14).toInt(16), raw.substring(14,18).toInt(16))
                            if (item.mid != mid || row.optInt("monitor_id",-1) != item.mid ||
                                row.optInt("test_id",-1) != item.tid || row.optInt("unit_scaling_id",-1) != item.scaling ||
                                row.optInt("value_encoded",-1) != item.value || row.optInt("minimum_encoded",-1) != item.minimum ||
                                row.optInt("maximum_encoded",-1) != item.maximum) break
                            parsed.add(item)
                        }
                        if (parsed.size == encoded.length()) monitors.addAll(parsed)
                    }
                }
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
        return DiagnosticValues(vin,dtcs,measured.values.sortedWith(compareBy({ it.mode },{ it.pid })),monitors.toList(),identity.toMap(),readiness)
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
