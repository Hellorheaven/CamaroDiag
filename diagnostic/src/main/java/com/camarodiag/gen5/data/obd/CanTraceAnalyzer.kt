package com.camarodiag.gen5.data.obd

import java.util.Locale

enum class CanTraceDirection { TX, RX, UNKNOWN }

data class CanTraceFrame(
    val lineNumber: Int,
    val timestampSeconds: Double?,
    val canId: Int,
    val direction: CanTraceDirection,
    val data: List<Int>
)

data class UdsTraceMessage(
    val timestampSeconds: Double?,
    val canId: Int,
    val direction: CanTraceDirection,
    val payload: List<Int>
) {
    val responseService: Int? get() = payload.firstOrNull()
    val service: Int?
        get() = when {
            payload.firstOrNull() == 0x7F -> payload.getOrNull(1)
            (payload.firstOrNull() ?: 0) in 0x40..0x7E -> (payload[0] - 0x40)
            else -> payload.firstOrNull()
        }
    val did: Int?
        get() = if (responseService != 0x7F &&
            (service == 0x22 || service == 0x2E || service == 0x2F) && payload.size >= 3
        ) {
            (payload[1] shl 8) or payload[2]
        } else null
    /** Mode-specific PID/test identifier for OBD services that carry one. */
    val parameterId: Int?
        get() = when (service) {
            0x01, 0x02, 0x05, 0x06, 0x08, 0x09 ->
                if (responseService != 0x7F) payload.getOrNull(1) else null
            else -> null
        }
    /** Echoed UDS sub-function for services whose positive responses repeat it. */
    val subFunction: Int?
        get() = when (service) {
            0x10, 0x11, 0x19, 0x27, 0x28, 0x31, 0x3E ->
                if (responseService != 0x7F) payload.getOrNull(1)?.and(0x7F) else null
            else -> null
        }
    /** RoutineControl identifier (RID), echoed by positive 0x71 responses. */
    val routineIdentifier: Int?
        get() = if (service == 0x31 && responseService != 0x7F && payload.size >= 4) {
            (payload[2] shl 8) or payload[3]
        } else null
    val negativeResponseCode: Int?
        get() = if (payload.firstOrNull() == 0x7F) payload.getOrNull(2) else null
    val serviceName: String
        get() = when (service) {
            0x01 -> "ShowCurrentData (OBD-II)"
            0x02 -> "ShowFreezeFrameData (OBD-II)"
            0x03 -> "ReadStoredDiagnosticTroubleCodes (OBD-II)"
            0x04 -> "ClearDiagnosticTroubleCodes (OBD-II)"
            0x05 -> "ReadOxygenSensorMonitoring (OBD-II)"
            0x06 -> "ReadOnBoardMonitoringTestResults (OBD-II)"
            0x07 -> "ReadPendingDiagnosticTroubleCodes (OBD-II)"
            0x08 -> "ControlOnBoardSystems (OBD-II)"
            0x09 -> "RequestVehicleInformation (OBD-II)"
            0x0A -> "ReadPermanentDiagnosticTroubleCodes (OBD-II)"
            0x10 -> "DiagnosticSessionControl"
            0x11 -> "ECUReset"
            0x14 -> "ClearDiagnosticInformation"
            0x19 -> "ReadDTCInformation"
            0x22 -> "ReadDataByIdentifier"
            0x23 -> "ReadMemoryByAddress"
            0x27 -> "SecurityAccess"
            0x28 -> "CommunicationControl"
            0x2E -> "WriteDataByIdentifier"
            0x2F -> "InputOutputControlByIdentifier"
            0x31 -> "RoutineControl"
            0x34 -> "RequestDownload"
            0x36 -> "TransferData"
            0x37 -> "RequestTransferExit"
            0x3E -> "TesterPresent"
            else -> "Unknown"
        }
}

data class CanTraceDidObservation(
    val timestampSeconds: Double?,
    val canId: Int,
    val direction: CanTraceDirection,
    val did: Int,
    val responseService: Int,
    val positiveResponse: Boolean
)

data class CanTraceDtcObservation(
    val timestampSeconds: Double?,
    val canId: Int,
    val direction: CanTraceDirection,
    /** Original OBD-II request mode: 03 stored, 07 pending, 0A permanent. */
    val service: Int,
    val code: String,
    val rawBytes: List<Int>
)

data class CanTracePidObservation(
    val timestampSeconds: Double?,
    val canId: Int,
    val direction: CanTraceDirection,
    val pid: Int,
    val name: String,
    val value: Double,
    val unit: String,
    val rawBytes: List<Int>
)

enum class IsoTpFlowStatus { CONTINUE_TO_SEND, WAIT, OVERFLOW, RESERVED }

data class CanTraceFlowControl(
    val timestampSeconds: Double?,
    val canId: Int,
    val direction: CanTraceDirection,
    val status: IsoTpFlowStatus,
    val blockSize: Int,
    val separationTimeRaw: Int
) {
    /** ISO-TP STmin in microseconds; null means the raw value is reserved. */
    val separationTimeMicros: Int?
        get() = when (separationTimeRaw) {
            in 0x00..0x7F -> separationTimeRaw * 1_000
            in 0xF1..0xF9 -> (separationTimeRaw - 0xF0) * 100
            else -> null
        }
}

