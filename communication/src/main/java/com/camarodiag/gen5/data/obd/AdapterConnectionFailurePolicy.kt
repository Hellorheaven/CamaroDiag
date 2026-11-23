package com.camarodiag.gen5.data.obd

/**
 * UI-facing classification of non-protocol adapter connection failures.
 * Never interpret Android USB permission handoff as a connected adapter.
 */
object AdapterConnectionFailurePolicy {
    enum class Kind { USB_PERMISSION_REQUIRED, CONNECTION_FAILED }

    fun classify(error: Throwable): Kind =
        if (error is UsbPermissionPendingException) Kind.USB_PERMISSION_REQUIRED
        else Kind.CONNECTION_FAILED
}
