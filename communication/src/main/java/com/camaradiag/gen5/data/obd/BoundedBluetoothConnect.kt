package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException

/** A blocking RFCOMM open is interrupted by closing its own socket. */
internal object BoundedBluetoothConnect {
    suspend fun open(
        timeoutMillis: Long = 12_000L,
        connect: () -> Unit,
        close: () -> Unit
    ) = bounded(timeoutMillis, connect, close, "Bluetooth RFCOMM connection timed out")

    /** A blocked native write cannot be cancelled by a coroutine alone.
     * Interrupt the exact socket, never retry the possibly emitted command. */
    suspend fun write(timeoutMillis: Long, write: () -> Unit, close: () -> Unit) {
        try {
            bounded(timeoutMillis, write, close, "Bluetooth RFCOMM write timed out")
        } catch (timeout: SocketTimeoutException) {
            // A write timeout differs from a prompt timeout: the socket was
            // retired and the command's transmission outcome is uncertain.
            throw ObdTransportDisconnectedException("Bluetooth RFCOMM write timed out; reconnect the adapter", timeout)
        }
    }

    private suspend fun bounded(timeoutMillis: Long, operation: () -> Unit,
        close: () -> Unit, timeoutMessage: String) {
        require(timeoutMillis > 0)
        val opened = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { runCatching(close) }
                Dispatchers.IO.dispatch(continuation.context, Runnable {
                    if (!continuation.isActive) return@Runnable
                    continuation.resumeWith(runCatching(operation))
                })
            }
            true
        }
        if (opened != true) throw SocketTimeoutException(timeoutMessage)
    }
}
