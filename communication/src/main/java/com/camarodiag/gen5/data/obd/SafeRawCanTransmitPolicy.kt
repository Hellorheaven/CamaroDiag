package com.camarodiag.gen5.data.obd

/**
 * The physical raw-CAN console must not bypass the documented read-only OBD
 * interface by wrapping destructive ECU programming services in an ISO-TP
 * single frame. This is an additional diagnostic guard, not evidence that
 * arbitrary OEM CAN commands are safe or qualified.
 */
object SafeRawCanTransmitPolicy {
    fun allows(frame: RawCanFrame): Boolean {
        if (frame.data.size !in 1..8) return false
        val diagnosticAddress = if (frame.isExtended) {
            (frame.canId and 0x1FFF00FFL) == 0x18DA00F1L
        } else {
            frame.canId == 0x7DFL || frame.canId in 0x7E0L..0x7E7L
        }
        // Existing expert CAN traffic on other addresses is not reclassified
        // as OBD; its remaining risks are tracked by the security audit.
        if (!diagnosticAddress) return true
        val first = frame.data[0].toInt() and 0xFF
        if (first !in 1..7 || frame.data.size < first + 1) return false
        val all = frame.data.map { it.toInt() and 0xFF }
        if (all.drop(first + 1).any { it != 0 }) return false
        val service = all[1]
        val body = all.subList(1, first + 1)
        val standard = body.joinToString("") { "%02X".format(java.util.Locale.ROOT, it) }
        if (SafeObdRequestPolicy.parse(standard) != null) return true
        // ReadDataByIdentifier and ReadDTCInformation are query-only UDS services.
        // TesterPresent is limited to its ordinary/suppressed-positive forms.
        return when (service) {
            0x22 -> body.size == 3
            0x19 -> body.size in 2..6
            0x3E -> body.size == 2 && body[1] in setOf(0x00, 0x80)
            else -> false
        }
    }
}
