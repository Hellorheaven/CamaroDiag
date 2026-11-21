package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * UDS payload transport for documented STN/OBDLink adapters using STPX.
 *
 * STPX and STCSEGR/STCSEGT are implemented by the adapter firmware: it performs ISO-TP
 * segmentation and reassembly internally. This is deliberately not exposed as raw CAN.
 */
class StnUdsPayloadTransport(
    private val requestCanId: Int,
    private val responseCanId: Int,
    private val protocolNumber: Int = 33,
    private val sendCommand: suspend (String, Long) -> String
) : UdsPayloadTransport {
    private val mutex = Mutex()
    private var configured = false

    init {
        val extended = protocolNumber in setOf(34, 36, 64)
        val maxId = if (extended) 0x1FFFFFFF else 0x7FF
        require(requestCanId in 0..maxId && responseCanId in 0..maxId && requestCanId != responseCanId)
        require(protocolNumber in setOf(33, 34, 35, 36, 63, 64)) {
            "STN protocol must be 33/34 (HS-CAN 500 kbit/s), 35/36 (HS-CAN 250 kbit/s), or 63/64 (SW-CAN 33.3 kbit/s)."
        }
        require(extended == (requestCanId > 0x7FF && responseCanId > 0x7FF)) {
            "CAN identifier format does not match the selected STN protocol."
        }
    }

    override suspend fun exchange(payload: ByteArray, timeoutMs: Long): ByteArray = mutex.withLock {
        require(payload.isNotEmpty() && payload.size <= 4095)
        ensureConfigured(timeoutMs)
        val command = "STPX h:${formatId(requestCanId)}, d:${payload.toHex()}, t:${timeoutMs.coerceIn(1L, 65535L)}, r:1"
        var response = parseResponse(sendCommand(command, timeoutMs), responseCanId)
        var pendingCount = 0
        while (isResponsePending(response, payload[0])) {
            if (++pendingCount > 5) error("UDS ResponsePending limit exceeded.")
            val monitor = sendCommand("STMA 1", maxOf(timeoutMs, 10_000L))
            response = parseResponse(monitor, responseCanId)
        }
        response
    }

    override suspend fun sendOnly(payload: ByteArray, timeoutMs: Long) = mutex.withLock {
        require(payload.isNotEmpty() && payload.size <= 4095)
        ensureConfigured(timeoutMs)
        val command = "STPX h:${formatId(requestCanId)}, d:${payload.toHex()}, t:${timeoutMs.coerceIn(1L, 65535L)}, r:0"
        val result = sendCommand(command, timeoutMs)
        if (result.contains("?", ignoreCase = true) || result.contains("CAN ERROR", ignoreCase = true)) {
            error("STN adapter rejected the UDS no-response transmission: $result")
        }
    }

    private suspend fun ensureConfigured(timeoutMs: Long) {
        if (configured) return
        val firmware = sendCommand("STI", timeoutMs)
        val hardware = sendCommand("STDI", timeoutMs)
        // STPX/ISO-TP is a family feature, not an MX+-only feature.
        // Detect documented OBDLink models by complete token so e.g. an
        // "OBDLink MX+ clone" cannot be mistaken for an unrelated model.
        // These identifiers are sufficient for the diagnostic transport only;
        // an adapter string can NEVER authorize ECU erasure/programming.
        val model = Regex("""(?i)\bOBDLink\s+(MX\+|MX|EX|CX|LX|SX)(?=\s|$)""")
            .find(hardware)?.groupValues?.get(1)?.uppercase(Locale.ROOT)
        require(firmware.contains("STN", true) && model != null) {
            "An identified OBDLink STN adapter is required for STPX diagnostics. Detected: $hardware / $firmware"
        }
        // Only the MX / MX+ families expose GM single-wire CAN in the
        // published manufacturer compatibility matrix. EX has Ford MS-CAN,
        // which is a different bus and must not be confused with GM SW-CAN.
        if (protocolNumber == 63 || protocolNumber == 64) {
            require(model == "MX+" || model == "MX") {
                "GM SW-CAN cannot be selected on OBDLink $model; use supported hardware."
            }
        }
        requireOk(sendCommand("STP $protocolNumber", timeoutMs), "select CAN protocol")
        requireOk(sendCommand("ATH1", timeoutMs), "enable CAN headers")
        requireOk(sendCommand("ATS1", timeoutMs), "enable separated output")
        requireOk(sendCommand("ATD0", timeoutMs), "disable DLC output")
        requireOk(sendCommand("STCAF 0", timeoutMs), "select normal CAN addressing")
        requireOk(sendCommand("STCSEGR 1", timeoutMs), "enable ISO-TP receive reassembly")
        requireOk(sendCommand("STCSEGT 1", timeoutMs), "enable ISO-TP transmit segmentation")
        requireOk(
            sendCommand("STCFCPA ${formatId(requestCanId)}, ${formatId(responseCanId)}", timeoutMs),
            "configure ISO-TP flow-control address pair"
        )
        requireOk(sendCommand("ATCRA ${formatId(responseCanId)}", timeoutMs), "filter ECU response CAN ID")
        configured = true
    }

    private fun requireOk(response: String, operation: String) {
        require(response.contains("OK", true)) { "STN adapter failed to $operation: $response" }
    }

    private fun isResponsePending(response: ByteArray, requestedService: Byte): Boolean =
        response.size >= 3 &&
            (response[0].toInt() and 0xFF) == 0x7F &&
            (response[1] == requestedService) &&
            (response[2].toInt() and 0xFF) == 0x78

    private fun parseResponse(output: String, expectedCanId: Int): ByteArray {
        require(!output.contains("NO DATA", true)) { "No UDS response from CAN ID ${formatId(expectedCanId)}." }
        require(!output.contains("CAN ERROR", true) && !output.contains("BUS ERROR", true)) {
            "STN adapter reported a CAN bus error: $output"
        }
        val lines = output.replace("\r", "\n").lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            val tokens = line.split(Regex("\\s+"))
            if (tokens.isEmpty()) continue
            val header = tokens.first().removePrefix("0X")
            val hasHeader = (header.length == 3 || header.length == 8) &&
                header.all { it.digitToIntOrNull(16) != null }
            if (hasHeader) {
                val receivedId = header.toLongOrNull(16)?.toInt() ?: continue
                if (receivedId != expectedCanId) continue
            }
            val dataTokens = if (hasHeader) tokens.drop(1) else tokens
            val bytes = dataTokens.filter { it.length == 2 && it.all { ch -> ch.digitToIntOrNull(16) != null } }
            if (bytes.isNotEmpty()) return bytes.map { it.toInt(16).toByte() }.toByteArray()
        }
        error("Could not decode a complete UDS response for CAN ID ${formatId(expectedCanId)}: $output")
    }

    private fun formatId(id: Int): String =
        if (id > 0x7FF) id.toString(16).uppercase(Locale.ROOT).padStart(8, '0')
        else id.toString(16).uppercase(Locale.ROOT).padStart(3, '0')

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }
}
