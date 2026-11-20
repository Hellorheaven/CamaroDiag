package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class Lfx2012ControllerReferencesTest {
    @Test fun referencesAreListedWithoutImplyingFirmwareCompatibility() {
        assertEquals("E39", Lfx2012ControllerReferences.controllerFamily)
        assertEquals(
            listOf("12651994", "12643248"),
            Lfx2012ControllerReferences.describedServiceParts()
        )
        assertEquals("12632286", Lfx2012ControllerReferences.associatedMainBootFile)
    }

    @Test fun fingerprintRequiresEcuNameCalIdAndCvn() {
        val complete = Lfx2012ControllerReferences.ObservedIdentity(
            ecuName = "ECM E39",
            calibrationIds = listOf("12345678"),
            calibrationVerificationNumbers = listOf("FABC")
        )
        assertTrue(Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(complete))
        assertFalse(Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(
            complete.copy(calibrationVerificationNumbers = emptyList())
        ))
        assertFalse(Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(
            complete.copy(ecuName = null)
        ))
    }

    @Test fun aCompleteReadOnlyFingerprintNeverAuthorizesAWrite() {
        val vehicle = Lfx2012FlexFuelPreflight.Vehicle(
            2012, "LFX", 5, "2G1FF3D36C9104199", "2G1FF3D36C9104199"
        )
        val evidence = Lfx2012FlexFuelPreflight.Evidence(
            controllerId = "E39",
            firmwareId = "12345678",
            verifiedFirmware = false,
            originalBackupVerified = false,
            recoveryVerified = false
        )
        assertFalse(Lfx2012FlexFuelPreflight.canWriteCalibration(vehicle, evidence))
    }
}
