package com.camarodiag.gen5.data.obd

/**
 * Published service-parts references for the Camaro Gen 5 2012 LFX 3.6L.
 *
 * Service parts and supersessions are not ECU firmware identifiers.
 * They must NEVER be used to automatically approve an ECU flash/calibration.
 */
object Lfx2012ControllerReferences {
    const val controllerFamily = "E39"
    const val currentServicePart = "12651994"
    const val supersededServicePart = "12643248"
    const val associatedMainBootFile = "12632286"

    const val servicePartsReference =
        "https://www.gmpartsgiant.com/parts-list/2012-chevrolet-camaro-coupe/fuel_system_exhaust_emission_system/e_c_m_module_wiring_harness.html"

    data class ObservedIdentity(
        val ecuName: String?,
        val calibrationIds: List<String>,
        val calibrationVerificationNumbers: List<String>
    )

    /**
     * These observations are informational only. A CAL ID/CVN is not a service part
     * number; an ECU name mentioning E39 does not validate memory layout or recovery.
     */
    fun hasCompleteReadOnlyFingerprint(observed: ObservedIdentity): Boolean =
        !observed.ecuName.isNullOrBlank() &&
            observed.calibrationIds.any(String::isNotBlank) &&
            observed.calibrationVerificationNumbers.any(String::isNotBlank)

    fun describedServiceParts(): List<String> =
        listOf(currentServicePart, supersededServicePart)
}
