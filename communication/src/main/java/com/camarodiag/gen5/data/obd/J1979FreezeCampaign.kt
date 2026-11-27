package com.camarodiag.gen5.data.obd

import com.camarodiag.connectors.J1979CampaignSemantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/** Explicit physical Mode 02 acquisition. Additional frame numbers are never
 * enumerated and their manufacturer-specific data remain raw. */
data class J1979FreezePlan(
    val target: J1979LiveTarget,
    val frames: List<Int>,
    val selectedPids: List<Int> = emptyList(),
    val budgetMs: Long = 180000
) {
    val valid: Boolean get() = target.valid &&
        frames.size in 1..8 && frames.distinct().size == frames.size &&
        frames.all { it in 0..255 } && selectedPids.size <= 64 &&
        selectedPids.distinct().size == selectedPids.size &&
        selectedPids.all { it in 1..255 && it != 2 && it % 32 != 0 } &&
        budgetMs in 1000..600000

    fun pinned(): J1979FreezePlan =
        copy(frames = frames.toList(), selectedPids = selectedPids.toList()).also { require(it.valid) }
}

data class J1979FreezePage(
    val base: Int, val bitmapHex: String, val advertisedPids: List<Int>, val hasNextPage: Boolean
)

data class J1979FreezeMeasurement(
    val mode: Int, val pid: Int, val name: String, val number: Double, val unit: String, val frame: Int
)

data class J1979FreezeEvidence(
    val vin: String? = null,
    val frame: Int? = null,
    val pid: Int? = null,
    val dataHex: String? = null,
    val page: J1979FreezePage? = null,
    val triggerPresent: Boolean? = null,
    val triggerDtc: String? = null,
    val measurement: J1979FreezeMeasurement? = null
)

data class J1979FreezeStep(
    val requestHex: String, val responseHex: String?, val transportOutcome: String,
    val durationMs: Long, val transmissionAttempted: Boolean, val status: String,
    val detail: String?, val evidence: J1979FreezeEvidence?,
    val rawAdapterResponse: String? = null
)

data class J1979FreezeFrame(
    val frame: Int, val requiredFrame: Boolean, val status: String, val triggerDtc: String?,
    val finalTriggerDtc: String?, val triggerStable: Boolean, val inventoryComplete: Boolean,
    val verifiedPages: List<J1979FreezePage>, val advertisedPids: List<Int>,
    val readPids: List<Int>, val unadvertisedSelectedPids: List<Int>, val notReadSelectedPids: List<Int>,
    val measurementSteps: List<Int>, val detail: String?
)

data class J1979FreezeResult(
    val plan: J1979FreezePlan, val initialVin: String?, val finalVin: String?,
    val completed: Boolean, val stopReason: String?, val identityRechecked: Boolean,
    val frames: List<J1979FreezeFrame>, val steps: List<J1979FreezeStep>,
    val unvisitedFrames: List<Int>, val allFramesStable: Boolean, val durationMs: Long
)

data class J1979FreezeReport(
    val result: J1979FreezeResult,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val rawProtocolResponse: String?,
    val observedProtocol: Int?,
    val transportProblem: String? = null,
    val legacyExchanges: List<LegacyLiveExchange> = emptyList()
) {
    val executionCompleted: Boolean get() = result.completed && transportProblem == null &&
        observedProtocol == result.plan.target.protocol
}

internal object J1979FreezeCampaign {
    const val MAX_RESPONSE = 4095
    const val MAX_RAW = 256 * 1024
    private const val MAX_STEPS = 8 * (8 + 2 + 247) + 2

    data class Read(
        val outcome: String, val response: ByteArray?, val durationMs: Long,
        val transmissionAttempted: Boolean, val raw: String? = null
    )

    internal class AdapterReplyError(val raw: String,cause: Throwable) : IllegalStateException("Invalid adapter reply",cause)

