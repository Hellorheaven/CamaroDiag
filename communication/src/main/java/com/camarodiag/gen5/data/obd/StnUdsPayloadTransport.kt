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
        check(isMatchingUdsService(response, payload[0])) {
            "STN received a response to a different UDS service."
        }
        var pendingCount = 0
        while (isResponsePending(response, payload[0])) {
            if (++pendingCount > 5) error("UDS ResponsePending limit exceeded.")
            val monitor = sendCommand("STMA 1", maxOf(timeoutMs, 10_000L))
            response = parseResponse(monitor, responseCanId)
            check(isMatchingUdsService(response, payload[0])) {
                "STN received a pending response for a different UDS request."
            }
        }
        response
    }

    override suspend fun sendOnly(payload: ByteArray, timeoutMs: Long) = mutex.withLock {
        require(payload.isNotEmpty() && payload.size <= 4095)
        ensureConfigured(timeoutMs)
        val command = "STPX h:${formatId(requestCanId)}, d:${payload.toHex()}, t:${timeoutMs.coerceIn(1L, 65535L)}, r:0"
        val result = sendCommand(command, timeoutMs)
        check(result.lines().any { it.trim().equals("OK", ignoreCase = true) }) {
            "STN did not acknowledge the UDS no-response transmission: $result"
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
        val model = hardwareModel(hardware)
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

    companion object {
        /** Only a complete STDI model/revision is accepted, never a Bluetooth name. */
        internal fun hardwareModel(hardware: String): String? {
            val pattern = Regex("""(?i)^OBDLink\s+(MX\+|MX|EX|CX|LX|SX)(?:\s+r[0-9]+(?:\.[0-9]+)*)?$""")
            return hardware.lineSequence().mapNotNull { pattern.matchEntire(it.trim()) }
                .firstOrNull()?.groupValues?.get(1)?.uppercase(Locale.ROOT)
        }
    }

    private fun requireOk(response: String, operation: String) {
        require(response.replace('\r', '\n').lines().any { it.trim().equals("OK", ignoreCase = true) }) {
            "STN adapter failed to $operation: $response"
        }
    }

    private fun isMatchingUdsService(response: ByteArray, requestedService: Byte): Boolean {
        val requestSid = requestedService.toInt() and 0xFF
        val positiveSid = (requestSid + 0x40) and 0xFF
        return (response.isNotEmpty() && (response[0].toInt() and 0xFF) == positiveSid) ||
            (response.size >= 3 && (response[0].toInt() and 0xFF) == 0x7F &&
                response[1] == requestedService)
    }

    private fun isResponsePending(response: ByteArray, requestedService: Byte): Boolean =
        response.size >= 3 &&
            (response[0].toInt() and 0xFF) == 0x7F &&
            (response[1] == requestedService) &&
            (response[2].toInt() and 0xFF) == 0x78

    /**
     * Parse an STN ISO-TP payload, not raw CAN frames.
     * ATH1 is enabled during configuration: accept ONLY matching responder IDs,
     * including continuations of long STPX responses. Never take two-byte
     * fragments from command echoes, other ECUs, or adapter status lines.
     */
    private fun parseResponse(output: String, expectedCanId: Int): ByteArray {
        require(!output.contains("NO DATA", true)) { "No UDS response from CAN ID " + formatId(expectedCanId) }
        require(!output.contains("CAN ERROR", true) && !output.contains("BUS ERROR", true)) {
            "STN adapter reported a CAN bus error: " + output
        }
        val buffer = ArrayList<Byte>()
        var accepting = false
        var matched = false
        for (line in output.replace('\r', '\n').lines()) {
            val tokens = line.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            if (tokens.isEmpty()) continue
            val normalizedFirst = tokens.first().removePrefix("0x").removePrefix("0X")
            val hasHeader = (normalizedFirst.length == 3 || normalizedFirst.length == 8) &&
                normalizedFirst.all { it.digitToIntOrNull(16) != null }
            if (hasHeader) {
                val id = normalizedFirst.toLongOrNull(16)?.toInt()
                accepting = id == expectedCanId
                if (!accepting) continue
                matched = true
            } else if (!accepting) {
                continue
            }
            val data = if (hasHeader) tokens.drop(1) else tokens
            if (data.isEmpty()) continue
            // End a payload on adapter messages; never parse arbitrary hex-like
            // fragments mixed with a status/error or a new command echo.
            if (data.any { token ->
                token.length % 2 != 0 || token.isEmpty() ||
                    token.any { it.digitToIntOrNull(16) == null }
            }) {
                accepting = false
                continue
            }
            for (token in data) {
                for (offset in token.indices step 2) {
                    check(buffer.size < 4095) { "Oversize ISO-TP payload from STN adapter." }
                    buffer.add(token.substring(offset, offset + 2).toInt(16).toByte())
                }
            }
        }
        require(matched && buffer.isNotEmpty()) {
            "No complete UDS payload for CAN ID " + formatId(expectedCanId)
        }
        return buffer.toByteArray()
    }

    private fun formatId(id: Int): String =
        if (id > 0x7FF) id.toString(16).uppercase(Locale.ROOT).padStart(8, '0')
        else id.toString(16).uppercase(Locale.ROOT).padStart(3, '0')

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }
}
