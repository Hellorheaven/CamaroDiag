package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.CancellationException

/**
 * Guarded, per-erase-sector programming sequence.
 *
 * This is a software foundation, NOT a validated LFX/Gen5/Gen6 ECU writer.
 * The production entry point obtains a driver exclusively from the (currently
 * empty) hardware-validated registry. Unit tests exercise the internal engine
 * with a fake in-memory controller; no real protocol is inferred.
 *
 * A caller-provided Boolean is not a substitute for OEM checksum validation,
 * verified memory geometry, stable power, or a real independent ECU backup.
 */
object EcuWriteTransaction {
    data class SafetyEvidence(
        val oemChecksumValidated: Boolean = false,
        val independentHardwareBackupVerified: Boolean = false,
        val recoveryProcedureValidated: Boolean = false
    )

    data class Request(
        val controllerFamily: String,
        val transport: EcuProgrammingDriverContract.Transport,
        val expectedIdentity: EcuProgrammingDriverContract.Identity,
        val region: EcuBackupCoverage.Region,
        val layout: EcuPartialWritePlanner.Layout,
        val originalImage: ByteArray,
        val updatedImage: ByteArray,
        val evidence: SafetyEvidence
    )

    enum class Blocker {
        UNKNOWN_TARGET, DIAGNOSTIC_ONLY_TRANSPORT, UNVERIFIED_MEMORY_MAP,
        UNVERIFIED_OEM_CHECKSUM, UNVERIFIED_HARDWARE_BACKUP, NO_VALIDATED_RECOVERY,
        INVALID_IMAGE_OR_LAYOUT, NO_VALIDATED_DRIVER
    }

    data class Preparation(
        val blockers: Set<Blocker>,
        val changedBytes: Long,
        val affectedEraseSectors: Int
    ) {
        val canAttemptWithValidatedHardware: Boolean get() = blockers.isEmpty()
    }

    enum class Stage { BASELINE_CHECKED, BEFORE_ERASE, VERIFIED_AFTER_WRITE }
    data class Record(
        val stage: Stage,
        val region: EcuBackupCoverage.Region,
        val address: Long,
        val originalSha256: String,
        val updatedSha256: String
    )

    /** Must be backed by persistent storage before enabling real hardware. */
    fun interface Journal {
        suspend fun append(record: Record)
    }

    /** Live voltage/power health must be verified by target-specific hardware. */
    fun interface PowerMonitor {
        suspend fun isStable(): Boolean
    }

    enum class Status { BLOCKED, NO_CHANGE, COMPLETED, STOPPED_REQUIRES_ASSESSMENT }

    data class Outcome(
        val status: Status,
        val blockers: Set<Blocker> = emptySet(),
        val verifiedSectors: Int = 0,
        val failedAddress: Long? = null
    )

    fun prepare(request: Request): Preparation {
        val blockers = linkedSetOf<Blocker>()
        if (request.controllerFamily.isBlank() ||
            request.expectedIdentity.controllerId.isBlank() ||
            request.expectedIdentity.firmwareId.isBlank() ||
            request.expectedIdentity.vin.isNullOrBlank()
        ) blockers += Blocker.UNKNOWN_TARGET
        if (request.transport == EcuProgrammingDriverContract.Transport.ELM_DIAGNOSTICS) {
            blockers += Blocker.DIAGNOSTIC_ONLY_TRANSPORT
        }
        if (!request.layout.mapVerifiedForTargetEcu) blockers += Blocker.UNVERIFIED_MEMORY_MAP
        if (!request.evidence.oemChecksumValidated) blockers += Blocker.UNVERIFIED_OEM_CHECKSUM
        if (!request.evidence.independentHardwareBackupVerified) {
            blockers += Blocker.UNVERIFIED_HARDWARE_BACKUP
        }
        if (!request.evidence.recoveryProcedureValidated) blockers += Blocker.NO_VALIDATED_RECOVERY
        val preview = try {
            EcuPartialWritePlanner.preview(
                request.layout, request.originalImage.copyOf(), request.updatedImage.copyOf()
            )
        } catch (_: IllegalArgumentException) {
            blockers += Blocker.INVALID_IMAGE_OR_LAYOUT
            null
        }
        return Preparation(
            blockers = blockers,
            changedBytes = preview?.changedBytes ?: 0L,
            affectedEraseSectors = preview?.affectedSectors?.size ?: 0
        )
    }

    /**
     * No externally supplied driver is accepted here. Until validated hardware
     * drivers are explicitly registered, no erase/write call is possible.
     */
    suspend fun execute(
        request: Request,
        journal: Journal,
        powerMonitor: PowerMonitor
    ): Outcome {
        val preparation = prepare(request)
        if (!preparation.canAttemptWithValidatedHardware) {
            return Outcome(Status.BLOCKED, preparation.blockers)
        }
        if (preparation.changedBytes == 0L) return Outcome(Status.NO_CHANGE)
        val driver = EcuProgrammingDriverContract.registeredHardwareDrivers.firstOrNull {
            it.descriptor.physicalHardwareValidated &&
                request.controllerFamily in it.descriptor.controllerFamilies &&
                it.descriptor.transport == request.transport &&
                setOf(
                    EcuProgrammingDriverContract.Capability.IDENTIFY,
                    EcuProgrammingDriverContract.Capability.READ_REGION,
                    EcuProgrammingDriverContract.Capability.ERASE_REGION,
                    EcuProgrammingDriverContract.Capability.WRITE_REGION,
                    EcuProgrammingDriverContract.Capability.READBACK_VERIFY
                ).all(it.descriptor.capabilities::contains)
        } ?: return Outcome(Status.BLOCKED, setOf(Blocker.NO_VALIDATED_DRIVER))
        return EcuWriteExecutionEngine(driver, journal, powerMonitor).run(request)
    }
}

