package com.camarodiag.gen5.data.obd

/**
 * The physical raw-CAN console must not bypass the documented read-only OBD
 * interface by wrapping destructive ECU programming services in an ISO-TP
 * single frame. This is an additional diagnostic guard, not evidence that
 * arbitrary OEM CAN commands are safe or qualified.
 */
object SafeRawCanTransmitPolicy {
    private val disallowedDiagnosticServices = setOf(
        0x04, 0x10, 0x11, 0x14, 0x27, 0x28, 0x2E, 0x2F,
        0x31, 0x34, 0x35, 0x36, 0x37, 0x38, 0x85
    )

    private fun isRecognizableUnsafeDiagnostic(data: ByteArray): Boolean {
        val first = data.firstOrNull()?.toInt()?.and(0xFF) ?: return false
        val service = when {
            // ISO-TP single frame: byte 1 holds the UDS/OBD service.
            first in 1..7 && data.size >= first + 1 ->
                data[1].toInt() and 0xFF
            // ISO-TP first frame: byte 2 holds the diagnostic service.
            first in 0x10..0x1F && data.size >= 3 ->
                data[2].toInt() and 0xFF
            else -> return false
        }
        return service in disallowedDiagnosticServices
    }

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
        // GM and aftermarket interfaces can use non-standard diagnostic IDs.
        // Block unmistakable ISO-TP programming services on *any* CAN ID,
        // rather than allowing writes simply because the header is unknown.
        // This does not certify arbitrary non-diagnostic CAN traffic as safe.
        if (!diagnosticAddress) return !isRecognizableUnsafeDiagnostic(frame.data)
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
            0x19 -> body.size in 2..6
            0x3E -> body.size == 2 && body[1] in setOf(0x00, 0x80)
            else -> false
        }
    }
}
