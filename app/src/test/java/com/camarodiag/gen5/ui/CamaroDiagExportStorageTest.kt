package com.camarodiag.gen5.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
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

    @Test fun categoryPathsAreUniqueAndStayBelowAuthorizedRoot() {
        val categories = CamaroDiagExportStorage.Category.entries
        assertEquals(categories.size, categories.map { it.folder }.toSet().size)
        for (category in categories) {
            assertTrue(category.folder.split('/').all { it.matches(Regex("[A-Za-z]+")) })
            assertEquals("primary:CamaroDiag/" + category.folder,
                CamaroDiagExportStorage.categoryDocumentId(category))
            assertEquals("/storage/emulated/0/CamaroDiag/" + category.folder + "/",
                CamaroDiagExportStorage.displayPath(category))
        }
        assertNotEquals(CamaroDiagExportStorage.Category.ECU_BACKUPS.folder,
            CamaroDiagExportStorage.Category.RECOVERY.folder)
    }

    @Test fun publishedFileIdentityAndDisplayedPathMustMatchItsCategory() {
        val storage = CamaroDiagExportStorage
        val category = CamaroDiagExportStorage.Category.RECOVERY
        val file = "checkpoint_safe.cdj"
        assertEquals("/storage/emulated/0/CamaroDiag/Logs/Recovery/$file",
            storage.fileDisplayPath(category, file))
        assertTrue(storage.isCategoryFile(storage.AUTHORITY,
            "primary:CamaroDiag/Logs/Recovery/$file", category, file))
        for (id in listOf("primary:CamaroDiag/$file", "primary:CamaroDiag/Backups/ECU/$file",
            "primary:CamaroDiag/Logs/Recovery/other.cdj")) {
            assertFalse(storage.isCategoryFile(storage.AUTHORITY, id, category, file))
        }
        assertFalse(storage.isCategoryFile("other.provider",
            "primary:CamaroDiag/Logs/Recovery/$file", category, file))
        assertTrue(runCatching { storage.fileDisplayPath(category, "../$file") }.isFailure)
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

    @Test fun partialDocumentDoesNotHaveACompletedFileExtension() {
        val temporary = CamaroDiagExportStorage.temporaryFileName("backup.zip")
        assertTrue(temporary.startsWith(".camarodiag-"))
        assertTrue(temporary.endsWith(".partial"))
        assertFalse(temporary.endsWith(".zip"))
    }

    @Test fun freshReadbackMatchesExactlyWrittenBytes() = runBlocking {
        val sink = ByteArrayOutputStream()
        val output = CamaroDiagExportStorage.VerifiedOutput(sink)
        output.use {
            it.write(0x61)
            it.write(byteArrayOf(0, 0x62, 0x63, 0), 1, 2)
        }
        val expected = output.fingerprint()
        assertEquals(3L, expected.length)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            expected.sha256)
        assertEquals(expected, CamaroDiagExportStorage.fingerprint(ByteArrayInputStream(sink.toByteArray())))
        assertNotEquals(expected, CamaroDiagExportStorage.fingerprint(ByteArrayInputStream(byteArrayOf(0x61, 0x62, 0))))
    }

    @Test fun diskWriteFailureIsNotReportedAsAnAdapterFailure() {
        val broken = object : OutputStream() {
            override fun write(value: Int) { throw IOException("disk full") }
        }
        val output = CamaroDiagExportStorage.VerifiedOutput(broken)
        val error = runCatching { output.write(1) }.exceptionOrNull()
        assertTrue(error is CamaroDiagExportStorage.ExportStorageException)
        assertTrue(error?.cause is IOException)
    }

    @Test fun swallowedWriteErrorCannotBecomeSuccessAfterClose() {
        val broken = object : OutputStream() {
            var writes = 0
            override fun write(value: Int) {
                writes++
                if (writes == 2) throw IOException("partial write")
            }
        }
        val output = CamaroDiagExportStorage.VerifiedOutput(broken)
        output.write(1)
        assertTrue(runCatching { output.write(2) }.isFailure)
        assertTrue(runCatching { output.write(3) }.isFailure)
        output.close()
        assertEquals(2, broken.writes)
        assertTrue(runCatching { output.fingerprint() }.isFailure)
    }

    @Test fun failedFlushPoisonsOutputEvenAfterSuccessfulClose() {
        val broken = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun flush() { throw IOException("flush failed") }
        }
        val output = CamaroDiagExportStorage.VerifiedOutput(broken)
        output.write(1)
        assertTrue(runCatching { output.flush() }.isFailure)
        output.close()
        assertTrue(runCatching { output.fingerprint() }.isFailure)
    }

    @Test fun fingerprintRemainsStableAfterCloseAndRepeatedAccess() {
        val output = CamaroDiagExportStorage.VerifiedOutput(ByteArrayOutputStream())
        output.write(byteArrayOf(12, 34))
        assertTrue(runCatching { output.fingerprint() }.isFailure)
        output.close()
        val first = output.fingerprint()
        output.close()
        assertEquals(first, output.fingerprint())
        assertEquals(2L, first.length)
    }

    @Test fun failedCloseDoesNotProduceSuccessfulFingerprint() {
        val broken = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun close() { throw IOException("close failed") }
        }
        val output = CamaroDiagExportStorage.VerifiedOutput(broken)
        output.write(1)
        assertTrue(runCatching { output.close() }.exceptionOrNull() is
            CamaroDiagExportStorage.ExportStorageException)
        assertTrue(runCatching { output.fingerprint() }.isFailure)
    }

    @Test fun readbackRejectsAdditionalBytesWithoutConsumingAnUnboundedStream() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertEquals(4L, CamaroDiagExportStorage.fingerprint(bytes.inputStream(), 4L).length)
        assertTrue(runCatching {
            CamaroDiagExportStorage.fingerprint(bytes.inputStream(), 3L)
        }.exceptionOrNull() is CamaroDiagExportStorage.ExportStorageException)
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
