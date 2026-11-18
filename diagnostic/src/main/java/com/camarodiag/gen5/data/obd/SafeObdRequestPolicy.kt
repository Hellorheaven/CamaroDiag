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

    /**
     * Accepts exactly one service/PID pair: compact ("010C"), spaced ("01 0C"),
     * or explicitly prefixed ("0x01 0x0C"). Extra tokens and misplaced prefixes
     * are rejected instead of being silently stripped.
     */
    fun parse(input: String): SafeObdRequest? {
        val tokens = input.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val compact = when (tokens.size) {
            1 -> normalizeHexToken(tokens[0], 4) ?: return null
            2 -> {
                val service = normalizeHexToken(tokens[0], 2) ?: return null
                val parameter = normalizeHexToken(tokens[1], 2) ?: return null
                service + parameter
            }
            else -> return null
        }.uppercase()

        val service = compact.substring(0, 2).toIntOrNull(16) ?: return null
        val parameter = compact.substring(2, 4).toIntOrNull(16) ?: return null
        if (parameter !in allowedParametersByService[service].orEmpty()) return null

        return SafeObdRequest(service, parameter, compact)
    }

    private fun normalizeHexToken(token: String, expectedLength: Int): String? {
        val value = if (token.startsWith("0x", ignoreCase = true)) token.substring(2) else token
        return value.takeIf {
            it.length == expectedLength && it.all { character -> character.digitToIntOrNull(16) != null }
        }
    }
}
