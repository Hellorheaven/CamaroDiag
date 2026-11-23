package com.camarodiag.gen5.data.obd

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuRestoreCoveragePreviewTest {
    private val vin = "1G1FA1RXXR0123456"
    private val flash = EcuBackupCoverage.Region.PROGRAM_FLASH
    private val eeprom = EcuBackupCoverage.Region.EXTERNAL_EEPROM

    private fun capture(
        region: EcuBackupCoverage.Region, start: Long, bytes: ByteArray,
        origin: EcuBackupManifest.Origin = EcuBackupManifest.Origin.REPORTED_HARDWARE_READ
    ) = EcuBackupManifest.Capture(
        region, start, bytes.size.toLong(), bytes.copyOf(), bytes.copyOf(), origin
    )

    private fun archive(vararg captures: EcuBackupManifest.Capture): EcuBackupArchive.Imported {
        val output = ByteArrayOutputStream()
        EcuBackupArchive.export(output, captures.toList(), emptySet(), vehicleVin = vin)
        return EcuBackupArchive.import(output.toByteArray().inputStream())
    }

    @Test fun explicitlySuppliedRegionsShowExactDoubleReadCoverage() {
        val input = archive(
            capture(flash, 0x1000, byteArrayOf(1, 2)),
            capture(flash, 0x1002, byteArrayOf(3, 4)),
            capture(eeprom, 0x8000, byteArrayOf(5, 6))
        )
        val report = EcuRestoreCoveragePreview.inspect(input, listOf(
            EcuRestoreImage.MemoryRegion(flash, 0x1000, 4),
            EcuRestoreImage.MemoryRegion(eeprom, 0x8000, 2)
        ))
        assertTrue(report.coversSuppliedMap)
        assertEquals(6L, report.requestedBytes)
        assertEquals(6L, report.doubleReadBytes)
        assertTrue(report.missing.isEmpty())
        // This only describes the supplied map. No OEM memory-map attestation.
    }

    @Test fun gapsAndUnmappedCapturedRegionsCannotLookComplete() {
        val input = archive(
            capture(flash, 0x1000, byteArrayOf(1, 2)),
            capture(flash, 0x1004, byteArrayOf(5, 6)),
            capture(eeprom, 0x8000, byteArrayOf(7))
        )
        val report = EcuRestoreCoveragePreview.inspect(input, listOf(
            EcuRestoreImage.MemoryRegion(flash, 0x1000, 6)
        ))
        assertFalse(report.coversSuppliedMap)
        assertEquals(6L, report.requestedBytes)
        assertEquals(4L, report.doubleReadBytes)
        assertEquals(1, report.unaccountedCaptureCount)
        assertEquals(listOf(
            EcuRestoreCoveragePreview.MissingSpan(flash, 0x1002, 2)
        ), report.missing)
    }

    @Test fun importedFileWithMatchingBytesCannotMasqueradeAsLiveDoubleRead() {
        val input = archive(capture(
            flash, 0x1000, byteArrayOf(1, 2),
            EcuBackupManifest.Origin.IMPORTED_FILE
        ))
        val report = EcuRestoreCoveragePreview.inspect(input, listOf(
            EcuRestoreImage.MemoryRegion(flash, 0x1000, 2)
        ))
        assertFalse(report.coversSuppliedMap)
        assertEquals(0L, report.doubleReadBytes)
        assertEquals(listOf(
            EcuRestoreCoveragePreview.MissingSpan(flash, 0x1000, 2)
        ), report.missing)
    }

    @Test fun excessiveOrOverlappingMemoryMapsAreRejectedWithoutAllocation() {
        val input = archive(capture(flash, 0x1000, byteArrayOf(1)))
        for (map in listOf(
            listOf(
                EcuRestoreImage.MemoryRegion(flash, 0x1000, 2),
                EcuRestoreImage.MemoryRegion(flash, 0x1001, 2)
            ),
            listOf(EcuRestoreImage.MemoryRegion(flash, 0, 512L * 1024 * 1024))
        )) {
            assertTrue(runCatching {
                EcuRestoreCoveragePreview.inspect(input, map)
            }.isFailure)
        }
    }
}
