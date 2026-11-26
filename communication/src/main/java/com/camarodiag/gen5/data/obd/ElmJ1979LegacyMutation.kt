package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The selected sources describe observed response scope, not physical
 * addressing. Functional legacy requests may also affect silent ECUs. */
data class LegacyMutationTarget(val protocol: Int, val engineSource: Int,
    val sources: List<Int>, val clear: Boolean) {
    val valid: Boolean get() = protocol in 1..5 && sources.size in 1..32 &&
        sources.distinct().size == sources.size && engineSource in sources &&
        sources.all { it in 1..254 && it != 0xF1 }
}
data class LegacyMutationObservation(val stage: String, val request: String,
    val response: String?, val sources: List<String>, val error: String? = null)
data class LegacyMutationResult(val target: LegacyMutationTarget, val outcome: String,
    val reason: String?, val attempted: Boolean, val observedVin: String?,
    val acknowledgments: Map<String,String>, val before: Map<String,Map<String,List<String>?>>,
    val after: Map<String,Map<String,List<String>?>>, val observations: List<LegacyMutationObservation>)

internal object ElmJ1979LegacyMutation {
    suspend fun run(target: LegacyMutationTarget, expectedVin: String,
        requireCurrent: () -> Unit, read: suspend (String) -> String,
        observedProtocol: suspend () -> Int?, transmit: suspend () -> String,
        saveBaseline: suspend (LegacyMutationResult) -> Unit): LegacyMutationResult {
        require(target.valid && expectedVin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")))
        val expected = target.sources.toSet()
        val keys = expected.sorted().map(ElmLegacyResponseFrames::hex)
        val modes = listOf("03","07","0A")
        fun emptyDtc() = keys.associateWith { modes.associateWith { null as List<String>? }.toMutableMap() }
        val before = emptyDtc()
        val after = emptyDtc()
        val acks = keys.associateWith { "NOT_TESTED" }.toMutableMap()
        val log = mutableListOf<LegacyMutationObservation>()
        var attempted = false
        var completeAck = false
        var vin: String? = null
        fun finish(outcome: String, reason: String? = null) = LegacyMutationResult(target,
            outcome, reason, attempted, vin, acks.toMap(), before.mapValues { it.value.toMap() },
            after.mapValues { it.value.toMap() }, log.toList())
        suspend fun active() {
            currentCoroutineContext().ensureActive()
            requireCurrent()
        }
        suspend fun query(stage: String, command: String): List<ElmLegacyResponseFrames.Frame> {
            active()
            val index = log.size
            log += LegacyMutationObservation(stage, command, null, emptyList())
            try {
                val raw = read(command)
                log[index] = log[index].copy(response = raw)
                val frames = ElmLegacyResponseFrames.decode(target.protocol, command, raw)
                log[index] = log[index].copy(sources = frames.map { ElmLegacyResponseFrames.hex(it.source) }.distinct())
                active()
                require(frames.all { it.source in expected }) { "Unexpected legacy response source." }
                return frames
            } catch (e: Exception) {
                log[index] = log[index].copy(error = e.javaClass.simpleName)
                throw e
            }
        }
        fun rows(frames: List<ElmLegacyResponseFrames.Frame>, source: Int) =
            frames.filter { it.source == source }.map { it.payload }
        suspend fun scope(): Boolean {
            val frames = query("SCOPE","0100")
            return expected.all { s ->
                val record = rows(frames,s).singleOrNull()
                record?.size == 6 && record.take(2) == listOf(0x41,0)
            }
        }
        suspend fun zero(command: String, positive: List<Int>): Boolean {
            val frames = query("STATIONARY",command)
            return rows(frames,target.engineSource) == listOf(positive) &&
                frames.all { it.payload == positive || it.source != target.engineSource &&
                    it.payload.size == 3 && it.payload.take(2) == listOf(0x7F,1) &&
                    it.payload[2] in setOf(0x11,0x12,0x31) }
        }
        suspend fun snapshot(stage: String, dest: Map<String,MutableMap<String,List<String>?>>) {
            for (mode in modes) {
                val frames = query(stage,mode)
                for (source in expected) dest.getValue(ElmLegacyResponseFrames.hex(source))[mode] =
                    ElmLegacyResponseFrames.dtcs(mode.toInt(16), rows(frames,source))
            }
            query(stage,"0101")
        }
        try {
            if (!scope() || observedProtocol() != target.protocol)
                return finish("NOT_TESTED","RESPONDER_OR_PROTOCOL_UNVERIFIED")
            val counts = query("IDENTITY","0901")
            val fragments = query("IDENTITY","0902")
            for (source in expected) {
                val count = rows(counts,source)
                val parts = rows(fragments,source)
                if (source != target.engineSource && count.isEmpty() && parts.isEmpty()) continue
                if (count != listOf(listOf(0x49,1,5)) || parts.size != 5 ||
                    parts.withIndex().any { (i,r) -> r.size != 7 || r.take(3) != listOf(0x49,2,i+1) })
                    return finish("NOT_TESTED","VIN_SEQUENCE_UNVERIFIED")
                val bytes = parts.flatMap { it.drop(3) }
                if (bytes != listOf(0,0,0) + expectedVin.map(Char::code))
                    return finish("NOT_TESTED","VIN_MISMATCH")
                if (source == target.engineSource) vin = expectedVin
            }
            if (vin == null) return finish("NOT_TESTED","ENGINE_VIN_UNVERIFIED")
            val ackSources = expected.toMutableSet()
            if (target.clear) {
                snapshot("BEFORE",before)
                if (before.values.any { it["03"] == null }) return finish("NOT_TESTED","BASELINE_UNVERIFIED")
            } else {
                val frames = query("SUPPORT","08000000000000")
                ackSources.clear()
                for (source in expected) {
                    val parts = rows(frames,source)
                    if (parts.isEmpty()) continue
                    val row = parts.singleOrNull()
                    if (row == null || row.size != 7 || row.take(3) != listOf(0x48,0,0))
                        return finish("NOT_TESTED","CONTROL_SUPPORT_UNVERIFIED")
                    if (row[3] and 0x80 != 0) ackSources += source
                }
                if (ackSources != setOf(target.engineSource)) return finish("NOT_TESTED","EVAP_TARGET_UNVERIFIED")
            }
            if (!scope() || !zero("010D",listOf(0x41,0x0D,0)) || !zero("010C",listOf(0x41,0x0C,0,0)))
                return finish("NOT_TESTED","STATIONARY_OR_SCOPE_UNVERIFIED")
            saveBaseline(finish("BASELINE_SAVED"))
            if (observedProtocol() != target.protocol || !scope() ||
                !zero("010D",listOf(0x41,0x0D,0)) || !zero("010C",listOf(0x41,0x0C,0,0)))
                return finish("NOT_TESTED","STATIONARY_OR_PROTOCOL_CHANGED")
            active()
            val command = if (target.clear) "04" else "08010000000000"
            attempted = true
            val index = log.size
            log += LegacyMutationObservation("MUTATION",command,null,emptyList())
            val raw = transmit()
            log[index] = log[index].copy(response = raw)
            val frames = ElmLegacyResponseFrames.decode(target.protocol,command,raw)
            log[index] = log[index].copy(sources = frames.map { ElmLegacyResponseFrames.hex(it.source) }.distinct())
            val ack = if (target.clear) listOf(0x44) else listOf(0x48,1,0,0,0,0,0)
            val mode = if (target.clear) 4 else 8
            for ((source,records) in frames.groupBy { it.source }) {
                val payload = records.singleOrNull()?.payload
                acks[ElmLegacyResponseFrames.hex(source)] = when {
                    payload == ack -> "ACKNOWLEDGED"
                    payload?.size == 3 && payload.take(2) == listOf(0x7F,mode) &&
                        payload[2] !in setOf(0,0x78) -> "REFUSED_" + ElmLegacyResponseFrames.hex(payload[2])
                    else -> "UNCERTAIN"
                }
            }
            completeAck = frames.all { it.source in ackSources } &&
                ackSources.all { acks[ElmLegacyResponseFrames.hex(it)] == "ACKNOWLEDGED" }
            if (target.clear) {
                snapshot("AFTER",after)
                if (completeAck && after.values.all { it["03"] != null }) {
                    val reason = when {
                        after.values.any { !it["03"].isNullOrEmpty() || !it["07"].isNullOrEmpty() } -> "DTCS_STILL_PRESENT"
                        after.values.any { it["07"] == null } -> "PENDING_READBACK_UNAVAILABLE"
                        else -> null
                    }
                    return finish(if (reason == "PENDING_READBACK_UNAVAILABLE") "ACKNOWLEDGED_PARTIAL"
                        else "ACKNOWLEDGED_READBACK",reason)
                }
            } else if (completeAck) return finish("ACKNOWLEDGED","LEAK_TEST_NOT_PERFORMED")
            return finish(if (completeAck) "ACKNOWLEDGED_PARTIAL" else "UNCERTAIN","MISSING_OR_CONFLICTING_EVIDENCE")
        } catch (e: CancellationException) {
            if (!attempted) throw e
            return finish(if (completeAck) "ACKNOWLEDGED_PARTIAL" else "UNCERTAIN","CANCELLED_AFTER_POSSIBLE_TX")
        } catch (e: Exception) {
            return finish(if (completeAck) "ACKNOWLEDGED_PARTIAL" else if (attempted) "UNCERTAIN" else "NOT_TESTED",
                e.javaClass.simpleName)
        }
    }
}
