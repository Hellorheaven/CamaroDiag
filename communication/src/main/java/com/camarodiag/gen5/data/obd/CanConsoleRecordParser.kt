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
enum class CanConsoleDiagnosticStatus { NONE, POSITIVE, NEGATIVE }

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

    /** An ECU refusal is a real diagnostic reply, but never a positive result. */
    fun diagnosticStatus(text: String, request: RawCanFrame): CanConsoleDiagnosticStatus {
        if (isFailure(text)) return CanConsoleDiagnosticStatus.NONE
        val frames = RawCanFrameParser.parseReceivedFrames(text)
        var negativeSeen = false
        fun record(payload: ByteArray?) {
            if (payload == null) return
            if ((payload[0].toInt() and 0xFF) == 0x7F)
                negativeSeen = true
        }
        if (CanConsoleEcuRouting.isFunctional(request)) {
            val responders = mutableMapOf<Long, CanConsoleDiagnosticCorrelation>()
            for (frame in frames) {
                val physical = CanConsoleEcuRouting.physicalRequestForResponse(request, frame) ?: continue
                val matcher = responders[frame.canId] ?: runCatching {
                    CanConsoleDiagnosticCorrelation(physical)
                }.getOrNull()?.also { responders[frame.canId] = it } ?: continue
                val payload = matcher.accept(frame, 0L) ?: continue
                if ((payload[0].toInt() and 0xFF) != 0x7F)
                    return CanConsoleDiagnosticStatus.POSITIVE
                record(payload)
            }
        } else {
            val matcher = runCatching { CanConsoleDiagnosticCorrelation(request) }.getOrNull()
                ?: return CanConsoleDiagnosticStatus.NONE
            for (frame in frames) {
                val payload = matcher.accept(frame, 0L) ?: continue
                if ((payload[0].toInt() and 0xFF) != 0x7F)
                    return CanConsoleDiagnosticStatus.POSITIVE
                record(payload)
            }
        }
        return if (negativeSeen) CanConsoleDiagnosticStatus.NEGATIVE
            else CanConsoleDiagnosticStatus.NONE
    }

    fun hasMatchedDiagnosticResponse(text: String, request: RawCanFrame): Boolean =
        diagnosticStatus(text, request) != CanConsoleDiagnosticStatus.NONE

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
    fun isFunctional(request: RawCanFrame): Boolean =
        (!request.isExtended && request.canId == 0x7DFL) ||
            (request.isExtended && request.canId == 0x18DB33F1L)

    /** Correlate functional replies per responder, never by mixing CAN IDs.
     * The derived physical address is used ONLY for RX correlation, not TX. */
    fun physicalRequestForResponse(request: RawCanFrame, response: RawCanFrame): RawCanFrame? {
        if (!isFunctional(request) || request.isExtended != response.isExtended) return null
        val physicalId = when {
            !request.isExtended && response.canId in 0x7E8L..0x7EFL -> response.canId - 8L
            request.isExtended && response.canId in 0x18DAF100L..0x18DAF1FFL ->
                0x18DA00F1L or ((response.canId and 0xFFL) shl 8)
            else -> return null
        }
        return request.copy(canId = physicalId)
    }

    fun isExpectedResponse(request: RawCanFrame, response: RawCanFrame): Boolean =
        if (isFunctional(request)) physicalRequestForResponse(request, response) != null
        else isSelectedResponder(request, response)
}

/** Presentation has no diagnostic ownership: every valid bus frame is RX.
 * Diagnostic correlation is a separate operation and must never relabel traffic. */
/** Transport-level failure: adapter configuration is no longer trusted.
 * The caller must stop the physical session before another diagnostic request. */
class CanAdapterRestorationException(val originalTransportKind: String? = null) :
    IllegalStateException()

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
/**
 * ELM327 ISO-TP Flow Control is required for a multi-frame ECU answer.
 * CAF0 preserves raw PCI bytes; CFC1 independently enables automatic FC.
 * Passive monitor modes must NOT issue any diagnostic flow control themselves.
 */
