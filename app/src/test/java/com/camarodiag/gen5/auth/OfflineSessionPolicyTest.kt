package com.camarodiag.gen5.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSessionPolicyTest {
    @Test fun neverAcceptsAFirstTimeUnvalidatedSession() {
        assertFalse(OfflineSessionPolicy.allowsOfflineAccess(0L, 1_000L))
        assertFalse(OfflineSessionPolicy.allowsOfflineAccess(-1L, 1_000L))
    }

    @Test fun acceptsRecentlyValidatedSessionDuringNetworkLoss() {
        val now = 1_800_000_000_000L
        assertTrue(OfflineSessionPolicy.allowsOfflineAccess(now - 60_000L, now))
        assertTrue(OfflineSessionPolicy.allowsOfflineAccess(
            now - OfflineSessionPolicy.MAX_OFFLINE_MILLIS, now
        ))
    }

    @Test fun rejectsExpiredOrFutureValidation() {
        val now = 1_800_000_000_000L
        assertFalse(OfflineSessionPolicy.allowsOfflineAccess(
            now - OfflineSessionPolicy.MAX_OFFLINE_MILLIS - 1L, now
        ))
        assertFalse(OfflineSessionPolicy.allowsOfflineAccess(now + 1L, now))
    }
}
