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

/** On a newly opened RFCOMM stream, discard residual adapter output before
 * sending ATZ. A previous monitor may still be producing data. Interrupt it
 * once with ESC (never an empty CR, which can repeat the previous command).
 * Require a prompt after interruption and a quiet boundary before proceeding.
 * This routine never sends a vehicle diagnostic request. */
internal object ElmStartupSynchronization {
    suspend fun synchronize(
        available: () -> Int,
        read: (Int) -> ByteArray,
        interrupt: suspend () -> Unit,
        ensureCurrent: () -> Unit,
        clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
        pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
    ) {
        val started = clockMs()
        var lastData = started
        var bytes = 0
        var interrupted = false
        var promptAfterInterrupt = false
        while (true) {
            ensureCurrent()
            val now = clockMs()
            if (now - started >= 2000L || bytes >= 65536)
                throw SocketTimeoutException("ELM startup stream did not become idle")
            val queued = available()
            require(queued >= 0)
            if (queued > 0) {
                val count = minOf(queued, 256, 65536 - bytes)
                val chunk = read(count)
                ensureCurrent()
                if (chunk.isEmpty()) throw java.io.EOFException("ELM startup stream closed")
                require(chunk.size <= count)
                bytes += chunk.size
                lastData = clockMs()
                if (interrupted && chunk.any { it == '>'.code.toByte() }) promptAfterInterrupt = true
                if (!interrupted && (bytes >= 512 || lastData - started >= 150L)) {
                    ensureCurrent()
                    interrupt()
                    interrupted = true
                    lastData = clockMs()
                }
                // Avoid starving cancellation even when data arrives continuously.
                pause(1L)
            } else {
                if ((!interrupted || promptAfterInterrupt) && now - lastData >= 75L) return
                pause(10L)
            }
        }
    }
}
