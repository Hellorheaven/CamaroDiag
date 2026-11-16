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
            (service == 0x22 || service == 0x2E) && payload.size >= 3
        ) {
            (payload[1] shl 8) or payload[2]
        } else null
    val negativeResponseCode: Int?
        get() = if (payload.firstOrNull() == 0x7F) payload.getOrNull(2) else null
    val serviceName: String
        get() = when (service) {
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
    val positiveResponse: Boolean,
    val negativeResponseCode: Int?
)

data class CanTraceAnalysisReport(
    val sourceName: String,
    val frames: List<CanTraceFrame>,
    val messages: List<UdsTraceMessage>,
    val didObservations: List<CanTraceDidObservation>,
    val errors: List<String>,
    val incompleteIsoTpMessages: Int
) {
    val observedCanIds: List<Int> = frames.map { it.canId }.distinct().sorted()
    val udsServices: List<Int> = messages.mapNotNull { it.service }.distinct().sorted()
    val readDidCandidates: List<Int> = didObservations.map { it.did }.distinct().sorted()
}

/**
 * Offline CAN/ISO-TP/UDS trace parser for Vector ASC and CamaroDiag CSV exports.
 *
 * The analyzer only reports observed traffic. It never promotes a DID, CAN address,
 * scaling rule, ECU identity or vehicle configuration to a verified catalogue entry.
 */
object CanTraceAnalyzer {
    private val ascFrameRegex = Regex(
        """^\\s*(\\d+(?:\\.\\d+)?)\\s+\\d+\\s+([0-9A-Fa-f]{3,8})\\s+(Tx|Rx)\\s+[dD]\\s+(\\d+)\\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )

    fun analyze(text: String, sourceName: String = "trace.asc"): CanTraceAnalysisReport {
        val lines = text.lineSequence().toList()
        val ascLike = sourceName.endsWith(".asc", true) || lines.any { ascFrameRegex.matches(it) }
        val parsed = if (ascLike) parseAsc(lines) else parseCsv(lines)
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
                positiveResponse = service != null && response == service + 0x40,
                negativeResponseCode = message.negativeResponseCode
            )
        }
        return CanTraceAnalysisReport(
            sourceName = sourceName,
            frames = parsed.frames,
            messages = messages,
            didObservations = observations,
            errors = allErrors,
            incompleteIsoTpMessages = incomplete
        )
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
            if (timestamp == null || canId == null || dlc == null || bytes == null) {
                errors += "Line ${index + 1}: invalid ASC frame header or hexadecimal data."
                return@forEachIndexed
            }
            if (dlc !in 0..64 || bytes.size < dlc) {
                errors += "Line ${index + 1}: DLC does not match the available data bytes."
                return@forEachIndexed
            }
            frames += CanTraceFrame(index + 1, timestamp, canId, direction, bytes.take(dlc))
        }
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
        val dataByteIndexes = headers.indices.filter { headers[it].matches(Regex("(data|byte)_?([0-9]|1[0-5])")) }
        if (canIdIndex < 0 || (dataIndex < 0 && dataByteIndexes.isEmpty())) {
            return ParsedFrames(emptyList(), listOf("CSV needs a CAN ID column and a data/payload column (or data0…data15 columns)."))
        }

        for (lineIndex in headerLineIndex + 1 until lines.size) {
            val line = lines[lineIndex]
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val fields = splitCsvLine(line, delimiter)
            fun field(index: Int): String = fields.getOrNull(index).orEmpty().trim().trim('"')
            val canId = parseCanId(field(canIdIndex))
            val bytes = if (dataIndex >= 0) parseHexBytes(field(dataIndex)) else {
                dataByteIndexes.mapNotNull { idx ->
                    field(idx).takeIf(String::isNotBlank)?.removePrefix("0x")?.toIntOrNull(16)
                }
            }
            val timestamp = if (timestampIndex >= 0) field(timestampIndex).toDoubleOrNull() else null
            val direction = if (directionIndex >= 0) parseDirection(field(directionIndex)) else CanTraceDirection.UNKNOWN
            val dlc = if (dlcIndex >= 0) field(dlcIndex).toIntOrNull() else bytes?.size
            if (canId == null || bytes == null || dlc == null || dlc !in 0..64 || bytes.size < dlc) {
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
        return normalized.toLongOrNull(16)?.takeIf { it in 0..0x1FFFFFFF }?.toInt()
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
    )

    private data class ReassemblyResult(
        val messages: List<UdsTraceMessage>,
        val errors: List<String>,
        val incomplete: Int
    )

    private fun reassemble(frames: List<CanTraceFrame>): ReassemblyResult {
        val messages = mutableListOf<UdsTraceMessage>()
        val errors = mutableListOf<String>()
        val pending = mutableMapOf<Pair<Int, CanTraceDirection>, PendingIsoTp>()

        frames.forEach { frame ->
            if (frame.data.isEmpty()) return@forEach
            val pci = frame.data[0]
            when (pci and 0xF0) {
                0x00 -> {
                    val length = pci and 0x0F
                    if (length == 0 || length > frame.data.size - 1) {
                        errors += "Line ${frame.lineNumber}: invalid ISO-TP single-frame length."
                    } else {
                        messages += UdsTraceMessage(
                            frame.timestampSeconds, frame.canId, frame.direction,
                            frame.data.drop(1).take(length)
                        )
                    }
                }
                0x10 -> {
                    if (frame.data.size < 2) {
                        errors += "Line ${frame.lineNumber}: truncated ISO-TP first frame."
                    } else {
                        val length = ((pci and 0x0F) shl 8) or frame.data[1]
                        if (length <= 0 || length > 4095) {
                            errors += "Line ${frame.lineNumber}: unsupported ISO-TP first-frame length."
                        } else {
                            val key = frame.canId to frame.direction
                            if (pending.containsKey(key)) {
                                errors += "Line ${frame.lineNumber}: replaced an unfinished ISO-TP message."
                            }
                            val bytes = frame.data.drop(2).take(length).toMutableList()
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
                0x20 -> {
                    val key = frame.canId to frame.direction
                    val state = pending[key]
                    if (state == null) {
                        errors += "Line ${frame.lineNumber}: ISO-TP consecutive frame has no matching first frame."
                    } else {
                        val sequence = pci and 0x0F
                        if (sequence != state.nextSequence) {
                            errors += "Line ${frame.lineNumber}: ISO-TP sequence mismatch (expected ${state.nextSequence}, got $sequence)."
                            pending.remove(key)
                        } else {
                            state.payload += frame.data.drop(1).take(state.expectedLength - state.payload.size)
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
                0x30 -> Unit // Flow-control frame; it is transport metadata, not a UDS payload.
                else -> errors += "Line ${frame.lineNumber}: unsupported ISO-TP PCI type 0x${pci.toString(16).uppercase(Locale.ROOT)}."
            }
        }
        return ReassemblyResult(messages, errors, pending.size)
    }
}
