package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class EcuBackupCoverageTest {
    private val digest = "ab".repeat(32)
    private fun segment(start: Long, expected: Long, captured: Long = expected,
                        verified: Boolean = true) = EcuBackupCoverage.Segment(
        EcuBackupCoverage.Region.PROGRAM_FLASH, start, expected, captured, digest, verified
    )

    @Test fun completeRequiresAllBytesAndIndependentVerification() {
        assertEquals(EcuBackupCoverage.Status.COMPLETE,
            EcuBackupCoverage.inspect(listOf(segment(0, 1024), segment(1024, 1024))).status)
        assertEquals(EcuBackupCoverage.Status.PARTIAL,
            EcuBackupCoverage.inspect(listOf(segment(0, 1024, verified = false))).status)
    }

    @Test fun missingBytesAndEmptyCoverageNeverClaimComplete() {
        val report = EcuBackupCoverage.inspect(listOf(segment(0, 100, 40)))
        assertEquals(EcuBackupCoverage.Status.PARTIAL, report.status)
        assertEquals(40L, report.capturedBytes)
        assertEquals(60L, report.missingBytes)
        assertEquals(EcuBackupCoverage.Status.PARTIAL,
            EcuBackupCoverage.inspect(emptyList()).status)
    }

    @Test fun overlapAndInvalidRangesAreInconsistent() {
        assertEquals(EcuBackupCoverage.Status.INCONSISTENT,
            EcuBackupCoverage.inspect(listOf(segment(0, 100), segment(50, 100))).status)
        assertEquals(EcuBackupCoverage.Status.INCONSISTENT,
            EcuBackupCoverage.inspect(listOf(segment(-1, 10))).status)
        assertEquals(EcuBackupCoverage.Status.INCONSISTENT,
            EcuBackupCoverage.inspect(listOf(segment(0, 10, 11))).status)
        assertEquals(EcuBackupCoverage.Status.INCONSISTENT,
            EcuBackupCoverage.inspect(listOf(segment(Long.MAX_VALUE, 2))).status)
    }

    @Test fun differentRegionsMayUseSameOffsets() {
        val p = segment(0, 64)
        val d = p.copy(region = EcuBackupCoverage.Region.DATA_FLASH)
        assertEquals(EcuBackupCoverage.Status.COMPLETE,
            EcuBackupCoverage.inspect(listOf(p, d)).status)
    }

    @Test fun missingRequiredRegionPreventsMisleadingCompleteBackup() {
        val report = EcuBackupCoverage.inspect(
            segments = listOf(segment(0, 64)),
            requiredRegions = setOf(
                EcuBackupCoverage.Region.PROGRAM_FLASH,
                EcuBackupCoverage.Region.DATA_FLASH,
                EcuBackupCoverage.Region.EXTERNAL_EEPROM
            )
        )
        assertEquals(EcuBackupCoverage.Status.PARTIAL, report.status)
        assertEquals(
            setOf(EcuBackupCoverage.Region.DATA_FLASH, EcuBackupCoverage.Region.EXTERNAL_EEPROM),
            report.missingRegions
        )
        assertEquals(EcuBackupCoverage.Status.PARTIAL,
            EcuBackupCoverage.inspect(emptyList(), setOf(EcuBackupCoverage.Region.PROGRAM_FLASH)).status
        )
    }

    @Test fun checksumMustBeWellFormedAndVerified() {
        assertEquals(EcuBackupCoverage.Status.PARTIAL,
            EcuBackupCoverage.inspect(listOf(segment(0, 64).copy(sha256 = "unknown"))).status)
    }
}
