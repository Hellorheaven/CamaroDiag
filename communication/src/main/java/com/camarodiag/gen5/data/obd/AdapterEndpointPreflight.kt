package com.camarodiag.gen5.data.obd

/**
 * Input-only adapter selection checks. Malformed UI input must be rejected
 * before the caller disconnects a functioning USB/OBD/PC bridge session.
 * The actual transports still validate their own connection and permissions.
 */
object AdapterEndpointPreflight {
    data class Wifi(val host: String, val port: Int)

    fun wifi(hostText: String, port: Int): Wifi {
        val host = hostText.trim()
        require(port in 1..65535 &&
            host.isNotEmpty() && host.length <= 253 &&
            host.none { it.isWhitespace() || it.isISOControl() } &&
            !host.contains('/') && !host.contains('\\') && !host.contains('@') &&
            !host.contains('#') && !host.contains('?') && !host.contains(':')) {
            "Invalid Wi-Fi OBD host or TCP port"
        }
        return Wifi(host, port)
    }

    fun bluetoothMac(address: String): String {
        val normalized = address.trim().uppercase(java.util.Locale.ROOT)
        require(Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}").matches(normalized)) {
            "Invalid Bluetooth adapter address"
        }
        return normalized
    }
}
