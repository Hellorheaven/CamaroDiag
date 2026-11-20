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
object ObdTransportFailurePolicy {
    fun isConnectionLoss(error: Throwable): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var hasIoFailure = false
        while (current != null && visited.add(current)) {
            if (current is SocketTimeoutException) return false
            if (current is IOException) hasIoFailure = true
            current = current.cause
        }
        return hasIoFailure
    }
}
