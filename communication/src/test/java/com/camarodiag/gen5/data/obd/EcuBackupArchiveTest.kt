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
        val failure = runCatching {
            EcuBackupArchive.export(archive, listOf(capture().copy(independentRead = null)),
                setOf(EcuBackupCoverage.Region.CALIBRATION))
        }
        assertTrue(failure.isFailure)
        assertEquals(0, archive.size())
    }

    @Test fun conflictingIndependentReadIsRejected() {
        val archive = ByteArrayOutputStream()
        val failure = runCatching {
            EcuBackupArchive.export(
                archive,
                listOf(capture().copy(independentRead = byteArrayOf(1, 2, 3, 9))),
                setOf(EcuBackupCoverage.Region.CALIBRATION)
            )
        }
        assertTrue(failure.isFailure)
        assertEquals(0, archive.size())
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
    @Test fun malformedAndOverlappingSegmentsAreRejectedBeforeExport() {
        val first = capture()
        val overlap = first.copy(startAddress = first.startAddress + 2L)
        val output = ByteArrayOutputStream()
        val failure = runCatching {
            EcuBackupArchive.export(output, listOf(first, overlap), setOf(first.region))
        }
        assertTrue(failure.isFailure)
        assertEquals(0, output.size())
    }

    @Test fun importedArchiveRejectsAlteredPrimaryBytes() {
        val original = ByteArrayOutputStream()
        EcuBackupArchive.export(original, listOf(capture()), setOf(capture().region))
        val altered = ByteArrayOutputStream()
        java.util.zip.ZipInputStream(ByteArrayInputStream(original.toByteArray())).use { source ->
            ZipOutputStream(altered).use { target ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    target.putNextEntry(ZipEntry(entry.name))
                    val bytes = source.readBytes()
                    if (entry.name.endsWith("first.bin")) bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
                    target.write(bytes)
                    target.closeEntry()
                    source.closeEntry()
                }
            }
        }
        assertTrue(runCatching {
            EcuBackupArchive.import(ByteArrayInputStream(altered.toByteArray()))
        }.isFailure)
    }

    @Test fun emptyBackupIsRejectedBeforeWritingOutput() {
        val output = ByteArrayOutputStream()
        assertTrue(runCatching {
            EcuBackupArchive.export(output, emptyList(), emptySet())
        }.isFailure)
        assertEquals(0, output.size())
    }

    @Test fun unknownManifestMetadataIsRejected() {
        val original = ByteArrayOutputStream()
        EcuBackupArchive.export(original, listOf(capture()), setOf(capture().region))
        val altered = ByteArrayOutputStream()
        java.util.zip.ZipInputStream(ByteArrayInputStream(original.toByteArray())).use { source ->
            ZipOutputStream(altered).use { target ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    target.putNextEntry(ZipEntry(entry.name))
                    val bytes = source.readBytes()
                    if (entry.name == "manifest.properties") {
                        target.write(bytes)
                        target.write("\nunknown.option=1\n".toByteArray())
                    } else {
                        target.write(bytes)
                    }
                    target.closeEntry()
                    source.closeEntry()
                }
            }
        }
        assertTrue(runCatching {
            EcuBackupArchive.import(ByteArrayInputStream(altered.toByteArray()))
        }.isFailure)
    }

    @Test fun duplicateManifestPropertiesAreRejected() {
        val original = ByteArrayOutputStream()
        EcuBackupArchive.export(original, listOf(capture()), setOf(capture().region))
        val altered = ByteArrayOutputStream()
        java.util.zip.ZipInputStream(ByteArrayInputStream(original.toByteArray())).use { source ->
            ZipOutputStream(altered).use { target ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    target.putNextEntry(ZipEntry(entry.name))
                    target.write(source.readBytes())
                    if (entry.name == "manifest.properties") {
                        target.write("\ncount=1\n".toByteArray())
                    }
                    target.closeEntry()
                    source.closeEntry()
                }
            }
        }
        assertTrue(runCatching {
            EcuBackupArchive.import(ByteArrayInputStream(altered.toByteArray()))
        }.isFailure)
    }

    @Test fun exportUsesFrozenBytesWhenCallerMutatesBufferDuringZipWrite() {
        val initial = capture()
        val destination = ByteArrayOutputStream()
        var mutated = false
        val interceptingOutput = object : java.io.FilterOutputStream(destination) {
            override fun write(b: Int) {
                if (!mutated) {
                    initial.firstRead[0] = 99
                    initial.independentRead!![0] = 99
                    mutated = true
                }
                out.write(b)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (!mutated) {
                    initial.firstRead[0] = 99
                    initial.independentRead!![0] = 99
                    mutated = true
                }
                out.write(b, off, len)
            }
        }
        EcuBackupArchive.export(interceptingOutput, listOf(initial), setOf(initial.region))
        assertTrue(mutated)
        val restored = EcuBackupArchive.import(ByteArrayInputStream(destination.toByteArray()))
        assertTrue(restored.verification.consistent)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), restored.captures.single().firstRead)
    }

}
