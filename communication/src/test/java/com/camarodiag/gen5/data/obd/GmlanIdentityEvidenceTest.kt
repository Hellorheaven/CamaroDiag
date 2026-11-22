package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class GmlanIdentityEvidenceTest {
    private val vin = "2G1FF3D36C9100001"

    @Test fun retainsPhysicalVinOsidAndResponderSeparately() {
        val identity = GmlanIdentityEvidence.validate(vin, 12664221, 0xC1,
            "7E8", "7E8", true, true, false)
        assertEquals(vin, identity.vin)
        assertEquals(12664221L, identity.operatingSystemId)
        assertEquals(0xC1, identity.osDid)
        assertEquals("7E8", identity.responseCanId)
        assertFalse(identity.flashQualified)
    }

    @Test fun rejectsMissingInvalidOrSimulatedIdentity() {
        for (id in listOf(0L, -1L, 0xFFFFFFFFL, 0x100000000L)) {
            assertThrows(IllegalArgumentException::class.java) {
                GmlanIdentityEvidence.validate(vin, id, 0xC1, "7E8", "7E8", true, true, false)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GmlanIdentityEvidence.validate("INVALID", 1, 0xC1, "7E8", "7E8", true, true, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GmlanIdentityEvidence.validate(vin, 1, 0xC2, "7E8", "7E8", true, true, false)
        }
        for ((physical, readOnly, qualified) in listOf(
            Triple(false, true, false), Triple(true, false, false), Triple(true, true, true)
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                GmlanIdentityEvidence.validate(vin, 1, 0xC1, "7E8", "7E8",
                    physical, readOnly, qualified)
            }
        }
    }

    @Test fun bindsPhysicalResponseToSelectedAddress() {
        for (id in listOf("7E9", "XYZ", "7e8", "000007E8", "800")) {
            assertThrows(IllegalArgumentException::class.java) {
                GmlanIdentityEvidence.validate(vin, 1, 0xC9, id, "7E8", true, true, false)
            }
        }
        assertEquals("18DAF110", GmlanIdentityEvidence.validate(vin, 1, 0xC9,
            "18DAF110", "18DAF110", true, true, false).responseCanId)
        assertThrows(IllegalArgumentException::class.java) {
            GmlanIdentityEvidence.validate(vin, 1, 0xC9, "20000000", "20000000", true, true, false)
        }
    }
}
