package com.camarodiag.gen5.auth

import org.junit.Assert.*
import org.junit.Test

class EmailVerificationPolicyTest {
    @Test fun linkedPasswordNeverBlocksServerVerifiedGoogleAccount() {
        assertFalse(EmailVerificationPolicy.requiresVerification(
            listOf("firebase", "google.com", "password"), mapOf("email_verified" to true)))
        assertFalse(EmailVerificationPolicy.requiresVerification(
            listOf("password"), mapOf("email_verified" to true)))
    }

    @Test fun genuinelyUnverifiedOrMissingClaimIsNeverBypassed() {
        for (provider in listOf(listOf("password"), listOf("google.com", "password"), listOf("google.com"))) {
            assertTrue(EmailVerificationPolicy.requiresVerification(provider, emptyMap()))
            assertTrue(EmailVerificationPolicy.requiresVerification(provider, mapOf("email_verified" to false)))
            assertTrue(EmailVerificationPolicy.requiresVerification(provider, mapOf("email_verified" to "true")))
        }
        assertTrue(EmailVerificationPolicy.requiresVerification(emptyList(),
            mapOf("firebase" to mapOf("sign_in_provider" to "password"))))
    }

    @Test fun offlineGraceRequiresPreviouslyVerifiedSameEmail() {
        val providers = listOf("password", "google.com")
        assertTrue(EmailVerificationPolicy.allowsOfflineEmail(providers, "user@example.org", true, "user@example.org"))
        assertFalse(EmailVerificationPolicy.allowsOfflineEmail(providers, "user@example.org", false, "user@example.org"))
        assertFalse(EmailVerificationPolicy.allowsOfflineEmail(providers, "user@example.org", true, null))
        assertFalse(EmailVerificationPolicy.allowsOfflineEmail(providers, "new@example.org", true, "user@example.org"))
    }
}
