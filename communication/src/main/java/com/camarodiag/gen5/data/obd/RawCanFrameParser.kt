package com.camarodiag.gen5.data.obd

/** A validated single Classical CAN data frame entered in the unified console. */
data class RawCanFrame(
    val canId: Long,
    val isExtended: Boolean,
    val data: ByteArray
)

/**
 * Parses console input in the form "7E0 02 01 0C" or
 * "18DAF110 03 22 F1 90". Whitespace and optional 0x prefixes are accepted.
 */
object RawCanFrameParser {
    private val receivedFramePattern = Regex(
        "(?i)(?<![0-9A-F])([0-9A-F]{3}|[0-9A-F]{8})\\s+((?:[0-9A-F]{2}\\s+){0,7}[0-9A-F]{2})(?![0-9A-F])"
    )

    fun parseReceivedFrames(adapterOutput: String): List<RawCanFrame> =
        receivedFramePattern.findAll(adapterOutput)
            .mapNotNull { match -> parse(match.value.trim()) }
            .toList()

    fun parse(input: String): RawCanFrame? {
        val tokens = input.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size < 2) return null
        val idToken = tokens.first().removePrefix("0x").removePrefix("0X")
        if (idToken.length !in setOf(3, 8) || !idToken.all { it.digitToIntOrNull(16) != null }) return null
        val id = idToken.toLongOrNull(16) ?: return null
        val extended = idToken.length > 3
        if ((!extended && id > 0x7FFL) || (extended && id > 0x1FFFFFFFL)) return null
        val byteTokens = tokens.drop(1)
        if (byteTokens.size !in 1..8 || byteTokens.any {
            val token = it.removePrefix("0x").removePrefix("0X")
            token.length != 2 || !token.all { char -> char.digitToIntOrNull(16) != null }
        }) return null
        val bytes = byteTokens.map { it.removePrefix("0x").removePrefix("0X").toInt(16).toByte() }.toByteArray()
        return RawCanFrame(id, extended, bytes)
    }
}
