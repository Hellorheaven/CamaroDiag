package com.camarodiag.gen5.ui

/** Conservative gate for attributing generic OBD DTCs to the selected Camaro ECM. */
internal object ObdDtcGuidancePolicy {
    private val vinPattern = Regex("[A-HJ-NPR-Z0-9]{17}")

    fun hasMatchedEcm(
        selectedVin: String?,
        reportedVin: String?,
        responderAddresses: Collection<String>,
        simulation: Boolean
    ): Boolean {
        if (simulation) return false
        val expected = selectedVin?.trim()?.uppercase() ?: return false
        val observed = reportedVin?.trim()?.uppercase() ?: return false
        if (!vinPattern.matches(expected) || observed != expected) return false
        // 7E8 is the standard 11-bit physical engine-controller reply address.
        // Ambiguous responder sets cannot establish which ECU originated a DTC.
        if (responderAddresses.size != 1) return false
        val response = responderAddresses.single().trim()
            .removePrefix("0x").removePrefix("0X").uppercase()
        return response == "7E8"
    }
}
