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
    private const val EXPECTED_CAPS = "CAPS hs_can=disabled sw_can=disabled eeprom_read=disabled eeprom_write=disabled ecu_flash=disabled j2534=disabled"

    interface Channel {
        fun write(bytes: ByteArray)
        fun read(buffer: ByteArray, timeoutMs: Int): Int
    }

    /** Public discovery replies establish neither device identity nor a secured session. */
    data class Report(val firmware: String, val lines: List<String>) {
        val authenticated: Boolean get() = false
        val vehicleAccessAllowed: Boolean get() = false

        /** Never map advertised discovery fields to a live diagnostic capability. */
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

    /** Explicit product-name hints only: FTDI is also used by real ELM devices. */
    fun isLegacyKklUsbLabel(label: String): Boolean =
        Regex("(?i)(?<![a-z0-9])(?:KKL|K[- ]?LINE)(?![a-z0-9])").containsMatchIn(label)

    fun requiresLegacyKklIsolation(deviceId: Int, deviceLabels: List<String>): Boolean =
        deviceLabels.any { label ->
            isLegacyKklUsbLabel(label) &&
                Regex("#(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull() == deviceId
        }

    fun requiresDedicatedUsbDiscovery(deviceId: Int, deviceLabels: List<String>): Boolean =
        deviceLabels.any { label ->
            isConnectorUsbLabel(label) &&
                Regex("#(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull() == deviceId
        }

    /** USB routing gate: a product-name match is necessary for discovery,
     * but never grants a diagnostic session or proves device authentication.
     * KKL isolation wins even if a label also contains CamaroDiagConnectors. */
    fun allowsDedicatedUsbDiscovery(deviceId: Int, deviceLabels: List<String>): Boolean =
        !requiresLegacyKklIsolation(deviceId, deviceLabels) &&
            requiresDedicatedUsbDiscovery(deviceId, deviceLabels)

    fun inspect(channel: Channel, ensureActive: () -> Unit = {}): Report =
        inspectWithClock(channel, ensureActive, System::nanoTime)

    /** Fixed-clock injection for deadline regression tests; never exposes ECU access. */
    internal fun inspectWithClock(
        channel: Channel,
        ensureActive: () -> Unit = {},
        clock: () -> Long
    ): Report {
        fun exchange(command: String): String {
            ensureActive()
            // Include USB write time in the deadline, not just response reads.
            val deadline = clock() + TIMEOUT_NANOS
            channel.write((command + "\n").toByteArray(Charsets.US_ASCII))
            ensureActive()
            val reply = StringBuilder()
            // CDC bulk USB may deliver the complete CAPS reply in one packet.
            // Do not truncate it to the 64-byte chunk used by older serial devices.
            val buffer = ByteArray(512)
            while (clock() - deadline < 0) {
                ensureActive()
                val count = channel.read(buffer, 100)
                if (count < 0 || count > buffer.size) throw IOException("Invalid USB read")
                for (i in 0 until count) {
                    val value = buffer[i].toInt() and 0xff
                    if (value == 10) {
                        if (i != count - 1) throw IOException("Unexpected trailing USB data")
                        ensureActive()
                        if (clock() - deadline >= 0) throw IOException("Connector discovery timed out")
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
        return verifyReplies({ opcode ->
            exchange(when (opcode) {
                1 -> "PING"
                2 -> "CAPS"
                3 -> "STATUS"
                else -> error("Unsupported discovery opcode")
            })
        }, ensureActive)
    }

    /** Shared policy for both public discovery formats; neither grants ECU access. */
    internal fun verifyReplies(exchange: (Int) -> String, ensureActive: () -> Unit): Report {
        val ping = exchange(1)
        if (ping != "PONG CamaroDiagConnectors 0.1.0") throw IOException("Unsupported connector identity/version")
        val caps = exchange(2)
        if (caps != EXPECTED_CAPS)
            throw IOException("Prototype capability response differs from the published read-only firmware")
        val status = exchange(3)
        if (status != "STATUS mode=prototype write=disabled")
            throw IOException("Unsupported connector status")
        ensureActive()
        return Report("0.1.0", listOf(ping, caps, status))
    }
}
