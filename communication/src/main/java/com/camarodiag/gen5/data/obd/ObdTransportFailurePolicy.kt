package com.camarodiag.gen5.data.obd

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Distinguishes a broken transport from an incomplete command response.
 *
 * ELM prompt timeouts can occur while the adapter remains connected, so they
 * must not trigger the app-wide physical disconnect flow.
 */
object ObdTransportFailurePolicy {
    fun isConnectionLoss(error: Throwable): Boolean =
        error is IOException && error !is SocketTimeoutException
}
