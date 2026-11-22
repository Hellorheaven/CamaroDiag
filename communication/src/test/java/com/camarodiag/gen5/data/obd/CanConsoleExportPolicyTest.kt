package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanConsoleExportPolicyTest {
    @Test fun completedCaptureCanBeExportedWithoutLosingHistory() {
        assertTrue(CanConsoleExportPolicy.canExport(
            isCapturing = false, archiveFailed = false,
            hasHistory = true, hasRawData = false
        ))
        assertTrue(CanConsoleExportPolicy.canExport(
            isCapturing = false, archiveFailed = false,
            hasHistory = false, hasRawData = true
        ))
    }

    @Test fun captureWithBrokenArchiveCannotClaimCompleteRawExport() {
        for (history in listOf(false, true)) {
            for (raw in listOf(false, true)) {
                assertFalse(CanConsoleExportPolicy.canExport(
                    isCapturing = false, archiveFailed = true,
                    hasHistory = history, hasRawData = raw
                ))
            }
        }
    }

    @Test fun disconnectionRetainsFinishedArchiveButMarksInterruptedCaptureIncomplete() {
        assertFalse(CanConsoleExportPolicy.archiveFailedAfterDisconnect(
            alreadyFailed = false, wasCapturing = false
        ))
        assertTrue(CanConsoleExportPolicy.archiveFailedAfterDisconnect(
            alreadyFailed = false, wasCapturing = true
        ))
        assertTrue(CanConsoleExportPolicy.archiveFailedAfterDisconnect(
            alreadyFailed = true, wasCapturing = false
        ))
        assertFalse(CanConsoleExportPolicy.canExport(
            isCapturing = false,
            archiveFailed = CanConsoleExportPolicy.archiveFailedAfterDisconnect(false, true),
            hasHistory = true, hasRawData = true,
            requiresRawArchive = true, hasRawArchive = true
        ))
    }

    @Test fun missingFullArchiveCannotFallBackToTruncatedRecentMemory() {
        assertFalse(CanConsoleExportPolicy.canExport(
            isCapturing = false, archiveFailed = false,
            hasHistory = true, hasRawData = true,
            requiresRawArchive = true, hasRawArchive = false
        ))
        assertTrue(CanConsoleExportPolicy.canExport(
            isCapturing = false, archiveFailed = false,
            hasHistory = true, hasRawData = true,
            requiresRawArchive = true, hasRawArchive = true
        ))
    }

    @Test fun inProgressOrEmptyCaptureIsNotExportable() {
        assertFalse(CanConsoleExportPolicy.canExport(
            isCapturing = true, archiveFailed = false,
            hasHistory = true, hasRawData = true
        ))
        assertFalse(CanConsoleExportPolicy.canExport(
            isCapturing = false, archiveFailed = false,
            hasHistory = false, hasRawData = false
        ))
    }
}
