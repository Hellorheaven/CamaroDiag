package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Lfx2012FlexFuelPreflightTest {
    private val vehicle = Lfx2012FlexFuelPreflight.Vehicle(
        modelYear = 2012,
        engineCode = "LFX",
        generation = 5,
        selectedVin = "2G1FF3D36C9104199",
        ecuVin = "2G1FF3D36C9104199"
    )
    private val evidence = Lfx2012FlexFuelPreflight.Evidence(
        controllerId = "unknown-controller",
        firmwareId = "unknown-firmware",
        verifiedFirmware = true,
        originalBackupVerified = true,
        recoveryVerified = true
    )

    @Test fun refusesWritingEvenWithEveryReportedPrerequisite() {
        val problems = Lfx2012FlexFuelPreflight.missingPreconditions(vehicle, evidence)
        assertTrue(problems.contains(Lfx2012FlexFuelPreflight.Status.WRITE_NOT_VALIDATED))
        assertFalse(Lfx2012FlexFuelPreflight.canWriteCalibration(vehicle, evidence))
    }

    @Test fun blocksVinMismatchAndUnidentifiedFirmware() {
        val mismatched = vehicle.copy(ecuVin = "1G1FF3D36C9104199")
        val unknown = evidence.copy(controllerId = null, verifiedFirmware = false)
        val problems = Lfx2012FlexFuelPreflight.missingPreconditions(mismatched, unknown)
        assertTrue(problems.contains(Lfx2012FlexFuelPreflight.Status.VIN_MISMATCH))
        assertTrue(problems.contains(Lfx2012FlexFuelPreflight.Status.ECU_IDENTITY_MISSING))
        assertTrue(problems.contains(Lfx2012FlexFuelPreflight.Status.FIRMWARE_NOT_VERIFIED))
        assertFalse(Lfx2012FlexFuelPreflight.canWriteCalibration(mismatched, unknown))
    }

    @Test fun refusesOtherEnginesAndYears() {
        assertTrue(
            Lfx2012FlexFuelPreflight.missingPreconditions(
                vehicle.copy(modelYear = 2013), evidence
            ).contains(Lfx2012FlexFuelPreflight.Status.WRONG_VEHICLE)
        )
    }
}
