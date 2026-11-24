package com.camarodiag.gen5.data.obd

/** Input check before closing a working adapter for a new USB selection. */
internal object UsbAdapterConnectParameters {
    fun requireValid(deviceId: Int, baudRate: Int) {
        require(deviceId >= 0) { "Android USB device identifier is invalid" }
        require(baudRate > 0) { "USB serial baud rate must be positive" }
    }
}
