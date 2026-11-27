package com.camarodiag.gen5.data.obd

import com.camarodiag.connectors.J1979FreezeUsbCapture
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/** Interrupted CF/1 interpretation is deliberately NOT a frozen-value report.
 * A missing terminal can never be promoted to a successful Mode 02 campaign.
 * No USB connection, ECU command or write capability is exposed here. */
object J1979FreezePrefix {
    const val SCHEMA = "camarodiag-j1979-native-freeze-prefix-v1"

    private fun evidence(value: J1979FreezeEvidence?): Map<String,Any?>? {
        if (value == null) return null
        value.vin?.let { return mapOf("vin" to it) }
        val row = linkedMapOf<String,Any?>(
            "frame" to requireNotNull(value.frame),
            "pid" to requireNotNull(value.pid),
            "data_hex" to requireNotNull(value.dataHex)
        )
        value.page?.let { p ->
            row["page"] = mapOf(
                "base" to p.base,
                "bitmap_hex" to p.bitmapHex,
                "advertised_pids" to p.advertisedPids,
                "has_next_page" to p.hasNextPage
            )
        }
        value.triggerPresent?.let {
            row["trigger_present"] = it
            row["trigger_dtc"] = value.triggerDtc
        }
        value.measurement?.let { measurement ->
            row["measurement"] = mapOf(
                "mode" to measurement.mode, "pid" to measurement.pid,
                "name" to measurement.name, "number" to measurement.number,
                "unit" to measurement.unit, "frame" to measurement.frame
            )
        }
        return row
    }

    private fun step(value: J1979FreezeStep): Map<String,Any?> =
        linkedMapOf(
            "request_hex" to value.requestHex,
            "response_hex" to value.responseHex,
            "transport_outcome" to value.transportOutcome,
            "duration_ms" to value.durationMs,
            "transmission_attempted" to value.transmissionAttempted,
            "status" to value.status, "detail" to value.detail,
            "evidence" to evidence(value.evidence)
        )

    suspend fun inspect(raw: ByteArray, expectedVin: String): Map<String,Any?> {
        require(expectedVin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")))
        require(raw.isNotEmpty() && raw.size.toLong() <= J1979FreezeUsbCapture.MAX_CAPTURE)
        currentCoroutineContext().ensureActive()
        val prefix = J1979FreezeUsbCapture.inspectPrefix(raw,expectedVin)
        val profile = prefix.profile
        val target = J1979LiveTarget(if(profile.extended) 7 else 6,profile.rx,profile.vin)
        val plan = J1979FreezePlan(
            target,profile.frames().toList(),profile.pids().toList(),profile.budgetMs.toLong())
        require(plan.valid)
        val observed = prefix.observations
        var position = 0
        var synthesized = false
        val result = J1979FreezeCampaign.execute(plan,observe={ request ->
            currentCoroutineContext().ensureActive()
            if(position == observed.size) {
                synthesized = true
                J1979FreezeCampaign.Read("CONTEXT_CHANGED",null,0,false)
            } else {
                val saved = observed[position++]
                require(saved.request().contentEquals(request)) {
                    "CF/1 prefix request differs from deterministic Mode 02 recipe"
                }
                J1979FreezeCampaign.Read(saved.outcome,saved.response(),saved.durationMs,saved.attempted)
            }
        },elapsed={observed.sumOf { it.durationMs }})
        require(position == observed.size && result.steps.size >= observed.size) {
            "Unexpected CF/1 prefix observations after recipe stop"
        }
        currentCoroutineContext().ensureActive()
        val profileData = mapOf(
            "request_can_id" to profile.tx.toString(16).uppercase(Locale.ROOT),
            "response_can_id" to profile.rx.toString(16).uppercase(Locale.ROOT),
            "extended" to profile.extended,
            "plan" to mapOf(
                "expected_vin" to profile.vin,
                "frames" to profile.frames().toList(),
                "selected_pids" to profile.pids().toList(),
                "budget_ms" to profile.budgetMs
            )
        )
        return linkedMapOf(
            "schema" to SCHEMA, "protocol" to "SAE-J1979", "source" to "OFFLINE_CF1_PREFIX",
            "profile" to profileData, "expected_vin_independently_supplied" to true,
            "prefix_integrity_verified" to true, "capture_integrity_verified" to false,
            "execution_completed" to false, "terminal_present" to false,
            "physical_vehicle_verified" to false,"hardware_provenance_verified" to false,
            "evidence_authenticated" to false,"permits_programming" to false,
            "complete_records" to prefix.completeRecords,
            "complete_observations" to observed.size,
            "last_complete_offset" to prefix.lastCompleteOffset,
            "discarded_tail_bytes" to prefix.discardedTailBytes,
            "interruption" to prefix.interruption,
            "replay_terminated_by_missing_observation" to synthesized,
            "observed_initial_vin_in_prefix" to result.initialVin,
            "observed_final_vin_in_prefix" to result.finalVin,
            "independently_replayed_steps" to result.steps.take(observed.size).map(::step),
            "visited_frame_numbers_in_prefix" to result.frames.map { it.frame },
            "frame_states_are_provisional" to true,
            "timing_independently_verified" to false,
            "recorded_observation_duration_ms" to observed.sumOf { it.durationMs },
            "authenticated" to false,"diagnostic_verdict" to null
        )
    }
}
