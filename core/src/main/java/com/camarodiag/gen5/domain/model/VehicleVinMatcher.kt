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

    /** Conservative candidate check for supported fifth/sixth generation Camaro VINs.
     * A candidate is not proof of the fitted ECU, engine or calibration.
     */
    fun isCamaroCandidate(vin: String?): Boolean {
        val normalized = normalize(vin) ?: return false
        val year = modelYearFromVin(normalized) ?: return false
        return (normalized.startsWith("2G1F") && year in 2010..2015) ||
            (normalized.startsWith("1G1F") && year in 2016..2024)
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

    /**
     * Decodes the VIN model-year character for the Camaro Gen 5/Gen 6 model-year
     * range supported by this app. It does not infer RPO, transmission or market.
     */
    fun modelYearFromVin(vin: String?): Int? {
        val normalized = normalize(vin) ?: return null
        val yearIndex = CAMARO_MODEL_YEAR_CODES.indexOf(normalized[9])
        return yearIndex.takeIf { it >= 0 }?.let { 2010 + it }
    }

    private const val CAMARO_MODEL_YEAR_CODES = "ABCDEFGHJKLMNPR"
}
