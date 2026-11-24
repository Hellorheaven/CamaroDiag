package com.camarodiag.gen5.ui

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanConsoleEventJournalStoreTest {
    private fun event(index: Int): CanConsoleHistoryEntry =
        CanConsoleHistoryEntry(
            id = index.toLong(), direction = when (index % 3) {
                0 -> "TX"
                1 -> "RX"
                else -> "SYSTEM"
            },
            timestampMillis = index.toLong(), content = "event-$index",
            succeeded = index % 2 == 0
        )

    @Test fun exportsAllChronologicalEventsIncludingThoseBeyondTheDisplayLimit() {
        val directory = Files.createTempDirectory("console-events").toFile()
        try {
            val store = CanConsoleEventJournalStore(directory)
            repeat(620) { store.append(listOf(event(it))) }
            val snapshot = requireNotNull(store.snapshot())
            assertEquals(620L, snapshot.eventCount)
            assertTrue(store.isCurrent(snapshot))
            val rows = snapshot.file.readLines()
            assertEquals(621, rows.size)
            assertEquals("1,0,TX,PENDING,\"event-0\"", rows[1])
            assertEquals("620,619,RX,FAILED,\"event-619\"", rows.last())
            store.append(listOf(event(620)))
            assertFalse(store.isCurrent(snapshot))
            store.reset()
            assertNull(store.snapshot())
            assertFalse(snapshot.file.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun clickRevisionNeverAuthorizesEventsAddedBeforeBackgroundSnapshot() {
        val directory = Files.createTempDirectory("console-export-revision").toFile()
        try {
            val store = CanConsoleEventJournalStore(directory)
            store.append(listOf(event(0)))
            val clickedAtRevision = store.revision()
            assertEquals(clickedAtRevision, store.revision())
            // IO worker starts later, after a second event arrived.
            store.append(listOf(event(1)))
            val lateSnapshot = requireNotNull(store.snapshot())
            assertEquals(2L, lateSnapshot.eventCount)
            assertTrue(store.isCurrent(lateSnapshot))
            assertTrue(store.revision() != clickedAtRevision)
            // Capturing another session's snapshot must not authorize export.
            store.reset()
            store.append(listOf(event(2)))
            assertTrue(store.revision() != clickedAtRevision)
            assertFalse(store.isCurrent(lateSnapshot))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun longEventJournalSnapshotRemainsCompleteAndRevisionStable() {
        val directory = Files.createTempDirectory("console-large-export").toFile()
        try {
            val store = CanConsoleEventJournalStore(directory)
            val events = (0 until 2000).map { event(it) }
            store.append(events)
            val atClick = store.revision()
            val snapshot = requireNotNull(store.snapshot())
            assertEquals(2000L, snapshot.eventCount)
            assertEquals(atClick, store.revision())
            assertTrue(store.isCurrent(snapshot))
            assertEquals(2001, snapshot.file.useLines { it.count() })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun disappearingArchiveInvalidatesSnapshotAndPreventsExport() {
        val directory = Files.createTempDirectory("console-lost").toFile()
        try {
            val store = CanConsoleEventJournalStore(directory)
            store.append(listOf(event(0)))
            val original = requireNotNull(store.snapshot())
            assertTrue(original.file.delete())
            assertNull(store.snapshot())
            assertTrue(store.hasFailed())
            assertFalse(store.isCurrent(original))
            assertTrue(runCatching { store.append(listOf(event(1))) }.isFailure)
            store.reset()
            assertFalse(store.hasFailed())
            store.append(listOf(event(2)))
            assertEquals(1L, requireNotNull(store.snapshot()).eventCount)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun truncationBeforeSnapshotIsRejectedAgainstLastFlushedSize() {
        val directory = Files.createTempDirectory("console-before-snapshot").toFile()
        try {
            val store = CanConsoleEventJournalStore(directory)
            store.append(listOf(event(0), event(1)))
            val file = requireNotNull(store.snapshot()).file
            java.io.RandomAccessFile(file, "rw").use { it.setLength(2L) }
            assertFalse(store.isAvailable())
            assertNull(store.snapshot())
            assertTrue(store.hasFailed())
            assertTrue(runCatching { store.append(listOf(event(2))) }.isFailure)
            store.reset()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun journalChangedBetweenAppendsFailsWithoutReclassifyingHistoryComplete() {
        val directory = Files.createTempDirectory("console-before-append").toFile()
        try {
            val store = CanConsoleEventJournalStore(directory)
            store.append(listOf(event(0)))
            val file = requireNotNull(store.snapshot()).file
            java.io.RandomAccessFile(file, "rw").use { it.setLength(1L) }
            assertTrue(runCatching { store.append(listOf(event(1))) }.isFailure)
            assertTrue(store.hasFailed())
            assertNull(store.snapshot())
            store.reset()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun externalTruncationInvalidatesAnOtherwiseUnchangedSnapshot() {
        val directory = Files.createTempDirectory("console-shortened").toFile()
        try {
            val store = CanConsoleEventJournalStore(directory)
            store.append(listOf(event(0), event(1)))
            val snapshot = requireNotNull(store.snapshot())
            assertTrue(snapshot.sizeBytes > 0)
            assertTrue(store.isCurrent(snapshot))
            java.io.RandomAccessFile(snapshot.file, "rw").use {
                it.setLength(1L)
            }
            assertFalse(store.isCurrent(snapshot))
            store.reset()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun sameSizeJournalTamperingInvalidatesChecksumAndDisablesExport() {
        val directory = Files.createTempDirectory("console-same-size-tamper").toFile()
        try {
            val store = CanConsoleEventJournalStore(directory)
            store.append(listOf(event(0), event(1)))
            val snapshot = requireNotNull(store.snapshot())
            assertEquals(64, snapshot.sha256.length)
            assertTrue(store.isCurrent(snapshot))
            java.io.RandomAccessFile(snapshot.file, "rw").use { file ->
                val originalSize = file.length()
                file.seek(10L)
                val previous = file.readUnsignedByte()
                file.seek(10L)
                file.writeByte(previous xor 0x01)
                assertEquals(originalSize, file.length())
            }
            assertFalse(store.isCurrent(snapshot))
            assertTrue(store.hasFailed())
            assertNull(store.snapshot())
            assertTrue(runCatching { store.append(listOf(event(2))) }.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun deletedFileFailsNextAppendWithoutCreatingSilentHistory() {
        val directory = Files.createTempDirectory("console-deleted-on-append").toFile()
        try {
            val store = CanConsoleEventJournalStore(directory)
            store.append(listOf(event(0)))
            val before = requireNotNull(store.snapshot())
            assertTrue(before.file.delete())
            assertTrue(runCatching { store.append(listOf(event(1))) }.isFailure)
            assertTrue(store.hasFailed())
            assertNull(store.snapshot())
            store.reset()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun invalidStorageDirectoryDoesNotLeakAnExportableJournal() {
        val directory = Files.createTempFile("console-invalid-dir", ".tmp").toFile()
        try {
            val store = CanConsoleEventJournalStore(directory)
            assertTrue(runCatching { store.append(listOf(event(1))) }.isFailure)
            assertTrue(store.hasFailed())
            assertNull(store.snapshot())
        } finally {
            directory.delete()
        }
    }

    @Test fun disposedStoreCannotBeReopenedByLateCanceledTask() {
        val directory = Files.createTempDirectory("console-disposed").toFile()
        try {
            val store = CanConsoleEventJournalStore(directory)
            store.append(listOf(event(10)))
            val snapshot = requireNotNull(store.snapshot())
            store.dispose()
            assertFalse(snapshot.file.exists())
            assertFalse(store.isCurrent(snapshot))
            assertFalse(store.isAvailable())
            assertNull(store.snapshot())
            store.reset()
            assertTrue(runCatching { store.append(listOf(event(11))) }.isFailure)
            assertEquals(0, directory.listFiles().orEmpty().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun resetSeparatesSessionsAndTheirTemporaryFiles() {
        val directory = Files.createTempDirectory("console-sessions").toFile()
        try {
            val store = CanConsoleEventJournalStore(directory)
            store.append(listOf(event(0)))
            val first = requireNotNull(store.snapshot())
            store.reset()
            store.append(listOf(event(100)))
            val second = requireNotNull(store.snapshot())
            assertFalse(store.isCurrent(first))
            assertFalse(first.file.exists())
            assertNotNull(second)
            assertEquals(1L, second.eventCount)
            assertTrue(second.file.readText().contains("event-100"))
            assertFalse(second.file.readText().contains("event-0\""))
            store.reset()
        } finally {
            directory.deleteRecursively()
        }
    }
}
