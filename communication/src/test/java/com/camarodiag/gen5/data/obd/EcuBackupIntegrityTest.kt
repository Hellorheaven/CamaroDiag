package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuBackupIntegrityTest {
    private val region = EcuBackupCoverage.Region.CALIBRATION
    private val required = setOf(region)
    private fun capture(first: ByteArray = byteArrayOf(1, 2, 3)) =
        EcuBackupManifest.Capture(
            region = region,
            startAddress = 0L,
            expectedBytes = 3L,
            firstRead = first,
            independentRead = first.copyOf(),
            origin = EcuBackupManifest.Origin.IMPORTED_FILE
        )

    private fun manifest(captures: List<EcuBackupManifest.Capture>) =
        EcuBackupManifest.inspect("test", 0L, captures, required)

    @Test fun consistentBytesAndManifestPassOfflineCheck() {
        val captures = listOf(capture())
        assertTrue(EcuBackupIntegrity.verify(manifest(captures), captures, required).consistent)
    }

    @Test fun tamperedBytesAreRejected() {
        val original = listOf(capture())
        val altered = listOf(capture(byteArrayOf(1, 2, 4)))
        val problems = EcuBackupIntegrity.verify(manifest(original), altered, required).problems
        assertTrue(EcuBackupIntegrity.Problem.DIGEST_MISMATCH in problems)
    }

    @Test fun alteredCoverageAndMetadataAreRejected() {
        val captures = listOf(capture())
        val m = manifest(captures)
        assertTrue(
            EcuBackupIntegrity.Problem.METADATA_MISMATCH in
                EcuBackupIntegrity.verify(
                    m.copy(entries = m.entries.map { it.copy(startAddress = 1L) }),
                    captures, required
                ).problems
        )
        assertTrue(
            EcuBackupIntegrity.Problem.COVERAGE_MISMATCH in
                EcuBackupIntegrity.verify(
                    m.copy(coverage = m.coverage.copy(capturedBytes = 999L)),
                    captures, required
                ).problems
        )
    }

    @Test fun missingIndependentReadAndExtraEntriesAreRejected() {
        val captures = listOf(capture())
        val m = manifest(captures)
        val changed = captures.map { it.copy(independentRead = null) }
        assertTrue(
            EcuBackupIntegrity.Problem.INDEPENDENT_READ_MISMATCH in
                EcuBackupIntegrity.verify(m, changed, required).problems
        )
        assertTrue(
            EcuBackupIntegrity.Problem.ENTRY_COUNT_MISMATCH in
                EcuBackupIntegrity.verify(
                    m.copy(entries = m.entries + m.entries.first()), captures, required
                ).problems
        )
    }

    @Test fun claimedHardwareProofIsNotAccepted() {
        val captures = listOf(capture())
        val result = EcuBackupIntegrity.verify(
            manifest(captures).copy(hardwareProvenanceVerified = true), captures, required
        )
        assertFalse(result.consistent)
        assertTrue(EcuBackupIntegrity.Problem.UNVERIFIABLE_HARDWARE_PROVENANCE in result.problems)
    }
}
