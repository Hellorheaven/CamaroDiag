package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.J1979FreezeEvidence
import com.camarodiag.gen5.data.obd.J1979FreezePage
import com.camarodiag.gen5.data.obd.J1979FreezeReport
import com.camarodiag.gen5.data.obd.J1979FreezeStep
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Portable application evidence plus the original Android adapter transcript.
 * The offline host independently checks the selected responder and request plan. */
internal object J1979FreezeEvidenceJson {
    private fun nullable(value: Any?): Any = value ?: JSONObject.NULL

    private fun page(value: J1979FreezePage): JSONObject = JSONObject()
        .put("base",value.base)
        .put("bitmap_hex",value.bitmapHex)
        .put("advertised_pids",JSONArray(value.advertisedPids))
        .put("has_next_page",value.hasNextPage)

    private fun evidence(value: J1979FreezeEvidence): JSONObject {
        if (value.vin != null) return JSONObject().put("vin",value.vin)
        val result = JSONObject()
            .put("frame",requireNotNull(value.frame))
            .put("pid",requireNotNull(value.pid))
            .put("data_hex",requireNotNull(value.dataHex))
        value.page?.let { result.put("page",page(it)) }
        value.triggerPresent?.let {
            result.put("trigger_present",it)
                .put("trigger_dtc",nullable(value.triggerDtc))
        }
        value.measurement?.let { measurement ->
            result.put("measurement",JSONObject()
                .put("mode",measurement.mode).put("pid",measurement.pid)
                .put("name",measurement.name).put("number",measurement.number)
                .put("unit",measurement.unit).put("frame",measurement.frame))
        }
        return result
    }

    private fun step(value: J1979FreezeStep): JSONObject = JSONObject()
        .put("request_hex",value.requestHex)
        .put("response_hex",nullable(value.responseHex))
        .put("transport_outcome",value.transportOutcome)
        .put("duration_ms",value.durationMs)
        .put("transmission_attempted",value.transmissionAttempted)
        .put("status",value.status)
        .put("detail",nullable(value.detail))
        .put("evidence",value.evidence?.let(::evidence) ?: JSONObject.NULL)

    private fun body(result: J1979FreezeResult): JSONObject {
        val plan = result.plan
        require(plan.valid)
        val target = plan.target
        return JSONObject()
            .put("plan",JSONObject()
                .put("expected_vin",target.vin)
                .put("frames",JSONArray(plan.frames))
                .put("selected_pids",JSONArray(plan.selectedPids))
                .put("budget_ms",plan.budgetMs))
            .put("initial_vin",nullable(result.initialVin))
            .put("final_vin",nullable(result.finalVin))
            .put("completed",result.completed)
            .put("stop_reason",nullable(result.stopReason))
            .put("identity_rechecked",result.identityRechecked)
            .put("frames",JSONArray(result.frames.map { frame ->
                JSONObject()
                    .put("frame",frame.frame).put("required_frame",frame.requiredFrame)
                    .put("status",frame.status).put("trigger_dtc",nullable(frame.triggerDtc))
                    .put("final_trigger_dtc",nullable(frame.finalTriggerDtc))
                    .put("trigger_stable",frame.triggerStable)
                    .put("inventory_complete",frame.inventoryComplete)
                    .put("verified_pages",JSONArray(frame.verifiedPages.map(::page)))
                    .put("advertised_pids",JSONArray(frame.advertisedPids))
                    .put("read_pids",JSONArray(frame.readPids))
                    .put("unadvertised_selected_pids",JSONArray(frame.unadvertisedSelectedPids))
                    .put("not_read_selected_pids",JSONArray(frame.notReadSelectedPids))
                    .put("measurement_steps",JSONArray(frame.measurementSteps))
                    .put("detail",nullable(frame.detail))
            }))
            .put("steps",JSONArray(result.steps.map(::step)))
            .put("unvisited_frames",JSONArray(result.unvisitedFrames))
            .put("all_frames_stable",result.allFramesStable)
            .put("duration_ms",result.durationMs)
            .put("physical_vehicle_verified",false)
            .put("authenticated",false)
            .put("whole_vehicle_coverage_verified",false)
            .put("permits_programming",false)
    }