enum class UdsExchangeStatus { COMPLETED, RESPONSE_PENDING, UNANSWERED }

data class UdsTraceExchange(
    val request: UdsTraceMessage,
    val responses: List<UdsTraceMessage>,
    val status: UdsExchangeStatus,
    /** Elapsed time to the final response, in milliseconds; null when timestamps are unavailable. */
    val elapsedMilliseconds: Double?
)

data class CanTraceAnalysisReport(
    val sourceName: String,
    val frames: List<CanTraceFrame>,
    val messages: List<UdsTraceMessage>,
    val didObservations: List<CanTraceDidObservation>,
    val pidObservations: List<CanTracePidObservation>,
    val errors: List<String>,
    /** Multi-frame messages not completed, including sessions discarded before end of capture. */
    val incompleteIsoTpMessages: Int,
    val flowControlObservations: List<CanTraceFlowControl> = emptyList(),
    val exchanges: List<UdsTraceExchange> = emptyList(),
    val dtcObservations: List<CanTraceDtcObservation> = emptyList()
) {
    val observedCanIds: List<Int> = frames.map { it.canId }.distinct().sorted()
    val udsServices: List<Int> = messages.mapNotNull { it.service }.distinct().sorted()
    val readDidCandidates: List<Int> = didObservations
        .filter { it.responseService == 0x22 || it.responseService == 0x62 }
        .map { it.did }.distinct().sorted()
}

/**
 * Offline CAN/ISO-TP/UDS trace parser for Vector ASC and CamaroDiag CSV exports.
 *
 * The analyzer only reports observed traffic. It never promotes a DID, CAN address,
 * scaling rule, ECU identity or vehicle configuration to a verified catalogue entry.
 */
object CanTraceAnalyzer {
    // Bound hostile or accidental 32-bit ISO-TP lengths before buffering a trace payload.
    private const val MAX_SUPPORTED_ISOTP_PAYLOAD_BYTES = 1_048_576

    // Offline traces use a conservative one-second gap to flag stale partial ISO-TP sessions.
    // The timeout is applied only when both frame timestamps are available and monotonic.
    private const val MAX_ISOTP_CONSECUTIVE_FRAME_GAP_SECONDS = 1.0

