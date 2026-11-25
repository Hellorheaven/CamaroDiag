package com.camarodiag.gen5.data.obd

import java.net.SocketTimeoutException

/**
 * Keeps ordinary ELM commands from exposing a partial response as complete.
 * Streaming monitor commands are intentionally exempt because they terminate
 * through an explicit stop command rather than the normal ELM prompt.
 */
internal object ElmResponseCompletion {
    fun isMonitorInterrupt(command: String): Boolean = command == "\u001B"

    /** Interrupt streaming with one byte, never an additional CR that could
     * repeat the previous monitor command. Ordinary commands still need CR. */
    fun wireBytes(command: String): ByteArray =
        (if (isMonitorInterrupt(command)) command else command.trim() + "\r")
            .toByteArray(Charsets.US_ASCII)

    /** Only true monitor commands may terminate without the normal ELM prompt.
     * A receive callback on a one-shot CAN transmission is NOT monitor mode. */
    fun isUnterminatedMonitorCommand(command: String, hasChunkConsumer: Boolean): Boolean =
        hasChunkConsumer && command.trim().uppercase(java.util.Locale.ROOT) in setOf("ATMA", "STM")

    fun requirePrompt(command: String, streaming: Boolean, promptReceived: Boolean) {
        if (!streaming && !promptReceived) {
            throw SocketTimeoutException(
                "Timed out waiting for the ELM prompt after command: $command"
            )
        }
    }
}

/** Monotonic deadline for the response phase of one ELM command.
 * A final USB/BLE/TCP read must not exceed the remaining poll budget;
 * zero milliseconds must never be passed to a blocking socket read. */
internal class ElmCommandReadBudget(
    private val timeoutMs: Long,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val started = clockMs()

    fun remainingMs(): Long = (timeoutMs - (clockMs() - started)).coerceAtLeast(0L)

    fun pollTimeoutMs(maxMs: Int): Int? {
        require(maxMs > 0)
        val remaining = remainingMs()
        return if (remaining == 0L) null else minOf(remaining, maxMs.toLong()).toInt()
    }
}
