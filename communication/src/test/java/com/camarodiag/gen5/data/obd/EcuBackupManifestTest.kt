package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuBackupManifestTest {
    private val flash = EcuBackupCoverage.Region.PROGRAM_FLASH
    private val data = EcuBackupCoverage.Region.DATA_FLASH

    private fun capture(
        region: EcuBackupCoverage.Region = flash,
        size: Int = 4,
        actual: ByteArray = byteArrayOf(1, 2, 3, 4),
        second: ByteArray? = actual.clone()
    ) = EcuBackupManifest.Capture(
        region = region,
        startAddress = 0,
        expectedBytes = size.toLong(),
        firstRead = actual,
        independentRead = second,
        origin = EcuBackupManifest.Origin.IMPORTED_FILE
    )

    @Test fun completeOfflineCopyIsNotAProvenPhysicalBackup() {
        val report = EcuBackupManifest.inspect(
            controllerLabel = "E39",
            createdAtUtcMillis = 12345L,
            captures = listOf(capture(flash), capture(data)),
            expectedRegions = setOf(flash, data)
        )
        assertEquals(EcuBackupCoverage.Status.COMPLETE, report.coverage.status)
        assertEquals(8L, report.coverage.capturedBytes)
        assertEquals(2, report.entries.size)
        assertTrue(report.entries.all { it.readsIdentical })
        assertFalse(report.hardwareProvenanceVerified)
        assertEquals(EcuBenchExpertPlan.sha256Hex(byteArrayOf(1, 2, 3, 4)),
            report.entries.first().sha256)
    }

    @Test fun missingRegionsAndUnknownMemoryMapAreNotComplete() {
        val missing = EcuBackupManifest.inspect("E39", 1L, listOf(capture()), setOf(flash, data))
        assertEquals(EcuBackupCoverage.Status.PARTIAL, missing.coverage.status)
        assertEquals(setOf(data), missing.coverage.missingRegions)

        val unknown = EcuBackupManifest.inspect("E39", 1L, listOf(capture()), emptySet())
        assertEquals(EcuBackupCoverage.Status.PARTIAL, unknown.coverage.status)
    }

    @Test fun independentReadbackMustMatchTheBytesExactly() {
        val altered = EcuBackupManifest.inspect("E39", 1L,
            listOf(capture(second = byteArrayOf(1, 2, 3, 9))), setOf(flash))
        assertEquals(EcuBackupCoverage.Status.PARTIAL, altered.coverage.status)
        assertFalse(altered.entries.single().readsIdentical)

        val unavailable = EcuBackupManifest.inspect("E39", 1L,
            listOf(capture(second = null)), setOf(flash))
        assertEquals(EcuBackupCoverage.Status.PARTIAL, unavailable.coverage.status)
        assertFalse(unavailable.entries.single().readsIdentical)
    }

    @Test fun shortOrOverlongReadsCannotLookComplete() {
        val truncated = EcuBackupManifest.inspect("E39", 1L,
            listOf(capture(size = 5)), setOf(flash))
        assertEquals(1L, truncated.coverage.missingBytes)
        assertEquals(EcuBackupCoverage.Status.PARTIAL, truncated.coverage.status)

        val overflow = EcuBackupManifest.inspect("E39", 1L,
            listOf(capture(size = 2)), setOf(flash))
        assertEquals(EcuBackupCoverage.Status.INCONSISTENT, overflow.coverage.status)
    }
}
