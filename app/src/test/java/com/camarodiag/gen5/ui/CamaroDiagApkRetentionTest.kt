package com.camarodiag.gen5.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CamaroDiagApkRetentionTest {
    @Test fun apkArchivePathIsDedicatedAndMatchesTheUserVisibleDestination() {
        val category = CamaroDiagExportStorage.Category.APK
        assertEquals("/storage/emulated/0/CamaroDiag/APK/", CamaroDiagExportStorage.displayPath(category))
        assertEquals("primary:CamaroDiag/APK", CamaroDiagExportStorage.categoryDocumentId(category))
        val name = CamaroDiagApkRetention.name(42, 100)
        assertEquals("/storage/emulated/0/CamaroDiag/APK/$name", CamaroDiagExportStorage.fileDisplayPath(category, name))
        assertTrue(CamaroDiagExportStorage.Category.values().filter { it != category }.none {
            it.folder.startsWith("APK")
        })
    }

    @Test fun onlyManagedPresentApksAreRanked() {
        val names = (1L..7L).map { CamaroDiagApkRetention.name(it, 100 + it) }
        val unrelated = setOf("manual.apk", "backup.zip", ".camarodiag-update.partial",
            "CamaroDiag-Update-99-999.apk")
        assertEquals(names.reversed(),
            CamaroDiagApkRetention.newestFirst(names.toSet(), names.toSet() + unrelated))
        assertEquals(names.take(5).reversed(),
            CamaroDiagApkRetention.newestFirst(names.take(5).toSet(), names.toSet()))
    }

    @Test fun latestVersionsThenNewestDownloadAreKept() {
        val names = (1L..6L).map { CamaroDiagApkRetention.name(it, 200L) }
        val oldLaterDownload = CamaroDiagApkRetention.name(99, 100)
        val present = (names + oldLaterDownload).toSet()
        assertEquals(names.reversed() + oldLaterDownload,
            CamaroDiagApkRetention.newestFirst(present, present))
    }

    @Test fun malformedNamesAndMissingFilesCannotAuthorizeDeletion() {
        for (name in listOf("../CamaroDiag-Update-1-2.apk", "CamaroDiag-Update-0-2.apk",
                "CamaroDiag-Update-1-2.apk.partial", "CamaroDiag-Update-99999999999999999999-2.apk"))
            assertNull(CamaroDiagApkRetention.parse(name))
        assertTrue(CamaroDiagApkRetention.newestFirst(setOf(CamaroDiagApkRetention.name(1, 2)), emptySet()).isEmpty())
    }

    @Test fun legacyNamesAndConflictingFingerprintsProvideNoOwnership() {
        val name = CamaroDiagApkRetention.name(42, 100)
        val fingerprint = CamaroDiagExportStorage.Fingerprint(100L, "a".repeat(64))
        val published = CamaroDiagApkRetention.OwnedApk(name, fingerprint)
        val conflicting = published.copy(fingerprint = fingerprint.copy(sha256 = "b".repeat(64)))
        assertEquals(mapOf(name to published), CamaroDiagApkRetention.unambiguous(setOf(published.encode())))
        assertTrue(CamaroDiagApkRetention.unambiguous(setOf(name)).isEmpty())
        assertTrue(CamaroDiagApkRetention.unambiguous(setOf(published.encode(), conflicting.encode())).isEmpty())
        assertNull(CamaroDiagApkRetention.decode("$name:0:${fingerprint.sha256}"))
        assertNull(CamaroDiagApkRetention.decode("$name:100:invalid"))
    }
}
