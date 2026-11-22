package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Lfx2012FlexFuelPreflightTest {
    private val vehicle = Lfx2012FlexFuelPreflight.Vehicle(
        modelYear = 2012,
        engineCode = "LFX",
        generation = 5,
        selectedVin = "2G1TEST00C0000001",
        ecuVin = "2G1TEST00C0000001"
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
        val mismatched = vehicle.copy(ecuVin = "1G1TEST00C0000001")
        val unknown = evidence.copy(controllerId = null, verifiedFirmware = false)
        val problems = Lfx2012FlexFuelPreflight.missingPreconditions(mismatched, unknown)
        assertTrue(problems.contains(Lfx2012FlexFuelPreflight.Status.VIN_MISMATCH))
        assertTrue(problems.contains(Lfx2012FlexFuelPreflight.Status.ECU_IDENTITY_MISSING))
        assertTrue(problems.contains(Lfx2012FlexFuelPreflight.Status.FIRMWARE_NOT_VERIFIED))
        assertFalse(Lfx2012FlexFuelPreflight.canWriteCalibration(mismatched, unknown))
    }

    @Test fun matchingVinWithWrongModelYearCodeCannotPassPreflight() {
        val wrongYear = "2G1TEST00B0000001"
        val problems = Lfx2012FlexFuelPreflight.missingPreconditions(
            vehicle.copy(selectedVin = wrongYear, ecuVin = wrongYear), evidence
        )
        assertTrue(problems.contains(Lfx2012FlexFuelPreflight.Status.VIN_MISMATCH))
        assertFalse(Lfx2012FlexFuelPreflight.canWriteCalibration(
            vehicle.copy(selectedVin = wrongYear, ecuVin = wrongYear), evidence
        ))
    }

    @Test fun placeholderControllerAndFirmwareNeverCountAsIdentity() {
        val problems = Lfx2012FlexFuelPreflight.missingPreconditions(vehicle, evidence)
        assertTrue(problems.contains(Lfx2012FlexFuelPreflight.Status.ECU_IDENTITY_UNVERIFIED))
        assertFalse(Lfx2012FlexFuelPreflight.canWriteCalibration(vehicle, evidence))
    }

    @Test fun concreteNonPlaceholderIdsDoNotTriggerPlaceholderWarning() {
        val identified = evidence.copy(controllerId = "12600001", firmwareId = "CAL-12345")
        val problems = Lfx2012FlexFuelPreflight.missingPreconditions(vehicle, identified)
        assertFalse(problems.contains(Lfx2012FlexFuelPreflight.Status.ECU_IDENTITY_UNVERIFIED))
        assertFalse(problems.contains(Lfx2012FlexFuelPreflight.Status.VIN_MISMATCH))
        assertTrue(problems.contains(Lfx2012FlexFuelPreflight.Status.WRITE_NOT_VALIDATED))
        assertFalse(Lfx2012FlexFuelPreflight.canWriteCalibration(vehicle, identified))
    }

    @Test fun refusesOtherEnginesAndYears() {
        assertTrue(
            Lfx2012FlexFuelPreflight.missingPreconditions(
                vehicle.copy(modelYear = 2013), evidence
            ).contains(Lfx2012FlexFuelPreflight.Status.WRONG_VEHICLE)
        )
    }
    @Test fun zeroFilledAndUnavailableIdentitiesAreRejected() {
        val invalidIds = listOf("00000000", "0x000000", "FFFFFFFF", "0xFFFFFF", "N/A", "TBD", "pending", "not supported", "test")
        invalidIds.forEach { invalid ->
            val problems = Lfx2012FlexFuelPreflight.missingPreconditions(
                vehicle,
                evidence.copy(controllerId = invalid, firmwareId = invalid)
            )
            assertTrue("Must reject placeholder: $invalid", problems.contains(Lfx2012FlexFuelPreflight.Status.ECU_IDENTITY_UNVERIFIED))
            assertFalse(Lfx2012FlexFuelPreflight.canWriteCalibration(vehicle, evidence.copy(controllerId = invalid, firmwareId = invalid)))
        }
    }

    @Test fun readOnlyInspectionDoesNotDependOnFlashQualification() {
        val identified = evidence.copy(controllerId = "12600001", firmwareId = "CAL-12345",
            verifiedFirmware = false, originalBackupVerified = false, recoveryVerified = false)
        assertTrue(Lfx2012FlexFuelPreflight.readOnlyIdentityWarnings(vehicle, identified).isEmpty())
        assertTrue(Lfx2012FlexFuelPreflight.missingPreconditions(vehicle, identified)
            .contains(Lfx2012FlexFuelPreflight.Status.WRITE_NOT_VALIDATED))
        assertFalse(Lfx2012FlexFuelPreflight.canWriteCalibration(vehicle, identified))
    }

    @Test fun readOnlyInspectionReportsMissingPhysicalIdentityWithoutRequiringWrite() {
        val unobserved = vehicle.copy(ecuVin = null)
        val warnings = Lfx2012FlexFuelPreflight.readOnlyIdentityWarnings(unobserved,
            evidence.copy(controllerId = null, firmwareId = null))
        assertTrue(warnings.contains(Lfx2012FlexFuelPreflight.Status.VIN_MISMATCH))
        assertTrue(warnings.contains(Lfx2012FlexFuelPreflight.Status.ECU_IDENTITY_MISSING))
        assertFalse(warnings.contains(Lfx2012FlexFuelPreflight.Status.WRITE_NOT_VALIDATED))
        assertFalse(warnings.contains(Lfx2012FlexFuelPreflight.Status.ORIGINAL_BACKUP_MISSING))
    }

    @Test fun wrongVehicleRemainsVisibleInReadOnlyIdentityWarnings() {
        val warnings = Lfx2012FlexFuelPreflight.readOnlyIdentityWarnings(
            vehicle.copy(modelYear = 2013), evidence)
        assertTrue(warnings.contains(Lfx2012FlexFuelPreflight.Status.WRONG_VEHICLE))
        assertFalse(Lfx2012FlexFuelPreflight.canWriteCalibration(vehicle.copy(modelYear = 2013), evidence))
    }

    @Test fun absentVehiclePreservesOriginalWritePreflightContract() {
        val problems = Lfx2012FlexFuelPreflight.missingPreconditions(null, evidence)
        assertTrue(problems == listOf(Lfx2012FlexFuelPreflight.Status.VEHICLE_NOT_SELECTED))
        assertFalse(Lfx2012FlexFuelPreflight.canWriteCalibration(null, evidence))
    }

    @Test fun incompatibleVehiclePreservesOriginalWritePreflightContract() {
        val problems = Lfx2012FlexFuelPreflight.missingPreconditions(
            vehicle.copy(engineCode = "LS3"), evidence)
        assertTrue(problems == listOf(Lfx2012FlexFuelPreflight.Status.WRONG_VEHICLE))
        assertFalse(Lfx2012FlexFuelPreflight.canWriteCalibration(vehicle.copy(engineCode = "LS3"), evidence))
    }

}
