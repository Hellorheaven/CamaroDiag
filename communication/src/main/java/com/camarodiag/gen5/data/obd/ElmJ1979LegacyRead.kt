package com.camaradiag.gen5.data.obd

import com.camarodiag.connectors.J1979CampaignSemantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/** One documented response source on a functional emissions network.
 * Filtering responses is not physical addressing or OEM ECU identification. */
data class LegacyReadTarget(val protocol: Int, val source: Int, val expectedVin: String? = null) {
    val valid: Boolean get() = protocol in 1..5 && source in 1..254 && source != 0xF1 &&
        (expectedVin == null || expectedVin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")))
    val sourceHex: String get() = "%02X".format(Locale.ROOT, source)
}

data class LegacyReadFrame(val source: String, val rawHex: String, val payloadHex: String)
data class LegacyReadStep(
    val requestHex: String, val rawResponse: String?, val frames: List<LegacyReadFrame>,
    val acceptedRecords: List<String>, val status: String, val detail: String?, val durationMs: Long
)
data class LegacyReadInventory(
    val mode: Int, val sensorMask: Int?, val state: String, val verifiedPages: Int, val advertisedIds: List<Int>
)
data class LegacyReadDtc(val mode: Int, val codes: List<String>?, val status: String?)
data class LegacyReadMeasurement(val mode: Int, val pid: Int, val name: String, val number: Double, val unit: String)
data class LegacyReadReadiness(val milOn: Boolean, val confirmedDtcCount: Int, val compressionIgnition: Boolean,
    val byteB: Int, val byteC: Int, val byteD: Int, val supportedMonitors: Map<String, Boolean>)
data class LegacyReadIdentification(val infoType: Int, val values: List<String>,
    val extended: ObdJ1979ExtendedVehicleInfo.Result? = null)
data class LegacyReadFreeze(val present: Boolean, val dtc: String?)
data class LegacyReadMonitor(val mode: Int, val testId: Int, val sensorMask: Int?,
    val encodedDataHex: String, val componentAndLimitEncoded: Int? = null,
    val valueEncoded: Int? = null, val limitEncoded: Int? = null)
data class LegacyReadReport(
    val target: LegacyReadTarget, val observedProtocol: Int?, val observedVin: String?,
    val protocolResponse: String?, val completed: Boolean, val stopReason: String?,
    val steps: List<LegacyReadStep>, val inventory: List<LegacyReadInventory>,
    val dtcModes: List<LegacyReadDtc>, val measurements: List<LegacyReadMeasurement>,
    val readiness: LegacyReadReadiness?, val identification: List<LegacyReadIdentification>,
    val freezeTrigger: LegacyReadFreeze?, val monitors: List<LegacyReadMonitor>,
    val otherSources: List<String>, val durationMs: Long,
    val multiSignalRecords: List<ObdJ1979MultiSignal.Record> = emptyList()
) {
    val statusRecords get()=steps.mapNotNull {
        ObdJ1979Status.tryRecord(it.requestHex,it.acceptedRecords.singleOrNull(),it.status)
    }
    val contextualMeasurements get() = ObdJ1979ScalingContext.project(steps.map {
        ObdJ1979ScalingContext.Observation(it.requestHex,it.acceptedRecords.singleOrNull(),it.status)
    })
    val vinMatch: String get() = when {
        target.expectedVin == null -> "NOT_REQUESTED"
        observedVin == null -> "UNVERIFIED"
        observedVin == target.expectedVin -> "MATCH"
        else -> "MISMATCH"
    }
    val wholeVehicleCoverageVerified: Boolean get() = false
    val physicalVehicleVerified: Boolean get() = false
    val notExecuted: List<String> get() = listOf("MODE_04_CLEAR", "MODE_08_CONTROL")
}

internal object ElmJ1979LegacyRead {
    private fun hex(values: List<Int>) = values.joinToString("", transform = ElmLegacyResponseFrames::hex)
    private fun cmd(vararg values: Int) = hex(values.toList())
    private fun word(bytes: List<Int>, offset: Int) = (bytes[offset] shl 8) or bytes[offset+1]

    /** Independent allowlist checked by both campaign and manager, no raw AT passthrough. */
    fun allowsRead(command: String): Boolean {
        if (!command.matches(Regex("(?:[0-9A-F]{2}){1,7}"))) return false
        val data = command.chunked(2).map { it.toInt(16) }
        return when (data.first()) {
            1, 6, 9 -> data.size == 2
            2 -> data.size == 3 && data[2] == 0
            3, 7, 10 -> data.size == 1
            5 -> data.size == 3 && data[2] in setOf(1,2,4,8,16,32,64,128)
            8 -> data.size == 7 && data[1] % 32 == 0 && data.drop(2).all { it == 0 }
            else -> false
        }
    }

