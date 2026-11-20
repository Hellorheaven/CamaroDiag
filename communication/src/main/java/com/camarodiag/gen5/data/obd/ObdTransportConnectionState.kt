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
