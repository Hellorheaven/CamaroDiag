package com.camarodiag.gen5.data.obd

/**
 * Conservative preflight for the first FlexFuel target: the 2012 Camaro LFX.
 *
 * A matching vehicle profile does not prove ECU calibration compatibility.
 * Until the exact controller/firmware, verified backup and recovery procedure
 * have been established, no calibration write is authorized.
 */
object Lfx2012FlexFuelPreflight {
    enum class Status {
        VEHICLE_NOT_SELECTED,
        WRONG_VEHICLE,
        VIN_MISMATCH,
        ECU_IDENTITY_MISSING,
        ECU_IDENTITY_UNVERIFIED,
        FIRMWARE_NOT_VERIFIED,
        ORIGINAL_BACKUP_MISSING,
        RECOVERY_NOT_VERIFIED,
        WRITE_NOT_VALIDATED
    }

    data class Vehicle(
        val modelYear: Int,
        val engineCode: String,
        val generation: Int,
        val selectedVin: String?,
        val ecuVin: String?
    )

    data class Evidence(
        val controllerId: String?,
        val firmwareId: String?,
        val verifiedFirmware: Boolean,
        val originalBackupVerified: Boolean,
        val recoveryVerified: Boolean
    )

    /**
     * Identity warnings for a *read-only* vehicle inspection. These are diagnostic
     * findings, not permission gates: a user may perform standard non-destructive
     * identification to collect the missing ECU/VIN evidence in the first place.
     *
     * In particular, unqualified ECU flashing must not block physical VIN/firmware
     * discovery. Never treat this list as authorization for a write operation.
     */
    fun readOnlyIdentityWarnings(vehicle: Vehicle?, evidence: Evidence): List<Status> {
        if (vehicle == null) return listOf(Status.VEHICLE_NOT_SELECTED)
        if (vehicle.modelYear != 2012 ||
            vehicle.generation != 5 ||
            !vehicle.engineCode.trim().equals("LFX", ignoreCase = true)
        ) return listOf(Status.WRONG_VEHICLE)

        val problems = mutableListOf<Status>()
        val selectedVin = vehicle.selectedVin?.trim()?.uppercase(java.util.Locale.ROOT)
        val ecuVin = vehicle.ecuVin?.trim()?.uppercase(java.util.Locale.ROOT)
        // The 10th VIN character C identifies model year 2012.
        if (!isVin(selectedVin) || !isVin(ecuVin) || selectedVin != ecuVin ||
            selectedVin?.getOrNull(9) != 'C'
        ) problems += Status.VIN_MISMATCH
        if (evidence.controllerId.isNullOrBlank() || evidence.firmwareId.isNullOrBlank()) {
            problems += Status.ECU_IDENTITY_MISSING
        }
        if (isPlaceholderIdentity(evidence.controllerId) || isPlaceholderIdentity(evidence.firmwareId)) {
            problems += Status.ECU_IDENTITY_UNVERIFIED
        }
        return problems
    }

    /**
     * Return all missing write preconditions. WRITE_NOT_VALIDATED is always
     * present until a separate, vehicle-verified implementation exists.
     * Read-only identity collection must remain independently usable.
     */
    fun missingPreconditions(vehicle: Vehicle?, evidence: Evidence): List<Status> {
        val problems = readOnlyIdentityWarnings(vehicle, evidence).toMutableList()
        // Preserve the original early return for an absent or incompatible vehicle;
        // write-specific checks are meaningful only after selecting the correct target.
        if (Status.VEHICLE_NOT_SELECTED in problems || Status.WRONG_VEHICLE in problems) {
            return problems
        }
        if (!evidence.verifiedFirmware) problems += Status.FIRMWARE_NOT_VERIFIED
        if (!evidence.originalBackupVerified) problems += Status.ORIGINAL_BACKUP_MISSING
        if (!evidence.recoveryVerified) problems += Status.RECOVERY_NOT_VERIFIED
        problems += Status.WRITE_NOT_VALIDATED
        return problems
    }

    /** No ECU writes are supported until a separately validated implementation exists. */
    fun canWriteCalibration(vehicle: Vehicle?, evidence: Evidence): Boolean =
        missingPreconditions(vehicle, evidence).isEmpty()

    private fun isPlaceholderIdentity(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty() || normalized == "n/a" ||
            normalized == "none" || normalized == "null" || normalized == "-"
        ) return true
        val placeholders = Regex(
            "(?:^|[^a-z0-9])(?:unknown|demo|sample|simulated|invalid|" +
                "not available|not supported|unavailable|no data|no response|" +
                "pending|tbd|test|error|stopped)(?:$|[^a-z0-9])"
        )
        if (placeholders.containsMatchIn(normalized)) return true
        // Some ELM responses include a 0x prefix, spaces or separators.
        // Remove them before checking for missing data filled with 00 or FF.
        val bytes = normalized.removePrefix("0x").replace(Regex("[\\s:_-]"), "")
        return bytes.isEmpty() || bytes.all { it == '0' } || bytes.all { it == 'f' }
    }

    private fun isVin(value: String?): Boolean =
        value != null && value.length == 17 && value.all {
            it in 'A'..'Z' && it != 'I' && it != 'O' && it != 'Q' || it in '0'..'9'
        }
}
