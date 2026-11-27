package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.ObdJ1979ExtendedVehicleInfo
import org.json.JSONArray
import org.json.JSONObject

/** An independent engineering view; keep the raw observations separately. */
internal object J1979ExtendedVehicleInfoJson {
    fun encode(record: ObdJ1979ExtendedVehicleInfo.Result): JSONObject = JSONObject()
        .put("infoType",record.infoType)
        .put("values",JSONArray(record.values))
        .put("generalDenominator",record.generalDenominator ?: JSONObject.NULL)
        .put("ignitionCycles",record.ignitionCycles ?: JSONObject.NULL)
        .put("counterCount",record.counterCount ?: JSONObject.NULL)
        .put("regulatoryVerdict",JSONObject.NULL)
        .put("monitors",JSONArray(record.monitors.map {
            JSONObject().put("monitor",it.monitor).put("numerator",it.numerator)
                .put("denominator",it.denominator).put("ratio",it.ratio ?: JSONObject.NULL)
        }))
}
