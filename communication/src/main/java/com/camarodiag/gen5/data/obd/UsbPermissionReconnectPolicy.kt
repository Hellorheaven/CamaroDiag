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
        if (transport !in setOf("usb", "connector_usb", "connector_usb_binary")) return null
        val id = CamaroDiagConnectorDiscovery.deviceIdFromLabel(endpoint) ?: return null
        return Pending(transport, endpoint, id, generation)
    }

    fun mayResume(
        pending: Pending?,
        deviceId: Int,
        granted: Boolean,
        currentGeneration: Long,
        enumerated: List<String>
    ): Boolean = pending != null && granted &&
        pending.deviceId == deviceId &&
        pending.generation == currentGeneration &&
        pending.endpoint in enumerated &&
        CamaroDiagConnectorDiscovery.deviceIdFromLabel(pending.endpoint) == deviceId &&
        (!pending.transport.startsWith("connector_usb") ||
            CamaroDiagConnectorDiscovery.allowsDedicatedUsbDiscovery(deviceId, enumerated))
}
