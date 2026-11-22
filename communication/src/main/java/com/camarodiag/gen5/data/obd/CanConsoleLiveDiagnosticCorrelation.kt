package com.camarodiag.gen5.data.obd

/**
 * Per-physical-command correlation driven by actual receive-chunk arrival times.
 *
 * The passive CAN monitor still records ALL ECU frames independently. This
 * engine never suppresses or re-labels traffic and never invents ECU replies.
 * Chunk timestamps are monotonic host receipt times, not claimed bus times.
 */
/** A decoded adapter line with a host receive timestamp, never a simulated ECU timestamp. */
data class CanConsoleTimedRecord(
    val record: ParsedCanConsoleRecord,
    val timestampMillis: Long
)

/** Physical adapter output plus a reply validated within its receive-time session. */
data class CanConsoleRawTransmissionResult(
    val adapterOutput: String,
    val diagnosticStatus: CanConsoleDiagnosticStatus,
    /** Recent received adapter records; never truncated by the 1 KiB ELM ACK buffer. */
    val receivedRecords: List<CanConsoleTimedRecord> = emptyList()
)

/**
 * A physical request can fail after RX frames have already arrived. Keep
 * those observations separate from diagnostic completion and preserve the
 * original cause so transport-disconnect handling still works.
 */
class CanConsolePhysicalExchangeException(
    val receivedRecords: List<CanConsoleTimedRecord>,
    cause: Throwable
) : IllegalStateException(cause)

class CanConsoleLiveDiagnosticCorrelation(
    private val request: RawCanFrame,
    private val timeoutMs: Long = 1_000L
) {
    init { require(timeoutMs > 0) }

    private val framer = CanConsoleStreamFramer()
    private val recentRecords = java.util.ArrayDeque<CanConsoleTimedRecord>()
    private val perResponder = mutableMapOf<Long, CanConsoleDiagnosticCorrelation>()
    private var diagnosticStatus = CanConsoleDiagnosticStatus.NONE
    private var adapterAckSeen = false
    private var adapterErrorSeen = false

    fun append(chunk: String, receivedAtMs: Long, receivedAtWallClockMs: Long = receivedAtMs) {
        evaluate(framer.append(chunk).completeLines, receivedAtMs, receivedAtWallClockMs)
    }

    /** Every received record is decoded, then the UI's bounded history keeps the latest 300. */
    fun recentReceivedRecords(): List<CanConsoleTimedRecord> = recentRecords.toList()
    fun hasAdapterError(): Boolean = adapterErrorSeen
    fun hasExplicitAdapterAck(): Boolean = adapterAckSeen && !adapterErrorSeen

    /** Called after the adapter command completes to process a final unterminated line. */
    fun complete(receivedAtMs: Long, receivedAtWallClockMs: Long = receivedAtMs): CanConsoleDiagnosticStatus {
        evaluate(framer.drainTrailing().completeLines, receivedAtMs, receivedAtWallClockMs)
        return diagnosticStatus
    }

    private fun evaluate(lines: String, receivedAtMs: Long, receivedAtWallClockMs: Long) {
        for (line in lines.lineSequence().map { it.trim().removeSuffix(">").trim() }) {
            if (line.isEmpty()) continue
            if (line.equals("OK", ignoreCase = true)) adapterAckSeen = true
            if (CanConsoleAdapterReplyPolicy.isFailure(line)) adapterErrorSeen = true
        }
        for (record in CanConsoleRecordParser.parse(lines)) {
            recentRecords.addLast(CanConsoleTimedRecord(record, receivedAtWallClockMs))
            if (recentRecords.size > 300) recentRecords.removeFirst()
        }
        for (frame in RawCanFrameParser.parseReceivedFrames(lines)) {
            val matcher = if (!request.isExtended && request.canId == 0x7DFL) {
                if (frame.isExtended || frame.canId !in 0x7E8L..0x7EFL) continue
                perResponder.getOrPut(frame.canId) {
                    CanConsoleDiagnosticCorrelation(
                        request.copy(canId = frame.canId - 8L), timeoutMs
                    )
                }
            } else {
                if (!CanConsoleEcuRouting.isSelectedResponder(request, frame)) continue
                perResponder.getOrPut(frame.canId) {
                    CanConsoleDiagnosticCorrelation(request, timeoutMs)
                }
            }
            val response = matcher.accept(frame, receivedAtMs) ?: continue
            if ((response[0].toInt() and 0xFF) == 0x7F) {
                if (diagnosticStatus == CanConsoleDiagnosticStatus.NONE)
                    diagnosticStatus = CanConsoleDiagnosticStatus.NEGATIVE
            } else {
                diagnosticStatus = CanConsoleDiagnosticStatus.POSITIVE
            }
        }
    }
}
