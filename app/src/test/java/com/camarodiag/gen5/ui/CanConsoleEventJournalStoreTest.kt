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
            assertEquals("1,0,TX,true,\"event-0\"", rows[1])
            assertEquals("620,619,RX,false,\"event-619\"", rows.last())
            store.append(listOf(event(620)))
            assertFalse(store.isCurrent(snapshot))
            store.reset()
            assertNull(store.snapshot())
            assertFalse(snapshot.file.exists())
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
