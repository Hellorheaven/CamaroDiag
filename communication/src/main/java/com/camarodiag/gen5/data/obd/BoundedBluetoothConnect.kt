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
    ) {
        require(timeoutMillis > 0)
        val opened = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { runCatching(close) }
                Dispatchers.IO.dispatch(continuation.context, Runnable {
                    if (!continuation.isActive) return@Runnable
                    continuation.resumeWith(runCatching(connect))
                })
            }
            true
        }
        if (opened != true) throw SocketTimeoutException("Bluetooth RFCOMM connection timed out")
    }
}
