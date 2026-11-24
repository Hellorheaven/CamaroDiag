package com.camarodiag.gen5.auth

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class LicenseRolloutConfigTest {
    @Test fun licensingIsEnabledForTheRollout() {
        assertTrue("A licence is required for advanced diagnostics",
            LicenseRolloutConfig.ENABLED)
    }

    @Test fun checkoutRemainsDisabledUntilRealPaymentsAreReady() {
        assertFalse("Do not offer a broken or premature checkout",
            LicenseRolloutConfig.PAYMENTS_ENABLED)
    }

    @Test fun absentEntitlementKeepsAdvancedFeaturesLocked() {
        assertEquals(LicenseLevel.FREE,
            LicenseAccessPolicy.resolve(
                uid = "sample-uid",
                email = "user@example.com",
                emailVerified = true,
                providerIds = listOf("password"),
                signedClaims = emptyMap(),
                license = null
            ))
    }
}
