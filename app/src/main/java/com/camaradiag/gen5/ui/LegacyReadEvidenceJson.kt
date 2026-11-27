package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.LegacyReadReport
import org.json.JSONArray
import org.json.JSONObject

internal object LegacyReadEvidenceJson {
    private fun value(it: Any?): Any = it ?: JSONObject.NULL
    fun encode(report: LegacyReadReport): JSONObject = JSONObject()
        .put("schema","camarodiag-functional-j1979-legacy-source-v1")
        .put("expectedProtocol",report.target.protocol)
        .put("observedProtocol",value(report.observedProtocol))
        .put("rawProtocolResponse",value(report.protocolResponse))
        .put("selectedResponseSource",report.target.sourceHex)
        .put("scope","FUNCTIONAL_SELECTED_RESPONSE")
        .put("physicalVehicleVerified",false)
        .put("wholeVehicleCoverageVerified",false)
        .put("declaredVin",value(report.target.expectedVin))
        .put("observedVin",value(report.observedVin))
        .put("vinMatch",report.vinMatch)
        .put("completed",report.completed)
        .put("stopReason",value(report.stopReason))
        .put("durationMs",report.durationMs)
        .put("otherResponseSources",JSONArray(report.otherSources))
        .put("notExecuted",JSONArray(report.notExecuted))
        .put("inventory",JSONArray(report.inventory.map {
            JSONObject().put("mode",it.mode).put("sensorMask",value(it.sensorMask))
                .put("state",it.state).put("verifiedPages",it.verifiedPages).put("advertisedIds",JSONArray(it.advertisedIds))
        }))
        .put("dtcModes",JSONArray(report.dtcModes.map {
            JSONObject().put("mode",it.mode).put("codes",it.codes?.let { rows -> JSONArray(rows) } ?: JSONObject.NULL)
                .put("status",value(it.status))
        }))
        .put("measurements",JSONArray(report.measurements.map {
            JSONObject().put("mode",it.mode).put("pid",it.pid).put("name",it.name)
                .put("number",it.number).put("unit",it.unit)
        }))
        .put("identification",JSONArray(report.identification.map {
            JSONObject().put("infoType",it.infoType).put("values",JSONArray(it.values))
        }))
        .put("readiness",report.readiness?.let {
            JSONObject().put("milOn",it.milOn).put("confirmedDtcCount",it.confirmedDtcCount)
                .put("compressionIgnition",it.compressionIgnition).put("byteB",it.byteB)
                .put("byteC",it.byteC).put("byteD",it.byteD).put("supportedMonitors",JSONObject(it.supportedMonitors))
        } ?: JSONObject.NULL)
        .put("freezeTrigger",report.freezeTrigger?.let {
            JSONObject().put("present",it.present).put("dtc",value(it.dtc))
        } ?: JSONObject.NULL)
        .put("monitorRecords",JSONArray(report.monitors.map {
            JSONObject().put("mode",it.mode).put("testId",it.testId).put("sensorMask",value(it.sensorMask))
                .put("encodedDataHex",it.encodedDataHex).put("componentAndLimitEncoded",value(it.componentAndLimitEncoded))
                .put("valueEncoded",value(it.valueEncoded)).put("limitEncoded",value(it.limitEncoded))
        }))
        .put("steps",JSONArray(report.steps.map {
            JSONObject().put("requestHex",it.requestHex).put("rawResponse",value(it.rawResponse))
                .put("status",it.status).put("detail",value(it.detail)).put("durationMs",it.durationMs)
                .put("acceptedRecords",JSONArray(it.acceptedRecords))
                .put("frames",JSONArray(it.frames.map { frame ->
                    JSONObject().put("source",frame.source).put("rawHex",frame.rawHex).put("payloadHex",frame.payloadHex)
                }))
        }))
}
