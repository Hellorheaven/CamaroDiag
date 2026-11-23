package com.camarodiag.gen5.data.obd

/**
 * Validates a Windows bridge connection form without touching an active adapter.
 * The transport independently revalidates TLS pinning, CAN profile and LAN scope
 * before network or ECU access. This object sends no commands.
 */
object J2534BridgeConnectionForm {
    data class Validated(
        val host: String,
        val port: Int,
        val pairingCode: String,
        val requestCanId: Int,
        val responseCanId: Int,
        val can29Bit: Boolean,
        val protocolNumber: Int
    )

    fun parse(
        hostText: String,
        portText: String,
        pairingCodeText: String,
        requestCanIdText: String,
        responseCanIdText: String,
        protocolText: String
    ): Validated {
        val host = hostText.trim()
        val parts = host.split('.')
        require(parts.size == 4 && parts.all { it.isNotBlank() &&
            it.length <= 3 && it.all(Char::isDigit) &&
            (it.length == 1 || it.first() != '0') }) {
            "Bridge host must be a canonical numeric LAN IPv4 address"
        }
        val octets = parts.map {
            it.toIntOrNull()?.takeIf { n -> n in 0..255 }
                ?: throw IllegalArgumentException("Invalid bridge IPv4 octet")
        }
        require(octets[0] == 10 ||
            octets[0] == 172 && octets[1] in 16..31 ||
            octets[0] == 192 && octets[1] == 168 ||
            octets[0] == 169 && octets[1] == 254) {
            "Bridge IP must belong to a private LAN network"
        }

        val port = portText.trim().toIntOrNull()
        require(port != null && port in 1024..65535) { "Bridge port is invalid" }
        val pairing = pairingCodeText.trim()
        require(J2534BridgePairingCodeFormat.isValid(pairing)) {
            "A certificate-pinned bridge pairing code is required"
        }

        fun canId(raw: String): Pair<Int, Int> {
            val normalized = raw.trim().removePrefix("0x").removePrefix("0X")
            require(normalized.length == 3 || normalized.length == 8) {
                "CAN ID must use exactly 3 or 8 hexadecimal digits"
            }
            val number = normalized.toLongOrNull(16)
            val width = normalized.length
            val maximum = if (width == 3) 0x7FFL else 0x1FFFFFFFL
            require(number != null && number in 0..maximum) {
                "CAN identifier is outside its declared width"
            }
            return number.toInt() to width
        }
        val (request, requestWidth) = canId(requestCanIdText)
        val (response, responseWidth) = canId(responseCanIdText)
        require(requestWidth == responseWidth && request != response) {
            "Bridge CAN request and response identifiers must be distinct and use the same width"
        }
        val protocol = protocolText.trim().toIntOrNull()
        require(protocol != null && protocol in setOf(33, 34, 35, 36, 63, 64)) {
            "Unsupported J2534 connection profile"
        }
        val extended = requestWidth == 8
        require(extended == (protocol in setOf(34, 36, 64))) {
            "CAN identifier width does not match the J2534 profile"
        }
        return Validated(host, port, pairing, request, response, extended, protocol)
    }
}
