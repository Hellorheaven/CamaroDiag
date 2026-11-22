package com.camarodiag.gen5.ui

/**
 * Validates the CAN identifiers before the UI enables any live bridge/UDS
 * operation. Matching text width does not guarantee an in-range, distinct,
 * hexadecimal CAN pair.
 */
internal object J2534CanProfileValidation {
    private val protocols11Bit = setOf("33", "35", "63")
    private val protocols29Bit = setOf("34", "36", "64")
    private val hex = Regex("^[0-9A-Fa-f]+$")

    fun isValid(requestId: String, responseId: String, protocol: String): Boolean {
        val is29Bit = when (protocol) {
            in protocols11Bit -> false
            in protocols29Bit -> true
            else -> return false
        }
        val digits = if (is29Bit) 8 else 3
        val maxId = if (is29Bit) 0x1FFF_FFFFL else 0x7FFL
        fun parse(value: String): Long? {
            if (value.length != digits || !hex.matches(value)) return null
            return value.toLongOrNull(16)?.takeIf { it in 0..maxId }
        }
        val request = parse(requestId) ?: return false
        val response = parse(responseId) ?: return false
        return request != response
    }
}
