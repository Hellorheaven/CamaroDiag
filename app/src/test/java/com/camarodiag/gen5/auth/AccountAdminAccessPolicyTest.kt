package com.camarodiag.gen5.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountAdminAccessPolicyTest {
    private val verifiedGoogle = mapOf(
        "email_verified" to true,
        "firebase" to mapOf("sign_in_provider" to "google.com")
    )

    @Test fun allowsAdministratorsAndOwnersOnVerifiedGoogleSessions() {
        assertTrue(AccountAdminAccessPolicy.isAllowed(verifiedGoogle + ("admin_accounts" to true)))
        assertTrue(AccountAdminAccessPolicy.isAllowed(
            verifiedGoogle + (OwnerFunctionalTestPolicy.CLAIM to true)
        ))
        assertFalse(AccountAdminAccessPolicy.isAllowed(verifiedGoogle))
        assertFalse(AccountAdminAccessPolicy.isAllowed(verifiedGoogle + ("admin_accounts" to "true")))
    }

    @Test fun rejectsUnverifiedAndNonGoogleSessions() {
        val admin = verifiedGoogle + ("admin_accounts" to true)
        assertFalse(AccountAdminAccessPolicy.isAllowed(admin + ("email_verified" to false)))
        assertFalse(AccountAdminAccessPolicy.isAllowed(admin - "firebase"))
        assertFalse(AccountAdminAccessPolicy.isAllowed(
            admin + ("firebase" to mapOf("sign_in_provider" to "password"))
        ))
    }
}
