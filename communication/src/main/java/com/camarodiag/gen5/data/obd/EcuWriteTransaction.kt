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
        val evidence: SafetyEvidence,
        val accessMethod: EcuBenchExpertPlan.AccessMethod = EcuBenchExpertPlan.AccessMethod.OBD
    ) {
        internal fun frozen() = copy(originalImage = originalImage.copyOf(), updatedImage = updatedImage.copyOf())
        internal val firmwareTarget get() = EcuProgrammingDriverContract.FirmwareTarget(
            controllerFamily, expectedIdentity.controllerId, expectedIdentity.firmwareId
        )
    }

    enum class Blocker {
        // A separate operation owns this exact physical driver instance.
        // This is not an unsupported-transport or licensing restriction.
        DRIVER_BUSY,
        UNKNOWN_TARGET, DIAGNOSTIC_ONLY_TRANSPORT, INCOMPATIBLE_TRANSPORT_REGION,
        INCOMPATIBLE_ACCESS_METHOD, SEPARATE_EEPROM_WORKFLOW_REQUIRED, UNVERIFIED_MEMORY_MAP,
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

    enum class Stage {
        BASELINE_CHECKED, BEFORE_ERASE, VERIFIED_AFTER_WRITE,
        FINAL_READBACK_VERIFIED, TRANSACTION_COMPLETED
    }
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

    private fun unavailableIdentity(value: String): Boolean {
        val normalized = value.trim().lowercase(java.util.Locale.ROOT)
        return normalized.isEmpty() ||
            normalized in setOf("unknown", "n/a", "none", "null", "demo", "sample",
                "simulated", "simulation", "mock", "fake", "pending",
                "not available", "no response", "unavailable") ||
            Regex("(?:^|[^a-z0-9])(?:unknown|demo|sample|simulated|simulation|mock|fake|pending|placeholder)(?:$|[^a-z0-9])")
                .containsMatchIn(normalized) ||
            normalized.removePrefix("0x").replace(Regex("[\\s:_-]"), "")
                .let { it.isNotEmpty() && it.all { ch -> ch == '0' || ch == 'f' } }
    }

    fun prepare(request: Request): Preparation {
        val blockers = linkedSetOf<Blocker>()
        if (unavailableIdentity(request.controllerFamily) ||
            unavailableIdentity(request.expectedIdentity.controllerId) ||
            unavailableIdentity(request.expectedIdentity.firmwareId) ||
            request.expectedIdentity.vin?.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) != true
        ) blockers += Blocker.UNKNOWN_TARGET
        // Admission is based on real protocol+driver capabilities, never an
        // invented blanket denial for a transport name such as ELM/STN.
        if (!request.transport.permitsMemoryProgramming(request.region)) {
            blockers += Blocker.INCOMPATIBLE_TRANSPORT_REGION
        }
        if (request.transport !in request.accessMethod.candidateTransports)
            blockers += Blocker.INCOMPATIBLE_ACCESS_METHOD
        // An external EEPROM page writer is not a flash-sector erase driver.
        if (request.region == EcuBackupCoverage.Region.EXTERNAL_EEPROM)
            blockers += Blocker.SEPARATE_EEPROM_WORKFLOW_REQUIRED
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
     * Public hardware entrypoint. The destination must be in persistent, app-private
     * storage. No destructive operation is permitted without the journal being
     * created and successfully synced; an earlier log is never overwritten.
     * An ECU writer still needs to be explicitly registered after bench validation.
     */
    suspend fun execute(
        request: Request,
        journalFile: java.io.File,
        powerMonitor: PowerMonitor
    ): Outcome {
        val snapshot = request.frozen()
        val preparation = prepare(snapshot)
        if (!preparation.canAttemptWithValidatedHardware) {
            return Outcome(Status.BLOCKED, preparation.blockers)
        }
        if (preparation.changedBytes == 0L) return Outcome(Status.NO_CHANGE)
        val driver = EcuProgrammingDriverContract.registeredHardwareDrivers.firstOrNull {
            it.descriptor.supportsWrite(snapshot.firmwareTarget, snapshot.transport, snapshot.region, snapshot.accessMethod)
        } ?: return Outcome(Status.BLOCKED, setOf(Blocker.NO_VALIDATED_DRIVER))
        val journal = try {
            PersistentEcuWriteJournal(
                journalFile, snapshot.firmwareTarget, requireNotNull(snapshot.expectedIdentity.vin)
            )
        } catch (_: Exception) {
            return Outcome(Status.STOPPED_REQUIRES_ASSESSMENT)
        }
        val physicalBackup = try {
            PersistentEcuPhysicalBlockBackup(
                journalFile, snapshot.firmwareTarget, requireNotNull(snapshot.expectedIdentity.vin)
            )
        } catch (_: Exception) {
            return Outcome(Status.STOPPED_REQUIRES_ASSESSMENT)
        }
        return EcuWriteExecutionEngine(driver, journal, powerMonitor, physicalBackup).run(snapshot)
    }

    /**
     * Internal test harness. Application callers cannot supply a volatile journal:
     * live programming must use the public file-backed entrypoint above.
     */
    internal suspend fun execute(
        request: Request,
        journal: Journal,
        powerMonitor: PowerMonitor
    ): Outcome {
        val snapshot = request.frozen()
        val preparation = prepare(snapshot)
        if (!preparation.canAttemptWithValidatedHardware) {
            return Outcome(Status.BLOCKED, preparation.blockers)
        }
        if (preparation.changedBytes == 0L) return Outcome(Status.NO_CHANGE)
        val driver = EcuProgrammingDriverContract.registeredHardwareDrivers.firstOrNull {
            it.descriptor.supportsWrite(snapshot.firmwareTarget, snapshot.transport, snapshot.region, snapshot.accessMethod)
        } ?: return Outcome(Status.BLOCKED, setOf(Blocker.NO_VALIDATED_DRIVER))
        return EcuWriteExecutionEngine(driver, journal, powerMonitor).run(snapshot)
    }
}

