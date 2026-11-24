package com.camarodiag.gen5.data.obd

/**
 * Explicit contract for future hardware-specific ECU readers/programmers.
 * There is currently no registered ECU writer; no transport is interchangeable.
 */
interface EcuProgrammingDriverContract {
    enum class Transport {
        ELM_DIAGNOSTICS, J2534, BENCH, BOOT_BAM, NEXUS_JTAG, CHIP_OFF,
        CAN_DIRECT, BDM, EEPROM_ISP, SWD;

        /** No transport is disabled just by its marketing label.
         *  Actual memory write support must be provided by its registered driver.
         *  Unsupported ELM/STN adapters still have no such driver.
         */
        fun permitsMemoryProgramming(region: EcuBackupCoverage.Region): Boolean =
            when (this) {
                CHIP_OFF, EEPROM_ISP -> region == EcuBackupCoverage.Region.EXTERNAL_EEPROM
                ELM_DIAGNOSTICS, J2534, BENCH, BOOT_BAM, NEXUS_JTAG, CAN_DIRECT, BDM, SWD ->
                    region in setOf(EcuBackupCoverage.Region.CALIBRATION,
                        EcuBackupCoverage.Region.PROGRAM_FLASH, EcuBackupCoverage.Region.DATA_FLASH)
            }
    }
    enum class Capability {
        IDENTIFY, READ_REGION, ERASE_REGION, WRITE_REGION, READBACK_VERIFY, RESET
    }

    /**
     * An ECU family defines the shared diagnostic/programming protocol.
     * GM service-part supersessions, printed part numbers and BCC labels
     * are catalogue aliases, NOT separate protocols or firmware identifiers.
     *
     * FirmwareTarget represents one known image/firmware configuration for
     * authorizing a destructive write, not a dedicated protocol driver.
     */
    data class FirmwareTarget(val controllerFamily: String, val controllerId: String, val firmwareId: String)

    /** An approval applies to one firmware, region and physical route only. */
    data class WriteQualification(
        val target: FirmwareTarget,
        val region: EcuBackupCoverage.Region,
        val accessMethod: EcuBenchExpertPlan.AccessMethod
    )

    data class Descriptor(
        val id: String,
        val transport: Transport,
        val capabilities: Set<Capability>,
        val controllerFamilies: Set<String>,
        val physicalHardwareValidated: Boolean,
        val writeTargets: Set<FirmwareTarget> = emptySet(),
        val writeQualifications: Set<WriteQualification> = emptySet()
    ) {
        /** Shared protocol-family match; neither a firmware nor a service number is required. */
        fun supportsFamilyProtocol(controllerFamily: String, requestedTransport: Transport): Boolean =
            transport == requestedTransport &&
                controllerFamily.isNotBlank() &&
                controllerFamilies.any { family ->
                    family.trim().equals(controllerFamily.trim(), ignoreCase = true)
                } &&
                Capability.IDENTIFY in capabilities

        /**
         * Destructive operations require a separately validated image/firmware
         * configuration. Never infer flash approval from a catalogue supersession.
         * One protocol driver may support many firmware versions on that family.
         */
        fun supportsWrite(target: FirmwareTarget, requestedTransport: Transport): Boolean =
            physicalHardwareValidated &&
                supportsFamilyProtocol(target.controllerFamily, requestedTransport) &&
                target in writeTargets &&
                capabilities.containsAll(requiredWriteCapabilities) &&
                writeQualifications.any { approval ->
                    approval.target == target &&
                        transport.permitsMemoryProgramming(approval.region) &&
                        transport in approval.accessMethod.candidateTransports
                }

        fun supportsWrite(
            target: FirmwareTarget,
            requestedTransport: Transport,
            region: EcuBackupCoverage.Region,
            accessMethod: EcuBenchExpertPlan.AccessMethod
        ): Boolean = supportsWrite(target, requestedTransport) &&
            transport.permitsMemoryProgramming(region) &&
            transport in accessMethod.candidateTransports &&
            WriteQualification(target, region, accessMethod) in writeQualifications
    }

    data class Identity(val controllerId: String, val firmwareId: String, val vin: String?)
    data class RegionRequest(
        val region: EcuBackupCoverage.Region,
        val address: Long,
        val length: Long
    )

    val descriptor: Descriptor
    suspend fun identify(): Identity
    suspend fun read(request: RegionRequest): ByteArray
    suspend fun erase(request: RegionRequest)
    suspend fun write(request: RegionRequest, bytes: ByteArray)
    suspend fun independentReadback(request: RegionRequest): ByteArray
    suspend fun resetController()

    companion object {
        private val requiredWriteCapabilities = setOf(
            Capability.IDENTIFY, Capability.READ_REGION, Capability.ERASE_REGION,
            Capability.WRITE_REGION, Capability.READBACK_VERIFY
        )

        /**
         * No experimental or user-declared adapter may silently become a
         * registered writer. Add a hardware-validated implementation only after
         * the target ECU, firmware and recovery procedure are proven.
         */
        val registeredHardwareDrivers: List<EcuProgrammingDriverContract> = emptyList()

        fun hasValidatedWriteDriver(controllerFamily: String, transport: Transport): Boolean =
            registeredHardwareDrivers.any { driver ->
                driver.descriptor.writeTargets.any { target ->
                    target.controllerFamily == controllerFamily && driver.descriptor.supportsWrite(target, transport)
                }
            }
    }
}
