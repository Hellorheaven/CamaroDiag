package com.camarodiag.gen5.data.obd

/**
 * Separate physical GM identity observation. An OSID is not a CAL ID, a
 * service part number, or authorization to write an ECU.
 */
data class GmlanIdentityEvidence private constructor(
    val vin: String,
    val operatingSystemId: Long,
    val osDid: Int,
    val responseCanId: String
) {
    val flashQualified: Boolean get() = false

    companion object {
        internal fun validate(
            vin: String, osId: Long, osDid: Int, responseId: String,
            expectedResponseId: String, physicalRead: Boolean,
            readOnly: Boolean, flashQualified: Boolean
        ): GmlanIdentityEvidence {
            require(physicalRead && readOnly && !flashQualified) {
                "GMLAN response is not a physical read-only observation."
            }
            require(vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) {
                "Malformed ECU VIN."
            }
            require(osId in 1L..0xFFFFFFFEL && osDid in setOf(0xC1, 0xC9)) {
                "Invalid GM operating-system identifier."
            }
            require(responseId.matches(Regex("[0-9A-F]{3}|[0-9A-F]{8}")) &&
                responseId == expectedResponseId &&
                responseId.toLong(16) <= if (responseId.length == 3) 0x7FFL else 0x1FFFFFFFL
            ) { "GM identity came from a different or invalid physical CAN address." }
            return GmlanIdentityEvidence(vin, osId, osDid, responseId)
        }
    }
}
