package com.camarodiag.gen5.data.obd

/**
 * A deliberately narrow command accepted by CamaroDiag's live CAN console.
 *
 * Only standard, read-only OBD-II requests are admitted. This policy does not
 * accept CAN identifiers, arbitrary payload bytes, UDS write services or ELM
 * adapter commands.
 */
data class SafeObdRequest(
    val service: Int,
    val parameter: Int,
    val elmCommand: String
)

object SafeObdRequestPolicy {
    private val allowedParametersByService = mapOf(
        // SAE J1979 current data and supported-PID bitmap requests.
        0x01 to setOf(
            0x00, 0x01, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11,
            0x1F, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0
        ),
        // SAE J1979 vehicle information: VIN, calibration IDs and CVNs.
        0x09 to setOf(0x02, 0x04, 0x06)
    )

    /** Accepts one service/parameter pair (for example "01 0C" or "0902"). */
    fun parse(input: String): SafeObdRequest? {
        val compact = input.trim()
            .replace("0x", "", ignoreCase = true)
            .filterNot(Char::isWhitespace)
            .uppercase()
        if (!compact.matches(Regex("[0-9A-F]{4}"))) return null

        val service = compact.substring(0, 2).toIntOrNull(16) ?: return null
        val parameter = compact.substring(2, 4).toIntOrNull(16) ?: return null
        if (parameter !in allowedParametersByService[service].orEmpty()) return null

        return SafeObdRequest(service, parameter, compact)
    }
}
