package com.camarodiag.gen5.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CamaroDiagExportStorageTest {
    @Test fun acceptsOnlyPublicCamaroDiagFolderOnPrimaryStorage() {
        val authority = CamaroDiagExportStorage.AUTHORITY
        assertTrue(CamaroDiagExportStorage.isExpectedDocument(authority, "primary:CamaroDiag"))
        for (id in listOf("primary:", "primary:Download", "primary:Documents/CamaroDiag",
            "primary:CamaroDiag/other", "1234-ABCD:CamaroDiag", "primary:CamaroDiag2")) {
            assertFalse(id, CamaroDiagExportStorage.isExpectedDocument(authority, id))
        }
        assertFalse(CamaroDiagExportStorage.isExpectedDocument("other.provider", "primary:CamaroDiag"))
    }

    @Test fun preservesBackupAndReportExtensionsWhilePreventingCollisions() {
        for (extension in listOf("zip", "json", "csv", "tsv", "txt", "bin", "ori", "mod", "hex")) {
            val original = "CamaroDiag_backup.$extension"
            val first = CamaroDiagExportStorage.uniqueFileName(original, "one")
            val second = CamaroDiagExportStorage.uniqueFileName(original, "two")
            assertEquals("CamaroDiag_backup_one.$extension", first)
            assertNotEquals(first, second)
            assertTrue(second.endsWith(".$extension"))
        }
    }

    @Test fun fileNamesCannotEscapeTheApprovedFolder() {
        val result = CamaroDiagExportStorage.uniqueFileName("../../backup\\name.zip", "safe")
        assertFalse(result.contains("/"))
        assertFalse(result.contains("\\"))
        assertFalse(result.startsWith("."))
        assertTrue(result.endsWith(".zip"))
    }

    @Test fun randomlyGeneratedNamesAreDifferent() {
        val first = CamaroDiagExportStorage.uniqueFileName("report.json")
        val second = CamaroDiagExportStorage.uniqueFileName("report.json")
        assertNotEquals(first, second)
    }

    @Test fun longFileNameIsBoundedAndRetainsExtension() {
        val result = CamaroDiagExportStorage.uniqueFileName("a".repeat(500) + ".bin", "test")
        assertTrue(result.length < 150)
        assertTrue(result.endsWith(".bin"))
    }
}
