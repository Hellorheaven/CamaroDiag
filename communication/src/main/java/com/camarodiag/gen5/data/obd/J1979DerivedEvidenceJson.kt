package com.camarodiag.gen5.data.obd

import org.json.JSONArray
import org.json.JSONObject

/**
 * Derived evidence only. Raw application bytes remain authoritative and
 * the state of a diagnostic exchange is not a monitor verdict.
 */
object J1979DerivedEvidenceJson {
    fun monitors(records:List<ObdCanMonitorRecords.EngineeringRecord>)=JSONArray(records.map { record ->
        val result=record.result
        JSONObject().put("request_hex",record.requestHex).put("record_index",record.recordIndex)
            .put("monitor_id",result.mid).put("test_id",result.tid).put("unit_scaling_id",result.uasid)
            .put("value_encoded",result.value).put("minimum_encoded",result.minimum)
            .put("maximum_encoded",result.maximum)
            .put("scaled",result.scaled()?.let { scaled ->
                JSONObject().put("definition","ISO15031-5:2006-ANNEX-E-SUBSET")
                    .put("unit",scaled.unit).put("value",scaled.value)
                    .put("minimum",scaled.minimum).put("maximum",scaled.maximum)
                    .put("numeric_relation",scaled.numericRelation).put("monitor_verdict",JSONObject.NULL)
            } ?: JSONObject.NULL)
    })

    fun states(records:List<ObdJ1979Status.Record>)=JSONArray(records.map { record ->
        JSONObject().put("request_hex",record.requestHex).put("states",JSONArray(record.states.map { state ->
            JSONObject().put("pid",state.pid).put("name",state.name)
                .put("raw_encoded",state.rawEncoded).put("state",state.code)
        }))
    })
}
