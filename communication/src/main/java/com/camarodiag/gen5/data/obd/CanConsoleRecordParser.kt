package com.camarodiag.gen5.data.obd

/**
 * A single line from a raw ELM/STN monitor stream, classified for the unified
 * console. Only a validated CAN identifier + DLC + payload is ECU RX traffic.
 */
data class ParsedCanConsoleRecord(
    val direction: String,
    val content: String,
    val succeeded: Boolean = true
)

/** Terminal errors and silent replies never establish successful adapter transmission. */
object CanConsoleAdapterReplyPolicy {
    private val failures = Regex(
        "(?i)\\b(CAN ERROR|BUS ERROR|BUFFER FULL|NO DATA|UNABLE TO CONNECT|STOPPED|ERROR)\\b"
    )
    fun isFailure(text: String): Boolean = text.isBlank() || text.contains("?") ||
        failures.containsMatchIn(text)
    /** Only a parsed physical CAN reply or an explicit adapter OK confirms an exchange.
     * SEARCHING, command echo and malformed CAN records are not acknowledgements. */
    fun hasConfirmedTransmission(text: String, request: RawCanFrame? = null): Boolean {
        if (isFailure(text)) return false
        // Explicit OK proves adapter transmission, not a diagnostic response.
        if (text.lineSequence().any {
                it.trim().removeSuffix(">").trim().equals("OK", ignoreCase = true)
            }) return true
        if (request == null) return RawCanFrameParser.parseReceivedFrames(text).isNotEmpty()
        return hasMatchedDiagnosticResponse(text, request)
    }

    /** Diagnostic completion is independent of an adapter OK and CAN monitor display. */
    fun hasMatchedDiagnosticResponse(text: String, request: RawCanFrame): Boolean {
        if (isFailure(text)) return false
        val frames = RawCanFrameParser.parseReceivedFrames(text)
        if (!request.isExtended && request.canId == 0x7DFL) {
            val responders = mutableMapOf<Long, CanConsoleDiagnosticCorrelation>()
            return frames.any { frame ->
                if (frame.isExtended || frame.canId !in 0x7E8L..0x7EFL) false
                else {
                    val matcher = responders.getOrPut(frame.canId) {
                        CanConsoleDiagnosticCorrelation(request.copy(canId = frame.canId - 8L))
                    }
                    matcher.accept(frame, 0L) != null
                }
            }
        }
        if (CanConsoleEcuRouting.expectedResponder(request) == null) return false
        val matcher = runCatching { CanConsoleDiagnosticCorrelation(request) }.getOrNull()
            ?: return false
        return frames.any { matcher.accept(it, 0L) != null }
    }
}

/** Physical OBD CAN requests have one responder. Never attribute another ECU's
 * frames to the selected physical ECU. Passive capture stays unfiltered. */
object CanConsoleEcuRouting {
    fun expectedResponder(request: RawCanFrame): Long? = when {
        !request.isExtended && request.canId in 0x7E0L..0x7E7L ->
            request.canId + 8L
        request.isExtended && request.canId and 0xFFFF00FFL == 0x18DA00F1L ->
            0x18DAF100L or ((request.canId shr 8) and 0xFFL)
        else -> null
    }

    fun isSelectedResponder(request: RawCanFrame, response: RawCanFrame): Boolean {
        val expected = expectedResponder(request) ?: return false
        return response.isExtended == request.isExtended && response.canId == expected
    }

    /** SAE J1979 functional request is a broadcast, not one selected ECU.
     * The console retains each responder's CAN ID and never coalesces payloads. */
    fun isExpectedResponse(request: RawCanFrame, response: RawCanFrame): Boolean =
        if (!request.isExtended && request.canId == 0x7DFL) {
            !response.isExtended && response.canId in 0x7E8L..0x7EFL
        } else isSelectedResponder(request, response)
}

/** Presentation has no diagnostic ownership: every valid bus frame is RX.
 * Diagnostic correlation is a separate operation and must never relabel traffic. */
/** Transport-level failure: adapter configuration is no longer trusted.
 * The caller must stop the physical session before another diagnostic request. */
class CanAdapterRestorationException : IllegalStateException()

object CanConsoleAdapterRestorationPolicy {
    /** Adapter AT configuration changes require an explicit OK, not ECU traffic. */
    fun accepted(reply: String): Boolean =
        !CanConsoleAdapterReplyPolicy.isFailure(reply) &&
            reply.lineSequence().any { it.trim().removeSuffix(">").trim()
                .equals("OK", ignoreCase = true) }
}

object CanConsoleRecordParser {
    fun parse(output: String): List<ParsedCanConsoleRecord> =
        output.lineSequence().mapNotNull { rawLine ->
            val line = rawLine.trim().removeSuffix(">").trim()
            if (line.isEmpty()) return@mapNotNull null

            val frame = RawCanFrameParser.parseReceivedFrames(line).singleOrNull()
            if (frame == null) {
                ParsedCanConsoleRecord(
                    direction = "SYSTEM",
                    content = line.take(4_096),
                    succeeded = !CanConsoleAdapterReplyPolicy.isFailure(line)
                )
            } else {
                val identifier = frame.canId.toString(16).uppercase().padStart(
                    if (frame.isExtended) 8 else 3, '0'
                )
                val payload = if (frame.data.isEmpty()) "(empty)" else frame.data.joinToString(" ") {
                    "%02X".format(java.util.Locale.ROOT, it.toInt() and 0xFF)
                }
                ParsedCanConsoleRecord(
                    direction = "RX",
                    content = "CAN ID=$identifier DLC=${frame.data.size} DATA=$payload"
                )
            }
        }.toList()
}

/**
 * The OBD read console is independently usable on a physical J2534 bridge.
 * CAN monitor availability is deliberately not a prerequisite for OBD reads.
 */
/** Hardware ELM/STN monitoring supports one Classical CAN format per session. */
object CanMonitorProtocolPolicy {
    fun protocolCommand(can29Bit: Boolean): String = if (can29Bit) "ATSP7" else "ATSP6"
    fun clearFilter(can29Bit: Boolean): String = if (can29Bit) "00000000" else "000"
}

object CanConsoleCapabilityPolicy {
    fun canSubmit(
        rawCanFrame: Boolean,
        monitorAvailable: Boolean,
        canTransmit: Boolean,
        readObdAvailable: Boolean,
        inputPresent: Boolean,
        busy: Boolean
    ): Boolean {
        if (!inputPresent || busy) return false
        return if (rawCanFrame) monitorAvailable && canTransmit else readObdAvailable
    }
}
