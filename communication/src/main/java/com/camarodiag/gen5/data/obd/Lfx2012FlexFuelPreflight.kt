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
     * Return all missing preconditions. WRITE_NOT_VALIDATED is always present
     * until a separate, vehicle-verified calibration implementation exists.
     */
    fun missingPreconditions(vehicle: Vehicle?, evidence: Evidence): List<Status> {
        if (vehicle == null) return listOf(Status.VEHICLE_NOT_SELECTED)
        if (vehicle.modelYear != 2012 ||
            vehicle.generation != 5 ||
            !vehicle.engineCode.trim().equals("LFX", ignoreCase = true)
        ) return listOf(Status.WRONG_VEHICLE)

        val problems = mutableListOf<Status>()
        val selectedVin = vehicle.selectedVin?.trim()?.uppercase()
        val ecuVin = vehicle.ecuVin?.trim()?.uppercase()
        if (!isVin(selectedVin) || !isVin(ecuVin) || selectedVin != ecuVin) {
            problems += Status.VIN_MISMATCH
        }
        if (evidence.controllerId.isNullOrBlank() || evidence.firmwareId.isNullOrBlank()) {
            problems += Status.ECU_IDENTITY_MISSING
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

    private fun isVin(value: String?): Boolean =
        value != null && value.length == 17 && value.all {
            it in 'A'..'Z' && it != 'I' && it != 'O' && it != 'Q' || it in '0'..'9'
        }
}
