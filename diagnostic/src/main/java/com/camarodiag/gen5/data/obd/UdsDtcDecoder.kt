package com.camarodiag.gen5.data.obd

/**
 * Decodes the ISO 14229 ReadDTCInformation (0x19/0x02) DTCAndStatusRecord list.
 *
 * The decoder deliberately returns the raw 24-bit DTC and status byte alongside
 * the SAE J2012 display code. It does not assign a fault description: proprietary
 * GM meanings must be resolved using a VIN/calibration/module-specific catalogue.
 */
data class UdsDtcRecord(
    val rawDtc: Int,
    val code: String,
    val statusByte: Int,
    val status: UdsDtcStatus
)

data class UdsDtcStatus(
    val testFailed: Boolean,
    val testFailedThisOperationCycle: Boolean,
    val pending: Boolean,
    val confirmed: Boolean,
    val testNotCompletedSinceLastClear: Boolean,
    val testFailedSinceLastClear: Boolean,
    val testNotCompletedThisOperationCycle: Boolean,
    val warningIndicatorRequested: Boolean
)

data class UdsDtcReport(
    val statusAvailabilityMask: Int,
    val records: List<UdsDtcRecord>
)

object UdsDtcDecoder {
    /**
     * Input is the payload returned by UdsReadOnlyClient.readDtcInformation():
     * statusAvailabilityMask followed by zero or more 4-byte DTC/status records.
     */
    fun decodeReadDtcInformation(payload: ByteArray): UdsDtcReport {
        require(payload.isNotEmpty()) { "UDS DTC payload must include the status availability mask." }
        require((payload.size - 1) % 4 == 0) {
            "UDS DTC payload has a truncated DTCAndStatusRecord."
        }

        val availability = payload[0].toInt() and 0xFF
        val records = payload.drop(1).chunked(4).map { bytes ->
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val status = bytes[3].toInt() and 0xFF
            val raw = (b0 shl 16) or (b1 shl 8) or b2
            UdsDtcRecord(
                rawDtc = raw,
                code = formatJ2012Dtc(b0, b1, b2),
                statusByte = status,
                status = UdsDtcStatus(
                    testFailed = status and 0x01 != 0,
                    testFailedThisOperationCycle = status and 0x02 != 0,
                    pending = status and 0x04 != 0,
                    confirmed = status and 0x08 != 0,
                    testNotCompletedSinceLastClear = status and 0x10 != 0,
                    testFailedSinceLastClear = status and 0x20 != 0,
                    testNotCompletedThisOperationCycle = status and 0x40 != 0,
                    warningIndicatorRequested = status and 0x80 != 0
                )
            )
        }
        return UdsDtcReport(availability, records)
    }

    private fun formatJ2012Dtc(b0: Int, b1: Int, b2: Int): String {
        val family = when (b0 ushr 6) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val firstDigit = (b0 ushr 4) and 0x03
        val secondDigit = b0 and 0x0F
        val thirdDigit = b1 ushr 4
        val fourthDigit = b1 and 0x0F
        val fifthDigit = b2 ushr 4
        val sixthDigit = b2 and 0x0F
        return "$family$firstDigit" +
            secondDigit.toString(16).uppercase().padStart(1, '0') +
            thirdDigit.toString(16).uppercase() +
            fourthDigit.toString(16).uppercase() +
            fifthDigit.toString(16).uppercase() +
            sixthDigit.toString(16).uppercase()
    }
}
