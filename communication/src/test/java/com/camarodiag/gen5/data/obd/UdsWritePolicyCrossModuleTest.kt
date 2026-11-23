package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: Android's communication layer may use documented UDS
 * protocol profiles without an extra CamaroDiag signing key or fake seed.
 */
class UdsWritePolicyCrossModuleTest {
    @Test fun ecuWithoutSeedKeyCanHaveAnActualDidWriteProfile() {
        val vin = "1G1FA1RXXR0123456"
        val id = UdsEcuIdentity("E92", "HW-1", "OS-1", "CAL-1", "A1B2", vin)
        val profile = UdsAccessPolicy.verified(
            vehicleVin = vin, identity = id, expectedVin = vin,
            expectedCalId = "CAL-1", expectedCvn = "A1B2",
            expectedControllerFamily = "E92",
            expectedHardwarePartNumber = "HW-1", expectedOperatingSystemId = "OS-1",
            readDids = setOf(0xF190), writeDids = setOf(0x1234),
            allowedSessions = setOf(0x01), routineControlPermissions = emptyMap(),
            allowedSecurityLevels = emptySet(),
            readableMemoryRegions = emptyList(), writableMemoryRegions = emptyList(),
            maxTransferBytes = 0, requireSecurityAccess = false,
            evidenceReference = ""
        )
        assertEquals(setOf(0x1234), profile.writeDids)
        assertEquals(setOf(0x01), profile.allowedSessions)
        assertFalse(profile.requireSecurityAccess)
        assertTrue(UdsAccessPolicy.readOnly().writeDids.isEmpty())
    }
}
