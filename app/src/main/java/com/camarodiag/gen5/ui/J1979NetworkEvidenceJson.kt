package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.J1979NetworkReport
import org.json.JSONArray
import org.json.JSONObject

internal object J1979NetworkEvidenceJson {
    private fun n(value:Any?):Any=value?:JSONObject.NULL
    fun encode(report:J1979NetworkReport):JSONObject {
        val d=report.discovery
        return JSONObject()
            .put("schema","camarodiag-j1979-network-v1")
            .put("protocol",report.plan.protocol).put("declaredVin",report.plan.vin)
            .put("selectedResponseIds",JSONArray(report.plan.selectedResponseIds))
            .put("budgetMs",report.plan.budgetMs).put("durationMs",report.durationMs)
            .put("completed",report.completed).put("stopReason",n(report.stopReason))
            .put("physicalVehicleVerified",false).put("wholeVehicleCoverageVerified",false)
            .put("notExecuted",JSONArray(listOf("MODE_04_CLEAR","MODE_08_CONTROL")))
            .put("discovery",JSONObject().put("observedProtocol",n(d.observedProtocol))
                .put("rawProtocol",n(d.rawProtocol)).put("rawResponse",n(d.rawResponse))
                .put("requestSent",d.requestSent).put("completed",d.completed)
                .put("stopReason",n(d.stopReason)).put("ignoredFrames",d.ignoredFrames)
                .put("frames",JSONArray(d.frames.map { JSONObject().put("responseId",it.responseId).put("dataHex",it.dataHex) }))
                .put("responders",JSONArray(d.responders.map {
                    JSONObject().put("requestId",it.requestId).put("responseId",it.responseId)
                        .put("status",it.status).put("detail",n(it.detail))
                        .put("supportedPids",n(it.supportedPids?.let { p->JSONArray(p) }))
                        .put("nextPage",n(it.nextPage)).put("negativeResponseCode",n(it.negativeCode))
                        .put("frames",JSONArray(it.frames.map { f->f.dataHex }))
                })))
            .put("ecus",JSONArray(report.ecus.map { ecu->
                JSONObject().put("requestId",ecu.requestId).put("responseId",ecu.responseId)
                    .put("observedVin",n(ecu.observedVin)).put("completed",ecu.completed)
                    .put("status",ecu.status).put("stopReason",n(ecu.stopReason))
                    .put("inventories",JSONArray(ecu.inventories.map {
                        JSONObject().put("mode",it.mode).put("status",it.status)
                            .put("advertised",JSONArray(it.advertised)).put("pages",it.pages)
                    }))
                    .put("multiSignalRecords",JSONArray(ecu.steps.mapNotNull { it.multiSignalRecord }.map(J1979MultiSignalJson::encode)))
                    .put("extendedVehicleInfo",JSONArray(ecu.steps.mapNotNull { it.extendedVehicleInfo }.map(J1979ExtendedVehicleInfoJson::encode)))
                    .put("steps",JSONArray(ecu.steps.map {
                        val evidence=it.application
                        JSONObject().put("requestHex",it.requestHex).put("responseHex",n(it.responseHex))
                            .put("rawResponse",n(it.rawResponse)).put("status",it.status)
                            .put("detail",n(it.detail)).put("durationMs",it.durationMs)
                            .put("dtcCodes",n(evidence?.dtcCodes()?.let { codes->JSONArray(codes) }))
                            .put("measurement",n(evidence?.measurement()?.let { m->
                                JSONObject().put("pid",m.pid).put("mode",m.mode).put("name",m.name)
                                    .put("number",m.number).put("unit",m.unit)
                            }))
                    }))
            }))
    }
}