/**
 * Internal engine; only production's registry-gated entry point can expose it.
 * Separately testable with an in-memory fake driver, without vehicle access.
 */
internal class EcuWriteExecutionEngine(
    private val driver: EcuProgrammingDriverContract,
    private val journal: EcuWriteTransaction.Journal,
    private val powerMonitor: EcuWriteTransaction.PowerMonitor
) {
    suspend fun run(request: EcuWriteTransaction.Request): EcuWriteTransaction.Outcome {
        val plan = EcuWriteTransaction.prepare(request)
        if (!plan.canAttemptWithValidatedHardware) {
            return EcuWriteTransaction.Outcome(
                EcuWriteTransaction.Status.BLOCKED, plan.blockers
            )
        }
        if (plan.changedBytes == 0L) {
            return EcuWriteTransaction.Outcome(EcuWriteTransaction.Status.NO_CHANGE)
        }
        // Freeze caller-owned mutable arrays before any suspension/driver I/O.
        val original = request.originalImage.copyOf()
        val updated = request.updatedImage.copyOf()
        val preview = EcuPartialWritePlanner.preview(request.layout, original, updated)
        val requiredCapabilities = setOf(
            EcuProgrammingDriverContract.Capability.IDENTIFY,
            EcuProgrammingDriverContract.Capability.READ_REGION,
            EcuProgrammingDriverContract.Capability.ERASE_REGION,
            EcuProgrammingDriverContract.Capability.WRITE_REGION,
            EcuProgrammingDriverContract.Capability.READBACK_VERIFY
        )
        if (!driver.descriptor.physicalHardwareValidated ||
            request.controllerFamily !in driver.descriptor.controllerFamilies ||
            driver.descriptor.transport != request.transport ||
            !driver.descriptor.capabilities.containsAll(requiredCapabilities)
        ) {
            return EcuWriteTransaction.Outcome(
                EcuWriteTransaction.Status.BLOCKED,
                setOf(EcuWriteTransaction.Blocker.NO_VALIDATED_DRIVER)
            )
        }
        var verified = 0
        var currentAddress: Long? = null
        try {
            if (!powerMonitor.isStable()) {
                return EcuWriteTransaction.Outcome(
                    EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT
                )
            }
            val identity = driver.identify()
            if (identity != request.expectedIdentity) {
                return EcuWriteTransaction.Outcome(
                    EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT
                )
            }
            // Check EVERY sector before the first erase. Readback must be an
            // independent acquisition, not merely a copy of the planned BIN.
            for (sector in preview.affectedSectors) {
                currentAddress = sector.absoluteAddress
                val region = EcuProgrammingDriverContract.RegionRequest(
                    request.region, sector.absoluteAddress, sector.originalBytes.size.toLong()
                )
                if (!driver.read(region).contentEquals(sector.originalBytes) ||
                    !driver.independentReadback(region).contentEquals(sector.originalBytes)
                ) {
                    return EcuWriteTransaction.Outcome(
                        EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                        verifiedSectors = verified,
                        failedAddress = currentAddress
                    )
                }
                journal.append(
                    EcuWriteTransaction.Record(
                        EcuWriteTransaction.Stage.BASELINE_CHECKED,
                        request.region, sector.absoluteAddress,
                        sector.originalSha256, sector.updatedSha256
                    )
                )
            }
            for (sector in preview.affectedSectors) {
                currentAddress = sector.absoluteAddress
                val region = EcuProgrammingDriverContract.RegionRequest(
                    request.region, sector.absoluteAddress, sector.patchedBytes.size.toLong()
                )
                if (!powerMonitor.isStable()) {
                    return EcuWriteTransaction.Outcome(
                        EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                        verifiedSectors = verified, failedAddress = currentAddress
                    )
                }
                // Journal must accept the irreversible step before any erase.
                journal.append(
                    EcuWriteTransaction.Record(
                        EcuWriteTransaction.Stage.BEFORE_ERASE,
                        request.region, sector.absoluteAddress,
                        sector.originalSha256, sector.updatedSha256
                    )
                )
                driver.erase(region)
                if (!powerMonitor.isStable()) {
                    return EcuWriteTransaction.Outcome(
                        EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                        verifiedSectors = verified, failedAddress = currentAddress
                    )
                }
                driver.write(region, sector.patchedBytes.copyOf())
                if (!driver.independentReadback(region).contentEquals(sector.patchedBytes)) {
                    return EcuWriteTransaction.Outcome(
                        EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                        verifiedSectors = verified, failedAddress = currentAddress
                    )
                }
                journal.append(
                    EcuWriteTransaction.Record(
                        EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE,
                        request.region, sector.absoluteAddress,
                        sector.originalSha256, sector.updatedSha256
                    )
                )
                verified++
            }
            return EcuWriteTransaction.Outcome(
                EcuWriteTransaction.Status.COMPLETED, verifiedSectors = verified
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No blind retry, reset, or automatic recovery after an uncertain
            // erase/write/transport failure. Preserve journal for bench triage.
            return EcuWriteTransaction.Outcome(
                EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                verifiedSectors = verified, failedAddress = currentAddress
            )
        }
    }
}
