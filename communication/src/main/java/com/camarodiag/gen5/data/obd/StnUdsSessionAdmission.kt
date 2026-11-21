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
    fun allowsRead(adapter: AdapterCapabilities?, physicalCommandChannel: Boolean): Boolean =
        physicalCommandChannel && adapter != null &&
            adapter.tier == AdapterTier.PRO_STN &&
            adapter.firmwareId.contains("STN", ignoreCase = true) &&
            adapter.supportsCanHeaders &&
            adapter.supportsCanTransmit
}
