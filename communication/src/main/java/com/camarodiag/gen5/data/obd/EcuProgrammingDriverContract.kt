package com.camarodiag.gen5.data.obd

/**
 * Explicit contract for future hardware-specific ECU readers/programmers.
 * There is currently no registered ECU writer; no transport is interchangeable.
 */
interface EcuProgrammingDriverContract {
    enum class Transport { ELM_DIAGNOSTICS, J2534, BENCH, BOOT_BAM, NEXUS_JTAG, CHIP_OFF }
    enum class Capability {
        IDENTIFY, READ_REGION, ERASE_REGION, WRITE_REGION, READBACK_VERIFY, RESET
    }

    data class Descriptor(
        val id: String,
        val transport: Transport,
        val capabilities: Set<Capability>,
        val controllerFamilies: Set<String>,
        val physicalHardwareValidated: Boolean
    )

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
        /**
         * No experimental or user-declared adapter may silently become a
         * registered writer. Add a hardware-validated implementation only after
         * the target ECU, firmware and recovery procedure are proven.
         */
        val registeredHardwareDrivers: List<EcuProgrammingDriverContract> = emptyList()

        fun hasValidatedWriteDriver(controllerFamily: String, transport: Transport): Boolean =
            registeredHardwareDrivers.any {
                it.descriptor.physicalHardwareValidated &&
                    controllerFamily in it.descriptor.controllerFamilies &&
                    it.descriptor.transport == transport &&
                    Capability.ERASE_REGION in it.descriptor.capabilities &&
                    Capability.WRITE_REGION in it.descriptor.capabilities &&
                    Capability.READBACK_VERIFY in it.descriptor.capabilities &&
                    Capability.READ_REGION in it.descriptor.capabilities
            }
    }
}
