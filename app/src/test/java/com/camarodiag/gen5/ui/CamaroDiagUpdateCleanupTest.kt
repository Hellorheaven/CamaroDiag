package com.camarodiag.gen5.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the installer-success gate. File deletion itself requires
 * Android DownloadManager and is exercised by Android/real-device testing.
 */
class CamaroDiagUpdateCleanupTest {
    @Test fun pendingDownloadRoundTripsWithoutLosingTargetVersion() {
        val pending = PendingCamaroDiagApk(48123L, 2026112101L)
        assertEquals(pending, PendingCamaroDiagApk.decode(pending.encode()))
    }

    @Test fun failedOrCancelledInstallNeverPermitsApkDeletion() {
        val pending = PendingCamaroDiagApk(1L, 400L)
        assertFalse(pending.installed(399L))
        assertFalse(pending.installed(1L))
    }

    @Test fun installedTargetOrNewerVersionPermitsCleanup() {
        val pending = PendingCamaroDiagApk(2L, 400L)
        assertTrue(pending.installed(400L))
        assertTrue(pending.installed(401L))
    }

    @Test fun invalidAndUntrustedStoredDownloadIdsAreIgnored() {
        listOf("", ":", "1:", ":1", "0:42", "-1:42", "2:0",
            "3:-2", "NaN:4", "4:NaN", "1:2:3", "3:4.apk", "99999999999999999999:1"
        ).forEach { raw ->
            assertNull("Invalid pending download: $raw", PendingCamaroDiagApk.decode(raw))
        }
    }

    @Test fun distinctDownloadIdsDoNotOverwriteEachOther() {
        val first = PendingCamaroDiagApk(23L, 400L)
        val second = PendingCamaroDiagApk(24L, 401L)
        val persisted = setOf(first.encode(), second.encode())
        val restored = persisted.mapNotNull(PendingCamaroDiagApk::decode).toSet()
        assertEquals(setOf(first, second), restored)
        assertTrue(first.installed(400L))
        assertFalse(second.installed(400L))
    }
}
