package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.J1979LiveReport
import com.camarodiag.gen5.data.obd.J1979LiveObservation
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object J1979LiveEvidenceJson {
    private fun n(value: Any?): Any=value?:JSONObject.NULL
    private fun row(it: J1979LiveObservation)=JSONObject()
        .put("phase",it.phase).put("cycle",n(it.cycle)).put("pid",n(it.pid))
        .put("requestHex",it.requestHex).put("responseHex",n(it.responseHex))
        .put("rawAdapterResponse",n(it.rawAdapterResponse)).put("startedElapsedMs",it.startedElapsedMs)
        .put("elapsedMs",it.elapsedMs).put("durationMs",it.durationMs).put("status",it.status)
        .put("detail",n(it.detail)).put("measurement",it.value?.let { value ->
            JSONObject().put("name",value.name).put("number",value.number).put("unit",value.unit)
        }?:JSONObject.NULL)
    fun encode(r: J1979LiveReport)=JSONObject()
        .put("schema","camarodiag-j1979-live-v1").put("scope","PHYSICAL_SELECTED_ECU_LIVE")
        .put("declaredVin",r.plan.target.vin).put("observedVin",n(r.observedVin)).put("vinMatch",r.vinMatch)
        .put("expectedProtocol",r.plan.target.protocol).put("observedProtocol",n(r.observedProtocol))
        .put("rawProtocolResponse",n(r.protocolResponse))
        .put("txCanId",r.plan.target.idHex(requireNotNull(r.plan.target.requestId)))
        .put("rxCanId",r.plan.target.idHex(r.plan.target.responseId))
        .put("physicalVehicleVerified",false).put("wholeVehicleCoverageVerified",false)
        .put("selectedPids",JSONArray(r.plan.pids)).put("advertisedPids",JSONArray(r.advertisedPids))
        .put("inventoryComplete",r.inventoryComplete)
        .put("inventoryPages",JSONArray(r.inventoryPages.map {
            JSONObject().put("base",it.base).put("supportedIds",JSONArray(it.supportedIds))
                .put("hasNextPage",it.hasNextPage)
        }))
        .put("requestedCycles",r.plan.cycles).put("completedCycles",r.completedCycles)
        .put("intervalMs",r.plan.intervalMs).put("budgetMs",r.plan.budgetMs)
        .put("durationMs",r.durationMs).put("completed",r.completed).put("stopReason",n(r.stopReason))
        .put("notExecuted",JSONArray(listOf("MODE_04_CLEAR","MODE_08_CONTROL")))
        .put("admission",JSONArray(r.admission.map(::row))).put("observations",JSONArray(r.observations.map(::row)))
        .put("summary",JSONArray(r.summaries.map {
            JSONObject().put("pid",it.pid).put("name",n(it.name)).put("unit",n(it.unit))
                .put("attempted",it.attempted).put("statusCounts",JSONObject(it.statusCounts))
                .put("minimum",n(it.minimum)).put("maximum",n(it.maximum)).put("mean",n(it.mean))
                .put("lastVerifiedValue",n(it.lastVerifiedValue)).put("lastVerifiedElapsedMs",n(it.lastVerifiedElapsedMs))
        }))
    fun csv(r: J1979LiveReport): String=buildString {
        fun escaped(value: String?)=value?.let { "\""+it.replace("\"","\"\"")+"\"" }?:""
        appendLine("cycle,pid_hex,started_elapsed_ms,elapsed_ms,duration_ms,status,value,unit,request_hex,response_hex,detail")
        r.observations.forEach {
            appendLine(listOf(it.cycle?.toString().orEmpty(),it.pid?.let { p->"%02X".format(Locale.ROOT,p) }.orEmpty(),
                it.startedElapsedMs.toString(),it.elapsedMs.toString(),it.durationMs.toString(),escaped(it.status),
                it.value?.number?.toString().orEmpty(),escaped(it.value?.unit),escaped(it.requestHex),
                escaped(it.responseHex),escaped(it.detail)).joinToString(","))
        }
    }
}
