package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.AdapterCapabilities
import com.camarodiag.gen5.domain.model.AdapterTier

/**
 * Shared admission gate for a real STPX/ISO-TP diagnostic session.
 *
 * Manufacturer models and service-part references are NOT protocol drivers:
 * probe the connected STN firmware and verify a real OBD response instead.
 * The transport performs its own STI/STDI model verification before sending UDS.
 */
internal object StnUdsSessionAdmission {
    /** Admission for a read-only probe of a different ECU, even when OBD PID 00 was absent. */
    fun allowsReadCandidate(adapter: AdapterCapabilities?, physicalCommandChannel: Boolean,
        protocolNumber: Int): Boolean {
        if (!physicalCommandChannel || adapter == null || !adapter.supportsCanHeaders ||
            !adapter.firmwareId.contains("STN", ignoreCase = true)) return false
        val model = StnUdsPayloadTransport.hardwareModel(adapter.hardwareId) ?: return false
        return when (protocolNumber) {
            33, 34, 35, 36 -> true
            63, 64 -> adapter.supportsSwCan && model in setOf("MX", "MX+")
            else -> false
        }
    }

    fun allowsRead(adapter: AdapterCapabilities?, physicalCommandChannel: Boolean): Boolean =
        physicalCommandChannel && adapter != null &&
            adapter.tier == AdapterTier.PRO_STN &&
            adapter.firmwareId.contains("STN", ignoreCase = true) &&
            adapter.supportsCanHeaders &&
            adapter.supportsCanTransmit
}
