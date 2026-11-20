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
    fun parseReceivedFrames(adapterOutput: String): List<RawCanFrame> =
        adapterOutput.lineSequence()
            .mapNotNull(::parseAdapterFrameLine)
            .toList()

    /**
     * ELM raw-CAN output includes a DLC after the arbitration ID. Validate it and
     * remove it: DLC is framing metadata, not the first byte of the ECU payload.
     * Lines such as SEARCHING, OK, NO DATA and the ELM prompt are intentionally ignored.
     */
    private fun parseAdapterFrameLine(line: String): RawCanFrame? {
        val tokens = line.trim()
            .removeSuffix(">")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (tokens.size < 3) return null
        val frame = parse(tokens.first() + " " + tokens.drop(2).joinToString(" ")) ?: return null
        val dlcToken = tokens[1]
        if (dlcToken.length != 2 || !dlcToken.all { it.digitToIntOrNull(16) != null }) return null
        val declaredLength = dlcToken.toInt(16)
        if (declaredLength !in 1..8 || frame.data.size != declaredLength) return null
        return frame
    }

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
