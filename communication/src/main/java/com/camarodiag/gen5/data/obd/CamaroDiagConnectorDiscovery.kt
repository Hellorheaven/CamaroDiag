package com.camarodiag.gen5.data.obd

import java.io.IOException

/**
 * Direct USB discovery of the published CamaroDiagConnectors 0.1.0 prototype.
 * ASCII discovery is not the future binary transport and must never enable
 * vehicle traffic or physical memory writes, even if a device claims support.
 */
object CamaroDiagConnectorDiscovery {
    const val BAUD_RATE = 115200
    private const val MAX_REPLY_BYTES = 256
    private const val TIMEOUT_NANOS = 3_000_000_000L
    private val keys = setOf("hs_can", "sw_can", "eeprom_read", "eeprom_write", "ecu_flash", "j2534")

    interface Channel {
        fun write(bytes: ByteArray)
        fun read(buffer: ByteArray, timeoutMs: Int): Int
    }

    /** Public ASCII replies establish neither device identity nor a secured session. */
    data class Report(val firmware: String, val lines: List<String>) {
        val authenticated: Boolean get() = false
        val vehicleAccessAllowed: Boolean get() = false

        /** Never map advertised ASCII fields to a live diagnostic capability. */
        fun toAdapterCapabilities() = com.camarodiag.gen5.domain.model.AdapterCapabilities(
            deviceName = "CamaroDiagConnectors",
            firmwareId = firmware,
            rawProbeLog = lines.toList()
        )
    }

    /**
     * Routing hint only, not authentication. A named connector must never be
     * probed as ELM, including when selected through a stale saved profile.
     */
    fun isConnectorUsbLabel(label: String): Boolean =
        label.contains("CamaroDiagConnectors", ignoreCase = true)

    fun requiresDedicatedUsbDiscovery(deviceId: Int, deviceLabels: List<String>): Boolean =
        deviceLabels.any { label ->
            isConnectorUsbLabel(label) &&
                Regex("#(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull() == deviceId
        }

    fun inspect(channel: Channel, ensureActive: () -> Unit = {}): Report {
        fun exchange(command: String): String {
            ensureActive()
            channel.write((command + "\n").toByteArray(Charsets.US_ASCII))
            val deadline = System.nanoTime() + TIMEOUT_NANOS
            val reply = StringBuilder()
            val buffer = ByteArray(64)
            while (System.nanoTime() - deadline < 0) {
                ensureActive()
                val count = channel.read(buffer, 100)
                if (count < 0 || count > buffer.size) throw IOException("Invalid USB read")
                for (i in 0 until count) {
                    val value = buffer[i].toInt() and 0xff
                    if (value == 10) {
                        if (i != count - 1) throw IOException("Unexpected trailing USB data")
                        val line = reply.toString().removeSuffix("\r")
                        if (line.isEmpty() || line.contains('\r')) throw IOException("Invalid connector reply")
                        return line
                    }
                    if (value != 13 && value !in 32..126 || reply.length >= MAX_REPLY_BYTES - 1)
                        throw IOException("Invalid or oversized connector reply")
                    reply.append(value.toChar())
                }
            }
            throw IOException("Connector discovery timed out")
        }
        val ping = exchange("PING")
        if (ping != "PONG CamaroDiagConnectors 0.1.0") throw IOException("Unsupported connector identity/version")
        val caps = exchange("CAPS")
        val tokens = caps.split(' ')
        if (tokens.firstOrNull() != "CAPS" || tokens.size != keys.size + 1)
            throw IOException("Invalid capability response")
        val seen = mutableSetOf<String>()
        for (token in tokens.drop(1)) {
            val pair = token.split('=')
            if (pair.size != 2 || pair[0] !in keys || !seen.add(pair[0]) || pair[1] != "disabled")
                throw IOException("Prototype capability not supported or malformed")
        }
        val status = exchange("STATUS")
        if (status != "STATUS mode=prototype write=disabled")
            throw IOException("Unsupported connector status")
        ensureActive()
        return Report("0.1.0", listOf(ping, caps, status))
    }
}
