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
        hasMeaningfulComponent(observed.ecuName.orEmpty()) &&
            observed.calibrationIds.any(::hasMeaningfulComponent) &&
            observed.calibrationVerificationNumbers.any(::hasMeaningfulComponent)

    // Identification responses may contain several CAL IDs/CVNs joined into one
    // display string. Validate each item, not the concatenation of placeholders.
    private fun hasMeaningfulComponent(response: String): Boolean =
        response.split('\r', '\n', '|', ';').any(::isMeaningfulIdentifier)

    /**
     * Reject demo/placeholder diagnostic fields before presenting an ECU
     * identification as complete. A complete fingerprint is still informational;
     * it never establishes a safe firmware, memory layout or flash procedure.
     */
    private fun isMeaningfulIdentifier(raw: String?): Boolean {
        val value = raw?.trim()?.uppercase().orEmpty()
        if (value.isEmpty() || value in setOf(
                "UNKNOWN", "N/A", "NONE", "NULL", "NOT AVAILABLE", "NOT SUPPORTED",
                "PENDING", "TBD", "TEST", "DEMO", "SIMULATED", "-"
            )
        ) return false
        if (listOf("UNKNOWN", "DEMO", "SIMULATED", "SAMPLE").any(value::startsWith)) return false
        val compact = value.removePrefix("0X").replace(" ", "")
        if (compact.isEmpty()) return false
        if (compact.all { it == '0' } || compact.all { it == 'F' }) return false
        return true
    }

    fun describedServiceParts(): List<String> =
        listOf(currentServicePart, supersededServicePart)
}
