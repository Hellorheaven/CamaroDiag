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
                    succeeded = !Regex("(?i)\\b(ERROR|CAN ERROR|BUS ERROR|UNABLE|BUFFER FULL|NO DATA)\\b")
                        .containsMatchIn(line)
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