/**
 * Internal engine; only production's registry-gated entry point can expose it.
 * Separately testable with an in-memory fake driver, without vehicle access.
 */
internal class EcuWriteExecutionEngine(
    private val driver: EcuProgrammingDriverContract,
    private val journal: EcuWriteTransaction.Journal,
    private val powerMonitor: EcuWriteTransaction.PowerMonitor,
    // Internal tests use in-memory capture; public physical execute() always
    // injects a durable, file-backed store that fsyncs live ECU bytes.
    private val physicalBlockBackup: EcuPhysicalBlockBackup = InMemoryEcuPhysicalBlockBackup()
) {
    private companion object {
        // Identity semantics: even a driver overriding equals/hashCode owns
        // its own physical communication context. Entries live only while an
        // operation is active, avoiding a permanent reference to disconnected
        // hardware. Synchronize only the short acquisition/release, NEVER I/O.
        private val activeDriverInstances =
            java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<EcuProgrammingDriverContract, Boolean>()
            )
    }

    /** Exclusive from preflight to final verification and journal closure. */
    suspend fun run(input: EcuWriteTransaction.Request): EcuWriteTransaction.Outcome {
        val acquired = synchronized(activeDriverInstances) { activeDriverInstances.add(driver) }
        if (!acquired) {
            return EcuWriteTransaction.Outcome(
                EcuWriteTransaction.Status.BLOCKED,
                setOf(EcuWriteTransaction.Blocker.DRIVER_BUSY)
            )
        }
        try {
            return runOwned(input)
        } finally {
            synchronized(activeDriverInstances) { activeDriverInstances.remove(driver) }
        }
    }

    private suspend fun runOwned(input: EcuWriteTransaction.Request): EcuWriteTransaction.Outcome {
        val request = input.frozen()
        val plan = EcuWriteTransaction.prepare(request)
        if (!plan.canAttemptWithValidatedHardware) {
            return EcuWriteTransaction.Outcome(
                EcuWriteTransaction.Status.BLOCKED, plan.blockers
            )
        }
        if (plan.changedBytes == 0L) {
            return EcuWriteTransaction.Outcome(EcuWriteTransaction.Status.NO_CHANGE)
        }
        // Use the same private snapshot for preparation and execution.
        val original = request.originalImage
        val updated = request.updatedImage
        val preview = EcuPartialWritePlanner.preview(request.layout, original, updated)
        // Bind the planned delta to ABSOLUTE ECU memory coordinates and its
        // exact target bytes before any physical reads or destructive commands.
        for (sector in preview.affectedSectors) {
            val region = EcuProgrammingDriverContract.RegionRequest(
                request.region, sector.absoluteAddress, sector.patchedBytes.size.toLong()
            )
            EcuWriteBlockVerifier.requireExactSector(
                request.layout, request.region, original, updated, sector, region
            )
        }
        if (!driver.descriptor.supportsWrite(request.firmwareTarget, request.transport, request.region, request.accessMethod)) {
            return EcuWriteTransaction.Outcome(
                EcuWriteTransaction.Status.BLOCKED,
                setOf(EcuWriteTransaction.Blocker.NO_VALIDATED_DRIVER)
            )
        }
        val changedAddresses = preview.affectedSectors.map { it.absoluteAddress }.toSet()
        // Unchanged sectors within the qualified input region must also match:
        // an erase/program disturbance can extend beyond the selected sector.
        val untouchedOffsets = original.indices.step(request.layout.eraseBlockBytes).filter {
            (request.layout.regionStartAddress + it) !in changedAddresses
        }
        var verified = 0
        var currentAddress: Long? = null
        suspend fun verifyUntouchedSectors(doubleRead: Boolean): Boolean {
            for (offset in untouchedOffsets) {
                val address = request.layout.regionStartAddress + offset
                currentAddress = address
                val length = request.layout.eraseBlockBytes
                val region = EcuProgrammingDriverContract.RegionRequest(
                    request.region, address, length.toLong()
                )
                val expected = original.copyOfRange(offset, offset + length)
                if (!powerMonitor.isStable() || driver.identify() != request.expectedIdentity) return false
                if (doubleRead) {
                    if (!driver.read(region).contentEquals(expected) ||
                        !powerMonitor.isStable() || driver.identify() != request.expectedIdentity
                    ) return false
                }
                if (!driver.independentReadback(region).contentEquals(expected) ||
                    !powerMonitor.isStable() || driver.identify() != request.expectedIdentity
                ) return false
            }
            return true
        }
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
            if (!verifyUntouchedSectors(doubleRead = true)) {
                return EcuWriteTransaction.Outcome(
                    EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                    failedAddress = currentAddress
                )
            }
            // Check EVERY sector before the first erase. Readback must be an
            // independent acquisition, not merely a copy of the planned BIN.
            for (sector in preview.affectedSectors) {
                currentAddress = sector.absoluteAddress
                val region = EcuProgrammingDriverContract.RegionRequest(
                    request.region, sector.absoluteAddress, sector.originalBytes.size.toLong()
                )
                if (!powerMonitor.isStable() || driver.identify() != request.expectedIdentity) {
                    return EcuWriteTransaction.Outcome(
                        EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                        verifiedSectors = verified, failedAddress = currentAddress
                    )
                }
                // Physical bytes captured from ECU itself, distinct from the
                // input BIN. Both acquisitions must match before backup is saved.
                val physicalBytes = driver.read(region)
                if (!physicalBytes.contentEquals(sector.originalBytes) ||
                    !powerMonitor.isStable() || driver.identify() != request.expectedIdentity ||
                    !driver.independentReadback(region).contentEquals(sector.originalBytes) ||
                    !powerMonitor.isStable() ||
                    driver.identify() != request.expectedIdentity
                ) {
                    return EcuWriteTransaction.Outcome(
                        EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                        verifiedSectors = verified,
                        failedAddress = currentAddress
                    )
                }
                physicalBlockBackup.preserve(region, physicalBytes, sector.originalSha256)
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
                if (!powerMonitor.isStable() ||
                    driver.identify() != request.expectedIdentity
                ) {
                    // An adapter can reconnect to a different controller mid-run.
                    // Never erase the next sector without confirming the live ECU.
                    return EcuWriteTransaction.Outcome(
                        EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                        verifiedSectors = verified, failedAddress = currentAddress
                    )
                }
                // Recheck bytes immediately before erasing the next sector:
                // previous sector programming may have modified this one.
                if (!driver.independentReadback(region).contentEquals(sector.originalBytes) ||
                    !powerMonitor.isStable() || driver.identify() != request.expectedIdentity
                ) {
                    return EcuWriteTransaction.Outcome(
                        EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                        verifiedSectors = verified, failedAddress = currentAddress
                    )
                }
                // Rebind every erase request to the exact original/target sector
                // immediately before the irreversible operation, not just when
                // the offline plan was created.
                EcuWriteBlockVerifier.requireExactSector(
                    request.layout, request.region, original, updated, sector, region
                )
                // Journal must accept the irreversible step before any erase.
                journal.append(
                    EcuWriteTransaction.Record(
                        EcuWriteTransaction.Stage.BEFORE_ERASE,
                        request.region, sector.absoluteAddress,
                        sector.originalSha256, sector.updatedSha256
                    )
                )
                // Durable storage and readback may take time. Recheck power
                // after journal I/O before issuing the destructive operation.
                if (!powerMonitor.isStable() || driver.identify() != request.expectedIdentity) {
                    return EcuWriteTransaction.Outcome(
                        EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                        verifiedSectors = verified, failedAddress = currentAddress
                    )
                }
                driver.erase(region)
                val unitBytes = request.layout.programBlockBytes
                for (offset in sector.patchedBytes.indices step unitBytes) {
                    val unitAddress = sector.absoluteAddress + offset
                    currentAddress = unitAddress
                    // An adapter can reconnect or switch controller between
                    // programming units. Re-identify before every write, not
                    // merely before erasing the sector.
                    if (!powerMonitor.isStable() ||
                        driver.identify() != request.expectedIdentity
                    ) {
                        return EcuWriteTransaction.Outcome(
                            EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                            verifiedSectors = verified, failedAddress = unitAddress
                        )
                    }
                    val unit = EcuProgrammingDriverContract.RegionRequest(
                        request.region, unitAddress, unitBytes.toLong()
                    )
                    val outgoing = sector.patchedBytes.copyOfRange(offset, offset + unitBytes)
                    EcuWriteBlockVerifier.requireExactUnit(
                        request.layout, request.region, updated, sector, unit, offset, outgoing
                    )
                    driver.write(unit, outgoing)
                }
                currentAddress = sector.absoluteAddress
                // Last unit of a sector has no subsequent write preflight.
                // Check power and live identity before starting readback too.
                if (!powerMonitor.isStable() ||
                    driver.identify() != request.expectedIdentity ||
                    !driver.independentReadback(region).contentEquals(sector.patchedBytes) ||
                    !powerMonitor.isStable() ||
                    driver.identify() != request.expectedIdentity
                ) {
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
            // A later erase/program operation can disturb an already verified
            // sector. Re-acquire every changed sector after ALL writes, before
            // reporting completion. Journal records remain historical evidence.
            for (sector in preview.affectedSectors) {
                currentAddress = sector.absoluteAddress
                val region = EcuProgrammingDriverContract.RegionRequest(
                    request.region, sector.absoluteAddress, sector.patchedBytes.size.toLong()
                )
                if (!powerMonitor.isStable() ||
                    driver.identify() != request.expectedIdentity ||
                    !driver.independentReadback(region).contentEquals(sector.patchedBytes) ||
                    !powerMonitor.isStable() ||
                    driver.identify() != request.expectedIdentity
                ) {
                    return EcuWriteTransaction.Outcome(
                        EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                        verifiedSectors = verified, failedAddress = currentAddress
                    )
                }
                journal.append(
                    EcuWriteTransaction.Record(
                        EcuWriteTransaction.Stage.FINAL_READBACK_VERIFIED,
                        request.region, sector.absoluteAddress,
                        sector.originalSha256, sector.updatedSha256
                    )
                )
            }
            if (!powerMonitor.isStable() || driver.identify() != request.expectedIdentity) {
                return EcuWriteTransaction.Outcome(
                    EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                    verifiedSectors = verified, failedAddress = currentAddress
                )
            }
            if (!verifyUntouchedSectors(doubleRead = false) ||
                !powerMonitor.isStable() || driver.identify() != request.expectedIdentity
            ) {
                return EcuWriteTransaction.Outcome(
                    EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,
                    verifiedSectors = verified, failedAddress = currentAddress
                )
            }
            // This terminal marker binds completion to the final sector and
            // must be durably stored before returning a completed outcome.
            val last = preview.affectedSectors.last()
            journal.append(EcuWriteTransaction.Record(
                EcuWriteTransaction.Stage.TRANSACTION_COMPLETED,
                request.region, last.absoluteAddress, last.originalSha256, last.updatedSha256
            ))
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
