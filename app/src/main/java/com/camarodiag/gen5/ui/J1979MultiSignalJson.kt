package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.ObdJ1979MultiSignal
import org.json.JSONArray
import org.json.JSONObject

internal object J1979MultiSignalJson {
    fun encode(record:ObdJ1979MultiSignal.Record):JSONObject=JSONObject()
        .put("requestHex",record.requestHex)
        .put("signals",JSONArray(record.signals.map { signal ->
            JSONObject().put("pid",signal.pid).put("name",signal.name)
                .put("unit",signal.unit).put("number",signal.number)
                .put("rawEncoded",signal.rawEncoded)
        }))
}
