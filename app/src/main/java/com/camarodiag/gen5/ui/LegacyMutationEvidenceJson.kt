package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.LegacyMutationResult
import org.json.JSONArray
import org.json.JSONObject

internal object LegacyMutationEvidenceJson {
    fun encode(result: LegacyMutationResult): JSONObject {
        fun dtcs(map: Map<String,Map<String,List<String>?>>) = JSONObject().apply {
            map.forEach { (source,modes) -> put(source,JSONObject().apply {
                modes.forEach { (mode,records) -> put(mode,records?.let { JSONArray(it) } ?: JSONObject.NULL) }
            }) }
        }
        return JSONObject()
            .put("scope","FUNCTIONAL_VEHICLE_WIDE")
            .put("wholeVehicleCoverageVerified",false)
            .put("leakTestPerformed",false)
            .put("elmProtocol",result.target.protocol)
            .put("operation",if (result.target.clear) "CLEAR" else "EVAP_PREPARATION")
            .put("engineSource","%02X".format(java.util.Locale.ROOT,result.target.engineSource))
            .put("documentedSources",JSONArray(result.target.sources.map { "%02X".format(java.util.Locale.ROOT,it) }))
            .put("outcome",result.outcome)
            .put("reason",result.reason ?: JSONObject.NULL)
            .put("commandAttempted",result.attempted)
            .put("observedVin",result.observedVin ?: JSONObject.NULL)
            .put("acknowledgments",JSONObject(result.acknowledgments))
            .put("beforeDtcs",dtcs(result.before)).put("afterDtcs",dtcs(result.after))
            .put("observations",JSONArray(result.observations.map {
                JSONObject().put("stage",it.stage).put("requestHex",it.request)
                    .put("rawResponse",it.response ?: JSONObject.NULL)
                    .put("sources",JSONArray(it.sources)).put("error",it.error ?: JSONObject.NULL)
            }))
    }
}
