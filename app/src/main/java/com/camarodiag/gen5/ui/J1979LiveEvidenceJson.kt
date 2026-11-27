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
        .put("multiSignals",it.multiSignals?.let { signals -> JSONArray(signals.map { signal ->
            JSONObject().put("pid",signal.pid).put("name",signal.name).put("unit",signal.unit)
                .put("number",signal.number).put("rawEncoded",signal.rawEncoded)
        }) }?:JSONObject.NULL)
    fun encode(r: J1979LiveReport)=JSONObject()
        .put("schema",if(r.plan.target.legacy)"camarodiag-j1979-legacy-live-v1" else "camarodiag-j1979-live-v1")
        .put("scope",r.scope).put("physicallyAddressed",r.physicallyAddressed)
        .put("responseHexRepresentation",if(r.physicallyAddressed)"APPLICATION_PAYLOAD" else "NORMALIZED_APPLICATION_PAYLOAD")
        .put("declaredVin",r.plan.target.vin).put("observedVin",n(r.observedVin)).put("vinMatch",r.vinMatch)
        .put("expectedProtocol",r.plan.target.protocol).put("observedProtocol",n(r.observedProtocol))
        .put("rawProtocolResponse",n(r.protocolResponse))
        .put("txCanId",n(r.plan.target.requestId?.let(r.plan.target::idHex)))
        .put("rxCanId",n(if(r.plan.target.legacy)null else r.plan.target.idHex(r.plan.target.responseId)))
        .put("selectedResponseSource",n(if(r.plan.target.legacy)r.plan.target.idHex(r.plan.target.responseId) else null))
        .put("transportExchanges",JSONArray(r.legacyExchanges.map {
            JSONObject().put("logicalQuery",it.logicalQuery).put("requestHex",it.requestHex)
                .put("rawResponse",n(it.rawResponse)).put("status",it.status).put("detail",n(it.detail))
                .put("selectedRecordsHex",JSONArray(it.selectedRecordsHex)).put("frames",JSONArray(it.frames.map { frame ->
                    JSONObject().put("source",frame.source).put("rawHex",frame.rawHex).put("payloadHex",frame.payloadHex)
                }))
        }))
        .put("physicalVehicleVerified",false).put("wholeVehicleCoverageVerified",false)
        .put("rawOnly",r.plan.rawOnly)
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
        .put("trimLayout",r.trimLayout?.let { JSONObject().put("state",it.state).put("rawEncoded",n(it.rawEncoded)) }?:JSONObject.NULL)
        .put("scalingConfiguration",r.scalingConfiguration?.let { configs -> JSONArray(configs.map {
            JSONObject().put("pid",it.pid).put("state",it.state).put("dataHex",n(it.dataHex))
        }) }?:JSONObject.NULL)
        .put("multiSignalSummary",JSONArray(r.multiSignalSummary.map {
            JSONObject().put("pid",it.pid).put("name",it.name).put("unit",it.unit)
                .put("count",it.count).put("attempted",it.attempted).put("missing",it.missing)
                .put("minimum",it.minimum).put("maximum",it.maximum).put("mean",it.mean)
                .put("lastVerifiedValue",it.lastVerifiedValue).put("lastVerifiedElapsedMs",it.lastVerifiedElapsedMs)
        }))
        .put("summary",JSONArray(r.summaries.map {
            JSONObject().put("pid",it.pid).put("name",n(it.name)).put("unit",n(it.unit))
                .put("attempted",it.attempted).put("statusCounts",JSONObject(it.statusCounts))
                .put("minimum",n(it.minimum)).put("maximum",n(it.maximum)).put("mean",n(it.mean))
                .put("lastVerifiedValue",n(it.lastVerifiedValue)).put("lastVerifiedElapsedMs",n(it.lastVerifiedElapsedMs))
        }))
    fun csv(r: J1979LiveReport): String=buildString {
        fun escaped(value: String?)=value?.let { "\""+it.replace("\"","\"\"")+"\"" }?:""
        val multi=!r.plan.rawOnly&&r.plan.pids.any { com.camarodiag.gen5.data.obd.ObdJ1979MultiSignal.width(it)!=null || com.camarodiag.gen5.data.obd.ObdJ1979OperatingContext.isTrim(it) }
        val header="cycle,pid_hex,started_elapsed_ms,elapsed_ms,duration_ms,status,value,unit,request_hex,response_hex,detail"
        appendLine(if(multi)header+",signal_name,raw_encoded" else header)
        r.observations.forEach { row ->
            fun line(number:Double?,unit:String?,name:String?,raw:Long?){
                val fields=listOf(row.cycle?.toString().orEmpty(),row.pid?.let { p->"%02X".format(Locale.ROOT,p) }.orEmpty(),
                    row.startedElapsedMs.toString(),row.elapsedMs.toString(),row.durationMs.toString(),escaped(row.status),
                    number?.toString().orEmpty(),escaped(unit),escaped(row.requestHex),escaped(row.responseHex),escaped(row.detail))
                appendLine((if(multi)fields+listOf(escaped(name),raw?.toString().orEmpty()) else fields).joinToString(","))
            }
            val signals=row.multiSignals.orEmpty()
            if(signals.isEmpty())line(row.value?.number,row.value?.unit,row.value?.name,null)
            else signals.forEach { line(it.number,it.unit,it.name,it.rawEncoded) }
        }
    }
}
