package com.camarodiag.gen5.data.obd

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

/** A single-command OBD transport. Implement with Bluetooth/USB/Wi-Fi ELM or a documented provider. */
fun interface ObdCommandChannel {
    suspend fun command(command: String, timeoutMs: Long): String
}

data class ObdValue(
    val pid: Int,
    val name: String,
    val value: String,
    val unit: String = "",
    val raw: List<Int>,
    val responder: String? = null
) {
    /** A value at the encoded temperature limit is not automatically a
     * sensor failure or valid physical temperature. Retain the evidence. */
    val temperatureAtEncodingLimit: Boolean get() = when (pid) {
        0x05, 0x0F, 0x46, 0x5C -> raw.singleOrNull() in setOf(0, 255)
        else -> false
    }
}

data class ObdDtc(val code: String, val status: String)

/** Null means no verified positive read; it never means zero DTCs. */
data class PhysicalObdDtcSnapshot(
    val confirmed: List<ObdDtc>?,
    val pending: List<ObdDtc>?,
    val permanent: List<ObdDtc>?
) {
    val unavailableModes: Set<Int> get() = buildSet {
        if (confirmed == null) add(3)
        if (pending == null) add(7)
        if (permanent == null) add(10)
    }
    val records: List<ObdDtc> get() = confirmed.orEmpty() + pending.orEmpty() + permanent.orEmpty()
    val modeCounts: Map<Int, Int?> get() = linkedMapOf(3 to confirmed?.size, 7 to pending?.size, 10 to permanent?.size)
}

data class ObdEcuIdentification(
    val vin: String?,
    val calibrationIds: List<String>,
    val calibrationVerificationNumbers: List<String>,
    val ecuNames: List<String>,
    val respondingEcuAddresses: List<String>
)

/** Raw observations are evidence, not necessarily a valid or decoded measurement. */
data class ObdMode01Observation(
    val responder: String?,
    val bytes: List<Int>,
    val eligibleForDecode: Boolean,
    val decodedValueCount: Int
)

data class ObdMode01Read(
    val pid: Int,
    val rawResponse: String,
    val observations: List<ObdMode01Observation>
)

data class ObdFreezeFrameRead(val pid: Int, val rawResponse: String, val verifiedBytes: List<Int>?)

data class ObdVehicleInfoRead(val infoType: Int, val rawResponse: String, val positiveRecords: List<List<Int>>)

data class ObdScanReport(
    val supportedPids: Set<Int>,
    val liveData: List<ObdValue>,
    val confirmedDtcs: List<ObdDtc>,
    val pendingDtcs: List<ObdDtc>,
    val permanentDtcs: List<ObdDtc>,
    val vin: String?,
    val calibrationIds: List<String>,
    val calibrationVerificationNumbers: List<String>,
    val ecuNames: List<String>,
    val readiness: String?,
    val freezeFrame: Map<Int, List<Int>>,
    val monitorTestRaw: String?,
    val adapterInfo: Map<String, String>,
    val respondingEcuAddresses: List<String>,
    val rawLog: List<String>,
    val freezeFrameDtc: ObdDtc? = null,
    val physicalCanResponder: String? = null,
    val unavailableDtcModes: Set<Int> = emptySet(),
    val supportedPidsByResponder: Map<String, Set<Int>> = emptyMap(),
    val firstUnresolvedPid: Int? = null,
    val pidAttributionIncomplete: Boolean = false,
    val mode01Reads: List<ObdMode01Read> = emptyList(),
    val freezeFrameSupportedPids: Set<Int> = emptySet(),
    val freezeFrameReads: List<ObdFreezeFrameRead> = emptyList(),
    val freezeFrameSupportComplete: Boolean? = null,
    val freezeFrameResponder: String? = null,
    val vehicleInfoSupportedTypes: Set<Int> = emptySet(),
    val vehicleInfoSupportComplete: Boolean? = null,
    val vehicleInfoReads: List<ObdVehicleInfoRead> = emptyList(),
    val mode05: ObdMode05Report? = null,
    val mode08: ObdMode08Inventory? = null
)

/**
 * Read-only SAE J1979 scanner.
 *
 * It only sends standardized OBD-II read services and adapter setup commands.
 * It does not invoke UDS programming, security access, actuator control or ECU writes.
 */
class ObdElmFormattingException : IllegalStateException("ELM response formatting could not be restored.")

