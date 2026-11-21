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

    data class ServicePartGroup(
        val servicePart: String,
        val replaces: List<String>,
        val exampleLabel: String,
        val catalogBootFile: String? = null
    )

    // Camaro MY2012 V6 catalog supersessions. A predecessor can also appear on
    // other models. The label on one donor identifies only that physical part.
    // 12653998 is deliberately excluded: GM bulletin PIC5696A limits it to MY2013.
    val servicePartGroups = listOf(
        // GMPartsGiant 2012 Camaro ECM catalog; Ovoko donor EVY6479.
        ServicePartGroup(
            servicePart = currentServicePart,
            replaces = listOf(supersededServicePart),
            exampleLabel = "Serv# 12643248 · 12648906 · AA69",
            catalogBootFile = associatedMainBootFile
        ),
        // https://www.chevypartsdeal.com/oem/gm~engine-control-module-w-2nd-mpu~12651993
        // Camaro donor label: https://www.ebay.com/itm/235840055258
        ServicePartGroup(
            servicePart = "12651993",
            replaces = listOf("12637106", "12642665", "12650256", "12616889"),
            exampleLabel = "Serv# 12637106 · 12637489 · AAMU"
        )
    )

    data class Label(val serviceNumber: String, val partNumber: String, val bcc: String)

    // Individual MY2012 3.6L catalog records, searchable by their printed identifiers.
    // The BCC on a replacement part does not attest to the software currently installed.
    // https://www.fs1inc.com/2012-chevrolet-camaro-3-6l-ecm-engine-computer-pcm-ecu-programmed-plug-play.html
    private val catalogLabels = listOf(
        Label("12616889", "12635821", "AAKG"),
        Label("12616889", "12636333", "AALW"),
        Label("12637106", "12636635", "AAL9"),
        Label("12637106", "12637157", "AAMK"),
        Label("12637106", "12637489", "AAMU"),
        Label("12637106", "12639985", "AAUB"),
        Label("12637106", "12639986", "AAUC"),
        Label("12637106", "12640385", "AAUL"),
        Label("12642665", "12641497", "AAWD"),
        Label("12642665", "12642569", "AAXP"),
        Label("12642665", "12642612", "AAXS"),
        Label("12642665", "12642843", "AAXY"),
        Label("12642665", "12642856", "AAX1"),
        Label("12642665", "12643469", "AAYY"),
        Label("12642665", "12643542", "AAZA"),
        Label("12642665", "12646438", "AA3Y"),
        Label("12642665", "12646944", "AA4G"),
        Label("12642665", "12647214", "AA4P"),
        Label("12643248", "12647402", "AA4U"),
        Label("12643248", "12648432", "AA6L"),
        Label("12643248", "12648906", "AA69"),
        Label("12643248", "12651869", "ABAS"),
        Label("12651994", "12650248", "AA8R"),
        Label("12651994", "12652814", "ABDL"),
        Label("12651994", "12653090", "ABG4"),
        Label("12651994", "12655480", "ABMY"),
        Label("12651994", "12663029", "AB3M")
    )

    fun findLabels(query: String): List<Label> {
        val value = query.trim()
        if (value.isEmpty()) return emptyList()
        return catalogLabels.filter {
            it.serviceNumber == value || it.partNumber == value || it.bcc.equals(value, ignoreCase = true)
        }
    }

    /**
     * Service numbers can be superseded and printed part numbers/BCC labels may
     * represent different calibrations on the same ECU protocol family.
     * Resolve them to the catalogued family without pretending that they are
     * separate communication drivers or proven identical circuit boards.
     */
    enum class CatalogReferenceKind {
        CURRENT_SERVICE_NUMBER, SUPERSEDED_SERVICE_NUMBER, PRINTED_PART_NUMBER, BROADCAST_CODE
    }

    data class CatalogReference(
        val family: String,
        val serviceGroup: String,
        val kind: CatalogReferenceKind,
        val queriedReference: String
    ) {
        /** A matching catalogue reference must never grant flash permission. */
        val authorizesProgramming: Boolean get() = false
    }

    fun resolveCatalogReference(reference: String): List<CatalogReference> {
        val query = reference.trim().uppercase()
        if (query.isEmpty()) return emptyList()
        return servicePartGroups.mapNotNull { group ->
            val knownServiceNumbers = (group.replaces + group.servicePart).toSet()
            val match = when {
                query == group.servicePart -> CatalogReferenceKind.CURRENT_SERVICE_NUMBER
                query in group.replaces -> CatalogReferenceKind.SUPERSEDED_SERVICE_NUMBER
                catalogLabels.any {
                    it.serviceNumber in knownServiceNumbers && it.partNumber == query
                } -> CatalogReferenceKind.PRINTED_PART_NUMBER
                catalogLabels.any {
                    it.serviceNumber in knownServiceNumbers && it.bcc.equals(query, ignoreCase = true)
                } -> CatalogReferenceKind.BROADCAST_CODE
                else -> null
            }
            match?.let { CatalogReference(controllerFamily, group.servicePart, it, query) }
        }
    }

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
        if (normalized.any { address ->
                !validAddress.matches(address) ||
                    address.toLong(16) > if (address.length == 3) 0x7FFL else 0x1FFFFFFFL
            }) {
            return ResponderAttribution.NO_VALID_CAN_ADDRESS
        }
        return if (normalized.distinct().size == 1) {
            ResponderAttribution.SINGLE_ADDRESS_OBSERVED
        } else {
            ResponderAttribution.MULTIPLE_ADDRESSES_OBSERVED
        }
    }

    fun describedServiceParts(): List<String> =
        servicePartGroups.flatMap { listOf(it.servicePart) + it.replaces }
}
