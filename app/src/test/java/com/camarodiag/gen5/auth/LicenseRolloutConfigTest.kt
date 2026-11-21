package com.camarodiag.gen5.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class LicenseRolloutConfigTest {
    @Test fun licensingIsExplicitlyDormantForThisBuild() {
        assertFalse("The licensing paywall must remain disconnected until approved",
            LicenseRolloutConfig.ENABLED)
    }

    @Test fun premiumEntitlementsAreStillModelledForLaterRollout() {
        assertEquals(
            LicenseLevel.FREE,
            LicenseAccessPolicy.resolve(
                uid = "sample-uid",
                email = "user@example.com",
                emailVerified = true,
                providerIds = listOf("password"),
                signedClaims = emptyMap(),
                license = null
            )
        )
    }
}