class Obd2FeatureEngine(
    private val channel: ObdCommandChannel,
    private val physicalElmHeaders: Boolean = false
) {

    private val log = mutableListOf<String>()
    private var freezeFrameSourcePinned = false
    private var freezeFrameSource: String? = null
    private var freezeFrameSupportComplete: Boolean? = null

    /** An entire freeze-frame record belongs to one responding ECU.
     * Do not switch to another responder when a later PID is unavailable. */
    private fun freezeFrameValue(response: String, pid: Int, nonzeroDtc: Boolean = false): List<Int>? {
        val observations = CamaroUdsProtocol.parseFreezeFrameObservations(response, pid)
        val grouped = observations.groupBy { it.responder }
        val valid = grouped.mapNotNull { (source, rows) ->
            val unique = rows.map { it.bytes }.distinct().singleOrNull() ?: return@mapNotNull null
            if (nonzeroDtc && unique.all { it == 0 }) return@mapNotNull null
            source to unique
        }.toMap()
        if (freezeFrameSourcePinned) return valid[freezeFrameSource]
        val physical = valid.keys.filterNotNull().sorted()
        val selected = physical.firstOrNull { it == "7E8" || it == "18DAF110" }
            ?: physical.firstOrNull()
        if (selected == null && !valid.containsKey(null)) return null
        freezeFrameSource = selected
        freezeFrameSourcePinned = true
        return valid[selected]
    }

    /** Actual command/response evidence; callers decide whether to export it. */
    fun rawExchangeLog(): List<String> = log.toList()

    private suspend fun ask(command: String, timeoutMs: Long = 1800L): String {
        val response = channel.command(command, timeoutMs).trim()
        log += "> $command -> $response"
        return response
    }

    private suspend fun formattingCommand(command: String) {
        val lines = ask(command).lineSequence().map { it.trim().removeSuffix(">").trim() }
            .filter { it.isNotEmpty() }.toList()
        if (lines.lastOrNull()?.equals("OK", ignoreCase = true) != true ||
            lines.any { it.uppercase(Locale.ROOT) in setOf("?", "ERROR", "NO DATA", "UNABLE TO CONNECT") }) {
            throw ObdElmFormattingException()
        }
    }

    private suspend fun restorePhysicalHeaders() = withContext(NonCancellable) {
        var failed = false
        for (command in listOf("ATH1", "ATS0")) {
            if (runCatching { formattingCommand(command) }.isFailure) failed = true
        }
        if (failed) throw ObdElmFormattingException()
    }

    /** The ELM itself assembles multi-frame Mode 09/DTC replies in headerless
     * automatic formatting. Restore the normal header-bearing PID channel
     * before releasing the transport's exclusive command session. */
    private suspend fun <T> withHeaderlessRecords(block: suspend () -> T): T {
        if (!physicalElmHeaders) return block()
        return try {
            formattingCommand("ATH0")
            formattingCommand("ATS0")
            block()
        } finally {
            restorePhysicalHeaders()
        }
    }

    /** One operator-selected read. Keep the original response, including
     * line boundaries. Let ELM assemble Mode 09 and DTC records, then
     * restore its header-bearing telemetry format before releasing the lock. */
    suspend fun readConsoleRequest(request: SafeObdRequest): String {
        require(SafeObdRequestPolicy.parse(request.elmCommand) == request)
        return if (request.service == 0x01) ask(request.elmCommand, 4_000L)
        else withHeaderlessRecords { ask(request.elmCommand, 4_000L) }
    }

    suspend fun initialize(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6").forEach {
            result[it] = ask(it, 2500L)
        }
        result.putAll(inspectAdapter())
        return result
    }

    /** Read adapter metadata without resetting its negotiated vehicle protocol. */
    private suspend fun inspectAdapter(): Map<String, String> = linkedMapOf(
        "IDENTIFICATION" to ask("ATI"),
        "DESCRIPTION" to ask("AT@1"),
        "PROTOCOL" to ask("ATDP"),
        "VOLTAGE" to ask("ATRV")
    )

    /**
     * Detects 11-bit CAN response identifiers returned to the standard OBD
     * functional request 0100. These are observed response IDs only; they are
     * deliberately not mapped to GM module names without a verified vehicle profile.
     */
    suspend fun discoverFunctionalObdResponders(): List<String> {
        return try {
            if (physicalElmHeaders) {
                formattingCommand("ATS1")
                formattingCommand("ATH1")
            } else {
                ask("ATS1")
                ask("ATH1")
            }
            val response = ask("0100", 2500L)
            val ids = response.lineSequence().flatMap { line ->
                // Some adapters put several header-tagged CAN replies on one line.
                line.split(Regex("(?i)\\s+(?=(?:7E[0-9A-F]|7DF|18DAF1[0-9A-F]{2})\\s+)" )).asSequence()
            }.mapNotNull { raw ->
                val line = raw.trim().removeSuffix(">").trim().uppercase(Locale.ROOT)
                val spaced = Regex("^(7E[8-F]|18DAF1[0-9A-F]{2})\\s+").find(line)
                val id = spaced?.groupValues?.get(1) ?: when {
                    line.length % 2 == 1 && line.startsWith("7E") -> line.take(3)
                    line.startsWith("18DAF1") -> line.take(8)
                    else -> return@mapNotNull null
                }
                if (!id.matches(Regex("7E[8-F]|18DAF1[0-9A-F]{2}"))) return@mapNotNull null
                val payload = CamaroUdsProtocol.parsePidBytes(line, "00") ?: return@mapNotNull null
                id.takeIf { payload.size == 4 }
            }.distinct().sorted().toList()
            ids
        } finally {
            // Restore normal ELM output framing regardless of the scan result.
            if (physicalElmHeaders) restorePhysicalHeaders()
            else {
                runCatching { ask("ATH0") }
                runCatching { ask("ATS0") }
            }
        }
    }

    /** Reads Mode 01 supported-PID bitmaps (PID 00, 20, ... E0). */
    data class PidDiscovery(val supported: Set<Int>, val firstUnresolvedPid: Int?,
        val byResponder: Map<String, Set<Int>> = emptyMap(),
        val attributionIncomplete: Boolean = false)

    suspend fun discoverSupportedPids(): Set<Int> = discoverPidAvailability().supported

    suspend fun discoverPidAvailability(primaryPowertrainResponderOnly: Boolean = false): PidDiscovery {
        val supported = linkedSetOf<Int>()
        val byResponder = linkedMapOf<String, MutableSet<Int>>()
        var attributionIncomplete = false
        fun result(unresolved: Int?) = PidDiscovery(supported.toSet(), unresolved,
            byResponder.mapValues { it.value.toSet() }, attributionIncomplete)
        var expectedResponders: Set<String?>? = null
        var firstUnresolved: Int? = null
        var basePid = 0x00
        repeat(8) {
            val pidHex = "%02X".format(Locale.US, basePid)
            val response = ask("01$pidHex")
            val grouped = CamaroUdsProtocol.parsePidObservations(response, pidHex,
                primaryPowertrainResponderOnly).filter { it.bytes.size == 4 }
                .groupBy { it.responder }
            val candidates = if (grouped.keys.any { it != null }) grouped.filterKeys { it != null } else grouped
            val valid = candidates.mapNotNull { (address, rows) ->
                val distinct = rows.map { it.bytes }.distinct()
                if (address == null && distinct.size > 1 && !primaryPowertrainResponderOnly) {
                    // Without headers, multiple functional replies cannot be
                    // tied to ECUs. Their OR is only a union of advertised PIDs,
                    // never the support bitmap of a single ECU.
                    attributionIncomplete = true
                    address to List(4) { index -> distinct.fold(0) { mask, bytes -> mask or bytes[index] } }
                } else distinct.singleOrNull()?.let { address to it }
            }.toMap()
            if (candidates.keys.any { it == null }) attributionIncomplete = true
            val expected = expectedResponders
            val accepted = if (expected == null) valid else valid.filterKeys { it in expected }
            if (accepted.isEmpty() || (expected != null && !accepted.keys.containsAll(expected)) ||
                (expected == null && accepted.size != candidates.size)) {
                if (firstUnresolved == null) firstUnresolved = basePid + 1
            }
            if (accepted.isEmpty()) return result(firstUnresolved)
            val next = linkedSetOf<String?>()
            for ((address, bytes) in accepted) {
                if (address != null) byResponder.getOrPut(address) { linkedSetOf() }
                for (bit in 0 until 31) {
                    val mask = 1 shl (7 - (bit % 8))
                    if ((bytes[bit / 8] and mask) != 0) {
                        val pid = basePid + bit + 1
                        supported += pid
                        if (address != null) byResponder.getOrPut(address) { linkedSetOf() } += pid
                    }
                }
                if ((bytes[3] and 0x01) != 0) next += address
            }
            if (next.isEmpty()) return result(firstUnresolved)
            expectedResponders = next
            basePid += 0x20
        }
        return result(firstUnresolved)
    }

    /**
     * Reads one Mode 01 PID only when the ECU has advertised it in its support bitmap.
     * Returns the raw ELM response so vehicle-specific presentation code can retain
     * the original response alongside any decoded value.
     */
    suspend fun readMode01PidIfSupported(pid: Int, supportedPids: Set<Int>): String? {
        require(pid in 0x01..0xFF) { "Mode 01 PID must be a single byte." }
        if (pid !in supportedPids) return null
        return ask("01%02X".format(Locale.US, pid), 1800L)
    }

    suspend fun readLiveData(supportedPids: Set<Int>,
        byResponder: Map<String, Set<Int>> = emptyMap()): List<ObdValue> =
        collectMode01Reads(supportedPids, byResponder).first

    /** Read every advertised data PID once, including those without a local
     * definition. Bitmap page markers are discovery commands, not sensor data.
     * Keep conflicting and anonymous replies as raw evidence, never measurements. */
    private suspend fun collectMode01Reads(
        supportedPids: Set<Int>,
        byResponder: Map<String, Set<Int>>,
        physicalCanResponder: String? = null
    ): Pair<List<ObdValue>, List<ObdMode01Read>> {
        val values = mutableListOf<ObdValue>()
        val reads = mutableListOf<ObdMode01Read>()
        for (pid in supportedPids.filter { it in 1..255 && it % 32 != 0 }.sorted()) {
            currentCoroutineContext().ensureActive()
            val response = ask("01%02X".format(Locale.US, pid), 1200L)
            val observations = CamaroUdsProtocol.parsePidObservations(response, "%02X".format(Locale.ROOT, pid))
            val groups = observations.groupBy { it.responder }
            val hasAddress = groups.keys.any { it != null }
            val evidence = mutableListOf<ObdMode01Observation>()
            for ((responder, rows) in groups) {
                val dataRows = rows.map { it.bytes }.distinct()
                val address = responder ?: physicalCanResponder
                val eligible = dataRows.size == 1 &&
                    !(hasAddress && responder == null) &&
                    !(responder == null && byResponder.isNotEmpty()) &&
                    !(responder != null && byResponder.isNotEmpty() && pid !in byResponder[responder].orEmpty()) &&
                    !(physicalCanResponder != null && responder != null && responder != physicalCanResponder)
                for (data in dataRows) {
                    val decoded = if (eligible) decode(pid, data).map { it.copy(responder = address) }
                        else emptyList()
                    values += decoded
                    evidence += ObdMode01Observation(address, data, eligible, decoded.size)
                }
            }
            reads += ObdMode01Read(pid, response, evidence)
        }
        return values to reads
    }

    suspend fun readDtcs(): Triple<List<ObdDtc>, List<ObdDtc>, List<ObdDtc>> = Triple(
        decodeDtcResponse(ask("03"), 0x43, "confirmed"),
        decodeDtcResponse(ask("07"), 0x47, "pending"),
        decodeDtcResponse(ask("0A"), 0x4A, "permanent")
    )

    /**
     * Verifies that at least one physical Mode 03/07/0A read actually returned
     * a positive SAE J1979 response. Zero codes alone is not evidence of a
     * successful ECU request (ELM "NO DATA" also decodes to an empty list).
     */
    suspend fun readDtcsWithPositiveResponseProof():
        Pair<Triple<List<ObdDtc>, List<ObdDtc>, List<ObdDtc>>, Boolean> {
        val confirmed = ask("03")
        val pending = ask("07")
        val permanent = ask("0A")
        val proved = listOf(
            Triple(confirmed, 0x43, "confirmed"),
            Triple(pending, 0x47, "pending"),
            Triple(permanent, 0x4A, "permanent")
        ).any { (raw, service, status) ->
            decodeDtcResponseOrNull(raw, service, status) != null
        }
        return Triple(
            decodeDtcResponse(confirmed, 0x43, "confirmed"),
            decodeDtcResponse(pending, 0x47, "pending"),
            decodeDtcResponse(permanent, 0x4A, "permanent")
        ) to proved
    }

    /** Per-service observations distinguish a positive zero count from no response. */
    suspend fun readDtcModeEvidence(): Map<Int, List<ObdDtc>?> = withHeaderlessRecords {
        linkedMapOf(
            3 to decodeDtcResponseOrNull(ask("03", 2500L), 0x43, "confirmed"),
            7 to decodeDtcResponseOrNull(ask("07", 2500L), 0x47, "pending"),
            10 to decodeDtcResponseOrNull(ask("0A", 2500L), 0x4A, "permanent")
        )
    }

    /** Reads the DTC that caused freeze-frame storage (Mode 02, PID 02, frame 00). */
    suspend fun readFreezeFrameDtc(): ObdDtc? {
        freezeFrameSourcePinned = false
        freezeFrameSource = null
        freezeFrameSupportComplete = null
        val data = freezeFrameValue(ask("020200"), 0x02, nonzeroDtc = true) ?: return null
        val raw = (data[0] shl 8) or data[1]
        if (raw == 0) return null
        val prefix = "PCBU"[(raw shr 14) and 0x03]
        val digit = (raw shr 12) and 0x03
        val code = "$prefix$digit%X%02X".format(
            Locale.US, (raw shr 8) and 0x0F, raw and 0xFF
        )
        return ObdDtc(code, "freeze-frame")
    }

    /** Discovers Mode 02 PIDs using the independent freeze-frame support bitmap (PID 00). */
    suspend fun discoverFreezeFrameSupportedPids(): Set<Int> {
        val supported = linkedSetOf<Int>()
        var basePid = 0x00
        freezeFrameSupportComplete = false
        repeat(8) {
            currentCoroutineContext().ensureActive()
            val response = ask("02%02X00".format(Locale.US, basePid))
            val bitmap = freezeFrameValue(response, basePid)?.takeIf { it.size == 4 } ?: return supported
            for (bit in 0 until 32) {
                val mask = 1 shl (7 - (bit % 8))
                if ((bitmap[bit / 8] and mask) != 0 && bit != 31) {
                    supported += basePid + bit + 1
                }
            }
            val hasNextPage = (bitmap[3] and 0x01) != 0
            if (!hasNextPage) {
                freezeFrameSupportComplete = true
                return supported
            }
            basePid += 0x20
        }
        return supported
    }

    /**
     * Reads freeze-frame values only for PIDs advertised by the Mode 02 support bitmap.
     * Each Mode 02 response contains the frame number before the actual PID data.
     */
    suspend fun readFreezeFrame(supportedPids: Set<Int>? = null): Map<Int, List<Int>> =
        collectFreezeFrameReads(supportedPids ?: discoverFreezeFrameSupportedPids()).first

    private suspend fun collectFreezeFrameReads(supportedPids: Set<Int>):
        Pair<Map<Int, List<Int>>, List<ObdFreezeFrameRead>> {
        val result = linkedMapOf<Int, List<Int>>()
        val reads = mutableListOf<ObdFreezeFrameRead>()
        // PID 02 has already been read to identify the triggering DTC.
        for (pid in supportedPids.filter { it in 1..255 && it % 32 != 0 && it != 2 }.sorted()) {
            currentCoroutineContext().ensureActive()
            val response = ask("02%02X00".format(Locale.US, pid))
            val data = freezeFrameValue(response, pid)
            reads += ObdFreezeFrameRead(pid, response, data)
            if (data != null) result[pid] = data
        }
        return result to reads
    }

    private suspend fun readVinRecord(timeoutMs: Long): String? =
        decodeAsciiRecords(ask("0902", timeoutMs), 0x49, 0x02, 17).distinct().singleOrNull()
            ?.takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) }

    suspend fun readVin(): String? = withHeaderlessRecords { readVinRecord(3500L) }

    /** A fresh VIN observation with normal ELM formatting and one bounded retry.
     * Never substitutes the saved or previously displayed VIN for a new response.
     */
    suspend fun readVinForVerification(): String? = withHeaderlessRecords {
        if (!physicalElmHeaders) {
            ask("ATH0")
            ask("ATS0")
        }
        readVinRecord(3500L) ?: readVinRecord(5000L)
    }

    // Some functional Mode 09 replies interleave numbered blocks from
    // several ECUs once headers are removed. Never guess their association.
    // A single retry with headers restores the source information.
    private suspend fun readIdentityField(command: String, decode: (String) -> List<String>): List<String> {
        val first = withHeaderlessRecords { decode(ask(command, 3500L)) }
        if (first.isNotEmpty() || !physicalElmHeaders) return first
        // withHeaderlessRecords restored ATH1/ATS0 in its finally block.
        val retry = ask(command, 3500L)
        if (!ObdNumberedIdentityFrames.hasPhysicalHeaders(retry)) return emptyList()
        return decode(retry)
    }

    suspend fun readCalibrationIds(): List<String> =
        readIdentityField("0904") { decodeAsciiRecords(it, 0x49, 0x04, 16) }

    suspend fun readCalibrationVerificationNumbers(): List<String> =
        readIdentityField("0906") { decodeHexRecords(it, 0x49, 0x06, 4) }

    suspend fun readEcuName(): List<String> =
        readIdentityField("090A") { decodeAsciiRecords(it, 0x49, 0x0A, 20) }
    /** Reads standardized OBD identity fields independently; unsupported optional records do not abort the read. */
    suspend fun readEcuIdentification(): ObdEcuIdentification {
        val responders = readOrDefault(emptyList()) { discoverFunctionalObdResponders() }
        val vin = readOrDefault<String?>(null) { readVin() }
        val calibrationIds = readOrDefault(emptyList()) { readCalibrationIds() }
        val cvns = readOrDefault(emptyList()) { readCalibrationVerificationNumbers() }
        val ecuNames = readOrDefault(emptyList()) { readEcuName() }
        return ObdEcuIdentification(vin, calibrationIds, cvns, ecuNames, responders)
    }

    private suspend fun <T> readOrDefault(default: T, read: suspend () -> T): T =
        try {
            read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (formatting: ObdElmFormattingException) {
            throw formatting
        } catch (_: Exception) {
            default
        }

    suspend fun readReadiness(): String? {
        val grouped = CamaroUdsProtocol.parsePidObservations(ask("0101"), "01")
            .filter { it.bytes.size == 4 }.groupBy { it.responder }
        val sources = if (grouped.keys.any { it != null }) grouped.filterKeys { it != null } else grouped
        return sources.flatMap { (responder, rows) ->
            rows.map { it.bytes }.distinct().map { data ->
                val mil = (data[0] and 0x80) != 0
                val dtcCount = data[0] and 0x7F
                val prefix = responder?.let { "CAN $it: " }.orEmpty()
                "${prefix}MIL=${if (mil) "ON" else "OFF"}; DTC_COUNT=$dtcCount; RAW=${data.joinToString(" ") { "%02X".format(Locale.US, it) }}"
            }
        }.joinToString("\n").ifBlank { null }
    }

    /** CAN Mode 06 is a separate namespace from Mode 01 PIDs.
     * Query only MIDs actually advertised by a valid support bitmap.
     * Report encoded words and UASID; do not invent engineering units or pass/fail. */
    suspend fun readMonitorTests(): String? =
        readMonitorTests(20_000L) { System.nanoTime() / 1_000_000L }

    /** A shared request budget, not a new 2.5 s budget for every advertised MID.
     * The channel must honor the supplied timeout; ELM setup/restore is outside
     * this budget and still executes under the exclusive session. */
    internal suspend fun readMonitorTests(
        budgetMs: Long,
        monotonicMs: () -> Long
    ): String? = withHeaderlessRecords {
        require(budgetMs > 0)
        val started = monotonicMs()
        suspend fun request(mid: Int): String? {
            currentCoroutineContext().ensureActive()
            val remaining = budgetMs - (monotonicMs() - started)
            if (remaining <= 0) return null
            val response = ask("06%02X".format(Locale.ROOT, mid), minOf(2500L, remaining))
            currentCoroutineContext().ensureActive()
            return response
        }

        val supported = linkedSetOf<Int>()
        var baseMid = 0
        var discovered = false
        var incomplete = false
        for (pageIndex in 0 until 8) {
            val response = request(baseMid)
            val page = response?.let { ObdCanMonitorRecords.support(it, baseMid) }
            if (page == null) {
                if (discovered) incomplete = true
                break
            }
            discovered = true
            supported += page.mids
            if (!page.more || baseMid == 0xE0) break
            baseMid += 0x20
        }
        if (!discovered) return@withHeaderlessRecords null
        val result = mutableListOf<String>()
        val mids = supported.toList()
        for ((index, mid) in mids.withIndex()) {
            val response = request(mid)
            if (response == null) {
                incomplete = true
                result += "UNQUERIED_MIDS=" + mids.drop(index).joinToString(",") {
                    "%02X".format(Locale.ROOT, it)
                }
                break
            }
            val records = ObdCanMonitorRecords.results(response, mid)
            if (records.isNullOrEmpty()) {
                incomplete = true
                result += "MID=%02X: N/A".format(Locale.ROOT, mid)
            } else result += records.map { it.rawText() }
        }
        // A valid empty support bitmap is evidence, unlike no response.
        if (result.isEmpty()) result += "DISCOVERED_MIDS=0"
        if (incomplete) result += "PARTIAL"
        currentCoroutineContext().ensureActive()
        result.joinToString("\n")
    }

    suspend fun fullReadOnlyScan(): ObdScanReport {
        log.clear()
        // The live adapter is already configured by its transport. Resetting it
        // here would silently switch 29-bit/250 kbit/s/legacy OBD to CAN 11-bit.
        val adapter = inspectAdapter()
        val respondingEcus = discoverFunctionalObdResponders()
        return collectReadOnlyScan(adapter, respondingEcus, readDtcRecords = {
            val modes = readDtcModeEvidence()
            PhysicalObdDtcSnapshot(modes[3], modes[7], modes[10])
        })
    }

    /**
     * Direct ISO 15765-4 physical scan of a single selected responder.
     * No AT commands, ELM initialization or functional discovery is performed.
     * The transport validates CAN framing and supplies its counted DTC decoder.
     */
    suspend fun fullPhysicalCanScan(
        responderCanId: String,
        adapterInfo: Map<String, String>,
        decodeDtcPayload: (Int, String) -> List<ObdDtc>?
    ): ObdScanReport {
        require(responderCanId.matches(Regex("[0-9A-F]{3}|[0-9A-F]{8}")))
        log.clear()
        return collectReadOnlyScan(adapterInfo, listOf(responderCanId),
            physicalCanResponder = responderCanId, readDtcRecords = {
                PhysicalObdDtcSnapshot(
                    decodeDtcPayload(3, ask("03")),
                    decodeDtcPayload(7, ask("07")), decodeDtcPayload(10, ask("0A")))
            })
    }

    private data class VehicleInfoInventory(val supported: Set<Int>, val complete: Boolean,
        val reads: List<ObdVehicleInfoRead>)

    /** Strip only the exact command echo; never discard an unrelated diagnostic record.
     * ELM echo settings are not guaranteed on every supported connector. */
    private fun mode09Records(response: String, type: Int): List<List<Int>> {
        val command = "09%02X".format(Locale.ROOT, type)
        val clean = response.lineSequence()
            .filterNot { it.trim().removeSuffix(">").filterNot(Char::isWhitespace).equals(command, ignoreCase = true) }
            .joinToString("\n")
        return ObdNumberedIdentityFrames.parseAll(clean).orEmpty()
    }

    /** The union is only an inventory, not proof that every ECU supports each type.
     * Preserve complete records separately; no invented units or counter labels. */
    private suspend fun discoverVehicleInfo(): VehicleInfoInventory = withHeaderlessRecords {
        val supported = linkedSetOf<Int>()
        val reads = mutableListOf<ObdVehicleInfoRead>()
        var complete = false
        for (base in 0..0xE0 step 0x20) {
            currentCoroutineContext().ensureActive()
            val response = ask("09%02X".format(Locale.ROOT, base), 2500L)
            val messages = mode09Records(response, base)
            val records = messages.filter { it.size == 6 && it[0] == 0x49 && it[1] == base }
            reads += ObdVehicleInfoRead(base, response, records)
            if (records.isEmpty()) break
            // Keep verified observations, but do not certify completeness when
            // another responder returned a malformed or unrelated record.
            val pageComplete = messages.size == records.size
            for (record in records) {
                for (bit in 0 until 31) {
                    if ((record[2 + bit / 8] and (1 shl (7 - bit % 8))) != 0)
                        supported += base + bit + 1
                }
            }
            if (!pageComplete) break
            if (records.none { (it[5] and 1) != 0 }) {
                complete = true
                break
            }
        }
        // These four identity records are acquired by the existing verified
        // VIN/CAL ID/CVN/ECU-name readers, including their fallback formatting.
        for (type in supported.filterNot { it in setOf(2,4,6,10) }.sorted()) {
            currentCoroutineContext().ensureActive()
            val response = ask("09%02X".format(Locale.ROOT, type), 2500L)
            val records = mode09Records(response, type)
                .filter { it.size > 2 && it[0] == 0x49 && it[1] == type }
            reads += ObdVehicleInfoRead(type, response, records)
        }
        VehicleInfoInventory(supported, complete, reads)
    }

    private suspend fun collectReadOnlyScan(
        adapter: Map<String, String>,
        respondingEcus: List<String>,
        physicalCanResponder: String? = null,
        readDtcRecords: suspend () -> PhysicalObdDtcSnapshot
    ): ObdScanReport {
        val discovery = discoverPidAvailability()
        val supported = discovery.supported
        val (live, mode01Reads) = collectMode01Reads(supported, discovery.byResponder, physicalCanResponder)
        val dtcSnapshot = readDtcRecords()
        val freezeFrameDtc = readFreezeFrameDtc()
        val freezeFrameSupported = if (freezeFrameDtc != null) discoverFreezeFrameSupportedPids() else emptySet()
        val (freeze, freezeReads) = if (freezeFrameDtc != null) collectFreezeFrameReads(freezeFrameSupported)
            else emptyMap<Int, List<Int>>() to emptyList<ObdFreezeFrameRead>()
        val vehicleInfo = discoverVehicleInfo()
        val vin = readVin()
        val calIds = readCalibrationIds()
        val cvns = readCalibrationVerificationNumbers()
        val ecuNames = readEcuName()
        val readiness = readReadiness()
        // Query the negotiated ELM protocol after a vehicle exchange. Do not
        // issue legacy oxygen monitor requests on a CAN or unidentified link.
        val protocolNumber = if (physicalCanResponder == null)
            ObdLegacyOxygenTests.protocolNumber(ask("ATDPN")) else null
        val legacy = protocolNumber != null && protocolNumber in 1..5
        val mode05 = if (protocolNumber != null && legacy) withHeaderlessRecords {
            ObdLegacyOxygenTests.scan(protocolNumber, { command, timeout -> ask(command, timeout) })
        } else null
        // Distinct legacy TID/CID records and CAN MID/TID/UASID records.
        val mode06 = if (legacy) withHeaderlessRecords {
            ObdLegacyOxygenTests.scanMode06({ command, timeout -> ask(command, timeout) })
        } else readMonitorTests()
        val mode08 = if (legacy || physicalCanResponder != null || protocolNumber != null && protocolNumber in 6..9) withHeaderlessRecords {
            ObdMode08Discovery.discover(legacy, physicalCanResponder) { command, timeout -> ask(command, timeout) }
        } else null
        return ObdScanReport(
            supportedPids = supported,
            liveData = live,
            confirmedDtcs = dtcSnapshot.confirmed.orEmpty(),
            pendingDtcs = dtcSnapshot.pending.orEmpty(),
            permanentDtcs = dtcSnapshot.permanent.orEmpty(),
            vin = vin,
            calibrationIds = calIds,
            calibrationVerificationNumbers = cvns,
            ecuNames = ecuNames,
            readiness = readiness,
            freezeFrame = freeze,
            monitorTestRaw = mode06,
            adapterInfo = adapter,
            respondingEcuAddresses = respondingEcus,
            rawLog = log.toList(),
            freezeFrameDtc = freezeFrameDtc,
            physicalCanResponder = physicalCanResponder,
            unavailableDtcModes = dtcSnapshot.unavailableModes,
            supportedPidsByResponder = if (physicalCanResponder != null)
                mapOf(physicalCanResponder to supported) else discovery.byResponder,
            firstUnresolvedPid = discovery.firstUnresolvedPid,
            pidAttributionIncomplete = physicalCanResponder == null && discovery.attributionIncomplete,
            mode01Reads = mode01Reads,
            freezeFrameSupportedPids = freezeFrameSupported,
            freezeFrameReads = freezeReads,
            freezeFrameSupportComplete = freezeFrameSupportComplete,
            freezeFrameResponder = if (freezeFrameSourcePinned) freezeFrameSource ?: physicalCanResponder else null,
            vehicleInfoSupportedTypes = vehicleInfo.supported,
            vehicleInfoSupportComplete = vehicleInfo.complete,
            vehicleInfoReads = vehicleInfo.reads,
            mode05 = mode05,
            mode08 = mode08
        )
    }

    private fun payload(response: String, service: Int, pid: Int): List<Int>? {
        if (service == 0x49 && ':' in response) {
            val bytes = ObdNumberedIdentityFrames.parse(response) ?: return null
            if (bytes.getOrNull(0) != service || bytes.getOrNull(1) != pid) return null
            return bytes.drop(2)
        }
        val bytes = parseResponseBytes(response) ?: return null
        val index = bytes.indices.firstOrNull {
            it + 1 < bytes.size && bytes[it] == service && bytes[it + 1] == pid
        } ?: return null
        return bytes.drop(index + 2)
    }

    /**
     * Handles ELM output with or without spaces and with optional 11-bit CAN IDs.
     * Three-nibble tokens such as 7E8 are treated as CAN headers, not payload bytes.
     */
    private fun parseResponseBytes(response: String): List<Int>? {
        val cleaned = response.uppercase(Locale.ROOT)
            .replace(
                Regex("SEARCHING(?:\\.\\.\\.)?|BUS INIT[^\\r\\n]*|CAN ERROR|BUS ERROR|DATA ERROR|BUFFER FULL|FB ERROR|LP ALERT|NO DATA|STOPPED|UNABLE TO CONNECT|\\bOK\\b|\\bERROR\\b|>"),
                " "
            )
        val tokens = cleaned.split(Regex("[^0-9A-F]+")).filter(String::isNotBlank)
        val bytes = mutableListOf<Int>()
        for (token in tokens) {
            if (token.length == 3) continue
            if (token.length % 2 != 0) continue
            for (pair in token.chunked(2)) {
                val value = pair.toIntOrNull(16) ?: return null
                bytes += value
            }
        }
        return bytes.takeIf { it.isNotEmpty() }
    }

    private fun identityRecords(response: String, service: Int, pid: Int, width: Int): List<List<Int>>? {
        val messages = ObdNumberedIdentityFrames.parseAll(response) ?: return null
        val matching = messages.filter { it.getOrNull(0) == service && it.getOrNull(1) == pid }
        if (matching.isEmpty()) return null
        val result = mutableListOf<List<Int>>()
        for (message in matching) {
            val data = message.drop(2)
            val records = recordData(data, width) ?: return null
            // VIN is one fixed record per responding ECU.
            if (pid == 0x02 && records.size != 1) return null
            result += records
        }
        return result
    }

    private fun decodeAsciiRecords(
        response: String,
        service: Int,
        pid: Int,
        recordWidth: Int
    ): List<String> {
        val records = identityRecords(response, service, pid, recordWidth) ?: return emptyList()
        val decoded = records.map { record ->
            val raw = record.map(Int::toChar).joinToString("")
            val trimmed = raw.trim(' ', '\u0000')
            // Padding is allowed only at either end. Do not silently discard
            // embedded control bytes and turn corrupted ECU IDs into real IDs.
            if (trimmed.isBlank() || trimmed.any { it !in ' '..'~' }) return emptyList()
            trimmed
        }
        return decoded
    }

    private fun decodeHexRecords(
        response: String,
        service: Int,
        pid: Int,
        recordWidth: Int
    ): List<String> {
        val records = identityRecords(response, service, pid, recordWidth) ?: return emptyList()
        return records.map { record -> record.joinToString("") { "%02X".format(Locale.US, it) } }
    }

    /**
     * Mode 09 records begin with the record count followed by fixed-width records.
     * A count-less payload is accepted only when its length is an exact multiple
     * of the known record width (some legacy adapters strip count bytes).
     */
    private fun recordData(data: List<Int>, recordWidth: Int): List<List<Int>>? {
        if (data.isEmpty() || recordWidth <= 0) return null
        val count = data.first()
        if (count in 1..32) {
            // Never truncate an overlong response or reinterpret its declared
            // count byte as a count-less identity record.
            if (data.size != 1 + count * recordWidth) return null
            return data.drop(1).chunked(recordWidth)
        }
        if (data.size % recordWidth == 0) return data.chunked(recordWidth)
        return null
    }

    private fun decodeDtcResponse(response: String, positiveService: Int, status: String): List<ObdDtc> =
        decodeDtcResponseOrNull(response, positiveService, status).orEmpty()

    /**
     * Distinguish counted physical CAN frames from legacy ELM DTC pairs,
     * rejecting ambiguous truncated packets rather than returning fake codes.
     * Non-null also proves that the read was physically acknowledged, even
     * when the ECU returned a valid zero-DTC response.
     */
    private fun decodeDtcResponseOrNull(
        response: String, positiveService: Int, status: String
    ): List<ObdDtc>? {
        val lines = response.lineSequence().map { it.trim().removeSuffix(">").trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }.toList()
        // Physical multi-frame CAN replies retain their responder while
        // reassembling ISO-TP. Never decode a first/consecutive frame alone.
        if (ObdNumberedDtcRecords.hasMultiFrame(response, positiveService)) {
            val records = ObdNumberedDtcRecords.parsePhysical(response, positiveService) ?: return null
            val positive = records.filter { it.firstOrNull() == positiveService }
            if (positive.isEmpty() || positive.any { !ObdNumberedDtcRecords.validPositive(it, positiveService) })
                return null
            return positive.flatMap { decodeSingleDtcPayload(it.drop(1), status).orEmpty() }
                .distinctBy(ObdDtc::code)
        }
        // Numbered ELM blocks must be complete, separately framed and
        // internally consistent. They contain CAN DTC counts, not legacy pairs.
        if (':' in response) {
            val records = ObdNumberedDtcRecords.parse(response) ?: return null
            val positive = records.filter { it.firstOrNull() == positiveService }
            if (positive.isEmpty() || positive.any { !ObdNumberedDtcRecords.validPositive(it, positiveService) })
                return null
            return positive.flatMap { decodeSingleDtcPayload(it.drop(1), status).orEmpty() }
                .distinctBy(ObdDtc::code)
        }
        val decoded = lines.mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            // Decode each physical responder independently. Concatenating
            // several CAN frames can create synthetic fault codes.
            val tagged = Regex("""^(?:7E[8-F]|18DAF1[0-9A-F]{2})(?:\s+|(?=[0-9A-F]{4}))""")
            val compactHeader = when {
                line.matches(Regex("^7E[8-F][0-9A-F]{6,}$")) -> line.substring(3)
                line.matches(Regex("^18DAF1[0-9A-F]{2}[0-9A-F]{6,}$")) -> line.substring(8)
                else -> null
            }
            val dataText = when {
                compactHeader != null -> compactHeader
                tagged.containsMatchIn(line) -> {
                    val body = line.replaceFirst(tagged, "").trim()
                    val tokens = body.split(Regex("""\s+"""))
                    // ELM/STN may emit the CAN DLC as either 4 or 04.
                    // Normalize one-nibble DLC without touching payload bytes.
                    if (tokens.size > 1 && tokens.first().length == 1)
                        "0" + tokens.joinToString("")
                    else tokens.joinToString("")
                }
                else -> line.replace(Regex("""\s+"""), "")
            }
            if (dataText.length % 2 != 0 ||
                dataText.isEmpty() ||
                dataText.any { it !in '0'..'9' && it !in 'A'..'F' }
            ) return@mapNotNull null
            val raw = dataText.chunked(2).map { it.toInt(16) }
            // With a physical CAN header, accept ISO-TP single-frame padding
            // only after the declared PCI length and service have been checked.
            val physical = compactHeader != null || tagged.containsMatchIn(line)
            val frame = if (physical && raw.size >= 3 && raw.first() == raw.size - 1 &&
                raw[1] in 1..7 && raw[2] == positiveService) raw.drop(1) else raw
            val bytes = when {
                physical && frame.size <= 8 && frame.first() in 1..7 &&
                    frame.first() <= frame.size - 1 && frame.getOrNull(1) == positiveService ->
                    frame.drop(1).take(frame.first())
                raw.first() in 1..8 && raw.first() == raw.size - 1 -> raw.drop(1)
                else -> raw
            }
            if (bytes.firstOrNull() != positiveService) return@mapNotNull null
            decodeSingleDtcPayload(bytes.drop(1), status)
        }.toList()
        val complete = decoded
        if (complete.isEmpty()) return null
        return complete.flatten().distinctBy(ObdDtc::code)
    }

    private fun decodeSingleDtcPayload(payload: List<Int>, status: String): List<ObdDtc>? {
        if (payload.isEmpty()) return null
        val count = payload.first()
        val data = when {
            count == 0 && payload.size == 1 -> emptyList()
            count > 0 && payload.size == 1 + 2 * count -> {
                val pairs = payload.drop(1)
                if (pairs.chunked(2).any { it.all { byte -> byte == 0 } }) return null
                pairs
            }
            payload.size == 2 && payload.all { it == 0 } -> payload
            // Legacy ELM pairs often include a trailing 0000 DTC slot.
            // A single ambiguous 2-byte pair could be a truncated count.
            payload.size >= 4 && payload.size % 2 == 0 -> payload
            else -> return null
        }
        val records = data.chunked(2)
        if (records.any { it.size != 2 }) return null
        return records.mapNotNull { pair ->
            val raw = (pair[0] shl 8) or pair[1]
            if (raw == 0) null else {
                val prefix = "PCBU"[(raw shr 14) and 0x03]
                val digit = (raw shr 12) and 0x03
                val code = "$prefix$digit%X%02X".format(Locale.US, (raw shr 8) and 0x0F, raw and 0xFF)
                ObdDtc(code, status)
            }
        }
    }

    private fun decode(pid: Int, data: List<Int>): List<ObdValue> {
        val a = data.getOrNull(0) ?: return emptyList()
        val b = data.getOrNull(1) ?: 0
        fun word(): Int? = if (data.size >= 2) (a shl 8) or b else null
        fun percent(value: Int) = value * 100.0 / 255.0
        fun value(name: String, number: Double, unit: String) = listOf(
            ObdValue(
                pid = pid,
                name = name,
                value = String.format(Locale.US, "%.2f", number),
                unit = unit,
                raw = data
            )
        )
        fun raw(name: String = pidName(pid)) = listOf(
            ObdValue(
                pid = pid,
                name = name,
                value = data.joinToString(" ") { "%02X".format(Locale.US, it) },
                unit = "raw",
                raw = data
            )
        )

        if (pid in 0x24..0x2B || pid in 0x34..0x3B) {
            if (data.size != 4) return emptyList()
            // Camaro uses a two-bank layout; the 3/4-bank PID-to-sensor
            // arrangement is a different SAE table and needs explicit detection.
            val currentFamily = pid in 0x34..0x3B
            val index = pid - (if (currentFamily) 0x34 else 0x24)
            val bank = if (index < 4) 1 else 2
            val sensor = index % 4 + 1
            val sensorLabel = "Bank $bank sensor $sensor"
            val four = data.toIntArray()
            val ratio = CamaroUdsProtocol.decodeWidebandLambda(four) ?: return emptyList()
            val secondary = if (currentFamily)
                CamaroUdsProtocol.decodeWidebandCurrent(four)
            else CamaroUdsProtocol.decodeWidebandVoltage(four)
            val reading = secondary ?: return emptyList()
            val secondaryName = if (currentFamily) "current" else "voltage"
            return value("O2 $sensorLabel equivalence ratio", ratio, "lambda") +
                value("O2 $sensorLabel $secondaryName",
                    reading, if (currentFamily) "mA" else "V")
        }

        val decoded: Pair<String, Pair<Double, String>> = when (pid) {
            0x01 -> return emptyList() // Monitor status is displayed in the dedicated readiness card.
            0x03 -> return raw("Fuel system status")
            0x04 -> "Calculated engine load" to (percent(a) to "%")
            0x05 -> "Coolant temperature" to ((a - 40).toDouble() to "°C")
            0x06 -> "Short-term fuel trim bank 1" to ((a * 100.0 / 128.0 - 100.0) to "%")
            0x07 -> "Long-term fuel trim bank 1" to ((a * 100.0 / 128.0 - 100.0) to "%")
            0x08 -> "Short-term fuel trim bank 2" to ((a * 100.0 / 128.0 - 100.0) to "%")
            0x09 -> "Long-term fuel trim bank 2" to ((a * 100.0 / 128.0 - 100.0) to "%")
            0x0A -> "Fuel pressure" to ((a * 3.0) to "kPa")
            0x0B -> "Intake manifold pressure" to (a.toDouble() to "kPa")
            0x0C -> "Engine speed" to ((word() ?: return emptyList()) / 4.0 to "rpm")
            0x0D -> "Vehicle speed" to (a.toDouble() to "km/h")
            0x0E -> "Timing advance" to ((a / 2.0 - 64.0) to "°")
            0x0F -> "Intake air temperature" to ((a - 40).toDouble() to "°C")
            0x10 -> "Mass air flow" to ((word() ?: return emptyList()) / 100.0 to "g/s")
            0x11 -> "Throttle position" to (percent(a) to "%")
            0x12, 0x13, 0x1C, 0x51 -> return raw()
            0x1F -> "Run time since engine start" to ((word() ?: return emptyList()).toDouble() to "s")
            0x21 -> "Distance with MIL on" to ((word() ?: return emptyList()).toDouble() to "km")
            0x22 -> "Fuel rail pressure relative to manifold vacuum" to ((word() ?: return emptyList()) * 0.079 to "kPa")
            0x23 -> "Fuel rail pressure" to ((word() ?: return emptyList()) * 10.0 to "kPa")
            0x2C -> "Commanded EGR" to (percent(a) to "%")
            0x2D -> "EGR error" to ((a * 100.0 / 128.0 - 100.0) to "%")
            0x2E -> "Commanded evaporative purge" to (percent(a) to "%")
            0x2F -> "Fuel tank level" to (percent(a) to "%")
            0x30 -> "Warm-ups since DTC clear" to (a.toDouble() to "count")
            0x31 -> "Distance since DTC clear" to ((word() ?: return emptyList()).toDouble() to "km")
            0x32 -> "Evaporative system vapour pressure" to (((word() ?: return emptyList()) - 32768) / 4.0 to "Pa")
            0x33 -> "Barometric pressure" to (a.toDouble() to "kPa")
            0x3C, 0x3D, 0x3E, 0x3F -> {
                val sensor = when (pid) {
                    0x3C -> "Bank 1 sensor 1"
                    0x3D -> "Bank 2 sensor 1"
                    0x3E -> "Bank 1 sensor 2"
                    else -> "Bank 2 sensor 2"
                }
                "$sensor catalyst temperature" to (((word() ?: return emptyList()) / 10.0 - 40.0) to "°C")
            }
            0x42 -> "Control module voltage" to ((word() ?: return emptyList()) / 1000.0 to "V")
            0x43 -> "Absolute load" to ((word() ?: return emptyList()) * 100.0 / 255.0 to "%")
            0x44 -> "Commanded equivalence ratio" to ((word() ?: return emptyList()) / 32768.0 to "lambda")
            0x45 -> "Relative throttle position" to (percent(a) to "%")
            0x46 -> "Ambient air temperature" to ((a - 40).toDouble() to "°C")
            0x47 -> "Absolute throttle position B" to (percent(a) to "%")
            0x48 -> "Absolute throttle position C" to (percent(a) to "%")
            0x49 -> "Accelerator pedal position D" to (percent(a) to "%")
            0x4A -> "Accelerator pedal position E" to (percent(a) to "%")
            0x4B -> "Accelerator pedal position F" to (percent(a) to "%")
            0x4C -> "Commanded throttle actuator" to (percent(a) to "%")
            0x4D -> "Time with MIL on" to ((word() ?: return emptyList()).toDouble() to "min")
            0x4E -> "Time since DTC clear" to ((word() ?: return emptyList()).toDouble() to "min")
            0x52 -> {
                if (data.size != 1) return emptyList()
                "Ethanol fuel percentage" to (percent(a) to "%")
            }
            0x59 -> "Fuel rail absolute pressure" to ((word() ?: return emptyList()) * 10.0 to "kPa")
            0x5A -> "Relative accelerator pedal position" to (percent(a) to "%")
            0x5B -> "Reported traction battery remaining" to (percent(a) to "%")
            0x5C -> "Engine oil temperature" to ((a - 40).toDouble() to "°C")
            0x5D -> "Fuel injection timing" to (((word() ?: return emptyList()) / 128.0) - 210.0 to "°")
            0x5E -> "Engine fuel rate" to ((word() ?: return emptyList()) / 20.0 to "L/h")
            0x5F -> return raw("Emission requirements to which vehicle is designed")
            0x61 -> "Driver demand engine torque" to ((a - 125).toDouble() to "%")
            0x62 -> "Actual engine torque" to ((a - 125).toDouble() to "%")
            0x63 -> "Engine reference torque" to ((word() ?: return emptyList()).toDouble() to "Nm")
            0xA6 -> {
                if (data.size != 4) return emptyList()
                "Odometer" to (data.fold(0L) { acc, byte -> (acc shl 8) or byte.toLong() } / 10.0 to "km")
            }
            else -> return emptyList()
        }
        return value(decoded.first, decoded.second.first, decoded.second.second)
    }

    private fun pidName(pid: Int): String = when (pid) {
        0x12 -> "Secondary air status"
        0x13 -> "Oxygen sensors present"
        0x1C -> "OBD standards compliance"
        else -> "PID %02X".format(Locale.US, pid)
    }
}
