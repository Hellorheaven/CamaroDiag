package com.camarodiag.gen5.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerFunctionalTestPolicyTest {
    private val google = mapOf<String, Any?>("firebase" to mapOf("sign_in_provider" to "google.com"))
    @Test fun signedGoogleClaimRequired() {
        assertFalse(OwnerFunctionalTestPolicy.isAllowed(listOf("google.com"), google))
        assertTrue(OwnerFunctionalTestPolicy.isAllowed(
            listOf("google.com"), google + (OwnerFunctionalTestPolicy.CLAIM to true)
        ))
        assertFalse(OwnerFunctionalTestPolicy.isAllowed(
            listOf("google.com"), google + (OwnerFunctionalTestPolicy.CLAIM to "true")
        ))
    }
    @Test fun passwordOrUnlinkedSessionDenied() {
        val granted = google + (OwnerFunctionalTestPolicy.CLAIM to true)
        assertFalse(OwnerFunctionalTestPolicy.isAllowed(listOf("password"), granted))
        assertFalse(OwnerFunctionalTestPolicy.isAllowed(
            listOf("google.com"), granted + ("firebase" to mapOf("sign_in_provider" to "password"))
        ))
        assertFalse(OwnerFunctionalTestPolicy.isAllowed(
            listOf("google.com"), mapOf(OwnerFunctionalTestPolicy.CLAIM to true)
        ))
    }
}