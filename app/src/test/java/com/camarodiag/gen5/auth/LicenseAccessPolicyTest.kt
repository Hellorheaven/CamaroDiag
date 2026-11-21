package com.camarodiag.gen5.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class LicenseAccessPolicyTest {
    private val now = 1_000_000L
    private val valid = ServerLicenseRecord(
        "uid", "user@example.com", "active", "perpetual", null, true
    )

    private fun check(
        record: ServerLicenseRecord? = valid,
        claims: Map<String, Any?> = mapOf(LicenseAccessPolicy.PREMIUM_CLAIM to true),
        uid: String = "uid",
        email: String = "User@Example.com",
        verified: Boolean = true
    ) = LicenseAccessPolicy.resolve(uid, email, verified, listOf("password"),
        claims, record, now)

    @Test fun permanentLicenseRequiresMatchingVerifiedIdentityAndFreshServerRecord() {
        assertEquals(LicenseLevel.PREMIUM, check())
        assertEquals(LicenseLevel.FREE, check(record = null))
        assertEquals(LicenseLevel.FREE, check(record = valid.copy(verifiedFromServer = false)))
        assertEquals(LicenseLevel.FREE, check(record = valid.copy(status = "revoked")))
        assertEquals(LicenseLevel.FREE, check(uid = "other"))
        assertEquals(LicenseLevel.FREE, check(email = "other@example.com"))
        assertEquals(LicenseLevel.FREE, check(verified = false))
        assertEquals(LicenseLevel.FREE, check(claims = emptyMap()))
        assertEquals(LicenseLevel.FREE, check(claims = mapOf(LicenseAccessPolicy.PREMIUM_CLAIM to "true")))
    }

    @Test fun normalizationMatchesServerForCompatibilityCharacters() {
        assertEquals("user@example.com", LicenseAccessPolicy.normalizeEmail(" Ｕｓｅｒ＠Ｅｘａｍｐｌｅ．ｃｏｍ "))
        assertEquals(LicenseLevel.PREMIUM, check(email = "Ｕｓｅｒ＠Ｅｘａｍｐｌｅ．ｃｏｍ"))
        assertEquals(LicenseLevel.FREE, check(email = "other@example.com"))
    }

    @Test fun expiredPerpetualGrantIsRejected() {
        assertEquals(LicenseLevel.FREE, check(record = valid.copy(expiresAtEpochMillis = now)))
        assertEquals(LicenseLevel.PREMIUM, check(record = valid.copy(expiresAtEpochMillis = now + 1)))
    }

    @Test fun subscriptionMustHaveFutureExpiry() {
        val subscription = valid.copy(kind = "subscription")
        assertEquals(LicenseLevel.FREE, check(record = subscription))
        assertEquals(LicenseLevel.FREE, check(record = subscription.copy(expiresAtEpochMillis = now - 1)))
        assertEquals(LicenseLevel.FREE, check(record = subscription.copy(expiresAtEpochMillis = now)))
        assertEquals(LicenseLevel.PREMIUM, check(record = subscription.copy(expiresAtEpochMillis = now + 1)))
        assertEquals(LicenseLevel.FREE, check(record = valid.copy(kind = "unknown")))
    }
}