    private val outcomes = setOf("RECEIVED","TIMEOUT","TRANSPORT_ERROR","BUDGET",
        "CANCELLED","CONTEXT_CHANGED","LATE_RESPONSE")
    private val scalarPids = setOf(
        0x04,0x05,0x0a,0x0c,0x0d,0x0e,0x0f,0x11,0x1f,0x21,0x22,
        0x23,0x2c,0x2d,0x2e,0x2f,0x30,0x31,0x32,0x33,0x3c,0x3d,
        0x3e,0x3f,0x42,0x43,0x45,0x46,0x47,0x48,0x49,0x4a,0x4b,
        0x4c,0x4d,0x4e,0x52,0x53,0x54,0x59,0x5a,0x5b,0x5c,
        0x5d,0x5e,0x61,0x62,0x63,0xa6
    )
    private fun u(value: Byte) = value.toInt() and 255
    private fun hex(data: ByteArray) = ElmJ1979EvapTest.hex(data)
    private fun hex(value: Int) = "%02X".format(Locale.ROOT,value)

    fun allowed(request: ByteArray, frames: List<Int>): Boolean =
        request.contentEquals(byteArrayOf(9,2)) ||
            request.size == 3 && u(request[0]) == 2 && u(request[2]) in frames

    fun fixedProtocol(raw: String): Int? {
        if (raw.length > MAX_RAW) return null
        return raw.replace('>','\n').lineSequence().map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && it != "ATDPN" }.toList().singleOrNull()
            ?.takeIf { it.matches(Regex("[6-9]")) }?.toInt()
    }

    private fun vin(data: ByteArray): String? {
        if (data.size != 20 || !data.copyOfRange(0,3).contentEquals(byteArrayOf(0x49,2,1))) return null
        val value = data.copyOfRange(3,20).toString(Charsets.US_ASCII)
        return value.takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) }
    }

    private fun dtc(a: Int,b: Int): String? = if (a == 0 && b == 0) null else
        "PCBU"[a shr 6].toString() + ((a shr 4) and 3).toString(16).uppercase(Locale.ROOT) +
            (a and 15).toString(16).uppercase(Locale.ROOT) + hex(b)

    private fun page(base: Int,data: ByteArray): J1979FreezePage? {
        if (data.size != 4 || base == 0xe0 && u(data[3]) and 1 != 0) return null
        val ids = (0 until 31).filter { bit -> u(data[bit / 8]) and (0x80 shr (bit % 8)) != 0 }
            .map { base + it + 1 }
        return J1979FreezePage(base,hex(data),ids,u(data[3]) and 1 != 0)
    }

    internal fun classify(request: ByteArray, read: Read): J1979FreezeStep {
        require(read.outcome in outcomes && read.durationMs in 0..3600000 &&
            (read.response?.size ?: 0) <= MAX_RESPONSE && (read.raw?.length ?: 0) <= MAX_RAW)
        require((read.outcome in setOf("RECEIVED","LATE_RESPONSE")) == (read.response != null))
        require(read.response == null || read.response.isNotEmpty())
        require(read.outcome !in setOf("RECEIVED","LATE_RESPONSE","TIMEOUT","TRANSPORT_ERROR") ||
            read.transmissionAttempted)
        require(read.outcome != "BUDGET" || !read.transmissionAttempted)
        fun row(status: String, detail: String? = null, evidence: J1979FreezeEvidence? = null) =
            J1979FreezeStep(hex(request),read.response?.let(::hex),read.outcome,read.durationMs,
                read.transmissionAttempted,status,detail,evidence,read.raw)
        if (read.outcome != "RECEIVED")
            return row(if (read.outcome == "TRANSPORT_ERROR") "FAILED" else "NOT_TESTED",read.outcome)
        val response = requireNotNull(read.response)
        if (response.size == 3 && u(response[0]) == 0x7f && response[1] == request[0] &&
            u(response[2]) !in setOf(0,0x78)) return row("NOT_TESTED","NRC_" + hex(u(response[2])))
        if (request.contentEquals(byteArrayOf(9,2))) {
            val observed = vin(response) ?: return row("FAILED","INVALID_VIN")
            return row("PASSED",evidence = J1979FreezeEvidence(vin=observed))
        }
        if (request.size != 3 || u(request[0]) != 2 || response.size <= 3 ||
            u(response[0]) != 0x42 || response[1] != request[1] || response[2] != request[2])
            return row("FAILED","WRONG_SERVICE_PID_OR_FRAME")
        val pid = u(request[1])
        val frame = u(request[2])
        val data = response.copyOfRange(3,response.size)
        val evidence = J1979FreezeEvidence(frame=frame,pid=pid,dataHex=hex(data))
        if (pid % 32 == 0) {
            val bitmap = page(pid,data) ?: return row("FAILED","INVALID_SUPPORT_BITMAP")
            return row("PASSED",evidence=evidence.copy(page=bitmap))
        }
        if (pid == 2) {
            if (data.size != 2) return row("FAILED","INVALID_TRIGGER_DTC")
            return row("PASSED",evidence=evidence.copy(triggerPresent=u(data[0]) != 0 || u(data[1]) != 0,
                triggerDtc=dtc(u(data[0]),u(data[1]))))
        }
        if (frame != 0) return row("RAW","STORED_RAW_VALUE",evidence)
        val decoded = J1979CampaignSemantics.decodePositive(hex(request),hex(response))
            ?: J1979CampaignSemantics.decodeObservation(hex(request),hex(response),"RAW")
            ?: return row("FAILED","INVALID_PID_DATA_WIDTH")
        val measurement = decoded.measurement()
        return if (pid in scalarPids && measurement != null)
            row("PASSED",evidence=evidence.copy(measurement=J1979FreezeMeasurement(
                2,pid,measurement.name,measurement.number,measurement.unit,frame)))
        else row("RAW","STORED_RAW_VALUE",evidence)
    }

    /** The same deterministic recipe is independently replayed by the host. */
    internal suspend fun execute(requested: J1979FreezePlan,
        observe: suspend (ByteArray) -> Read, elapsed: () -> Long = { 0 }): J1979FreezeResult {
        val plan = requested.pinned()
        val steps = mutableListOf<J1979FreezeStep>()
        val frames = mutableListOf<J1979FreezeFrame>()
        var stop: String? = null
        var finalVin: String? = null

        suspend fun read(request: ByteArray): J1979FreezeStep? {
            if (stop != null) return null
            check(allowed(request,plan.frames) && steps.size < MAX_STEPS)
            val row = classify(request,observe(request))
            steps += row
            when (row.transportOutcome) {
                "BUDGET","LATE_RESPONSE" -> stop = "BUDGET_EXHAUSTED"
                "TRANSPORT_ERROR","CANCELLED","CONTEXT_CHANGED" -> stop = row.transportOutcome
            }
            return row
        }

        fun evidence(step: J1979FreezeStep?): J1979FreezeEvidence? =
            step?.takeIf { it.status == "PASSED" }?.evidence

        suspend fun acquire(frame: Int): J1979FreezeFrame {
            var triggerDtc: String? = null
            var finalTrigger: String? = null
            var stable = false
            var complete = false
            val ids = sortedSetOf<Int>()
            val pages = mutableListOf<J1979FreezePage>()
            val readPids = mutableListOf<Int>()
            val missing = sortedSetOf<Int>()
            val notRead = sortedSetOf<Int>()
            val indexes = mutableListOf<Int>()
            val selected = plan.selectedPids.toSet()
            fun finish(status: String,detail: String? = null) = J1979FreezeFrame(
                frame,frame==0,status,triggerDtc,finalTrigger,stable,complete,pages.toList(),
                ids.toList(),readPids.toList(),missing.toList(),notRead.toList(),indexes.toList(),detail)
            suspend fun readPage(base: Int): J1979FreezePage? {
                val found = evidence(read(byteArrayOf(2,base.toByte(),frame.toByte())))?.page ?: return null
                pages += found
                ids += found.advertisedPids
                return found
            }

            val initial = readPage(0) ?: return finish("UNAVAILABLE","PID00_UNVERIFIED")
            if (2 !in ids) return finish("UNAVAILABLE","TRIGGER_NOT_ADVERTISED")
            val trigger = evidence(read(byteArrayOf(2,2,frame.toByte())))
                ?: return finish("UNAVAILABLE","TRIGGER_UNVERIFIED")
            triggerDtc = trigger.triggerDtc
            if (trigger.triggerPresent == false) return finish("ABSENT","ZERO_TRIGGER_DTC")
            var continuation = initial.hasNextPage
            complete = !continuation
            for (base in 32..224 step 32) {
                if (!continuation || stop != null) break
                val next = readPage(base) ?: break
                continuation = next.hasNextPage
                complete = !continuation
            }
            val candidates = ids.filter { if (selected.isEmpty()) it != 2 else it in selected }
            if (complete) missing += selected - ids else notRead += selected - ids
            var failures = 0
            for (pid in candidates) {
                val row = read(byteArrayOf(2,pid.toByte(),frame.toByte())) ?: break
                if (row.transmissionAttempted) readPids += pid
                indexes += steps.lastIndex
                if (row.status !in setOf("PASSED","RAW")) failures++
            }
            notRead += selected.intersect(ids) - readPids.toSet()
            if (stop != null) return finish("PARTIAL",stop)
            val final = evidence(read(byteArrayOf(2,2,frame.toByte())))
            finalTrigger = final?.triggerDtc
            stable = final != null && finalTrigger == triggerDtc
            if (!stable) return if (final == null) finish("PARTIAL","FINAL_TRIGGER_UNVERIFIED")
                else finish("CHANGED","TRIGGER_CHANGED")
            return if (!complete || failures > 0 || readPids.size != candidates.size)
                finish("PARTIAL","INCOMPLETE_INVENTORY_OR_VALUES") else finish("COMPLETE")
        }

        val firstVin = evidence(read(byteArrayOf(9,2)))?.vin
        if (stop == null && firstVin != plan.target.vin)
            stop = if (firstVin == null) "VIN_UNVERIFIED" else "VIN_MISMATCH"
        for (frame in plan.frames) {
            if (stop != null) break
            frames += acquire(frame)
        }
        if (stop == null) {
            finalVin = evidence(read(byteArrayOf(9,2)))?.vin
            if (stop == null && finalVin != firstVin)
                stop = if (finalVin == null) "FINAL_VIN_UNVERIFIED" else "FINAL_VIN_MISMATCH"
        }
        val completed = stop == null && frames.size == plan.frames.size
        return J1979FreezeResult(plan,firstVin,finalVin,completed,stop,
            completed && firstVin == finalVin && firstVin == plan.target.vin,frames.toList(),
            steps.toList(),plan.frames.drop(frames.size),
            completed && frames.all { it.status in setOf("COMPLETE","ABSENT") },elapsed())
    }

    suspend fun run(requested: J1979FreezePlan,
        exchange: suspend (ByteArray,Long) -> J1979LiveRecorder.Reply,
        guard: () -> Unit, clock: () -> Long = { System.nanoTime()/1000000 },
        requestTimeout: (ByteArray)->Long = { 2500L }): J1979FreezeResult {
        val plan = requested.pinned()
        val started = clock()
        fun elapsed() = (clock()-started).coerceAtLeast(0)
        return execute(plan,observe={ request ->
            val begin = elapsed()
            var attempted = false
            var raw: String? = null
            fun observed(outcome: String,response: ByteArray? = null) =
                Read(outcome,response,(elapsed()-begin).coerceAtLeast(0),attempted,raw)
            try {
                currentCoroutineContext().ensureActive()
                guard()
                val left = plan.budgetMs - begin
                if (left <= 0) observed("BUDGET") else {
                    attempted = true
                    val maximum=requestTimeout(request)
                    require(maximum in 1..20000)
                    val reply = exchange(request,minOf(maximum,left))
                    raw = reply.raw?.take(MAX_RAW)
                    guard()
                    currentCoroutineContext().ensureActive()
                    val response = reply.payload
                    when {
                        (reply.raw?.length ?: 0) > MAX_RAW || (response?.size ?: 0) > MAX_RESPONSE || response?.isEmpty() == true ->
                            observed("TRANSPORT_ERROR")
                        response == null -> observed("TIMEOUT")
                        elapsed() >= plan.budgetMs -> observed("LATE_RESPONSE",response)
                        else -> observed("RECEIVED",response)
                    }
                }
            } catch (error: AdapterReplyError) {
                raw = error.raw.take(MAX_RAW)
                observed("TRANSPORT_ERROR")
            } catch (_: CancellationException) { observed("CANCELLED") }
            catch (_: java.util.concurrent.TimeoutException) {
                observed(if (attempted) "TIMEOUT" else "CONTEXT_CHANGED")
            } catch (_: Exception) {
                observed(if (attempted) "TRANSPORT_ERROR" else "CONTEXT_CHANGED")
            }
        },elapsed=::elapsed)
    }
}
