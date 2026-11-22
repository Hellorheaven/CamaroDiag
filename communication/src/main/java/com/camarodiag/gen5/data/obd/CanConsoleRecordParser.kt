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
    fun hasConfirmedTransmission(text: String): Boolean {
        if (isFailure(text)) return false
        return text.lineSequence().map { it.trim().removeSuffix(">").trim() }
            .any { line -> line.equals("OK", ignoreCase = true) ||
                RawCanFrameParser.parseReceivedFrames(line).isNotEmpty() }
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
}

object CanConsoleRecordParser {
    /** A request-specific exchange never presents unmatched CAN IDs as ECU RX.
     * Use the no-request overload only for an intentionally passive capture. */
    fun parse(output: String, request: RawCanFrame? = null): List<ParsedCanConsoleRecord> =
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
            } else if (request != null && !CanConsoleEcuRouting.isSelectedResponder(request, frame)) {
                ParsedCanConsoleRecord(
                    direction = "SYSTEM",
                    content = line.take(4_096),
                    succeeded = false
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
