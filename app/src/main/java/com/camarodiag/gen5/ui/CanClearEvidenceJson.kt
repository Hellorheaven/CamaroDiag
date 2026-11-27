package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.ObdClearRecipeResult
import org.json.JSONArray
import org.json.JSONObject

/** Raw observations and nullable DTC evidence, never physical proof. */
internal object CanClearEvidenceJson {
    fun encode(result:ObdClearRecipeResult):JSONObject {
        fun dtcs(values:Map<String,List<String>?>)=JSONObject().apply {
            values.forEach { (mode,records)->put(mode,records?.let(::JSONArray)?:JSONObject.NULL) }
        }
        return JSONObject().put("outcome",result.outcome).put("commandAttempted",result.attempted)
            .put("acknowledged",result.acknowledged).put("reason",result.reason?:JSONObject.NULL)
            .put("observedVin",result.observedVin?:JSONObject.NULL)
            .put("elmProtocol",result.protocol).put("requestCanId",result.requestCanId)
            .put("responseCanId",result.responseCanId)
            .put("beforeDtcs",dtcs(result.beforeDtcs)).put("afterDtcs",dtcs(result.afterDtcs))
            .put("observations",JSONArray(result.observations.map { observation ->
                JSONObject().put("stage",observation.stage).put("requestHex",observation.requestHex)
                    .put("responseHex",observation.responseHex?:JSONObject.NULL)
                    .put("interpretation",observation.interpretation?:JSONObject.NULL)
                    .put("error",observation.error?:JSONObject.NULL)
            }))
    }
}
