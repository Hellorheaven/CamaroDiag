package com.camarodiag.gen5.data.obd

import java.util.Locale

/**
 * ISO 15765-4 payloads are already assembled by J2534. Do not pass them
 * through an ELM line parser: CAN DTC responses contain a count byte.
 * The caller supplies a physical channel and must correlate its CAN ID.
 */
internal object J2534StandardObd {
    fun supportsChannel(request: Int, response: Int, protocol: Int): Boolean {
        if (protocol in setOf(33, 35)) return request in 0x7E0..0x7E7 && response == request + 8
        if (protocol !in setOf(34, 36) || (request and 0xFF00.inv()) != 0x18DA00F1) return false
        val address = (request shr 8) and 0xFF
        return address !in setOf(0xF1, 0xFE, 0xFF) && response == (0x18DAF100 or address)
    }

    fun supportsPrimaryChannel(request: Int, response: Int, protocol: Int): Boolean =
        (protocol in setOf(33, 35) && request == 0x7E0 && response == 0x7E8) ||
            (protocol in setOf(34, 36) && request == 0x18DA10F1 && response == 0x18DAF110)

    /** Bind Mode 09 records to the negotiated physical channel. Reject
     * malformed metadata rather than importing a different ECU fingerprint. */
    fun validateIdentity(
        vin: String?, calibrationIds: List<String>,
        calibrationVerificationNumbers: List<String>, ecuNames: List<String>,
        responseCanId: String, expectedResponseCanId: String
    ): ObdEcuIdentification {
        require(responseCanId == expectedResponseCanId &&
            responseCanId.matches(Regex("[0-9A-F]{3}|[0-9A-F]{8}")) &&
            responseCanId.toLong(16) <= if (responseCanId.length == 3) 0x7FFL else 0x1FFFFFFFL
        ) { "Mode 09 identity responder differs from the selected physical CAN channel." }
        require(vin == null || vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) {
            "Malformed Mode 09 VIN."
        }
        fun printable(value: String, width: Int) =
            value.length in 1..width && value.all { it in ' '..'~' } && value.isNotBlank()
        require(calibrationIds.all { printable(it,16) } &&
            ecuNames.all { printable(it,20) } &&
            calibrationVerificationNumbers.all { it.matches(Regex("[0-9A-F]{8}")) }
        ) { "Malformed physical Mode 09 record." }
        require(vin != null || calibrationIds.isNotEmpty() ||
            calibrationVerificationNumbers.isNotEmpty() || ecuNames.isNotEmpty()) {
            "No physical identity records."
        }
        return ObdEcuIdentification(vin = vin, calibrationIds = calibrationIds.toList(),
            calibrationVerificationNumbers = calibrationVerificationNumbers.toList(),
            ecuNames = ecuNames.toList(), respondingEcuAddresses = listOf(responseCanId))
    }

    private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

    fun pidData(pid: Int, reply: ByteArray): ByteArray {
        require(pid in 0..255)
        require(reply.size >= 3 && reply.u(0) == 0x41 && reply.u(1) == pid) {
            "Invalid or negative Mode 01 response."
        }
        // Enforce exact data length for the standard signals used by the
        // fuel/ethanol monitor. ISO-TP padding is not part of a J2534 payload.
        val expected = when (pid) {
            0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0, 0x01,
            0x24, 0x25, 0x28, 0x29, 0x34, 0x35, 0x38, 0x39, 0xA6 -> 4
            0x0C, 0x10, 0x14, 0x15, 0x16, 0x17, 0x1F, 0x42, 0x43, 0x44 -> 2
            0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
            0x0D, 0x0E, 0x0F, 0x11, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A,
            0x4B, 0x4C, 0x52, 0x5B -> 1
            else -> null // unknown/nonstandard PIDs require their own decoder
        }
        require(expected == null || reply.size == expected + 2) {
            "Invalid Mode 01 data length for PID 0x" + "%02X".format(Locale.ROOT,pid)
        }
        return reply.copyOfRange(2, reply.size)
    }

    /** Bit 31 announces another support bitmap, not a sensor PID. */
    fun supportPage(base: Int, reply: ByteArray): Pair<Set<Int>, Boolean> {
        require(base in 0..0xE0 && base % 32 == 0)
        val bitmap = pidData(base, reply)
        require(bitmap.size == 4) { "Mode 01 support bitmap must be exactly four bytes." }
        val pids = (0 until 31).filter { index ->
            bitmap.u(index / 8) and (1 shl (7 - index % 8)) != 0
        }.map { base + it + 1 }.toSet()
        return pids to (base < 0xE0 && bitmap.u(3) and 1 != 0)
    }

    fun dtcs(mode: Int, reply: ByteArray): List<ObdDtc> {
        val status = when (mode) {
            3 -> "confirmed"
            7 -> "pending"
            10 -> "permanent"
            else -> throw IllegalArgumentException("Unsupported DTC read mode.")
        }
        require(reply.size >= 2 && reply.u(0) == mode + 0x40) {
            "Invalid or negative CAN DTC response."
        }
        val count = reply.u(1)
        require(reply.size == 2 + count * 2) { "CAN DTC count does not match payload length." }
        return List(count) { index ->
            val hi = reply.u(2 + 2 * index)
            val lo = reply.u(3 + 2 * index)
            require(hi != 0 || lo != 0) { "Zero DTC inside a declared CAN DTC record." }
            val code = "%c%d%X%02X".format(Locale.ROOT, "PCBU"[hi shr 6], (hi shr 4) and 3, hi and 15, lo)
            ObdDtc(code, status)
        }
    }

    /** Optional modes may explicitly be unsupported; never treat malformed or
     * other negative responses as successful empty records. */
    fun optionalDtcs(mode: Int, reply: ByteArray): List<ObdDtc>? =
        if (mode in setOf(7,10) && isExplicitlyUnsupported(mode,reply)) null
        else dtcs(mode,reply)

    /** Inventory all three modes independently. Explicit refusal is unavailable,
     * while malformed replies and other NRCs remain errors. */
    fun evidenceDtcs(mode: Int, reply: ByteArray): List<ObdDtc>? {
        require(mode in setOf(3, 7, 10))
        return if (isExplicitlyUnsupported(mode, reply)) null else dtcs(mode, reply)
    }

    fun isExplicitlyUnsupported(mode: Int, reply: ByteArray): Boolean =
        reply.size == 3 && reply.u(0) == 0x7F && reply.u(1) == mode &&
            reply.u(2) in setOf(0x11, 0x12, 0x31)
}
