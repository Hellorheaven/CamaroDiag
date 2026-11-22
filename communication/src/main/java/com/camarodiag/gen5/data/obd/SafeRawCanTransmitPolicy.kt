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
        // CAN IDs reserved for ECU replies must never be used to impersonate
        // the responder on the physical vehicle bus.
        if (!frame.isExtended && frame.canId in 0x7E8L..0x7EFL) return false
        if (frame.isExtended && (frame.canId and 0x1FFFFF00L) == 0x18DAF100L)
            return false
        val diagnosticAddress = if (frame.isExtended) {
            (frame.canId and 0x1FFF00FFL) == 0x18DA00F1L
        } else {
            frame.canId == 0x7DFL || frame.canId in 0x7E0L..0x7E7L
        }
        // No arbitrary frames on unqualified CAN addresses in PR #95.
        // An unknown bus message can actuate an ECU even when its payload
        // does not resemble a familiar UDS service. Only known read-only
        // diagnostic addressing is eligible for active transmission.
        if (!diagnosticAddress) return false
        val first = frame.data[0].toInt() and 0xFF
        if (first !in 1..7 || frame.data.size < first + 1) return false
        val all = frame.data.map { it.toInt() and 0xFF }
        if (all.drop(first + 1).any { it != 0 }) return false
        val service = all[1]
        val body = all.subList(1, first + 1)
        val standard = body.joinToString("") { "%02X".format(java.util.Locale.ROOT, it) }
        if (SafeObdRequestPolicy.parse(standard) != null) return true
        // Enhanced UDS reads are addressed to an identified physical ECU,
        // never broadcast to every ECU on the vehicle bus.
        if (!frame.isExtended && frame.canId == 0x7DFL) return false
        // ReadDataByIdentifier and ReadDTCInformation are query-only UDS services.
        // TesterPresent is limited to its ordinary/suppressed-positive forms.
        return when (service) {
            0x22 -> body.size == 3
            // Match the actual ISO-TP response correlator and Windows J2534
            // read-only subset. Unknown or truncated DTC subfunctions are not
            // permitted on a physical ECU.
            0x19 -> when (body.getOrNull(1)) {
                0x01, 0x02 -> body.size == 3 // request status mask
                0x0A -> body.size == 2       // all supported DTCs
                else -> false
            }
            0x3E -> body.size == 2 && body[1] in setOf(0x00, 0x80)
            else -> false
        }
    }
}
