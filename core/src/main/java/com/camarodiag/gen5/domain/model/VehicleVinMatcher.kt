package com.camarodiag.gen5.domain.model

/**
 * Comparison between the VIN stored in the selected vehicle profile and the VIN
 * actually returned by the OBD-accessible ECU.
 *
 * [matches] is null when the selected profile has no valid VIN yet.
 */
data class VehicleVinComparison(
    val selectedVehicleVin: String?,
    val ecuVin: String,
    val matches: Boolean?
)

object VehicleVinMatcher {
    private val vinPattern = Regex("[A-HJ-NPR-Z0-9]{17}")

    fun normalize(vin: String?): String? {
        val normalized = vin
            ?.uppercase()
            ?.filter(Char::isLetterOrDigit)
            ?: return null
        return normalized.takeIf(vinPattern::matches)
    }

    fun compare(selectedVehicleVin: String?, ecuVin: String?): VehicleVinComparison? {
        val normalizedEcuVin = normalize(ecuVin) ?: return null
        val normalizedSelectedVin = normalize(selectedVehicleVin)
        return VehicleVinComparison(
            selectedVehicleVin = normalizedSelectedVin,
            ecuVin = normalizedEcuVin,
            matches = normalizedSelectedVin?.let { it == normalizedEcuVin }
        )
    }
}
