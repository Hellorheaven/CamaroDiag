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
            0x20, 0x24, 0x25, 0x28, 0x29, 0x34, 0x35, 0x38, 0x39,
            0x40, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A,
            0x4B, 0x4C, 0x52, 0x60, 0x80, 0xA0, 0xC0
        ),
        // SAE J1979 vehicle information: VIN, calibration IDs and CVNs.
        0x09 to setOf(0x02, 0x04, 0x06, 0x0A)
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

    /**
     * Exact SAE J1979 Mode 01 lengths for the currently whitelisted commands.
     * A short RPM/MAF reading is no more reliable than a malformed ethanol value.
     * This is a check on the returned bytes, not a claim that the ECU supports the PID.
     */
    internal fun expectedMode01DataBytes(pid: Int): Int? = when (pid) {
        0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0x01 -> 4
        0x0C, 0x10, 0x14, 0x15, 0x16, 0x17, 0x1F, 0x42, 0x43, 0x44 -> 2
        0x24, 0x25, 0x28, 0x29, 0x34, 0x35, 0x38, 0x39 -> 4
        0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F,
        0x11, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x52 -> 1
        else -> null
    }

    /**
     * A command echo, adapter banner, or UDS negative response is never proof
     * of a successful physical read. Works with spaced and compact ELM/J2534
     * payloads, optionally prefixed by a supported CAN responder header.
     */
    fun hasPositiveResponse(request: SafeObdRequest, response: String): Boolean {
        if (parse(request.elmCommand) != request) return false
        if (request.service == 0x09 && ':' in response) {
            val assembled = ObdNumberedIdentityFrames.parse(response) ?: return false
            return hasPositiveResponse(request, assembled.joinToString("") {
                "%02X".format(java.util.Locale.ROOT, it)
            })
        }
        val expected = when (request.service) {
            0x01 -> "41%02X".format(java.util.Locale.ROOT, requireNotNull(request.parameter))
            0x03 -> "43"
            0x07 -> "47"
            0x0A -> "4A"
            0x09 -> "49%02X".format(java.util.Locale.ROOT, requireNotNull(request.parameter))
            else -> return false
        }
        for (line in response.lineSequence()) {
            val trimmed = line.trim().uppercase(java.util.Locale.ROOT)
            if (trimmed.isEmpty() || trimmed.any {
                    it !in '0'..'9' && it !in 'A'..'F' && !it.isWhitespace()
                }) continue
            var bytes = trimmed.filterNot(Char::isWhitespace)
            // Only known OBD physical response headers are stripped.
            if (bytes.startsWith("18DAF110") && bytes.length >= 8) {
                bytes = bytes.drop(8)
            } else if (bytes.length >= 3 && bytes.startsWith("7E") &&
                bytes[2] in '8'..'F') {
                bytes = bytes.drop(3)
            }
            // ELM's optional single-frame PCI may precede the positive service.
            val declaredLength = bytes.take(2).toIntOrNull(16)
            if (declaredLength != null && declaredLength in 1..7 &&
                bytes.length >= 2 + declaredLength * 2 &&
                bytes.drop(2).startsWith(expected)) {
                val trailing = bytes.drop(2 + declaredLength * 2)
                // A short DLC, or arbitrary bytes masquerading as CAN padding,
                // must not turn a truncated reply into successful ECU evidence.
                if (trailing.length % 2 != 0 || trailing.any { it != '0' }) continue
                bytes = bytes.substring(2, 2 + declaredLength * 2)
            }
            if (bytes.startsWith(expected) && bytes.length > expected.length &&
                bytes.length % 2 == 0) {
                // Do not accept plausible prefixes with truncated or padded
                // payloads, particularly for engine RPM and ethanol PID 0x52.
                if (request.service == 0x01) {
                    val expectedBytes = expectedMode01DataBytes(requireNotNull(request.parameter))
                        ?: continue
                    if (bytes.length != expected.length + 2 * expectedBytes) continue
                }
                if (request.service == 0x09) {
                    val pid = requireNotNull(request.parameter)
                    val width = when (pid) {
                        0x02 -> 17 // VIN, exactly one record
                        0x04 -> 16 // Calibration identifier
                        0x06 -> 4  // Calibration verification number
                        0x0A -> 20 // ECU name
                        else -> continue
                    }
                    val count = bytes.substring(4, 6).toIntOrNull(16) ?: continue
                    if (count <= 0 || (pid == 0x02 && count != 1) ||
                        bytes.length != 6 + 2 * count * width) continue
                    if (pid != 0x06) {
                        val values = (0 until count).map { index ->
                            bytes.substring(6 + index * width * 2, 6 + (index + 1) * width * 2)
                                .chunked(2).mapNotNull { it.toIntOrNull(16)?.toChar() }
                                .joinToString("").trim(' ', '\u0000')
                        }
                        if (pid == 0x02) {
                            if (!Regex("[A-HJ-NPR-Z0-9]{17}").matches(values.single())) continue
                        } else if (values.any { value ->
                                value.isBlank() || value.any { it !in ' '..'~' }
                            }) continue
                    }
                }
                if (request.service in setOf(0x03, 0x07, 0x0A)) {
                    val count = bytes.substring(2, 4).toIntOrNull(16) ?: continue
                    val counted = bytes.length == 4 + count * 4 &&
                        (0 until count).none { index ->
                            bytes.substring(4 + index * 4, 8 + index * 4) == "0000"
                        }
                    // Some legacy ELM replies contain DTC pairs followed by
                    // an unused zero slot, without the CAN count byte.
                    // Require at least two full pairs to avoid accepting an
                    // ambiguous truncated count as a valid diagnostic result.
                    val legacy = bytes.length >= 10 &&
                        (bytes.length - 2) % 4 == 0 &&
                        bytes.substring(2).chunked(4).any { it != "0000" }
                    if (!counted && !legacy) continue
                }
                return true
            }
        }
        return false
    }

    /**
     * A negative response is still real ECU RX traffic, not an adapter
     * acknowledgement. It must echo the requested service and include one NRC.
     */
    fun hasNegativeResponse(request: SafeObdRequest, response: String): Boolean {
        if (parse(request.elmCommand) != request) return false
        val serviceHex = "%02X".format(java.util.Locale.ROOT, request.service)
        val expectedPrefix = "7F" + serviceHex
        for (line in response.lineSequence()) {
            val trimmed = line.trim().uppercase(java.util.Locale.ROOT)
            if (trimmed.isEmpty() || trimmed.any {
                    it !in '0'..'9' && it !in 'A'..'F' && !it.isWhitespace()
                }) continue
            var bytes = trimmed.filterNot(Char::isWhitespace)
            if (bytes.startsWith("18DAF110") && bytes.length >= 8) bytes = bytes.drop(8)
            else if (bytes.length >= 3 && bytes.startsWith("7E") &&
                bytes[2] in '8'..'F') bytes = bytes.drop(3)
            if (bytes.startsWith("03" + expectedPrefix) && bytes.length >= 8 &&
                (bytes.length - 8) % 2 == 0 && bytes.drop(8).all { it == '0' }) {
                bytes = bytes.substring(2, 8)
            }
            if (bytes.length == 6 && bytes.startsWith(expectedPrefix)) {
                val nrc = bytes.substring(4, 6).toIntOrNull(16) ?: continue
                // NRC 0 is invalid and ResponsePending is not a final reply.
                if (nrc !in setOf(0x00, 0x78)) return true
            }
        }
        return false
    }

    enum class ReadResponseKind { POSITIVE_ECU, NEGATIVE_ECU, UNMATCHED_OR_ADAPTER }

    /**
     * One shared classification for the console history and its TX/RX status.
     * A negative ECU reply proves delivery but is not a successful diagnostic read.
     */
    fun classifyResponse(request: SafeObdRequest, response: String): ReadResponseKind =
        when {
            hasNegativeResponse(request, response) -> ReadResponseKind.NEGATIVE_ECU
            hasPositiveResponse(request, response) -> ReadResponseKind.POSITIVE_ECU
            else -> ReadResponseKind.UNMATCHED_OR_ADAPTER
        }

    /** Conflicting ECU results are not proof of a successful single read. */
    fun hasUnambiguousPositiveResponse(request: SafeObdRequest, response: String): Boolean =
        hasPositiveResponse(request, response) && !hasNegativeResponse(request, response)

    private fun normalizeHexToken(token: String, expectedLength: Int): String? {
        val value = if (token.startsWith("0x", ignoreCase = true)) token.substring(2) else token
        return value.takeIf {
            it.length == expectedLength && it.all { character ->
                character in '0'..'9' || character in 'A'..'F' || character in 'a'..'f'
            }
        }
    }
}
