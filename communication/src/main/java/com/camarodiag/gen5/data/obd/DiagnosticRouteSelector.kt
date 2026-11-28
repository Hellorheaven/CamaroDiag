package com.camarodiag.gen5.data.obd

/** Physical link, framing and service are independent. A route is executable only
 * when the connector, vehicle, ECU and operation all provide matching evidence.
 * Discovery is a separate operation; unknown capabilities never imply support.
 */
object DiagnosticRouteSelector {
    enum class Link { CAN, SW_CAN, CAN_FD, K_LINE, J1850_PWM, J1850_VPW, ETHERNET, JTAG, BOOT, SPI }
    enum class Framing { RAW, ISO_TP, KWP, ISO9141, J1850, DOIP, J1939, NATIVE }
    enum class Service { RAW, OBD, UDS, GMLAN, KWP, J1939, MEMORY }
    enum class Operation { CONSOLE, IDENTIFY, DTC, MONITOR, BACKUP, RESTORE, PROGRAM, FLEX_FUEL }
    enum class Evidence { UNKNOWN, ADVERTISED, DETECTED, VERIFIED }
    data class Protocol(val link: Link, val framing: Framing, val service: Service)
    data class Capability(val protocol: Protocol, val evidence: Evidence,
        val implemented: Boolean, val operations: Set<Operation>,
        // Never derive a physical programming interface from link framing.
        // An ELM CAN transport and a J2534 CAN transport are not interchangeable.
        val programmingTransport: EcuProgrammingDriverContract.Transport? = null)
    data class Target(val vehicleKey: String, val ecuKey: String,
        val vehicleLinks: Set<Link>, val ecuProtocols: Set<Protocol>,
        val addressingVerified: Boolean,
        // Exact *observed* target, not a catalogue family or user-input label.
        val programmingFirmware: EcuProgrammingDriverContract.FirmwareTarget? = null,
        val programmingRegion: EcuBackupCoverage.Region? = null,
        val programmingAccess: EcuBenchExpertPlan.AccessMethod? = null)
    data class Route(val capability: Capability, val priority: Int)
    enum class Rejection {
        VEHICLE_LINK, ECU_PROTOCOL, OPERATION, NOT_IMPLEMENTED, UNVERIFIED, ADDRESS,
        UNQUALIFIED_PROGRAMMING_DRIVER
    }
    data class Excluded(val capability: Capability, val reasons: Set<Rejection>)
    data class Selection(val routes: List<Route>, val excluded: List<Excluded>)

    fun select(capabilities: List<Capability>, target: Target, operation: Operation,
        preference: List<Protocol> = emptyList()): Selection =
        selectAgainstVerifiedHardware(capabilities, target, operation, preference,
            EcuProgrammingDriverContract.registeredHardwareDrivers.map { it.descriptor })

    /** Offline descriptor comparison is testable without registering fake ECU writers.
     * Production always calls select() and uses the actual hardware registry.
     */
    internal fun selectAgainstVerifiedHardware(
        capabilities: List<Capability>, target: Target, operation: Operation,
        preference: List<Protocol>,
        hardwareDrivers: List<EcuProgrammingDriverContract.Descriptor>
    ): Selection {
        require(target.vehicleKey.isNotBlank() && target.ecuKey.isNotBlank())
        val routes = mutableListOf<Route>()
        val excluded = mutableListOf<Excluded>()
        for (capability in capabilities) {
            val protocol = capability.protocol
            val reasons = mutableSetOf<Rejection>()
            if (protocol.link !in target.vehicleLinks) reasons += Rejection.VEHICLE_LINK
            if (protocol !in target.ecuProtocols) reasons += Rejection.ECU_PROTOCOL
            if (operation !in capability.operations) reasons += Rejection.OPERATION
            if (!capability.implemented) reasons += Rejection.NOT_IMPLEMENTED
            if (capability.evidence != Evidence.VERIFIED) reasons += Rejection.UNVERIFIED
            if (!target.addressingVerified) reasons += Rejection.ADDRESS
            if (operation == Operation.PROGRAM || operation == Operation.RESTORE) {
                val firmware = target.programmingFirmware
                val region = target.programmingRegion
                val access = target.programmingAccess
                val transport = capability.programmingTransport
                if (firmware == null || region == null || access == null ||
                    transport == null || hardwareDrivers.none { driver ->
                        driver.supportsWrite(firmware, transport, region, access)
                    }) {
                    reasons += Rejection.UNQUALIFIED_PROGRAMMING_DRIVER
                }
            }
            if (reasons.isEmpty()) {
                val rank = preference.indexOf(protocol).let { if (it < 0) Int.MAX_VALUE else it }
                routes += Route(capability, rank)
            } else excluded += Excluded(capability, reasons.toSet())
        }
        return Selection(routes.sortedWith(compareBy<Route> { it.priority }
            .thenBy { it.capability.protocol.link.name }
            .thenBy { it.capability.protocol.framing.name }
            .thenBy { it.capability.protocol.service.name }), excluded)
    }

    const val SW_CAN_BITRATE = 33_333

    /** 61/62 are STN raw ISO 11898, not segmented ISO 15765 presets. */
    fun rawCanProfile(extended: Boolean, bitrate: Int): Int =
        if (bitrate == SW_CAN_BITRATE) { if (extended) 62 else 61 }
        else elmCanProtocol(extended, bitrate)

    /** Explicit CAN wire profile, never inferred from an adapter brand. */
    fun elmCanProtocol(extended: Boolean, bitrate: Int): Int = when (bitrate) {
        500_000 -> if (extended) 7 else 6
        250_000 -> if (extended) 9 else 8
        else -> throw IllegalArgumentException("Unsupported standard ELM CAN bitrate")
    }
}
