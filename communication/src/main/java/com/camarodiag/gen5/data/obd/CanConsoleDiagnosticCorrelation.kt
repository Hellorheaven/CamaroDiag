package com.camarodiag.gen5.data.obd

/**
 * Read-only correlation for a single physical diagnostic request and transport session.
 * The CAN monitor is independent and must always display all observed frames.
 *
 * Supports normal-addressed Classic CAN ISO-TP. Flow-control frames are never
 * treated as diagnostic payloads; transport owns flow control and timing.
 */
class CanConsoleDiagnosticCorrelation(
    private val request: RawCanFrame,
    private val timeoutMs: Long = 1_000L
) {
    private val requestPayload: ByteArray
    private val expectedResponder: Long
    private var expectedSize = 0
    private var nextSequence = 1
    private var startedAt = 0L
    private var pending = ByteArray(0)
    private var completed = false

    init {
        require(timeoutMs > 0)
        expectedResponder = requireNotNull(CanConsoleEcuRouting.expectedResponder(request)) {
            "Diagnostic correlation requires a known physical ECU address."
        }
        val bytes = request.data
        require(bytes.size >= 2 && (bytes[0].toInt() and 0xF0) == 0) {
            "Expected a single-frame diagnostic request."
        }
        val length = bytes[0].toInt() and 0x0F
        require(length in 1 until bytes.size) { "Invalid diagnostic request length." }
        requestPayload = bytes.copyOfRange(1, length + 1)
    }

    /** Returns a fully validated response only when ISO-TP assembly completes. */
    fun accept(frame: RawCanFrame, nowMs: Long): ByteArray? {
        if (completed || !CanConsoleEcuRouting.isSelectedResponder(request, frame) ||
            frame.isExtended != request.isExtended) return null
        val bytes = frame.data
        if (bytes.isEmpty()) return null
        if (expectedSize != 0 && (nowMs < startedAt || nowMs - startedAt >= timeoutMs)) {
            reset()
            return null
        }
        return when ((bytes[0].toInt() and 0xF0) ushr 4) {
            0 -> {
                if (expectedSize != 0) { reset(); return null }
                val length = bytes[0].toInt() and 0x0F
                if (length < 1 || length > bytes.size - 1) return null
                validate(bytes.copyOfRange(1, length + 1))
            }
            1 -> {
                if (expectedSize != 0 || bytes.size != 8) { reset(); return null }
                val length = ((bytes[0].toInt() and 0x0F) shl 8) or (bytes[1].toInt() and 0xFF)
                if (length <= 7 || length > 4095) return null
                expectedSize = length
                pending = bytes.copyOfRange(2, bytes.size)
                startedAt = nowMs
                nextSequence = 1
                null
            }
            2 -> {
                if (expectedSize == 0 || bytes.size < 2 ||
                    (bytes[0].toInt() and 0x0F) != nextSequence) {
                    reset()
                    return null
                }
                val remain = expectedSize - pending.size
                if (remain > bytes.size - 1 && bytes.size != 8) {
                    reset()
                    return null
                }
                val count = minOf(remain, bytes.size - 1)
                pending += bytes.copyOfRange(1, count + 1)
                nextSequence = (nextSequence + 1) and 0x0F
                if (pending.size == expectedSize) {
                    val data = pending
                    reset()
                    validate(data)
                } else null
            }
            else -> null
        }
    }

    private fun validate(response: ByteArray): ByteArray? {
        val service = requestPayload[0].toInt() and 0xFF
        if (response.isEmpty()) return null
        val negative = response.size == 3 &&
            (response[0].toInt() and 0xFF) == 0x7F &&
            (response[1].toInt() and 0xFF) == service
        val positive = (response[0].toInt() and 0xFF) == service + 0x40 &&
            (requestPayload.size == 1 || response.size >= requestPayload.size &&
                requestPayload.drop(1).indices.all { i ->
                    response[i + 1] == requestPayload[i + 1]
                })
        if (!negative && !positive) return null
        completed = true
        return response.copyOf()
    }

    fun reset() {
        expectedSize = 0
        nextSequence = 1
        pending = ByteArray(0)
        startedAt = 0L
    }
}
