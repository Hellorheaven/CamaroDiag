package com.camarodiag.gen5.ui

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugBuildCatalogTest {
    private val now = Instant.parse("2026-11-21T12:00:00Z")
    private val sha = "a".repeat(40)
    private val otherSha = "b".repeat(40)

    private fun artifact(
        platform: DebugBuildPlatform,
        shaInName: String = sha,
        runSha: String = sha,
        branch: String = "feat/ecu-backup-integrity",
        expired: Boolean = false,
        expiresAt: String = "2026-12-21T12:00:00Z",
        createdAt: String = "2026-11-21T10:00:00Z",
        id: Long = 42L
    ): DebugBuildArtifact {
        val prefix = if (platform == DebugBuildPlatform.ANDROID)
            "CamaroDiag-debug-pr-70-" else "CamaroDiag-J2534-Bridge-debug-pr-70-"
        return DebugBuildArtifact(
            id, prefix + shaInName, 123L, branch, runSha, createdAt,
            expiresAt, expired, 42_000L
        )
    }

    @Test fun newestValidAndroidAndWindowsArtifactsAreFoundSeparately() {
        val old = artifact(DebugBuildPlatform.ANDROID, id = 2L,
            createdAt = "2026-11-21T09:00:00Z")
        val newer = artifact(DebugBuildPlatform.ANDROID, id = 5L,
            createdAt = "2026-11-21T11:00:00Z")
        val windows = artifact(DebugBuildPlatform.WINDOWS)
        assertEquals(5L, DebugBuildCatalog.choose(listOf(old, windows, newer),
            DebugBuildPlatform.ANDROID, now)?.id)
        assertEquals(windows, DebugBuildCatalog.choose(listOf(old, windows, newer),
            DebugBuildPlatform.WINDOWS, now))
    }

    @Test fun expiredWrongBranchAndWrongShaNeverAppear() {
        val items = listOf(
            artifact(DebugBuildPlatform.ANDROID, expired = true),
            artifact(DebugBuildPlatform.ANDROID, expiresAt = "2026-11-20T12:00:00Z"),
            artifact(DebugBuildPlatform.ANDROID, branch = "main"),
            artifact(DebugBuildPlatform.ANDROID, shaInName = otherSha),
            artifact(DebugBuildPlatform.ANDROID, id = 0),
            artifact(DebugBuildPlatform.WINDOWS)
        )
        assertNull(DebugBuildCatalog.choose(items, DebugBuildPlatform.ANDROID, now))
    }

    @Test fun generatedLinksStayOnPinnedGithubRepoAndNumericIds() {
        val result = artifact(DebugBuildPlatform.ANDROID, id = 345L)
        assertEquals(
            "https://github.com/Hellorheaven/CamaroDiag/actions/runs/123/artifacts/345",
            result.downloadPage()
        )
    }
}
