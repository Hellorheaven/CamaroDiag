package com.camarodiag.gen5.data.obd

import java.util.concurrent.CancellationException

/**
 * Multi-controller diagnostic inventory for 2012 Camaro. Controller addresses and DIDs are
 * deliberately not bundled: they must be backed by a vehicle-specific verified source.
 *
 * A manual-transmission vehicle must not be reported as having a TCM merely because
 * an automatic-transmission variant does.
 */
enum class CamaroControlModule(val displayName: String) {
    BCM("Body Control Module"),
    TCM("Transmission Control Module"),
    EBCM("Electronic Brake Control Module (ABS)"),
    IPC("Instrument Panel Cluster"),
    SDM("Sensing and Diagnostic Module (SRS)")
}

enum class CamaroTransmission { MANUAL, AUTOMATIC, UNKNOWN }

data class VerifiedModuleReadAddress(
    val module: CamaroControlModule,
    val requestCanId: Int,
    val responseCanId: Int,
    val extendedCanId: Boolean = false,
    val evidenceReference: String
) {
    init {
        val maxId = if (extendedCanId) 0x1FFFFFFF else 0x7FF
        require(requestCanId in 0..maxId && responseCanId in 0..maxId)
        require(requestCanId != responseCanId)
        require(evidenceReference.isNotBlank()) { "Verified source is required for module addressing." }
    }
}

sealed interface ModuleDiagnosticResult {
    val module: CamaroControlModule

    data class NotConfigured(override val module: CamaroControlModule, val reason: String) : ModuleDiagnosticResult
    data class ReadFailure(override val module: CamaroControlModule, val reason: String) : ModuleDiagnosticResult
    data class DtcRead(override val module: CamaroControlModule, val report: UdsDtcReport) : ModuleDiagnosticResult
}

/**
 * Non-destructive per-module DTC read. No discovery broadcasts, guessed CAN identifiers,
 * session changes, fault clearing or SRS actuator commands.
 */
class CamaroModuleDtcScanner(
    private val transportFactory: (VerifiedModuleReadAddress) -> RawCanFrameTransport
) {
    suspend fun scan(
        transmission: CamaroTransmission,
        verifiedAddresses: Collection<VerifiedModuleReadAddress>,
        timeoutMs: Long = 3000L
    ): List<ModuleDiagnosticResult> {
        require(timeoutMs in 1..30_000L) { "Per-module read deadline out of range." }
        require(verifiedAddresses.map { it.module }.distinct().size == verifiedAddresses.size) {
            "Multiple address profiles for the same module must be resolved by vehicle identity first."
        }
        val profiles = verifiedAddresses.associateBy { it.module }
        return CamaroControlModule.entries.map { module ->
            when {
                module == CamaroControlModule.TCM && transmission == CamaroTransmission.MANUAL ->
                    ModuleDiagnosticResult.NotConfigured(module, "Manual transmission: no TCM assumed.")
                profiles[module] == null ->
                    ModuleDiagnosticResult.NotConfigured(module, "No verified vehicle-specific CAN address.")
                else -> {
                    val address = requireNotNull(profiles[module])
                    try {
                        val client = UdsReadOnlyClient(
                            transportFactory(address),
                            address.requestCanId,
                            address.responseCanId,
                            address.extendedCanId,
                            timeoutMs
                        )
                        ModuleDiagnosticResult.DtcRead(module, client.readDtcReport())
                    } catch (e: CancellationException) {
                        // An operator cancellation must interrupt the entire scan,
                        // not silently continue to query the next ECU.
                        throw e
                    } catch (e: Exception) {
                        // Never turn a timeout, negative UDS response or bus failure into "no faults".
                        ModuleDiagnosticResult.ReadFailure(module, e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }
    }
}
