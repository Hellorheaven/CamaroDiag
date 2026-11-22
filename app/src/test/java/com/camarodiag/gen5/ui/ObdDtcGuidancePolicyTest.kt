package com.camarodiag.gen5.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDtcGuidancePolicyTest {
    private val vin = "2G1TEST00C0000001"

    @Test fun allowsOneRealEcmResponderWithMatchingVin() {
        assertTrue(ObdDtcGuidancePolicy.hasMatchedEcm(vin, vin, listOf("7E8"), false))
        assertTrue(ObdDtcGuidancePolicy.hasMatchedEcm(vin.lowercase(), vin, listOf("0x7e8"), false))
    }

    @Test fun blocksUnidentifiedOrAmbiguousModule() {
        assertFalse(ObdDtcGuidancePolicy.hasMatchedEcm(vin, vin, listOf("7E9"), false))
        assertFalse(ObdDtcGuidancePolicy.hasMatchedEcm(vin, vin, listOf("7E8", "7E9"), false))
        assertFalse(ObdDtcGuidancePolicy.hasMatchedEcm(vin, vin, emptyList(), false))
    }

    @Test fun blocksVinMismatchInvalidVinAndSimulation() {
        assertFalse(ObdDtcGuidancePolicy.hasMatchedEcm(vin, "1G1FF3D36C9104199", listOf("7E8"), false))
        assertFalse(ObdDtcGuidancePolicy.hasMatchedEcm("UNKNOWN", vin, listOf("7E8"), false))
        assertFalse(ObdDtcGuidancePolicy.hasMatchedEcm(vin, null, listOf("7E8"), false))
        assertFalse(ObdDtcGuidancePolicy.hasMatchedEcm(vin, vin, listOf("7E8"), true))
    }
}
