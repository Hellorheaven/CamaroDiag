package com.camarodiag.gen5.data.obd

/**
 * A USB permission grant may only resume the exact adapter selected by the
 * operator. No protocol or ECU command is sent from this policy.
 */
object UsbPermissionReconnectPolicy {
    data class Pending(
        val transport: String,
        val endpoint: String,
        val deviceId: Int,
        val generation: Long
    )

    fun forSelection(transport: String, endpoint: String, generation: Long): Pending? {
        val dedicated = CamaroDiagConnectorDiscovery.isConnectorUsbLabel(endpoint)
        val kkl = CamaroDiagConnectorDiscovery.isLegacyKklUsbLabel(endpoint)
        when (transport) {
            "usb" -> if (dedicated || kkl) return null
            "connector_usb", "connector_usb_binary" -> if (!dedicated || kkl) return null
            else -> return null
        }
        val id = CamaroDiagConnectorDiscovery.deviceIdFromLabel(endpoint) ?: return null
        return Pending(transport, endpoint, id, generation)
    }

    fun mayResume(
        pending: Pending?,
        deviceId: Int,
        granted: Boolean,
        currentGeneration: Long,
        enumerated: List<String>
    ): Boolean {
        if (pending == null || !granted || pending.deviceId != deviceId ||
            pending.generation != currentGeneration || pending.endpoint !in enumerated ||
            CamaroDiagConnectorDiscovery.deviceIdFromLabel(pending.endpoint) != deviceId
        ) return false

        // An Android device ID is only valid for the current enumeration. Avoid
        // silently choosing a different serial interface if labels collide or
        // discovery now assigns this ID to another adapter type.
        if (enumerated.count {
                CamaroDiagConnectorDiscovery.deviceIdFromLabel(it) == deviceId
            } != 1
        ) return false
        return when (pending.transport) {
            "usb" -> !CamaroDiagConnectorDiscovery.requiresDedicatedUsbDiscovery(deviceId, enumerated) &&
                !CamaroDiagConnectorDiscovery.requiresLegacyKklIsolation(deviceId, enumerated)
            "connector_usb", "connector_usb_binary" ->
                CamaroDiagConnectorDiscovery.allowsDedicatedUsbDiscovery(deviceId, enumerated)
            else -> false
        }
    }
}