object CanConsoleIsotpFlowControlPolicy {
    // Mode 0 lets ELM choose both the Flow Control header and bytes.
    // Otherwise a previous user-defined FC mode may target the wrong ECU.
    const val RESTORE_AUTOMATIC_MODE = "ATFCSM0"
    const val ENABLE_AUTOMATIC = "ATCFC1"

    fun setupCommands(): List<String> =
        listOf(RESTORE_AUTOMATIC_MODE, ENABLE_AUTOMATIC)

    fun adapterConfirmed(reply: String): Boolean =
        CanConsoleAdapterRestorationPolicy.accepted(reply)
}

/** A silent passive bus is normal; a textual adapter failure is not. */
object CanMonitorCompletionPolicy {
    fun accepted(adapterOutput: String): Boolean =
        adapterOutput.isBlank() || !CanConsoleAdapterReplyPolicy.isFailure(adapterOutput)
}

/**
 * Checks the entire physical monitor stream, including text beyond the
 * adapter's small command-response buffer and CR/LF split across USB/BLE reads.
 * A late adapter error must never be disguised as a successful capture.
 */
class CanMonitorErrorTracker {
    private val framer = CanConsoleStreamFramer()
    private var failed = false

    fun append(chunk: String) {
        val batch = framer.append(chunk)
        // Unknown discarded monitor content cannot justify a valid capture.
        if (batch.discardedLines > 0) failed = true
        observe(batch.completeLines)
    }

    fun hasFailureAtEnd(): Boolean {
        val trailing = framer.drainTrailing()
        if (trailing.discardedLines > 0) failed = true
        observe(trailing.completeLines)
        return failed
    }

    private fun observe(lines: String) {
        if (failed || lines.isEmpty()) return
        failed = CanConsoleRecordParser.parse(lines)
            .any { it.direction == "SYSTEM" && !it.succeeded }
    }
}

object CanMonitorProtocolPolicy {
    fun protocolCommand(can29Bit: Boolean, bitrate: Int = 500_000): String =
        if (bitrate == DiagnosticRouteSelector.SW_CAN_BITRATE)
            "STP " + DiagnosticRouteSelector.rawCanProfile(can29Bit, bitrate)
        else "ATSP" + DiagnosticRouteSelector.elmCanProtocol(can29Bit, bitrate)
    fun clearFilter(can29Bit: Boolean): String = if (can29Bit) "00000000" else "000"

    fun setupCommands(can29Bit: Boolean, stn: Boolean, bitrate: Int = 500_000): List<String> = buildList {
        require(bitrate != DiagnosticRouteSelector.SW_CAN_BITRATE || stn)
        add(protocolCommand(can29Bit, bitrate))
        addAll(listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATCAF0"))
        if (stn) {
            if (bitrate == DiagnosticRouteSelector.SW_CAN_BITRATE)
                addAll(listOf("STCMM 0", "STCSEGR 0", "STCSEGT 0"))
            val filter = clearFilter(can29Bit)
            addAll(listOf("ATCF $filter", "ATCM $filter", "STFAC"))
        }
    }

    fun restoreCommands(stn: Boolean, protocol: Int = 6): List<String> = buildList {
        if (stn) add("ATAR")
        addAll(ElmObdSessionProfile(protocol).restoreCommands())
    }
}

object CanConsoleCapabilityPolicy {
    /**
     * A CAN TX confirmation authorizes only the session in which it opened.
     * A newly connected adapter is not authority to replay a stale dialog.
     */
    fun canConfirmPhysicalTx(
        frame: RawCanFrame?,
        pendingContextGeneration: Long,
        currentContextGeneration: Long,
        monitorAvailable: Boolean,
        canTransmit: Boolean,
        busy: Boolean
    ): Boolean = frame != null &&
        pendingContextGeneration == currentContextGeneration &&
        monitorAvailable && canTransmit && !busy &&
        SafeRawCanTransmitPolicy.allows(frame)

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
