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

    @Test fun exportStaysLockedUntilThePhysicalTxAndItsHistoryAreComplete() {
        // RX can still arrive after the TX row was appended. A snapshot taken
        // now would falsely look like a complete chronological console log.
        assertFalse(CanConsoleExportPolicy.canExport(
            isCapturing = false, archiveFailed = false,
            hasHistory = true, hasRawData = true,
            isSendingRequest = true
        ))
        // The same rule applies when a full passive raw archive is present:
        // the mixed TX/RX history must not be cut at an in-flight operation.
        assertFalse(CanConsoleExportPolicy.canExport(
            isCapturing = false, archiveFailed = false,
            hasHistory = true, hasRawData = true,
            requiresRawArchive = true, hasRawArchive = true,
            isSendingRequest = true
        ))
        assertTrue(CanConsoleExportPolicy.canExport(
            isCapturing = false, archiveFailed = false,
            hasHistory = true, hasRawData = true,
            requiresRawArchive = true, hasRawArchive = true,
            isSendingRequest = false
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
