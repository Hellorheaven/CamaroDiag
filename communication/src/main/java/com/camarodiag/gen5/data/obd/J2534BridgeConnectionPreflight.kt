package com.camarodiag.gen5.data.obd

/**
 * Pure validation before replacing a working adapter with a Windows bridge.
 * This does not open TCP, consult J2534 drivers or grant any ECU capabilities.
 */
internal object J2534BridgeConnectionPreflight {
    fun requireValid(
        host: String,
        port: Int,
        pairingCode: String,
        requestCanId: Int,
        responseCanId: Int,
        can29Bit: Boolean,
        protocolNumber: Int
    ) {
        require(port in 1024..65535) { "Invalid J2534 LAN bridge port" }
        val pairing = J2534BridgeTlsPairing.parse(pairingCode)
        pairing.localBridgeIpv4(host) // numeric private LAN only; no DNS lookup.
        require(protocolNumber in setOf(33, 34, 35, 36, 63, 64)) {
            "Unsupported J2534 physical connection profile"
        }
        val maxId = if (can29Bit) 0x1FFFFFFF else 0x7FF
        require(requestCanId in 0..maxId && responseCanId in 0..maxId &&
            requestCanId != responseCanId) {
            "Invalid J2534 request and response CAN identifiers"
        }
        require(can29Bit == (protocolNumber in setOf(34, 36, 64))) {
            "J2534 CAN identifier width does not match the selected profile"
        }
    }
}
