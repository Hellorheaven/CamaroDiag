package com.camarodiag.gen5.ui

import org.json.JSONObject
import com.camarodiag.connectors.J1979CampaignSemantics

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
    data class InventoryPage(val mode:Int,val base:Int,val ids:List<Int>,val next:Boolean)
    data class Inventory(val ids:Set<Int>,val complete:Boolean,val pages:List<InventoryPage>)
    /** A disconnected or duplicated page is not evidence of advertised support. */
    fun inventory(pages:List<InventoryPage>,mode:Int):Inventory {
        val accepted=mutableListOf<InventoryPage>()
        for(base in 0..224 step 32){
            val page=pages.filter { it.mode==mode&&it.base==base }.singleOrNull() ?: break
            accepted+=page
            if(!page.next) return Inventory(accepted.flatMap { it.ids }.toSet(),true,accepted.toList())
        }
        return Inventory(accepted.flatMap { it.ids }.toSet(),false,accepted.toList())
    }
    data class FreezeTrigger(val present:Boolean,val dtc:String?)
    data class DiagnosticValues(val observedVin: String?, val dtcs: Map<Int, List<String>?>,
        val measurements: List<Measurement>, val monitors: List<Monitor>, val identity: Map<Int,List<String>>, val readiness: Readiness?, val pages:List<InventoryPage>, val freeze:FreezeTrigger?)

    private fun inventoryValue(step:JSONObject):InventoryPage? {
        val request=step.optString("request_hex")
        if(!request.matches(Regex("(?:0[1689][0-9A-F]{2}|02[0-9A-F]{2}00)"))) return null
        val mode=request.take(2).toInt(16)
        val base=request.substring(2,4).toInt(16)
        if(base%32!=0) return null
        val raw=step.optString("response_hex")
        val prefix="%02X".format(java.util.Locale.ROOT,mode+64)+request.drop(2)
        if(!raw.matches(Regex(prefix+"[0-9A-F]{8}"))) return null
        val bytes=raw.drop(prefix.length).chunked(2).map { it.toInt(16) }
        val ids=(0..30).filter { bytes[it/8] and (128 shr (it%8))!=0 }.map { base+it+1 }
        val next=bytes[3] and 1!=0
        if(base==224&&next) return null
        val data=step.optJSONObject("supported_page") ?: return null
        val list=data.optJSONArray("supported_ids") ?: return null
        if(data.optInt("mode",-1)!=mode || data.optInt("base",-1)!=base ||
            data.opt("has_next_page")!=next || list.length()!=ids.size ||
            ids.indices.any { list.optInt(it,-1)!=ids[it] }) return null
        return InventoryPage(mode,base,ids,next)
    }

    private fun freezeValue(step:JSONObject):FreezeTrigger? {
        if(step.optString("request_hex")!="020200") return null
        val raw=step.optString("response_hex")
        if(!raw.matches(Regex("420200[0-9A-F]{4}"))) return null
        val a=raw.substring(6,8).toInt(16)
        val b=raw.substring(8,10).toInt(16)
        val present=a!=0||b!=0
        val dtc=if(present) "${"PCBU"[a shr 6]}${(a shr 4) and 3}"+raw.substring(7,10) else null
        val data=step.optJSONObject("freeze_trigger") ?: return null
        if(data.opt("present")!=present || !data.has("dtc") ||
            (if(dtc==null) !data.isNull("dtc") else data.opt("dtc")!=dtc)) return null
        return FreezeTrigger(present,dtc)
    }

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

    private fun positive(step:JSONObject):J1979CampaignSemantics.Step? =
        if(step.optString("status")=="PASSED") J1979CampaignSemantics.decodePositive(
            step.optString("request_hex"),step.optString("response_hex")) else null

    private fun observedVin(report:JSONObject):String? {
        val claimed=report.optString("observed_vin")
        if(!claimed.matches(Regex("[A-HJ-NPR-Z0-9]{17}")))return null
        val steps=report.optJSONArray("steps") ?: return null
        val vinRows=(0 until steps.length()).mapNotNull { steps.optJSONObject(it) }
            .filter { it.optString("request_hex")=="0902" }
        return claimed.takeIf { vinRows.size==1 && positive(vinRows.single())?.observedVin()==claimed }
    }

    /** Only a coherent authenticated-session report can feed the value display.
     * Missing mode evidence remains null, never an empty fault list. */
    fun diagnosticValues(report: JSONObject): DiagnosticValues? {
        if (report.optBoolean("cancelled") || !report.optBoolean("device_credential_authenticated") ||
            !report.optBoolean("capture_integrity_verified")) return null
        val steps = report.optJSONArray("steps") ?: return null
        val requests=(0 until steps.length()).map { steps.optJSONObject(it)?.optString("request_hex") ?: return null }
        if(requests.any { it.isBlank() } || requests.distinct().size!=requests.size) return null
        val vin = observedVin(report)
        val dtcs = linkedMapOf<Int, List<String>?>(3 to null, 7 to null, 10 to null)
        val measured = linkedMapOf<Pair<Int, Int>, Measurement>()
        val monitors = mutableListOf<Monitor>()
        val identity=linkedMapOf<Int,List<String>>()
        var readiness:Readiness?=null
        var freeze:FreezeTrigger?=null
        val pages=linkedMapOf<Pair<Int,Int>,InventoryPage>()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("status") != "PASSED") continue
            val request = step.optString("request_hex")
            inventoryValue(step)?.let { pages[it.mode to it.base]=it }
            freezeValue(step)?.let { freeze=it }
            readinessValue(step)?.let { readiness=it }
            identityValues(step)?.let { (type,values)->identity[type]=values }
            if (request in setOf("03","07","0A")) {
                val mode = request.toInt(16)
                val codes = step.optJSONArray("dtc_codes") ?: continue
                val list = (0 until codes.length()).map { codes.optString(it) }
                if (list==positive(step)?.dtcCodes()) dtcs[mode] = list
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
            val decoded=positive(step)?.measurement() ?: continue
            if(decoded.mode!=mode||decoded.pid!=pid||decoded.name!=name||
                decoded.unit!=unit||decoded.number!=number) continue
            measured[mode to pid] = Measurement(mode,pid,name,number,unit)
        }
        return DiagnosticValues(vin,dtcs,measured.values.sortedWith(compareBy({ it.mode },{ it.pid })),monitors.toList(),identity.toMap(),readiness,pages.values.sortedWith(compareBy({it.mode},{it.base})),freeze)
    }

    fun results(state: FunctionalTestState, report: JSONObject): FunctionalTestState {
        val auth = report.optBoolean("device_credential_authenticated")
        val consistent = auth && !report.optBoolean("cancelled") && report.optBoolean("capture_integrity_verified")
        val summary = report.optJSONObject("summary")
        val vin = observedVin(report)
        val matches = consistent && vin != null && vin == state.context?.selectedVin
        val steps = report.optJSONArray("steps")
        val values=diagnosticValues(report)
        fun positive(req: String): Boolean = consistent && values?.dtcs?.get(req.toInt(16))!=null
        fun attempted(req: String): Boolean = consistent && steps != null &&
            (0 until steps.length()).any { i -> steps.optJSONObject(i)?.optString("request_hex") == req }
        val dtcRequests = mapOf("dtc3" to "03", "dtc7" to "07", "dtca" to "0A")
        fun flag(name: String) = consistent && summary?.opt(name)==true
        fun complete(mode:Int,name:String)=flag(name)&&values?.let { inventory(it.pages,mode).complete }==true
        val checks = initial().map { row ->
            val key = row.key.substringAfter(':')
            val passed = when (key) {
                "auth" -> auth
                "integrity" -> consistent
                "transport" -> flag("transport_observed") && values?.let { inventory(it.pages,1).pages.isNotEmpty() }==true
                "vin" -> matches
                "pid" -> complete(1,"pid_inventory_complete")
                "dtc3" -> positive("03")
                "dtc7" -> positive("07")
                "dtca" -> positive("0A")
                "freeze" -> flag("freeze_trigger_observed") && values?.freeze!=null
                "monitor" -> complete(6,"monitor_inventory_complete")
                "control" -> complete(8,"control_inventory_complete")
                "identity" -> complete(9,"identity_inventory_complete")
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