    suspend fun run(
        target: LegacyReadTarget,
        exchange: suspend (String, Long) -> String,
        protocolCheck: suspend (Long) -> String,
        requireCurrent: () -> Unit,
        budgetMs: Long = 180_000,
        clock: () -> Long = { System.nanoTime() / 1_000_000 }
    ): LegacyReadReport {
        require(target.valid && budgetMs in 1..600_000)
        val start = clock()
        val steps = mutableListOf<LegacyReadStep>()
        val inventories = mutableListOf<LegacyReadInventory>()
        val dtcs = mutableMapOf<Int, List<String>>()
        val measurements = mutableListOf<LegacyReadMeasurement>()
        val multiSignals = mutableListOf<ObdJ1979MultiSignal.Record>()
        val identification = mutableListOf<LegacyReadIdentification>()
        val monitors = mutableListOf<LegacyReadMonitor>()
        val otherSources = sortedSetOf<Int>()
        var readiness: LegacyReadReadiness? = null
        var freeze: LegacyReadFreeze? = null
        var stop: String? = null
        var vin: String? = null
        var protocol: Int? = null
        var protocolRaw: String? = null
        suspend fun active() {
            currentCoroutineContext().ensureActive()
            requireCurrent()
        }
        fun remaining() = budgetMs - (clock() - start)
        fun finish() = LegacyReadReport(target, protocol, vin, protocolRaw, stop == null, stop,
            steps.toList(), inventories.toList(), listOf(3,7,10).map { mode ->
                LegacyReadDtc(mode, dtcs[mode], steps.singleOrNull { it.requestHex == cmd(mode) }?.status)
            }, measurements.toList(), readiness, identification.toList(), freeze, monitors.toList(),
            otherSources.map(ElmLegacyResponseFrames::hex), (clock()-start).coerceAtLeast(0),multiSignals.toList())

        suspend fun read(command: String, single: Boolean = false,
            validate: (List<Int>) -> Boolean): List<List<Int>> {
            if (stop != null) return emptyList()
            active()
            check(allowsRead(command))
            val left = remaining()
            if (left <= 0) {
                stop = "BUDGET_EXHAUSTED"
                return emptyList()
            }
            val begun = clock()
            var raw: String? = null
            var frames = emptyList<LegacyReadFrame>()
            var accepted = emptyList<List<Int>>()
            var status = "NOT_TESTED"
            var detail: String? = null
            try {
                raw = exchange(command, minOf(left, 4000))
                active()
                if (remaining() <= 0) {
                    stop = "BUDGET_EXHAUSTED"
                    detail = "LATE_RESPONSE"
                } else {
                    val decoded = ElmLegacyResponseFrames.decode(target.protocol, command, raw)
                    frames = decoded.map { LegacyReadFrame(ElmLegacyResponseFrames.hex(it.source),
                        it.rawHex, hex(it.payload)) }
                    otherSources.addAll(decoded.map { it.source }.filter { it != target.source })
                    val selected = decoded.filter { it.source == target.source }.map { it.payload }
                    val mode = command.take(2).toInt(16)
                    val negative = selected.singleOrNull()?.takeIf {
                        it.size == 3 && it.take(2) == listOf(0x7F,mode) && it[2] !in setOf(0,0x78)
                    }
                    when {
                        selected.isEmpty() -> detail = "NO_SELECTED_RESPONSE"
                        negative != null -> detail = "NRC_" + ElmLegacyResponseFrames.hex(negative[2])
                        (single && selected.size != 1) || selected.any { !validate(it) } -> {
                            status = "FAILED"
                            detail = "MIXED_OR_UNMATCHED_RECORDS"
                        }
                        else -> { accepted = selected; status = "PASSED" }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                status = "FAILED"
                detail = "MALFORMED_LEGACY_FRAME"
            } catch (error: Exception) {
                // A transport/context failure must not produce another request.
                stop = "TRANSPORT_OR_CONTEXT_ERROR"
                status = "FAILED"
                detail = error.javaClass.simpleName
            }
            steps += LegacyReadStep(command, raw, frames, accepted.map(::hex), status, detail,
                (clock()-begun).coerceAtLeast(0))
            return accepted
        }

        fun validPage(row: List<Int>, mode: Int, base: Int, offset: Int, filler: Int?) =
            row.size == offset+4 && row.take(2) == listOf(mode+0x40,base) &&
                (filler == null || row[2] == filler) && (base != 0xE0 || row.last() and 1 == 0)

        suspend fun inventory(mode: Int, offset: Int, filler: Int? = null,
            first: List<Int>? = null, command: (Int) -> String): List<Int> {
            val ids = sortedSetOf<Int>()
            var pages = 0
            var complete = false
            for (base in 0..0xE0 step 32) {
                if (stop != null) break
                val row = if (base == 0 && first != null) first else
                    read(command(base), true) { validPage(it,mode,base,offset,filler) }.singleOrNull()
                if (row == null) break
                pages++
                for (bit in 0..30) if (row[offset+bit/8] and (0x80 shr (bit%8)) != 0)
                    ids += base+bit+1
                if (row.last() and 1 == 0) { complete = true; break }
            }
            val state = if (complete) "COMPLETE" else if (pages > 0) "PARTIAL" else "UNAVAILABLE"
            inventories += LegacyReadInventory(mode, filler.takeIf { mode == 5 }, state,pages,ids.toList())
            return ids.toList()
        }

        val initial = read("0100",true) { validPage(it,1,0,2,null) }.singleOrNull()
        if (initial == null) {
            if (stop == null) stop = "INITIAL_PID00_UNVERIFIED"
            return finish()
        }
        try {
            active()
            if (remaining() <= 0) stop = "BUDGET_EXHAUSTED"
            else {
                protocolRaw = protocolCheck(minOf(remaining(),1500))
                active()
                if (remaining() <= 0) stop = "BUDGET_EXHAUSTED"
                else {
                    protocol = ElmLegacyResponseFrames.fixedProtocol(protocolRaw)
                    if (protocol != target.protocol) stop = "PROTOCOL_UNVERIFIED"
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            stop = "PROTOCOL_UNVERIFIED"
        }
        if (stop != null) return finish()

        // Identity first. Never combine fragments from different source addresses.
        val counts = mutableMapOf<Int,Int>()
        for (kind in inventory(9,3,1) { cmd(9,it) }) {
            if (stop != null) break
            if (kind in ObdJ1979ExtendedVehicleInfo.legacyCountTypes) {
                read(cmd(9,kind),true) { it.size == 3 && it.take(2) == listOf(0x49,kind) && it[2] > 0 }
                    .singleOrNull()?.let { counts[kind] = it[2] }
                continue
            }
            val rows = read(cmd(9,kind)) { it.size == 7 && it.take(2) == listOf(0x49,kind) && it[2] > 0 }
            if (rows.isEmpty()) continue
            val count = counts[ObdJ1979ExtendedVehicleInfo.countType(kind)]
            fun mark(status: String, detail: String) {
                steps[steps.lastIndex] = steps.last().copy(status=status,detail=detail)
            }
            if (count == null || rows.size != count || rows.map { it[2] } != (1..count).toList()) {
                mark("FAILED","INCOMPLETE_OR_AMBIGUOUS_SEQUENCE")
                continue
            }
            val bytes = rows.flatMap { it.drop(3) }
            when (kind) {
                2 -> {
                    val candidate = bytes.drop(3).map(Int::toChar).joinToString("")
                    if (count == 5 && bytes.take(3) == listOf(0,0,0) &&
                        candidate.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) {
                        vin = candidate
                        identification += LegacyReadIdentification(kind,listOf(candidate))
                    } else mark("FAILED","INVALID_VIN_RECORD")
                }
                4 -> {
                    val fields = bytes.chunked(16).map { field ->
                        field.dropLastWhile { it == 0 || it == 32 }
                    }
                    if (count % 4 == 0 && fields.all { it.isNotEmpty() && it.all { b -> b in 32..126 } })
                        identification += LegacyReadIdentification(kind,fields.map { it.map(Int::toChar).joinToString("") })
                    else mark("FAILED","INVALID_CALID_RECORD")
                }
                6 -> identification += LegacyReadIdentification(kind,rows.map { hex(it.drop(3)) })
                8,10,11,13,15 -> {
                    val decoded = ObdJ1979ExtendedVehicleInfo.legacy(kind,count,rows)
                    if (decoded == null) mark("FAILED","INVALID_EXTENDED_INFOTYPE")
                    else identification += LegacyReadIdentification(kind,decoded.values,decoded)
                }
                else -> mark("RAW","UNINTERPRETED_INFOTYPE")
            }
        }
        if (stop == null && target.expectedVin != null && vin != target.expectedVin)
            stop = if (vin == null) "VIN_UNVERIFIED" else "VIN_MISMATCH"
        if (stop != null) return finish()

        for (mode in listOf(3,7,10)) {
            val rows = read(cmd(mode)) { it.size == 7 && it[0] == mode+0x40 }
            ElmLegacyResponseFrames.dtcs(mode,rows)?.let { dtcs[mode] = it }
        }

        fun pidRequest(mode: Int,pid: Int) = if (mode == 2) cmd(2,pid,0) else cmd(1,pid)
        fun pidEvidence(request: String,row: List<Int>) =
            J1979CampaignSemantics.decodePositive(request,hex(row))
                ?:J1979CampaignSemantics.decodeObservation(request,hex(row),"RAW")
        suspend fun readPid(mode: Int,pid: Int): List<Int>? {
            val request = pidRequest(mode,pid)
            val row = read(request,true) {
                if(mode==1&&ObdJ1979Status.width(pid)!=null&&
                    ObdJ1979Status.tryRecord(request,hex(it),"RAW")==null)false
                else if(mode==1&&ObdJ1979ScalingContext.isConfiguration(pid))
                    it.take(2)==listOf(0x41,pid)&&ObdJ1979ScalingContext.validConfiguration(pid,it.drop(2))
                else if(mode==1&&ObdJ1979MultiSignal.width(pid)!=null)
                    ObdJ1979MultiSignal.decodeResponse(request,hex(it))!=null
                else pidEvidence(request,it)!=null
            }.singleOrNull() ?: return null
            if(mode==1&&ObdJ1979ScalingContext.isConfiguration(pid)){
                steps[steps.lastIndex]=steps.last().copy(status="RAW",detail="SCALING_CONFIGURATION")
                return row
            }
            if(mode==1&&ObdJ1979MultiSignal.width(pid)!=null){
                val decoded=ObdJ1979MultiSignal.decodeResponse(request,hex(row)) ?: return null
                multiSignals+=ObdJ1979MultiSignal.Record(request,decoded)
                steps[steps.lastIndex]=steps.last().copy(status="RAW",detail="MULTI_SIGNAL_DECODED")
                return row
            }
            val evidence = pidEvidence(request,row) ?: error("Accepted PID shape changed.")
            evidence.measurement()?.let { measurements += LegacyReadMeasurement(it.mode,it.pid,it.name,it.number,it.unit) }
            if (mode == 1 && pid == 1) evidence.readiness()?.let {
                readiness = LegacyReadReadiness(it.milOn,it.confirmedDtcCount,it.compressionIgnition,
                    it.byteB,it.byteC,it.byteD,it.supportedMonitors.toMap())
            }
            if (evidence.status == "RAW")
                steps[steps.lastIndex] = steps.last().copy(status="RAW",detail="UNINTERPRETED_PID")
            return row
        }

        val sensors = sortedSetOf<Int>()
        for (pid in inventory(1,2,first=initial) { cmd(1,it) }) {
            val row = readPid(1,pid)
            if (row != null && pid in setOf(0x13,0x1D)) {
                for (bit in 0..7) if (row[2] and (1 shl bit) != 0) sensors += 1 shl bit
            }
        }
        val trigger = read("020200",true) { it.size == 5 && it.take(3) == listOf(0x42,2,0) }.singleOrNull()
        if (trigger != null) {
            val value = word(trigger,3)
            val dtc = if (value == 0) null else "PCBU"[value ushr 14].toString() +
                ((value ushr 12) and 3) + "%03X".format(Locale.ROOT,value and 0xFFF)
            freeze = LegacyReadFreeze(value != 0,dtc)
        }
        if (freeze?.present == true) {
            for (pid in inventory(2,3,0) { cmd(2,it,0) }.filter { it != 2 }) readPid(2,pid)
        } else inventories += LegacyReadInventory(2,null,
            if (freeze?.present == false) "NOT_APPLICABLE" else "UNAVAILABLE",0,emptyList())
        for (sensor in sensors) {
            for (tid in inventory(5,3,sensor) { cmd(5,it,sensor) }) {
                val rows = read(cmd(5,tid,sensor)) {
                    it.size == (if (tid in 1..4) 4 else 6) && it.take(3) == listOf(0x45,tid,sensor)
                }
                monitors += rows.map { LegacyReadMonitor(5,tid,sensor,hex(it.drop(3))) }
            }
        }
        for (tid in inventory(6,3,0xFF) { cmd(6,it) }) {
            val rows = read(cmd(6,tid)) { it.size == 7 && it.take(2) == listOf(0x46,tid) }
            monitors += rows.map { LegacyReadMonitor(6,tid,null,hex(it.drop(2)),it[2],word(it,3),word(it,5)) }
        }
        inventory(8,3,0) { cmd(8,it,0,0,0,0,0) }
        active()
        if (remaining() <= 0 && stop == null) stop = "BUDGET_EXHAUSTED"
        return finish()
    }
}
