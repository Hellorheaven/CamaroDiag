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
        val calibrationVerificationNumbers: List<String>,
        val simulated: Boolean = false
    )

    /**
     * These observations are informational only. A CAL ID/CVN is not a service part
     * number; an ECU name mentioning E39 does not validate memory layout or recovery.
     */
    fun hasCompleteReadOnlyFingerprint(observed: ObservedIdentity): Boolean =
        !observed.simulated &&
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
        if (value.isEmpty() || value in setOf("N/A", "NONE", "NULL", "-", "?")) return false
        // ELM and diagnostic placeholders must never count as observed ECU evidence.
        // Reject placeholder tokens even when the response prefixes them with "ECU:".
        val placeholder = Regex(
            "(?i)(?:^|[^A-Z0-9])(?:UNKNOWN|UNAVAILABLE|DEMO|SIMULATED|SAMPLE|" +
                "INVALID|NOT AVAILABLE|NOT SUPPORTED|NO DATA|NO RESPONSE|PENDING|" +
                "TBD|TEST|ERROR|STOPPED)(?:$|[^A-Z0-9])"
        )
        if (placeholder.containsMatchIn(value)) return false
        // Some adapters format zero/FF responses with hex prefixes and separators.
        val compact = value.removePrefix("0X").replace(Regex("[\\s:_-]"), "")
        if (compact.isEmpty()) return false
        if (compact.all { it == '0' } || compact.all { it == 'F' }) return false
        return true
    }

    /** Candidate E39/E39A family label, not proof of a specific physical ECU or firmware. */
    fun hasCandidateE39FamilyLabel(ecuName: String?): Boolean =
        ecuName?.let { name ->
            hasMeaningfulComponent(name) &&
                Regex("(?i)(?:^|[^A-Z0-9])E39A?(?:$|[^A-Z0-9])").containsMatchIn(name)
        } ?: false

    /**
     * Distinguishes an unknown, single or mixed responder set observed during Mode 09.
     * Even one response address does not prove which physical ECU supplied a CAL ID,
     * that the ECU is an ECM, or that its firmware is compatible with writing.
     */
    enum class ResponderAttribution {
        NO_VALID_CAN_ADDRESS,
        SINGLE_ADDRESS_OBSERVED,
        MULTIPLE_ADDRESSES_OBSERVED
    }

    fun classifyResponderAttribution(respondingAddresses: Collection<String>): ResponderAttribution {
        val rawAddresses = respondingAddresses.map(String::trim).filter(String::isNotEmpty)
        if (rawAddresses.isEmpty()) return ResponderAttribution.NO_VALID_CAN_ADDRESS
        val validAddress = Regex("(?:[0-9A-F]{3}|[0-9A-F]{8})")
        val normalized = rawAddresses.map { it.uppercase().removePrefix("0X") }
        if (normalized.any { !validAddress.matches(it) }) {
            return ResponderAttribution.NO_VALID_CAN_ADDRESS
        }
        return if (normalized.distinct().size == 1) {
            ResponderAttribution.SINGLE_ADDRESS_OBSERVED
        } else {
            ResponderAttribution.MULTIPLE_ADDRESSES_OBSERVED
        }
    }

    fun describedServiceParts(): List<String> =
        listOf(currentServicePart, supersededServicePart)
}
