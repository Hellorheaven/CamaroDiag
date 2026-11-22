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
/**
 * A sent CAN request is confirmed by an explicit clean adapter acknowledgement
 * or a fully correlated ECU diagnostic reply, never by unrelated bus traffic.
 * A streamed adapter error overrides any prior OK or valid ECU data.
 */
object CanConsolePhysicalTransmissionPolicy {
    fun confirmed(
        adapterOutput: String,
        diagnosticStatus: CanConsoleDiagnosticStatus,
        hasExplicitAck: Boolean,
        hasAdapterError: Boolean
    ): Boolean =
        !hasAdapterError && !CanConsoleAdapterReplyPolicy.isFailure(adapterOutput) &&
            // Only a timestamp-validated stream ACK can confirm TX. The
            // adapterOutput is truncated and has no individual receive times.
            (hasExplicitAck || diagnosticStatus != CanConsoleDiagnosticStatus.NONE)
}

class CanConsolePhysicalExchangeException(
    val receivedRecords: List<CanConsoleTimedRecord>,
    cause: Throwable
) : IllegalStateException(cause) {
    /** A command-response timeout is not necessarily a disconnected adapter. */
    fun isResponseTimeout(): Boolean =
        generateSequence(this.cause) { it.cause }
            .take(16)
            .any { it is java.net.SocketTimeoutException }
}

/**
 * Cancellation preserves observed RX without converting cancellation into a
 * recoverable command failure. The UI may publish these records only while
 * the original request job still owns the active vehicle session.
 */
class CanConsolePhysicalExchangeCancelled(
    val receivedRecords: List<CanConsoleTimedRecord>,
    cause: java.util.concurrent.CancellationException
) : java.util.concurrent.CancellationException(cause.message) {
    init { initCause(cause) }
}

class CanConsoleLiveDiagnosticCorrelation(
    private val request: RawCanFrame,
    private val timeoutMs: Long = 1_000L,
    private val requestStartedAtMs: Long? = null,
    private val requestDeadlineMs: Long = 4_000L,
    /** Physical transport requires beginRequest before any diagnostic ownership. */
    private val requireExplicitStart: Boolean = false
) {
    init {
        require(timeoutMs > 0)
        require(requestDeadlineMs > 0)
    }

    private var startedAtMs: Long? = requestStartedAtMs

    /** Mark the actual TX command start, after the adapter's AT setup. */
    fun beginRequest(sentAtMs: Long) {
        require(startedAtMs == null) { "CAN request session already started." }
        // A partial CAN/OK line begun before physical TX must never be
        // completed by a later chunk and claimed by this request.
        // Completed pre-TX records remain in the global display history.
        framer.reset()
        startedAtMs = sentAtMs
    }

    private fun withinRequestSession(receivedAtMs: Long): Boolean {
        val started = startedAtMs ?: return !requireExplicitStart
        return receivedAtMs >= started && receivedAtMs - started < requestDeadlineMs
    }

    private val framer = CanConsoleStreamFramer()
    private val recentRecords = java.util.ArrayDeque<CanConsoleTimedRecord>()
    private val perResponder = mutableMapOf<Long, CanConsoleDiagnosticCorrelation>()
    private var diagnosticStatus = CanConsoleDiagnosticStatus.NONE
    private var adapterAckSeen = false
    private var adapterErrorSeen = false
    // The final unterminated line arrived with the last RX chunk, not when
    // the adapter command later returned or timed out.
    private var lastChunkAtMs: Long? = null
    private var lastChunkWallClockMs: Long? = null

    fun append(chunk: String, receivedAtMs: Long, receivedAtWallClockMs: Long = receivedAtMs) {
        if (chunk.isNotEmpty()) {
            lastChunkAtMs = receivedAtMs
            lastChunkWallClockMs = receivedAtWallClockMs
        }
        val batch = framer.append(chunk)
        // Discarding an oversized adapter line can conceal an error after OK.
        // A physical exchange with missing adapter data must fail closed.
        if (batch.discardedLines > 0 &&
            (startedAtMs?.let { receivedAtMs >= it } ?: !requireExplicitStart)) {
            adapterErrorSeen = true
        }
        evaluate(batch.completeLines, receivedAtMs, receivedAtWallClockMs)
    }

    /** Every received record is decoded, then the UI's bounded history keeps the latest 300. */
    fun recentReceivedRecords(): List<CanConsoleTimedRecord> = recentRecords.toList()
    fun hasAdapterError(): Boolean = adapterErrorSeen
    fun hasExplicitAdapterAck(): Boolean = adapterAckSeen && !adapterErrorSeen

    /** Called after the adapter command completes to process a final unterminated line. */
    fun complete(receivedAtMs: Long, receivedAtWallClockMs: Long = receivedAtMs): CanConsoleDiagnosticStatus {
        val trailingBatch = framer.drainTrailing()
        val trailingAtMs = lastChunkAtMs ?: receivedAtMs
        if (trailingBatch.discardedLines > 0 &&
            (startedAtMs?.let { trailingAtMs >= it } ?: !requireExplicitStart)) {
            adapterErrorSeen = true
        }
        if (trailingBatch.completeLines.isNotEmpty()) {
            evaluate(trailingBatch.completeLines, trailingAtMs,
                lastChunkWallClockMs ?: receivedAtWallClockMs)
        }
        return diagnosticStatus
    }

    private fun evaluate(lines: String, receivedAtMs: Long, receivedAtWallClockMs: Long) {
        for (line in lines.lineSequence().map { it.trim().removeSuffix(">").trim() }) {
            if (line.isEmpty()) continue
            if (line.equals("OK", ignoreCase = true) &&
                withinRequestSession(receivedAtMs)) adapterAckSeen = true
            // Pre-TX setup output is visible history, but cannot poison the
            // transmission that has not yet begun. Once TX has started, any
            // adapter failure remains authoritative (including a late one).
            // Legacy/implicit correlation starts with its first RX chunk; explicit
            // physical TX correlation must exclude pre-start adapter errors.
            val belongsToRequest = startedAtMs?.let { receivedAtMs >= it }
                ?: !requireExplicitStart
            if (belongsToRequest && CanConsoleAdapterReplyPolicy.isFailure(line))
                adapterErrorSeen = true
        }
        for (record in CanConsoleRecordParser.parse(lines)) {
            recentRecords.addLast(CanConsoleTimedRecord(record, receivedAtWallClockMs))
            if (recentRecords.size > 300) recentRecords.removeFirst()
        }
        for (frame in RawCanFrameParser.parseReceivedFrames(lines)) {
            // Display is independent of diagnostic ownership. Late or stale
            // frames remain in recentRecords above, but never complete a
            // physical request after its bounded transport session expires.
            if (!withinRequestSession(receivedAtMs)) continue
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
