package com.camarodiag.gen5.data.obd

/**
 * SAE J1979 Mode 01 PID 52: ethanol fuel percentage, A * 100 / 255.
 *
 * A valid diagnostic response reports what the ECU exposes; it does NOT
 * establish the presence of a physical ethanol sensor, FlexFuel enablement,
 * or permission to change ECU calibrations.
 */
object EthanolContentPidDecoder {
    /** A response payload without CAN framing: 41 52 AA. */
    fun decode(response: ByteArray): Double? {
        if (response.size != 3 ||
            (response[0].toInt() and 0xFF) != 0x41 ||
            (response[1].toInt() and 0xFF) != 0x52
        ) return null
        return (response[2].toInt() and 0xFF) * 100.0 / 255.0
    }

    /** The corresponding supported-PID bit is in Mode 01 PID 40, bit 14 (PID 52). */
    fun isSupported(pid40Response: ByteArray): Boolean {
        if (pid40Response.size != 6 ||
            (pid40Response[0].toInt() and 0xFF) != 0x41 ||
            (pid40Response[1].toInt() and 0xFF) != 0x40
        ) return false
        // PIDs 41..60 are indexed from MSB of A; PID 52 is offset 18.
        val offset = 0x52 - 0x40
        val byteIndex = 2 + (offset - 1) / 8
        val mask = 0x80 ushr ((offset - 1) % 8)
        return (pid40Response[byteIndex].toInt() and mask) != 0
    }
}