    fun encode(report: J1979FreezeReport,adapter: String,version: String): JSONObject {
        require(adapter.isNotBlank() && version.isNotBlank())
        val result = report.result
        val target = result.plan.target
        return JSONObject()
            .put("schema","camarodiag-functional-j1979-freeze-v1")
            .put("protocol","SAE-J1979")
            .put("transport","ANDROID_ELM_ISOTP")
            .put("version",version)
            .put("adapter",adapter)
            .put("profile",target.protocol)
            .put("tx_can_id",target.idHex(requireNotNull(target.requestId)))
            .put("rx_can_id",target.idHex(target.responseId))
            .put("physical_channel_confirmed_by_operator",true)
            .put("started_at_utc",Instant.ofEpochMilli(report.startedAtMillis).toString())
            .put("finished_at_utc",Instant.ofEpochMilli(report.finishedAtMillis).toString())
            .put("transport_problem",nullable(report.transportProblem))
            .put("execution_completed",report.executionCompleted)
            .put("raw_protocol_response",nullable(report.rawProtocolResponse))
            .put("observed_protocol",nullable(report.observedProtocol))
            .put("adapter_exchanges",JSONArray(result.steps.mapIndexed { index,row ->
                JSONObject().put("step",index)
                    .put("request_hex",row.requestHex)
                    .put("raw_response",nullable(row.rawAdapterResponse))
            }))
            .put("result",body(result))
    }
    fun encodeNative(evidence: CamaroDiagConnectorFreezeCampaign.Evidence,version:String,
        exportedCaptureName:String?):JSONObject {
        val profile=evidence.target.profile
        val channel=evidence.target.channel
        val result=evidence.report
        return JSONObject()
            .put("schema","camarodiag-functional-j1979-native-freeze-v1")
            .put("protocol","SAE-J1979")
            .put("source","LIVE_USB_CU2")
            .put("version",version)
            .put("declared_connector",channel.connector)
            .put("declared_ecu",channel.ecu)
            .put("connection_generation",channel.connectionGeneration)
            .put("started_at_utc",Instant.ofEpochMilli(evidence.startedAtMillis).toString())
            .put("finished_at_utc",Instant.ofEpochMilli(evidence.finishedAtMillis).toString())
            .put("profile",JSONObject()
                .put("request_can_id",profile.tx.toString(16).uppercase(java.util.Locale.ROOT))
                .put("response_can_id",profile.rx.toString(16).uppercase(java.util.Locale.ROOT))
                .put("extended",profile.extended)
                .put("plan",JSONObject().put("expected_vin",profile.vin)
                    .put("frames",JSONArray(profile.frames().toList()))
                    .put("selected_pids",JSONArray(profile.pids().toList()))
                    .put("budget_ms",profile.budgetMs)))
            .put("device_credential_authenticated",evidence.credentialAuthenticated)
            .put("client_proof_sent",evidence.proofSent)
            .put("frame_integrity_verified",evidence.framed)
            .put("capture_integrity_verified",evidence.captureIntegrityVerified)
            .put("execution_completed",evidence.executionCompleted)
            .put("cancelled",evidence.cancelled)
            .put("observations_received",evidence.observationCount)
            .put("capture_sha256",nullable(evidence.sha256))
            .put("raw_capture_name",nullable(exportedCaptureName))
            .put("error",nullable(evidence.error))
            .put("hardware_provenance_verified",false)
            .put("evidence_authenticated",false)
            .put("physical_vehicle_verified",false)
            .put("permits_programming",false)
            .put("summary",result?.let { r -> JSONObject()
                .put("stop_reason",nullable(r.stopReason)).put("visited_frames",r.frames.size)
                .put("completed",r.completed).put("identity_rechecked",r.identityRechecked)
                .put("all_frames_stable",r.allFramesStable).put("steps",r.steps.size)
                .put("duration_ms",r.durationMs).put("frame_states",JSONArray(r.frames.map { it.status }))
            } ?: JSONObject.NULL)
            .put("result",result?.let(::body) ?: JSONObject.NULL)
    }
}
