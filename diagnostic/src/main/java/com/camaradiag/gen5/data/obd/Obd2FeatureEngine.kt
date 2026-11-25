package com.camarodiag.gen5.data.obd

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A single-command OBD transport. Implement with Bluetooth/USB/Wi-Fi ELM or a documented provider. */
fun interface ObdCommandChannel {
    suspend fun command(command: String, timeoutMs: Long): String
}

data class ObdValue(
    val pid: Int,
    val name: String,
    val value: String,
    val unit: String = "",
    val raw: List<Int>
)

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
    val unavailableDtcModes: Set<Int> = emptySet()
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
    data class PidDiscovery(val supported: Set<Int>, val firstUnresolvedPid: Int?)

    suspend fun discoverSupportedPids(): Set<Int> = discoverPidAvailability().supported

    suspend fun discoverPidAvailability(primaryPowertrainResponderOnly: Boolean = false): PidDiscovery {
        val supported = linkedSetOf<Int>()
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
                rows.map { it.bytes }.distinct().singleOrNull()?.let { address to it }
            }.toMap()
            val expected = expectedResponders
            val accepted = if (expected == null) valid else valid.filterKeys { it in expected }
            if (accepted.isEmpty() || (expected != null && !accepted.keys.containsAll(expected)) ||
                (expected == null && accepted.size != candidates.size)) {
                if (firstUnresolved == null) firstUnresolved = basePid + 1
            }
            if (accepted.isEmpty()) return PidDiscovery(supported, firstUnresolved)
            val next = linkedSetOf<String?>()
            for ((address, bytes) in accepted) {
                for (bit in 0 until 31) {
                    val mask = 1 shl (7 - (bit % 8))
                    if ((bytes[bit / 8] and mask) != 0) supported += basePid + bit + 1
                }
                if ((bytes[3] and 0x01) != 0) next += address
            }
            if (next.isEmpty()) return PidDiscovery(supported, firstUnresolved)
            expectedResponders = next
            basePid += 0x20
        }
        return PidDiscovery(supported, firstUnresolved)
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

    suspend fun readLiveData(supportedPids: Set<Int>): List<ObdValue> {
        val requested = listOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
            0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x1C, 0x1F,
            0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A,
            0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34,
            0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E,
            0x3F, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A,
            0x4B, 0x4C, 0x4D, 0x4E, 0x52, 0x5A, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F,
            0x59, 0x61, 0x62, 0x63
        )
        val values = mutableListOf<ObdValue>()
        for (pid in requested) {
            if (pid !in supportedPids) continue
            val response = ask("01%02X".format(Locale.US, pid), 1200L)
            val data = CamaroUdsProtocol.parsePidBytes(response, "%02X".format(Locale.ROOT, pid))
                ?.asList() ?: continue
            values += decode(pid, data)
        }
        return values
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
        repeat(8) {
            val response = ask("02%02X00".format(Locale.US, basePid))
            val bitmap = freezeFrameValue(response, basePid) ?: return supported
            for (bit in 0 until 32) {
                val mask = 1 shl (7 - (bit % 8))
                if ((bitmap[bit / 8] and mask) != 0 && bit != 31) {
                    supported += basePid + bit + 1
                }
            }
            val hasNextPage = (bitmap[3] and 0x01) != 0
            if (!hasNextPage) return supported
            basePid += 0x20
        }
        return supported
    }

    /**
     * Reads freeze-frame values only for PIDs advertised by the Mode 02 support bitmap.
     * Each Mode 02 response contains the frame number before the actual PID data.
     */
    suspend fun readFreezeFrame(supportedPids: Set<Int>? = null): Map<Int, List<Int>> {
        val result = linkedMapOf<Int, List<Int>>()
        val pids = listOf(0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x42)
        for (pid in pids) {
            if (supportedPids != null && pid !in supportedPids) continue
            val data = freezeFrameValue(ask("02%02X00".format(Locale.US, pid)), pid) ?: continue
            result[pid] = data
        }
        return result
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

    suspend fun readCalibrationIds(): List<String> = withHeaderlessRecords {
        decodeAsciiRecords(ask("0904"), 0x49, 0x04, 16)
    }

    suspend fun readCalibrationVerificationNumbers(): List<String> = withHeaderlessRecords {
        decodeHexRecords(ask("0906"), 0x49, 0x06, 4)
    }

    suspend fun readEcuName(): List<String> = withHeaderlessRecords {
        decodeAsciiRecords(ask("090A"), 0x49, 0x0A, 20)
    }
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
        val data = CamaroUdsProtocol.parsePidBytes(ask("0101"), "01")?.asList() ?: return null
        if (data.size != 4) return null
        val mil = (data[0] and 0x80) != 0
        val dtcCount = data[0] and 0x7F
        return "MIL=${if (mil) "ON" else "OFF"}; DTC_COUNT=$dtcCount; RAW=${data.joinToString(" ") { "%02X".format(Locale.US, it) }}"
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

    private suspend fun collectReadOnlyScan(
        adapter: Map<String, String>,
        respondingEcus: List<String>,
        physicalCanResponder: String? = null,
        readDtcRecords: suspend () -> PhysicalObdDtcSnapshot
    ): ObdScanReport {
        val supported = discoverSupportedPids()
        val live = readLiveData(supported)
        val dtcSnapshot = readDtcRecords()
        val freezeFrameDtc = readFreezeFrameDtc()
        val freezeFrameSupported = if (freezeFrameDtc != null) discoverFreezeFrameSupportedPids() else emptySet()
        val freeze = if (freezeFrameDtc != null) readFreezeFrame(freezeFrameSupported) else emptyMap()
        val vin = readVin()
        val calIds = readCalibrationIds()
        val cvns = readCalibrationVerificationNumbers()
        val ecuNames = readEcuName()
        val readiness = readReadiness()
        val mode06 = readMonitorTests()
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
            unavailableDtcModes = dtcSnapshot.unavailableModes
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
            0x5B -> "Hybrid battery pack remaining life" to (percent(a) to "%")
            0x5C -> "Engine oil temperature" to ((a - 40).toDouble() to "°C")
            0x5D -> "Fuel injection timing" to (((word() ?: return emptyList()) / 128.0) - 210.0 to "°")
            0x5E -> "Engine fuel rate" to ((word() ?: return emptyList()) / 20.0 to "L/h")
            0x5F -> return raw("Emission requirements to which vehicle is designed")
            0x61 -> "Driver demand engine torque" to ((a - 125).toDouble() to "%")
            0x62 -> "Actual engine torque" to ((a - 125).toDouble() to "%")
            0x63 -> "Engine reference torque" to ((word() ?: return emptyList()).toDouble() to "Nm")
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
