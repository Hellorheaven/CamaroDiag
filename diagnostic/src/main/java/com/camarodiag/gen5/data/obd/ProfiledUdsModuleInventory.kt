package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.VehicleProfile
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Read-only module inventory with no guessed GM addresses or service DIDs.
 *
 * Each ECU must have exactly one VIN- and calibration-bound profile before its
 * transport is even opened. Missing, conflicting or unresponsive modules are
 * NEVER marked accessible. This is not an actuator, security-access or flash API.
 * The caller remains responsible for the transport's lifecycle.
 */
object ProfiledUdsModuleInventory {
    val CORE_CAMARO_MODULES: List<String> =
        listOf("ECM", "TCM", "BCM", "IPC", "EBCM", "SDM")

    enum class Status {
        IDENTIFIED_READ_ONLY,
        PROFILE_REQUIRED,
        IDENTITY_UNVERIFIED
    }

    data class Result(
        val moduleId: String,
        val status: Status,
        val identity: UdsEcuIdentity? = null,
        val detail: String
    ) {
        val hardwareConfirmed: Boolean get() = false
        val programmingAuthorized: Boolean get() = false
    }

    suspend fun scan(
        vehicle: VehicleProfile,
        profiles: List<DocumentedUdsReadProfile>,
        transportFor: suspend (DocumentedUdsReadProfile) -> RawCanFrameTransport,
        moduleIds: List<String> = CORE_CAMARO_MODULES,
        timeoutMs: Long = 3000L
    ): List<Result> {
        require(timeoutMs in 1..30_000L) { "Module identification deadline out of range." }
        require(moduleIds.size <= 32 && moduleIds.all(String::isNotBlank) &&
            moduleIds.map { it.uppercase(Locale.ROOT) }.distinct().size == moduleIds.size) {
            "Module inventory requires unique nonblank module identifiers."
        }
        // Snapshot caller-owned collections; a profile-pack edit during a
        // suspending ECU exchange cannot add or reorder targets in this run.
        val approvedProfiles = profiles.map {
            it.copy(readableDids = it.readableDids.toSet(),
                identityDids = it.identityDids.toList())
        }
        return moduleIds.map { moduleId ->
            val candidates = approvedProfiles.filter {
                it.moduleId.equals(moduleId, ignoreCase = true)
            }
            val profile = candidates.singleOrNull()
            if (profile == null) {
                Result(moduleId, Status.PROFILE_REQUIRED,
                    detail = "Missing or ambiguous VIN-specific ECU profile.")
            } else {
                val problems = profile.compatibilityDefinitionIssues(vehicle)
                if (problems.isNotEmpty()) {
                    Result(moduleId, Status.PROFILE_REQUIRED,
                        detail = "Profile is incompatible with the selected vehicle.")
                } else {
                    try {
                        val channel = transportFor(profile)
                        val session = ProfiledUdsReadSession.open(
                            channel, vehicle, profile, timeoutMs
                        )
                        Result(moduleId, Status.IDENTIFIED_READ_ONLY,
                            identity = session.identity,
                            detail = "Exact ECU identity DIDs observed. Read-only; no flash qualification.")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result(moduleId, Status.IDENTITY_UNVERIFIED,
                            detail = "No complete matching ECU identity could be verified.")
                    }
                }
            }
        }
    }
}
