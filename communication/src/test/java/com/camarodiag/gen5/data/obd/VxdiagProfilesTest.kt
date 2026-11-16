package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VxdiagProfilesTest {
    @Test fun exposesBothLinksForSeAndNanoVariants() {
        val profiles = VxdiagProfiles.all()
        assertEquals(4, profiles.size)
        assertTrue(profiles.any { it.model == VxdiagModel.VCX_SE && it.link == VxdiagLink.USB })
        assertTrue(profiles.any { it.model == VxdiagModel.VCX_SE && it.link == VxdiagLink.WIFI })
        assertTrue(profiles.any { it.model == VxdiagModel.VCX_NANO && it.link == VxdiagLink.USB })
        assertTrue(profiles.any { it.model == VxdiagModel.VCX_NANO && it.link == VxdiagLink.WIFI })
    }

    @Test fun exposesTheImplementedWindowsBridgeWithoutClaimingAnAndroidDriver() {
        assertTrue(VxdiagProfiles.all().all {
            it.readiness == VxdiagReadiness.PC_BRIDGE_IMPLEMENTED_REQUIRES_VENDOR_DRIVER
        })
    }
}
