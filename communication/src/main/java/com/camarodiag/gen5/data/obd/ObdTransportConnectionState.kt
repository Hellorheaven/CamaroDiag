package com.camarodiag.gen5.data.obd

/** Pure availability policy shared by the OBD manager and its unit tests. */
internal object ObdTransportConnectionState {
    fun isCommandChannelAvailable(
        isDemoMode: Boolean,
        isSimulationActive: Boolean,
        activeTransport: String,
        classicSocketConnected: Boolean = false,
        classicSocketClosed: Boolean = true,
        bleConnected: Boolean = false,
        usbConnected: Boolean = false,
        wifiConnected: Boolean = false
    ): Boolean {
        if (isDemoMode || isSimulationActive) return false
        return when (activeTransport) {
            "BLUETOOTH" -> classicSocketConnected && !classicSocketClosed
            "BLE" -> bleConnected
            "USB" -> usbConnected
            "WIFI" -> wifiConnected
            else -> false
        }
    }
}


/** Signals a physical adapter disconnect to the app-level connection monitor. */
class ObdTransportDisconnectedException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

/**
 * Invalidates work started by an older transport session, including a rapid
 * disconnect/reconnect where both sessions use the same transport family.
 */
internal class ObdTransportSessionEpoch {
    private val value = java.util.concurrent.atomic.AtomicLong(0L)

    fun capture(): Long = value.get()

    fun invalidate(): Long = value.incrementAndGet()

    fun isCurrent(captured: Long): Boolean = value.get() == captured

    fun requireCurrent(captured: Long) {
        if (value.get() != captured) {
            throw ObdTransportDisconnectedException("OBD connection changed during command")
        }
    }
}

/** A response from a replaced connection must never disconnect its successor. */
class ObdSupersededSessionException(cause: Throwable) :
    java.io.IOException("An earlier OBD connection was replaced", cause)