    private val ascFrameRegex = Regex(
        """^\s*([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?|NaN|Infinity)\s+\d+\s+([0-9A-Fa-f]{3,8})\s+(Tx|Rx)\s+[dD]\s+(\d+)\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )

    private val elmFrameRegex = Regex(
        """^\s*([0-9A-Fa-f]{3,8})\s+([0-9A-Fa-f]{1,2})\s+((?:[0-9A-Fa-f]{2}\s*)*)$"""
    )

    fun analyze(text: String, sourceName: String = "trace.asc"): CanTraceAnalysisReport {
        val lines = text.lineSequence().toList()
        val ascLike = sourceName.endsWith(".asc", true) || lines.any { ascFrameRegex.matches(it) }
        val csvLike = sourceName.endsWith(".csv", true)
        val elmLike = lines.any { elmFrameRegex.matches(it) }
        val parsed = when {
            ascLike -> parseAsc(lines)
            csvLike -> parseCsv(lines)
            elmLike -> parseElm(lines)
            else -> parseCsv(lines)
        }
        val (messages, isoTpErrors, incomplete) = reassemble(parsed.frames)
        val allErrors = parsed.errors + isoTpErrors
        val observations = messages.mapNotNull { message ->
            val service = message.service ?: return@mapNotNull null
            val did = message.did ?: return@mapNotNull null
            if (service != 0x22 && service != 0x2E) return@mapNotNull null
            val response = message.responseService ?: return@mapNotNull null
            CanTraceDidObservation(
                timestampSeconds = message.timestampSeconds,
                canId = message.canId,
                direction = message.direction,
                did = did,
                responseService = response,
                positiveResponse = response == service + 0x40
            )
        }
        val pidObservations = messages.mapNotNull(::decodeStandardPid)
        val dtcObservations = messages.flatMap(::decodeStandardDtc)
        return CanTraceAnalysisReport(
            sourceName = sourceName,
            frames = parsed.frames,
            messages = messages,
            didObservations = observations,
            pidObservations = pidObservations,
            errors = allErrors,
            incompleteIsoTpMessages = incomplete,
            flowControlObservations = parsed.frames.mapNotNull(::decodeFlowControl),
            exchanges = correlateExchanges(messages),
            dtcObservations = dtcObservations
        )
    }

    private data class PendingUdsRequest(
        val request: UdsTraceMessage,
        val responses: MutableList<UdsTraceMessage> = mutableListOf()
    ) {
        fun toExchange(status: UdsExchangeStatus): UdsTraceExchange {
            val lastTimestamp = responses.lastOrNull()?.timestampSeconds
            val startTimestamp = request.timestampSeconds
            val elapsed = if (lastTimestamp != null && startTimestamp != null &&
                lastTimestamp >= startTimestamp
            ) {
                (lastTimestamp - startTimestamp) * 1_000.0
            } else null
            return UdsTraceExchange(request, responses.toList(), status, elapsed)
        }
    }

    /**
     * Correlates only explicit TX/RX traffic. Unknown-direction ELM captures remain unpaired
     * rather than guessing which ECU sent a request or response. NRC 0x78 keeps the request open.
     */
    private fun correlateExchanges(messages: List<UdsTraceMessage>): List<UdsTraceExchange> {
        val pending = mutableListOf<PendingUdsRequest>()
        val exchanges = mutableListOf<UdsTraceExchange>()
        messages.forEach { message ->
            if (message.direction == CanTraceDirection.UNKNOWN) return@forEach
            val rawService = message.responseService ?: return@forEach
            val service = message.service ?: return@forEach
            val isPositiveResponse = rawService == service + 0x40
            val isNegativeResponse = rawService == 0x7F
            // A negative UDS response must contain both the original service and an NRC.
            // Ignore a truncated 0x7F record instead of misclassifying it as a request.
            if (isNegativeResponse && message.negativeResponseCode == null) return@forEach
            if (isPositiveResponse || isNegativeResponse) {
                // A negative response has no DID. When multiple same-service requests are
                // outstanding, choosing the first one would fabricate a request/response pair.
                // Keep the response unpaired unless exactly one candidate is compatible.
                val matchingIndexes = pending.indices.filter { index ->
                    val request = pending[index].request
                    request.direction != message.direction &&
                        isCompatibleObdCanResponseId(request.canId, message.canId) &&
                        // A physical ECU may send several NRC 0x78 responses before its final reply.
                        // For functional requests, also allow that same ECU to complete a pending
                        // exchange, but do not count duplicate terminal replies from one responder.
                        pending[index].responses.none {
                            it.canId == message.canId && it.negativeResponseCode != 0x78
                        } &&
                        request.service == service &&
                        // If both timestamps exist, never pair a response that predates
                        // its request. Missing timestamps retain capture-order behavior.
                        (request.timestampSeconds == null || message.timestampSeconds == null ||
                            message.timestampSeconds >= request.timestampSeconds) &&
                        when {
                            // Read, write and I/O-control DID responses must echo the DID.
                            // A negative response has no DID, so it is safe only when the
                            // surrounding candidate set contains exactly one request.
                            service == 0x22 || service == 0x2E || service == 0x2F ->
                                request.did != null && (isNegativeResponse || message.did == request.did)
                            // OBD modes with a PID/test identifier must be paired by that
                            // identifier when several requests of the same mode are in flight.
                            request.parameterId != null ->
                                isNegativeResponse || message.parameterId == request.parameterId
                            request.routineIdentifier != null ->
                                isNegativeResponse ||
                                    (message.subFunction == request.subFunction &&
                                        message.routineIdentifier == request.routineIdentifier)
                            request.subFunction != null ->
                                isNegativeResponse || message.subFunction == request.subFunction
                            else -> request.did == null || message.did == null ||
                                request.did == message.did
                        }
                }
                val matchIndex = matchingIndexes.singleOrNull()
                if (matchIndex != null) {
                    val candidate = pending[matchIndex]
                    candidate.responses += message
                    // A functional 7DF request can receive one response from
                    // several ECUs. Keep it pending for distinct responder IDs and
                    // aggregate those replies into one exchange until capture end.
                    val functionalRequest = candidate.request.canId == 0x7DF
                    if (!functionalRequest && message.negativeResponseCode != 0x78) {
                        pending.removeAt(matchIndex)
                        exchanges += candidate.toExchange(UdsExchangeStatus.COMPLETED)
                    }
                    return@forEach
                }
            } else if (service in 0x01..0x0A || service in 0x10..0x3E) {
                // Include standard OBD-II modes as well as UDS services in the exchange history.
                pending += PendingUdsRequest(message)
            }
        }
        pending.forEach { candidate ->
            val status = when {
                candidate.responses.lastOrNull()?.negativeResponseCode == 0x78 ->
                    UdsExchangeStatus.RESPONSE_PENDING
                candidate.responses.isNotEmpty() ->
                    UdsExchangeStatus.COMPLETED
                else -> UdsExchangeStatus.UNANSWERED
            }
            exchanges += candidate.toExchange(status)
        }
        return exchanges.sortedBy { it.request.timestampSeconds ?: Double.POSITIVE_INFINITY }
    }


    /**
     * Correlate only the conventional 11-bit OBD-II response ranges.
     * For physical requests 7E0..7E7 the matching response is +8;
     * functional request 7DF may be answered by 7E8..7EF.
     * Unknown/29-bit addressing is deliberately left unpaired.
     */
    private fun isCompatibleObdCanResponseId(requestId: Int, responseId: Int): Boolean {
        if (requestId == 0x7DF) return responseId in 0x7E8..0x7EF
        if (requestId in 0x7E0..0x7E7) return responseId == requestId + 0x08
        return false
    }

    /** Decode ISO-TP Flow Control (PCI type 3) without treating it as a UDS payload. */
    private fun decodeFlowControl(frame: CanTraceFrame): CanTraceFlowControl? {
        if (frame.data.firstOrNull()?.and(0xF0) != 0x30 || frame.data.size < 3) return null
        val rawStatus = frame.data[0] and 0x0F
        val status = when (rawStatus) {
            0x00 -> IsoTpFlowStatus.CONTINUE_TO_SEND
            0x01 -> IsoTpFlowStatus.WAIT
            0x02 -> IsoTpFlowStatus.OVERFLOW
            else -> IsoTpFlowStatus.RESERVED
        }
        return CanTraceFlowControl(
            timestampSeconds = frame.timestampSeconds,
            canId = frame.canId,
            direction = frame.direction,
            status = status,
            blockSize = frame.data[1],
            separationTimeRaw = frame.data[2]
        )
    }

    /**
     * Decodes standardized SAE J1979 Mode 01 response PIDs.
     * Unsupported PIDs and truncated payloads remain available in the raw trace.
     */
    private fun decodeStandardPid(message: UdsTraceMessage): CanTracePidObservation? {
        if (message.responseService != 0x41 || message.payload.size < 3) return null
        val pid = message.payload[1]
        val bytes = message.payload.drop(2)
        val a = bytes.getOrNull(0) ?: return null
        val b = bytes.getOrNull(1) ?: 0
        val decoded = when (pid) {
            0x04 -> Triple("Calculated engine load", a * 100.0 / 255.0, "%")
            0x05 -> Triple("Engine coolant temperature", (a - 40).toDouble(), "°C")
            0x06 -> Triple("Short-term fuel trim Bank 1", a * 100.0 / 128.0 - 100.0, "%")
            0x07 -> Triple("Long-term fuel trim Bank 1", a * 100.0 / 128.0 - 100.0, "%")
            0x08 -> Triple("Short-term fuel trim Bank 2", a * 100.0 / 128.0 - 100.0, "%")
            0x09 -> Triple("Long-term fuel trim Bank 2", a * 100.0 / 128.0 - 100.0, "%")
            0x0A -> Triple("Fuel pressure", a * 3.0, "kPa")
            0x0B -> Triple("Intake manifold absolute pressure", a.toDouble(), "kPa")
            0x0C -> Triple("Engine speed", ((a * 256) + b) / 4.0, "rpm")
            0x0D -> Triple("Vehicle speed", a.toDouble(), "km/h")
            0x0E -> Triple("Timing advance", a / 2.0 - 64.0, "°")
            0x0F -> Triple("Intake air temperature", (a - 40).toDouble(), "°C")
            0x10 -> Triple("Mass air flow", ((a * 256) + b) / 100.0, "g/s")
            0x11 -> Triple("Throttle position", a * 100.0 / 255.0, "%")
            0x1F -> Triple("Run time since engine start", (a * 256 + b).toDouble(), "s")
            0x21 -> Triple("Distance traveled with MIL on", (a * 256 + b).toDouble(), "km")
            0x2F -> Triple("Fuel tank level", a * 100.0 / 255.0, "%")
            0x31 -> Triple("Distance since diagnostic codes cleared", (a * 256 + b).toDouble(), "km")
            0x33 -> Triple("Barometric pressure", a.toDouble(), "kPa")
            in 0x14..0x1B -> Triple("Oxygen sensor " + (pid - 0x13) + " voltage", a / 200.0, "V")
            in 0x24..0x2B -> Triple("Wide-range oxygen sensor " + (pid - 0x23) + " equivalence ratio", ((a * 256 + b) * 2.0) / 65536.0, "ratio")
            in 0x34..0x3B -> Triple("Wide-range oxygen sensor " + (pid - 0x33) + " equivalence ratio", ((a * 256 + b) * 2.0) / 65536.0, "ratio")
            0x3C -> Triple("Catalyst temperature Bank 1 Sensor 1", ((a * 256 + b) / 10.0) - 40.0, "°C")
            0x3D -> Triple("Catalyst temperature Bank 2 Sensor 1", ((a * 256 + b) / 10.0) - 40.0, "°C")
            0x3E -> Triple("Catalyst temperature Bank 1 Sensor 2", ((a * 256 + b) / 10.0) - 40.0, "°C")
            0x3F -> Triple("Catalyst temperature Bank 2 Sensor 2", ((a * 256 + b) / 10.0) - 40.0, "°C")
            0x42 -> Triple("Control module voltage", (a * 256 + b) / 1000.0, "V")
            0x43 -> Triple("Absolute engine load", ((a * 256 + b) * 100.0) / 65535.0, "%")
            0x44 -> Triple("Commanded equivalence ratio", ((a * 256 + b) * 2.0) / 65535.0, "ratio")
            0x45 -> Triple("Relative throttle position", a * 100.0 / 255.0, "%")
            0x46 -> Triple("Ambient air temperature", (a - 40).toDouble(), "°C")
            0x47 -> Triple("Absolute throttle position B", a * 100.0 / 255.0, "%")
            0x48 -> Triple("Absolute throttle position C", a * 100.0 / 255.0, "%")
            0x49 -> Triple("Accelerator pedal position D", a * 100.0 / 255.0, "%")
            0x4A -> Triple("Accelerator pedal position E", a * 100.0 / 255.0, "%")
            0x4B -> Triple("Accelerator pedal position F", a * 100.0 / 255.0, "%")
            0x4C -> Triple("Commanded throttle actuator", a * 100.0 / 255.0, "%")
            0x4D -> Triple("Time run with MIL on", (a * 256 + b).toDouble(), "min")
            0x4E -> Triple("Time since diagnostic codes cleared", (a * 256 + b).toDouble(), "min")
            0x5C -> Triple("Engine oil temperature", (a - 40).toDouble(), "°C")
            0x5D -> Triple("Fuel injection timing", (((a * 256 + b) - 26_880) / 128.0), "°")
            0x5A -> Triple("Relative accelerator pedal position", a * 100.0 / 255.0, "%")
            0x5E -> Triple("Engine fuel rate", ((a * 256 + b) / 20.0), "L/h")
            0x61 -> Triple("Driver demand engine torque", (a - 125).toDouble(), "%")
            0x62 -> Triple("Actual engine torque", (a - 125).toDouble(), "%")
            0x63 -> Triple("Engine reference torque", (a * 256 + b).toDouble(), "Nm")
            else -> return null
        }
        val requiredDataBytes = when (pid) {
            0x0C, 0x10, 0x1F, 0x21, 0x31, in 0x14..0x1B,
            in 0x3C..0x3F, 0x42, 0x43, 0x44, 0x4D, 0x4E, 0x5D, 0x5E, 0x63 -> 2
            in 0x24..0x2B, in 0x34..0x3B -> 4
            else -> 1
        }
        if (bytes.size < requiredDataBytes) return null
        return CanTracePidObservation(
            timestampSeconds = message.timestampSeconds,
            canId = message.canId,
            direction = message.direction,
            pid = pid,
            name = decoded.first,
            value = decoded.second,
            unit = decoded.third,
            rawBytes = bytes.take(requiredDataBytes)
        )
    }
    /**
     * Decodes the standardized five-character DTC encoding in OBD-II Mode 03/07/0A
     * positive responses. Zero pairs are padding, not reported trouble codes.
     */
    private fun decodeStandardDtc(message: UdsTraceMessage): List<CanTraceDtcObservation> {
        val mode = when (message.responseService) {
            0x43 -> 0x03
            0x47 -> 0x07
            0x4A -> 0x0A
            else -> return emptyList()
        }
        if (message.payload.size < 3) return emptyList()

        return message.payload.drop(1).chunked(2).mapNotNull { pair ->
            if (pair.size != 2 || (pair[0] == 0 && pair[1] == 0)) return@mapNotNull null
            val first = pair[0]
            val second = pair[1]
            val system = when (first ushr 6) {
                0 -> 'P'
                1 -> 'C'
                2 -> 'B'
                else -> 'U'
            }
            val code = buildString(5) {
                append(system)
                append((first ushr 4) and 0x03)
                append((first and 0x0F).toString(16).uppercase(Locale.ROOT))
                append((second ushr 4).toString(16).uppercase(Locale.ROOT))
                append((second and 0x0F).toString(16).uppercase(Locale.ROOT))
            }
            CanTraceDtcObservation(
                timestampSeconds = message.timestampSeconds,
                canId = message.canId,
                direction = message.direction,
                service = mode,
                code = code,
                rawBytes = pair
            )
        }
    }

    private data class ParsedFrames(val frames: List<CanTraceFrame>, val errors: List<String>)

    private fun parseAsc(lines: List<String>): ParsedFrames {
        val frames = mutableListOf<CanTraceFrame>()
        val errors = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            if (line.isBlank() || line.trimStart().startsWith("//")) return@forEachIndexed
            val match = ascFrameRegex.matchEntire(line) ?: return@forEachIndexed
            val timestamp = match.groupValues[1].toDoubleOrNull()
            val canId = parseCanId(match.groupValues[2])
            val direction = parseDirection(match.groupValues[3])
            val dlc = match.groupValues[4].toIntOrNull()
            val bytes = parseHexBytes(match.groupValues[5])
            if (timestamp == null || !timestamp.isFinite() || canId == null || dlc == null || bytes == null) {
                errors += "Line ${index + 1}: invalid ASC frame header or hexadecimal data."
                return@forEachIndexed
            }
            if (dlc !in 0..64 || bytes.size != dlc) {
                errors += "Line ${index + 1}: DLC does not match the available data bytes."
                return@forEachIndexed
            }
            frames += CanTraceFrame(index + 1, timestamp, canId, direction, bytes.take(dlc))
        }
        return ParsedFrames(frames, errors)
    }

    /**
     * Parses ELM327 monitor logs with headers enabled (CAN ID, DLC, data bytes).
     * ELM logs do not normally carry a timestamp or TX/RX marker, so those remain unknown.
     */
    private fun parseElm(lines: List<String>): ParsedFrames {
        val frames = mutableListOf<CanTraceFrame>()
        val errors = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            val match = elmFrameRegex.matchEntire(line) ?: return@forEachIndexed
            val canId = parseCanId(match.groupValues[1])
            val dlc = match.groupValues[2].toIntOrNull(16)
            val bytes = parseHexBytes(match.groupValues[3])
            if (canId == null || dlc == null || bytes == null || dlc !in 0..8 || bytes.size != dlc) {
                errors += "Line ${index + 1}: invalid ELM327 header/DLC/data record."
                return@forEachIndexed
            }
            frames += CanTraceFrame(index + 1, null, canId, CanTraceDirection.UNKNOWN, bytes.take(dlc))
        }
        if (frames.isEmpty()) errors += "No ELM327 header records found; enable CAN headers and save the monitor output."
        return ParsedFrames(frames, errors)
    }

    private fun parseCsv(lines: List<String>): ParsedFrames {
        val frames = mutableListOf<CanTraceFrame>()
        val errors = mutableListOf<String>()
        val headerLineIndex = lines.indexOfFirst { it.isNotBlank() && !it.trimStart().startsWith("#") }
        if (headerLineIndex < 0) return ParsedFrames(emptyList(), listOf("CSV trace is empty."))

        val delimiter = if (lines[headerLineIndex].count { it == ';' } >
            lines[headerLineIndex].count { it == ',' }) ';' else ','
        val headers = splitCsvLine(lines[headerLineIndex], delimiter)
            .map { it.trim().trim('"').lowercase(Locale.ROOT).replace(" ", "_") }
        val timestampIndex = headers.indexOfFirst { it in setOf("timestamp", "time", "seconds", "time_s") }
        val canIdIndex = headers.indexOfFirst { it in setOf("can_id", "canid", "arbitration_id", "id") }
        val directionIndex = headers.indexOfFirst { it in setOf("direction", "dir", "tx_rx") }
        val dataIndex = headers.indexOfFirst { it in setOf("data", "payload", "bytes", "frame_data") }
        val dlcIndex = headers.indexOfFirst { it == "dlc" }
        val dataByteIndexes = headers.indices.filter { headers[it].matches(Regex("(data|byte)_?([0-9]|[1-5][0-9]|6[0-3])")) }
        if (canIdIndex < 0 || (dataIndex < 0 && dataByteIndexes.isEmpty())) {
            return ParsedFrames(emptyList(), listOf("CSV needs a CAN ID column and a data/payload column (or data0…data63 columns)."))
        }

        for (lineIndex in headerLineIndex + 1 until lines.size) {
            val line = lines[lineIndex]
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val fields = splitCsvLine(line, delimiter)
            fun field(index: Int): String = fields.getOrNull(index).orEmpty().trim().trim('"')
            val canId = parseCanId(field(canIdIndex))
            val dlcText = if (dlcIndex >= 0) field(dlcIndex) else ""
            val declaredDlc = if (dlcIndex >= 0) dlcText.toIntOrNull() else null
            if (dlcIndex >= 0 && (declaredDlc == null || declaredDlc !in 0..64)) {
                errors += "Line ${lineIndex + 1}: invalid CSV CAN frame."
                continue
            }
            val timestampText = if (timestampIndex >= 0) field(timestampIndex) else ""
            val timestamp = when {
                timestampIndex < 0 || timestampText.isBlank() -> null
                else -> timestampText.toDoubleOrNull()?.takeIf { it.isFinite() }
            }
            if (timestampIndex >= 0 && timestampText.isNotBlank() && timestamp == null) {
                errors += "Line ${lineIndex + 1}: invalid CSV CAN frame."
                continue
            }
            val bytes = if (dataIndex >= 0) parseHexBytes(field(dataIndex)) else {
                val rawValues: List<String> = dataByteIndexes.map { index -> field(index) }
                // Empty columns after the declared DLC are normal in fixed-width CSV exports.
                // Without a DLC column, trim only trailing empties; a gap inside the frame is invalid.
                val byteCount: Int = declaredDlc
                    ?: (rawValues.indexOfLast { value -> value.isNotBlank() } + 1)
                val hasBytesBeyondDeclaredDlc = declaredDlc != null &&
                    rawValues.drop(declaredDlc).any { value -> value.isNotBlank() }
                if (byteCount !in 0..rawValues.size || hasBytesBeyondDeclaredDlc) {
                    null
                } else {
                    val values: List<Int?> = rawValues.take(byteCount).map { value: String ->
                        if (value.isBlank()) null else value.removePrefix("0x").removePrefix("0X")
                            .toIntOrNull(16)?.takeIf { byte -> byte in 0..0xFF }
                    }
                    if (values.any { byte -> byte == null }) null else values.filterNotNull()
                }
            }
            val direction = if (directionIndex >= 0) parseDirection(field(directionIndex)) else CanTraceDirection.UNKNOWN
            val dlc = declaredDlc ?: bytes?.size
            if (canId == null || bytes == null || dlc == null || dlc !in 0..64 || bytes.size != dlc) {
                errors += "Line ${lineIndex + 1}: invalid CSV CAN frame."
                continue
            }
            frames += CanTraceFrame(lineIndex + 1, timestamp, canId, direction, bytes.take(dlc))
        }
        return ParsedFrames(frames, errors)
    }

    private fun splitCsvLine(line: String, delimiter: Char): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        fields += current.toString()
        return fields
    }

    private fun parseHexBytes(text: String): List<Int>? {
        val cleaned = text.trim()
            .replace(Regex("(?i)0x"), "")
            .replace(Regex("[\\[\\]{}():|]"), " ")
        if (cleaned.isBlank()) return emptyList()
        val tokens = cleaned.split(Regex("[\\s,;]+")).filter(String::isNotBlank)
        val bytes = mutableListOf<Int>()
        for (token in tokens) {
            if (token.length % 2 != 0 || token.any { it.digitToIntOrNull(16) == null }) return null
            token.chunked(2).forEach { pair -> bytes += pair.toInt(16) }
        }
        return bytes
    }

    private fun parseCanId(value: String): Int? {
        val normalized = value.trim().removePrefix("0x").removePrefix("0X")
        if (normalized.length !in 3..8 || normalized.any { it.digitToIntOrNull(16) == null }) return null
        return normalized.toLongOrNull(16)?.takeIf { it in 0L..0x1FFFFFFFL }?.toInt()
    }

    private fun parseDirection(value: String): CanTraceDirection = when (value.trim().uppercase(Locale.ROOT)) {
        "TX", "TRANSMIT", "SEND" -> CanTraceDirection.TX
        "RX", "RECEIVE", "RECV" -> CanTraceDirection.RX
        else -> CanTraceDirection.UNKNOWN
    }

    private data class PendingIsoTp(
        val timestampSeconds: Double?,
        val canId: Int,
        val direction: CanTraceDirection,
        val expectedLength: Int,
        val payload: MutableList<Int>,
        var nextSequence: Int
    ) {
        var lastFrameTimestampSeconds: Double? = timestampSeconds
    }

    private fun hasIsoTpConsecutiveFrameTimedOut(
        previousTimestampSeconds: Double?,
        currentTimestampSeconds: Double?
    ): Boolean {
        if (previousTimestampSeconds == null || currentTimestampSeconds == null) return false
        if (currentTimestampSeconds < previousTimestampSeconds) return false
        return currentTimestampSeconds - previousTimestampSeconds >
            MAX_ISOTP_CONSECUTIVE_FRAME_GAP_SECONDS
    }

    private fun hasIsoTpTimestampMovedBackwards(
        previousTimestampSeconds: Double?,
        currentTimestampSeconds: Double?
    ): Boolean = previousTimestampSeconds != null && currentTimestampSeconds != null &&
        currentTimestampSeconds < previousTimestampSeconds

    private fun isValidIsoTpSeparationTime(value: Int): Boolean =
        value in 0x00..0x7F || value in 0xF1..0xF9

    private data class ReassemblyResult(
        val messages: List<UdsTraceMessage>,
        val errors: List<String>,
        val incomplete: Int
    )

    private fun reassemble(frames: List<CanTraceFrame>): ReassemblyResult {
        val messages = mutableListOf<UdsTraceMessage>()
        val errors = mutableListOf<String>()
        val pending = mutableMapOf<Pair<Int, CanTraceDirection>, PendingIsoTp>()
        var discardedIncompleteMessages = 0

        frames.forEach { frame ->
            if (frame.data.isEmpty()) return@forEach
            val pci = frame.data[0]
            when (pci and 0xF0) {
                0x00 -> {
                    val key = frame.canId to frame.direction
                    if (pending.remove(key) != null) {
                        errors += "Line ${frame.lineNumber}: single frame interrupted an unfinished ISO-TP message."
                        discardedIncompleteMessages++
                    }
                    // CAN FD permits the escaped Single Frame length form: SF_DL=0,
                    // followed by one byte containing the actual payload length.
                    val shortLength = pci and 0x0F
                    val headerLength = if (shortLength == 0) 2 else 1
                    val length = if (shortLength == 0) frame.data.getOrNull(1) ?: 0 else shortLength
                    if (length == 0 || length > 62 ||
                        length > frame.data.size - headerLength
                    ) {
                        errors += "Line ${frame.lineNumber}: invalid ISO-TP single-frame length."
                    } else {
                        messages += UdsTraceMessage(
                            frame.timestampSeconds, frame.canId, frame.direction,
                            frame.data.drop(headerLength).take(length)
                        )
                    }
                }
                0x10 -> {
                    val key = frame.canId to frame.direction
                    if (pending.remove(key) != null) {
                        errors += "Line ${frame.lineNumber}: replaced an unfinished ISO-TP message."
                        discardedIncompleteMessages++
                    }
                    if (frame.data.size < 2) {
                        errors += "Line ${frame.lineNumber}: truncated ISO-TP first frame."
                    } else {
                        val shortLength = ((pci and 0x0F) shl 8) or frame.data[1]
                        val usesExtendedLength = shortLength == 0
                        val headerLength = if (usesExtendedLength) 6 else 2
                        if (usesExtendedLength && frame.data.size < headerLength) {
                            errors += "Line ${frame.lineNumber}: truncated ISO-TP extended first-frame length."
                        } else {
                            val lengthLong = if (usesExtendedLength) {
                                (frame.data[2].toLong() shl 24) or
                                    (frame.data[3].toLong() shl 16) or
                                    (frame.data[4].toLong() shl 8) or
                                    frame.data[5].toLong()
                            } else shortLength.toLong()
                            // A First Frame is only valid when the payload cannot fit in
                            // a Single Frame of the same CAN data length. Otherwise malformed
                            // PCI data could be promoted to a complete UDS message.
                            val maxSingleFramePayload = minOf(62, frame.data.size - 1)
                            if (lengthLong <= 0 ||
                                lengthLong > MAX_SUPPORTED_ISOTP_PAYLOAD_BYTES.toLong() ||
                                (usesExtendedLength && lengthLong <= 4095) ||
                                (!usesExtendedLength && lengthLong <= maxSingleFramePayload)
                            ) {
                                errors += "Line ${frame.lineNumber}: unsupported ISO-TP first-frame length."
                            } else {
                                val length = lengthLong.toInt()
                                val bytes = frame.data.drop(headerLength).take(length).toMutableList()
                                if (bytes.size >= length) {
                                    messages += UdsTraceMessage(
                                        frame.timestampSeconds, frame.canId, frame.direction, bytes.take(length)
                                    )
                                    pending.remove(key)
                                } else {
                                    pending[key] = PendingIsoTp(
                                        frame.timestampSeconds, frame.canId, frame.direction,
                                        length, bytes, nextSequence = 1
                                    )
                                }
                            }
                        }
                    }
                }
                0x20 -> {
                    val key = frame.canId to frame.direction
                    val state = pending[key]
                    if (state == null) {
                        errors += "Line ${frame.lineNumber}: ISO-TP consecutive frame has no matching first frame."
                    } else if (hasIsoTpTimestampMovedBackwards(
                            state.lastFrameTimestampSeconds,
                            frame.timestampSeconds
                        )
                    ) {
                        errors += "Line ${frame.lineNumber}: ISO-TP consecutive frame timestamp moved backwards."
                        pending.remove(key)
                        discardedIncompleteMessages++
                    } else if (hasIsoTpConsecutiveFrameTimedOut(
                            state.lastFrameTimestampSeconds,
                            frame.timestampSeconds
                        )
                    ) {
                        val elapsed = frame.timestampSeconds!! - state.lastFrameTimestampSeconds!!
                        errors += "Line ${frame.lineNumber}: ISO-TP consecutive frame timed out after ${elapsed}s."
                        pending.remove(key)
                        discardedIncompleteMessages++
                    } else if (frame.data.size < 2) {
                        errors += "Line ${frame.lineNumber}: truncated ISO-TP consecutive frame."
                        pending.remove(key)
                        discardedIncompleteMessages++
                    } else {
                        val sequence = pci and 0x0F
                        if (sequence != state.nextSequence) {
                            errors += "Line ${frame.lineNumber}: ISO-TP sequence mismatch (expected ${state.nextSequence}, got $sequence)."
                            pending.remove(key)
                            discardedIncompleteMessages++
                        } else {
                            state.payload += frame.data.drop(1).take(state.expectedLength - state.payload.size)
                            if (frame.timestampSeconds != null) {
                                state.lastFrameTimestampSeconds = frame.timestampSeconds
                            }
                            state.nextSequence = (state.nextSequence + 1) and 0x0F
                            if (state.payload.size >= state.expectedLength) {
                                messages += UdsTraceMessage(
                                    state.timestampSeconds, state.canId, state.direction,
                                    state.payload.take(state.expectedLength)
                                )
                                pending.remove(key)
                            }
                        }
                    }
                }
                0x30 -> {
                    // ISO-TP Flow Control carries FlowStatus, BlockSize and STmin.
                    // Validate metadata even though it is not exposed as a UDS payload.
                    val flowStatus = pci and 0x0F
                    val separationTime = frame.data.getOrNull(2)
                    if (frame.data.size < 3 || flowStatus !in 0..2 ||
                        separationTime == null || !isValidIsoTpSeparationTime(separationTime)
                    ) {
                        errors += "Line ${frame.lineNumber}: invalid ISO-TP flow-control frame."
                    }
                }
                else -> errors += "Line ${frame.lineNumber}: unsupported ISO-TP PCI type 0x${pci.toString(16).uppercase(Locale.ROOT)}."
            }
        }
        // Include partial messages discarded mid-stream as well as sessions still open at EOF.
        return ReassemblyResult(messages, errors, discardedIncompleteMessages + pending.size)
    }
}
