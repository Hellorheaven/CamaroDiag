package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EcuBackupArchiveTest {
    private fun capture() = EcuBackupManifest.Capture(
        EcuBackupCoverage.Region.CALIBRATION, 0x1000, 4,
        byteArrayOf(1, 2, 3, 4), byteArrayOf(1, 2, 3, 4),
        EcuBackupManifest.Origin.IMPORTED_FILE
    )

    @Test fun exportAndImportPreserveBytesAndCoverage() {
        val archive = ByteArrayOutputStream()
        EcuBackupArchive.export(archive, listOf(capture()), setOf(capture().region), "LFX offline")
        val result = EcuBackupArchive.import(ByteArrayInputStream(archive.toByteArray()))
        assertTrue(result.verification.consistent)
        assertEquals("LFX offline", result.manifest.controllerLabel)
        assertEquals(EcuBackupCoverage.Status.COMPLETE, result.manifest.coverage.status)
        assertArrayEquals(capture().firstRead, result.captures.single().firstRead)
        assertArrayEquals(capture().independentRead, result.captures.single().independentRead)
    }

    @Test fun missingIndependentReadIsRejected() {
        val archive = ByteArrayOutputStream()
        EcuBackupArchive.export(archive, listOf(capture().copy(independentRead = null)),
            setOf(EcuBackupCoverage.Region.CALIBRATION))
        val failure = runCatching { EcuBackupArchive.import(ByteArrayInputStream(archive.toByteArray())) }
        assertTrue(failure.isFailure)
    }

    @Test fun conflictingIndependentReadIsRejected() {
        val archive = ByteArrayOutputStream()
        EcuBackupArchive.export(
            archive,
            listOf(capture().copy(independentRead = byteArrayOf(1, 2, 3, 9))),
            setOf(EcuBackupCoverage.Region.CALIBRATION)
        )
        assertTrue(runCatching {
            EcuBackupArchive.import(ByteArrayInputStream(archive.toByteArray()))
        }.isFailure)
    }

    @Test fun unexpectedArchiveEntryIsRejected() {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("../unexpected.bin"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        assertTrue(runCatching {
            EcuBackupArchive.import(ByteArrayInputStream(archive.toByteArray()))
        }.isFailure)
    }

    @Test fun oversizedOrMissingRegionsRemainPartial() {
        val archive = ByteArrayOutputStream()
        EcuBackupArchive.export(archive, listOf(capture()),
            setOf(EcuBackupCoverage.Region.CALIBRATION, EcuBackupCoverage.Region.EXTERNAL_EEPROM))
        val result = EcuBackupArchive.import(ByteArrayInputStream(archive.toByteArray()))
        assertEquals(EcuBackupCoverage.Status.PARTIAL, result.manifest.coverage.status)
        assertTrue(EcuBackupCoverage.Region.EXTERNAL_EEPROM in result.manifest.coverage.missingRegions)
    }
}
