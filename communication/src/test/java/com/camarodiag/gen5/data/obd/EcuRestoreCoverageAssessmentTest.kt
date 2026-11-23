package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class EcuRestoreCoverageAssessmentTest {
    private val region = EcuBackupCoverage.Region.PROGRAM_FLASH

    private fun capture(address: Long, bytes: ByteArray) =
        EcuBackupManifest.Capture(
            region, address, bytes.size.toLong(), bytes.copyOf(), bytes.copyOf(),
            EcuBackupManifest.Origin.REPORTED_HARDWARE_READ
        )

    private fun archive(vararg captures: EcuBackupManifest.Capture): EcuBackupArchive.Imported {
        val bytes = ByteArrayOutputStream()
        EcuBackupArchive.export(
            bytes, captures.toList(), emptySet(), vehicleVin = "1G1FA1RXXR0123456"
        )
        return EcuBackupArchive.import(bytes.toByteArray().inputStream())
    }

    @Test fun distinguishesUnknownEcuMapFromCompleteDeclaredRegionCoverage() {
        val data = archive(capture(0x1000, byteArrayOf(1, 2, 3, 4)))
        assertEquals(
            EcuRestoreCoverageAssessment.Status.UNKNOWN_MAP,
            EcuRestoreCoverageAssessment.inspect(data, emptyList()).status
        )
        val mapped = EcuRestoreCoverageAssessment.inspect(
            data, listOf(EcuRestoreImage.MemoryRegion(region, 0x1000, 4))
        )
        assertEquals(EcuRestoreCoverageAssessment.Status.COVERS_SUPPLIED_MAP, mapped.status)
        assertEquals(4L, mapped.expectedBytes)
        assertTrue(mapped.gaps.isEmpty())
    }

    @Test fun reportsExactMissingAbsoluteAddressAndByteLength() {
        val data = archive(
            capture(0x1000, byteArrayOf(1, 2)),
            capture(0x1004, byteArrayOf(5, 6))
        )
        val report = EcuRestoreCoverageAssessment.inspect(
            data, listOf(EcuRestoreImage.MemoryRegion(region, 0x1000, 6))
        )
        assertEquals(EcuRestoreCoverageAssessment.Status.PARTIAL, report.status)
        assertEquals(
            listOf(EcuRestoreCoverageAssessment.Gap(region, 0x1002, 2)), report.gaps
        )
    }

    @Test fun rejectsCaptureOutsideDeclaredMapInsteadOfIgnoringIt() {
        val data = archive(
            capture(0x1000, byteArrayOf(1, 2)),
            capture(0x2000, byteArrayOf(3, 4))
        )
        val report = EcuRestoreCoverageAssessment.inspect(
            data, listOf(EcuRestoreImage.MemoryRegion(region, 0x1000, 2))
        )
        assertEquals(EcuRestoreCoverageAssessment.Status.INVALID, report.status)
    }
}
