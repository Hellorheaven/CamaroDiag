package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.DiagnosticDtc

/**
 * DTC format identifiers returned by UDS ReadDTCInformation (ISO 14229).
 * Unknown identifiers are preserved as raw 24-bit values, never guessed.
 */
enum class UdsDtcFormat(val identifier: Int) {
    SAE_J2012_DA_FORMAT_00(0x00),
    ISO_14229_1(0x01),
    SAE_J1939_73(0x02),
    ISO_11992_4(0x03),
    SAE_J2012_DA_FORMAT_04(0x04);

    companion object {
        fun from(identifier: Int): UdsDtcFormat? = entries.firstOrNull { it.identifier == identifier }
    }
}

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

/** Maps a decoded UDS record into the application's shared DTC presentation model. */
fun UdsDtcRecord.toDiagnosticDtc(moduleId: String): DiagnosticDtc {
    require(moduleId.isNotBlank()) { "The responding ECU module ID is required." }
    val stateEn = buildList {
        if (status.testFailed) add("failed")
        if (status.pending) add("pending")
        if (status.confirmed) add("confirmed")
        if (status.warningIndicatorRequested) add("warning indicator requested")
    }.ifEmpty { listOf("status not asserted") }.joinToString(", ")
    val stateFr = buildList {
        if (status.testFailed) add("défaut présent")
        if (status.pending) add("en attente")
        if (status.confirmed) add("confirmé")
        if (status.warningIndicatorRequested) add("voyant demandé")
    }.ifEmpty { listOf("aucun état de défaut actif") }.joinToString(", ")
    return DiagnosticDtc(
        code = code,
        module = moduleId,
        statusEn = stateEn,
        statusFr = stateFr,
        descEn = "Raw UDS DTC 0x" + rawDtc.toString(16).uppercase().padStart(6, '0') +
            ". A verified OEM definition for module $moduleId and this ECU calibration is not loaded.",
        descFr = "DTC UDS brut 0x" + rawDtc.toString(16).uppercase().padStart(6, '0') +
            ". Aucune définition constructeur vérifiée n'est chargée pour le calculateur $moduleId et cette calibration.",
        isPending = status.pending
    )
}

data class UdsDtcReport(
    val statusAvailabilityMask: Int,
    val formatIdentifier: Int,
    val format: UdsDtcFormat?,
    val records: List<UdsDtcRecord>
)

object UdsDtcDecoder {
    /**
     * Input is the payload returned by UdsReadOnlyClient.readDtcInformation():
     * DTCStatusAvailabilityMask, DTCFormatIdentifier, then 4-byte records
     * (3-byte DTC + 1-byte status).
     */
    fun decodeReadDtcInformation(payload: ByteArray): UdsDtcReport {
        require(payload.size >= 2) {
            "UDS DTC payload must include the status availability mask and DTC format identifier."
        }
        require((payload.size - 2) % 4 == 0) {
            "UDS DTC payload has a truncated DTCAndStatusRecord."
        }

        val availability = payload[0].toInt() and 0xFF
        val formatIdentifier = payload[1].toInt() and 0xFF
        val format = UdsDtcFormat.from(formatIdentifier)
        val records = payload.drop(2).chunked(4).map { bytes ->
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val status = bytes[3].toInt() and 0xFF
            val raw = (b0 shl 16) or (b1 shl 8) or b2
            UdsDtcRecord(
                rawDtc = raw,
                code = formatDisplayCode(format, b0, b1, b2),
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
        return UdsDtcReport(availability, formatIdentifier, format, records)
    }

    private fun formatDisplayCode(format: UdsDtcFormat?, b0: Int, b1: Int, b2: Int): String =
        when (format) {
            UdsDtcFormat.SAE_J2012_DA_FORMAT_00,
            UdsDtcFormat.SAE_J2012_DA_FORMAT_04 -> formatJ2012Dtc(b0, b1, b2)
            else -> "0x" + ((b0 shl 16) or (b1 shl 8) or b2)
                .toString(16).uppercase().padStart(6, '0')
        }

    /** SAE J2012 3-byte representation: five-character DTC plus failure-type byte. */
    private fun formatJ2012Dtc(b0: Int, b1: Int, failureType: Int): String {
        val family = when (b0 ushr 6) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val firstDigit = (b0 ushr 4) and 0x03
        val secondDigit = b0 and 0x0F
        return "$family$firstDigit" +
            secondDigit.toString(16).uppercase() +
            (b1 ushr 4).toString(16).uppercase() +
            (b1 and 0x0F).toString(16).uppercase() +
            "-" + failureType.toString(16).uppercase().padStart(2, '0')
    }
}
