package com.camarodiag.gen5.data.obd

/**
 * Non-destructive before/after evidence checklist for a 2012 Camaro LFX ECM.
 * A successful flash response alone never proves the vehicle is operational.
 * No active controller adaptations or clutch-pedal relearns are issued here.
 */
object Lfx2012ProgrammingAudit {
    enum class Item {
        VEHICLE_VIN,
        ECU_NAME,
        CALIBRATION_ID,
        CALIBRATION_VERIFICATION_NUMBER,
        DIAGNOSTIC_TROUBLE_CODES,
        VOLTAGE,
        COMMUNICATION,
        VEHICLE_FUNCTIONAL_CHECK,
        OEM_RELEARN_REQUIREMENTS
    }
    enum class CheckStatus { OBSERVED, UNAVAILABLE, NOT_PERFORMED, REQUIRES_REVIEW }
    enum class Overall { NOT_PERFORMED, INCOMPLETE, REQUIRES_REVIEW, READY_FOR_MANUAL_REVIEW }

    data class Snapshot(
        val vin: String?,
        val ecuName: String?,
        val calibrationIds: List<String>,
        val verificationNumbers: List<String>,
        val dtcs: List<String>?,
        val voltage: Double?,
        val communicationConfirmed: Boolean,
        val functionalCheckPassed: Boolean?,
        val oemRelearnReviewed: Boolean,
        val capturedAtUtcMillis: Long
    )

    data class Check(val item: Item, val status: CheckStatus)
    data class Report(
        val before: List<Check>,
        val after: List<Check>,
        val overall: Overall,
        val physicalValidationComplete: Boolean = false
    )

    fun review(before: Snapshot?, after: Snapshot?): Report {
        val original = before?.let(::checks).orEmpty()
        val current = after?.let(::checks).orEmpty()
        if (before == null || after == null) {
            return Report(original, current, Overall.NOT_PERFORMED)
        }
        val vinChanged = !before.vin.isNullOrBlank() && !after.vin.isNullOrBlank() &&
            !before.vin.equals(after.vin, ignoreCase = true)
        val hasUnavailable = original.any {
            it.status == CheckStatus.UNAVAILABLE || it.status == CheckStatus.NOT_PERFORMED
        } || current.any {
            it.status == CheckStatus.UNAVAILABLE || it.status == CheckStatus.NOT_PERFORMED
        }
        val requiresReview = vinChanged ||
            after.capturedAtUtcMillis <= before.capturedAtUtcMillis ||
            original.any { it.status == CheckStatus.REQUIRES_REVIEW } ||
            current.any { it.status == CheckStatus.REQUIRES_REVIEW }
        return Report(
            before = original,
            after = current,
            overall = when {
                requiresReview -> Overall.REQUIRES_REVIEW
                hasUnavailable -> Overall.INCOMPLETE
                else -> Overall.READY_FOR_MANUAL_REVIEW
            }
        )
    }

    private fun checks(snapshot: Snapshot): List<Check> {
        fun reported(text: String?): CheckStatus =
            if (text.isNullOrBlank()) CheckStatus.UNAVAILABLE else CheckStatus.OBSERVED
        val normalizedVin = snapshot.vin?.trim()?.uppercase()
        val vinStatus = when {
            normalizedVin.isNullOrBlank() -> CheckStatus.UNAVAILABLE
            !Regex("[A-HJ-NPR-Z0-9]{17}").matches(normalizedVin) -> CheckStatus.REQUIRES_REVIEW
            else -> CheckStatus.OBSERVED
        }
        return listOf(
            Check(Item.VEHICLE_VIN, vinStatus),
            Check(Item.ECU_NAME, reported(snapshot.ecuName)),
            Check(Item.CALIBRATION_ID, if (snapshot.calibrationIds.any(String::isNotBlank))
                CheckStatus.OBSERVED else CheckStatus.UNAVAILABLE),
            Check(Item.CALIBRATION_VERIFICATION_NUMBER,
                if (snapshot.verificationNumbers.any(String::isNotBlank))
                    CheckStatus.OBSERVED else CheckStatus.UNAVAILABLE),
            Check(Item.DIAGNOSTIC_TROUBLE_CODES,
                if (snapshot.dtcs == null) CheckStatus.UNAVAILABLE else CheckStatus.OBSERVED),
            Check(Item.VOLTAGE,
                when {
                    snapshot.voltage == null || !snapshot.voltage.isFinite() -> CheckStatus.UNAVAILABLE
                    snapshot.voltage <= 0.0 -> CheckStatus.REQUIRES_REVIEW
                    else -> CheckStatus.OBSERVED
                }),
            Check(Item.COMMUNICATION,
                if (snapshot.communicationConfirmed) CheckStatus.OBSERVED
                    else CheckStatus.REQUIRES_REVIEW),
            Check(Item.VEHICLE_FUNCTIONAL_CHECK,
                when (snapshot.functionalCheckPassed) {
                    null -> CheckStatus.NOT_PERFORMED
                    true -> CheckStatus.OBSERVED
                    false -> CheckStatus.REQUIRES_REVIEW
                }),
            Check(Item.OEM_RELEARN_REQUIREMENTS,
                if (snapshot.oemRelearnReviewed) CheckStatus.OBSERVED
                    else CheckStatus.NOT_PERFORMED)
        )
    }
}
