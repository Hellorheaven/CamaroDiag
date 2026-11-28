package com.camarodiag.gen5.data.obd

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Distinguishes a broken transport from an incomplete command response.
 *
 * ELM prompt timeouts can occur while the adapter remains connected, so they
 * must not trigger the app-wide physical disconnect flow. Timeout causes may
 * be wrapped by transport implementations, therefore inspect the full cause chain.
 */
/** A failure of the app's local CAN archive, not an ECU or adapter link loss. */
class CanCaptureArchiveWriteException(cause: IOException) :
    IllegalStateException(cause)

object ObdTransportFailurePolicy {
    fun isConnectionLoss(error: Throwable): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var hasIoFailure = false
        var hasUnsafeAdapterState = false
        var hasExplicitDisconnect = false
        var hasPromptTimeout = false
        while (current != null && visited.add(current)) {
            // A late failure from an already replaced connection is not a
            // failure of the currently connected physical adapter.
            if (current is ObdSupersededSessionException) return false
            if (current is CanCaptureArchiveWriteException) return false
            if (current is SocketTimeoutException) hasPromptTimeout = true
            if (current is ObdTransportDisconnectedException) hasExplicitDisconnect = true
            if (current is IOException) hasIoFailure = true
            if (current is CanAdapterRestorationException) hasUnsafeAdapterState = true
            current = current.cause
        }
        // A timeout from an uncertain write can explicitly retire a socket.
        // A plain prompt timeout, even wrapped in IOException, is different.
        return hasExplicitDisconnect || hasUnsafeAdapterState || (hasIoFailure && !hasPromptTimeout)
    }
}
