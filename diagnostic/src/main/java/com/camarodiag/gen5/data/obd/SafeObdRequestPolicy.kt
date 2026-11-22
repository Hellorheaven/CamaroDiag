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
    val parameter: Int?,
    val elmCommand: String
)

object SafeObdRequestPolicy {
    // Accepted commands are tiny; reject oversized text before allocating token lists.
    private const val MAX_INPUT_LENGTH = 32

    private val allowedParametersByService = mapOf(
        // SAE J1979 current data and supported-PID bitmap requests.
        0x01 to setOf(
            0x00, 0x01, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
            0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
            0x14, 0x15, 0x16, 0x17, 0x1F,
            0x20, 0x24, 0x25, 0x34, 0x35,
            0x40, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A,
            0x4B, 0x4C, 0x52, 0x60, 0x80, 0xA0, 0xC0
        ),
        // SAE J1979 vehicle information: VIN, calibration IDs and CVNs.
        0x09 to setOf(0x02, 0x04, 0x06)
    )
    // Standard diagnostic trouble-code retrieval modes; these have no PID parameter.
    private val parameterlessReadOnlyServices = setOf(0x03, 0x07, 0x0A)

    /**
     * Accepts one whitelisted read-only request: a service/PID pair such as "010C"
     * or "01 0C", or a parameterless DTC read such as "03", "07" or "0A".
     * Explicit byte prefixes are supported; extra tokens and misplaced prefixes are rejected.
     */
    fun parse(input: String): SafeObdRequest? {
        if (input.length > MAX_INPUT_LENGTH) return null
        val tokens = input.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val compact = when (tokens.size) {
            1 -> {
                val token = tokens[0]
                when {
                    normalizeHexToken(token, 2) != null -> normalizeHexToken(token, 2)!!
                    normalizeHexToken(token, 4) != null -> normalizeHexToken(token, 4)!!
                    else -> return null
                }
            }
            2 -> {
                val service = normalizeHexToken(tokens[0], 2) ?: return null
                val parameter = normalizeHexToken(tokens[1], 2) ?: return null
                service + parameter
            }
            else -> return null
        }.uppercase()

        val service = compact.substring(0, 2).toIntOrNull(16) ?: return null
        if (service in parameterlessReadOnlyServices) {
            if (compact.length != 2) return null
            return SafeObdRequest(service, null, compact)
        }
        if (compact.length != 4) return null

        val parameter = compact.substring(2, 4).toIntOrNull(16) ?: return null
        if (parameter !in allowedParametersByService[service].orEmpty()) return null

        return SafeObdRequest(service, parameter, compact)
    }

    private fun normalizeHexToken(token: String, expectedLength: Int): String? {
        val value = if (token.startsWith("0x", ignoreCase = true)) token.substring(2) else token
        return value.takeIf {
            it.length == expectedLength && it.all { character ->
                character in '0'..'9' || character in 'A'..'F' || character in 'a'..'f'
            }
        }
    }
}
