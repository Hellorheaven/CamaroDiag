package com.camarodiag.gen5.ui

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-for-byte export snapshot regressions; simulated private files only.
 * No CAN bus, vehicle, adapter or Android device is used.
 */
class CanConsoleRawArchiveIntegrityTest {
    private fun file(): File = Files.createTempFile("camarodiag-raw-can-", ".log").toFile()

    @Test fun largeRawArchiveIsReadIncrementallyAndNeverReducedToUiPreview() {
        val file = file()
        try {
            val chunk = ("7E8 03 41 0C 1A\r\n").toByteArray()
            file.outputStream().use { output ->
                repeat(150_000) { output.write(chunk) }
            }
            val expected = chunk.size.toLong() * 150_000L
            val snapshot = CanConsoleRawArchiveIntegrity.capture(file)
            assertEquals(expected, snapshot.sizeBytes)
            assertEquals(64, snapshot.sha256.length)
            assertTrue(CanConsoleRawArchiveIntegrity.isCurrent(snapshot))
        } finally {
            file.delete()
        }
    }

    @Test fun rawArchiveAppendedAfterClickMustNotBePublishedAsOriginal() {
        val file = file()
        try {
            file.writeText("7E8 03 41 0C 1A\r\n")
            val captured = CanConsoleRawArchiveIntegrity.capture(file)
            file.appendText("7E9 03 41 0C 1B\r\n")
            assertFalse(CanConsoleRawArchiveIntegrity.isCurrent(captured))
        } finally {
            file.delete()
        }
    }

    @Test fun sameSizeModificationCannotPassThroughFileSizeOnlyCheck() {
        val file = file()
        try {
            file.writeText("7E8 03 41 0C 1A\r\n")
            val captured = CanConsoleRawArchiveIntegrity.capture(file)
            RandomAccessFile(file, "rw").use { output ->
                output.seek(0)
                output.writeByte('6'.code)
            }
            assertEquals(captured.sizeBytes, file.length())
            assertFalse(CanConsoleRawArchiveIntegrity.isCurrent(captured))
        } finally {
            file.delete()
        }
    }

    @Test fun deletionTruncationAndMissingFileFailClosed() {
        val file = file()
        val missing = File(file.parentFile, file.name + ".missing")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                CanConsoleRawArchiveIntegrity.capture(missing)
            }
            file.writeText("7E8 03 41 0C 1A\r\n")
            val snapshot = CanConsoleRawArchiveIntegrity.capture(file)
            RandomAccessFile(file, "rw").use { it.setLength(1L) }
            assertFalse(CanConsoleRawArchiveIntegrity.isCurrent(snapshot))
            file.delete()
            assertFalse(CanConsoleRawArchiveIntegrity.isCurrent(snapshot))
        } finally {
            file.delete()
        }
    }
}
