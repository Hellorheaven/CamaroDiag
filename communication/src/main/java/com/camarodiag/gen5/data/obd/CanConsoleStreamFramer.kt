package com.camarodiag.gen5.data.obd

/**
 * Incremental line framing for physical ELM/STN CAN monitor output.
 *
 * TCP, USB and BLE chunk boundaries are arbitrary. Complete CAN records
 * must be emitted before any partial-line memory bound is applied. A single
 * unterminated oversized line is dropped, never truncated into a plausible
 * fabricated ECU frame. Raw capture archiving is independent of this parser.
 */
class CanConsoleStreamFramer(private val maxLineChars: Int = 4096) {
    init { require(maxLineChars > 0) }

    data class Batch(val completeLines: String, val discardedLines: Int)

    private val incomplete = StringBuilder()
    private var droppingOversizedLine = false

    fun append(chunk: String): Batch {
        val complete = StringBuilder()
        var discarded = 0
        for (ch in chunk) {
            if (ch == '\r' || ch == '\n') {
                if (droppingOversizedLine) {
                    discarded++
                    droppingOversizedLine = false
                } else if (incomplete.isNotEmpty()) {
                    complete.append(incomplete).append('\n')
                }
                incomplete.clear()
            } else if (!droppingOversizedLine) {
                if (incomplete.length < maxLineChars) incomplete.append(ch)
                else {
                    // Never treat the last N bytes of a malformed record as CAN RX.
                    incomplete.clear()
                    droppingOversizedLine = true
                }
            }
        }
        return Batch(complete.toString(), discarded)
    }

    /** Call only when monitor mode has stopped and the stream is closed. */
    fun drainTrailing(): Batch {
        val result = Batch(
            if (droppingOversizedLine) "" else incomplete.toString(),
            if (droppingOversizedLine) 1 else 0
        )
        reset()
        return result
    }

    fun reset() {
        incomplete.clear()
        droppingOversizedLine = false
    }
}
