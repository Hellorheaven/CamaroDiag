package com.camarodiag.gen5.data.obd

/**
 * Per-physical-command correlation driven by actual receive-chunk arrival times.
 *
 * The passive CAN monitor still records ALL ECU frames independently. This
 * engine never suppresses or re-labels traffic and never invents ECU replies.
 * Chunk timestamps are monotonic host receipt times, not claimed bus times.
 */
class CanConsoleLiveDiagnosticCorrelation(
    private val request: RawCanFrame,
    private val timeoutMs: Long = 1_000L
) {
    init { require(timeoutMs > 0) }

    private val framer = CanConsoleStreamFramer()
    private val perResponder = mutableMapOf<Long, CanConsoleDiagnosticCorrelation>()
    private var diagnosticStatus = CanConsoleDiagnosticStatus.NONE

    fun append(chunk: String, receivedAtMs: Long) {
        evaluate(framer.append(chunk).completeLines, receivedAtMs)
    }

    /** Called after the adapter command completes to process a final unterminated line. */
    fun complete(receivedAtMs: Long): CanConsoleDiagnosticStatus {
        evaluate(framer.drainTrailing().completeLines, receivedAtMs)
        return diagnosticStatus
    }

    private fun evaluate(lines: String, receivedAtMs: Long) {
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
