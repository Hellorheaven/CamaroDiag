package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.ObdDtc
import com.camarodiag.gen5.data.obd.ObdScanReport
import org.json.JSONArray
import org.json.JSONObject

/** Snapshot of the actual report, independent of UI sensor selection and garage profile. */
internal object ObdScanJsonExport {
    fun encode(report: ObdScanReport, exportedAtMillis: Long): String {
        fun dtc(d: ObdDtc) = JSONObject().put("code", d.code).put("status", d.status)
        fun dtcs(values: List<ObdDtc>) = JSONArray(values.map { dtc(it) })
        return JSONObject().apply {
            put("schema", "camarodiag-obd-scan-v1")
            put("exportedAtMillis", exportedAtMillis)
            put("vin", report.vin ?: JSONObject.NULL)
            put("simulated", report.adapterInfo["IDENTIFICATION"].orEmpty().contains("SIMULATED", ignoreCase = true))
            put("adapterInfo", JSONObject(report.adapterInfo))
            put("calibrationIds", JSONArray(report.calibrationIds))
            put("calibrationVerificationNumbers", JSONArray(report.calibrationVerificationNumbers))
            put("ecuNames", JSONArray(report.ecuNames))
            put("respondingEcuAddresses", JSONArray(report.respondingEcuAddresses))
            put("physicalCanResponder", report.physicalCanResponder ?: JSONObject.NULL)
            put("supportedPids", JSONArray(report.supportedPids.sorted()))
            put("supportedPidsByResponder", JSONObject().apply {
                report.supportedPidsByResponder.toSortedMap().forEach { (address, pids) ->
                    put(address, JSONArray(pids.sorted()))
                }
            })
            put("firstUnresolvedPid", report.firstUnresolvedPid ?: JSONObject.NULL)
            put("pidAttributionIncomplete", report.pidAttributionIncomplete)
            put("liveData", JSONArray(report.liveData.map { value ->
                JSONObject().put("pid", value.pid).put("name", value.name)
                    .put("value", value.value).put("unit", value.unit)
                    .put("raw", JSONArray(value.raw)).put("responder", value.responder ?: JSONObject.NULL)
                    .put("temperatureAtEncodingLimit", value.temperatureAtEncodingLimit)
            }))
            put("mode01Reads", JSONArray(report.mode01Reads.map { read ->
                JSONObject().put("pid", read.pid).put("rawResponse", read.rawResponse)
                    .put("observations", JSONArray(read.observations.map { observation ->
                        JSONObject().put("responder", observation.responder ?: JSONObject.NULL)
                            .put("raw", JSONArray(observation.bytes))
                            .put("eligibleForDecode", observation.eligibleForDecode)
                            .put("decodedValueCount", observation.decodedValueCount)
                    }))
            }))
            put("confirmedDtcs", dtcs(report.confirmedDtcs))
            put("pendingDtcs", dtcs(report.pendingDtcs))
            put("permanentDtcs", dtcs(report.permanentDtcs))
            put("unavailableDtcModes", JSONArray(report.unavailableDtcModes.sorted()))
            put("readiness", report.readiness ?: JSONObject.NULL)
            put("freezeFrameDtc", report.freezeFrameDtc?.let { dtc(it) } ?: JSONObject.NULL)
            put("freezeFrame", JSONObject().apply {
                report.freezeFrame.toSortedMap().forEach { (pid, bytes) -> put(pid.toString(), JSONArray(bytes)) }
            })
            put("monitorTestRaw", report.monitorTestRaw ?: JSONObject.NULL)
            put("rawLog", JSONArray(report.rawLog))
        }.toString(2)
    }
}
