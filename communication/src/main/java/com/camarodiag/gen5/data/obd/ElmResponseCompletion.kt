package com.camarodiag.gen5.data.obd

import java.net.SocketTimeoutException

/**
 * Keeps ordinary ELM commands from exposing a partial response as complete.
 * Streaming monitor commands are intentionally exempt because they terminate
 * through an explicit stop command rather than the normal ELM prompt.
 */
internal object ElmResponseCompletion {
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
