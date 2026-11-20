package com.camarodiag.gen5.data.obd

/**
 * Keeps only the most recent CAN monitor text in memory during an open-ended capture.
 * Hitting the buffer limit never ends the capture or interrupts adapter cleanup.
 */
internal class CanCaptureTextBuffer(private val maxChars: Int = 131_072) {
    init {
        require(maxChars > 0) { "CAN capture buffer capacity must be positive." }
    }

    private val text = StringBuilder()

    fun append(chunk: String) {
        if (chunk.length >= maxChars) {
            text.clear()
            text.append(chunk.takeLast(maxChars))
            return
        }
        val overflow = text.length + chunk.length - maxChars
        if (overflow > 0) text.delete(0, overflow)
        text.append(chunk)
    }

    fun snapshot(): String = text.toString()
}
