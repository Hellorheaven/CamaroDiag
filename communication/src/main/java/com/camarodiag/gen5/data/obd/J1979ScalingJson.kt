package com.camarodiag.gen5.data.obd

import org.json.JSONArray
import org.json.JSONObject

/** Shared evidence encoding for contextual SAE J1979 measurements. */
object J1979ScalingJson {
    fun encode(projection: ObdJ1979ScalingContext.Projection): JSONObject =
        JSONObject()
            .put("configuration", JSONArray(projection.configuration.map {
                JSONObject().put("pid", it.pid).put("state", it.state)
                    .put("data_hex", it.dataHex ?: JSONObject.NULL)
            }))
            .put("records", JSONArray(projection.records.map { measurement ->
                JSONObject().put("request_hex", measurement.requestHex)
                    .put("configuration_pid", measurement.configurationPid)
                    .put("signals", JSONArray(measurement.signals.map { signal ->
                        JSONObject().put("name", signal.name).put("unit", signal.unit)
                            .put("raw_encoded", signal.rawEncoded)
                            .put("number", signal.number ?: JSONObject.NULL)
                            .put("scale_source", signal.scaleSource)
                    }))
            }))
}
