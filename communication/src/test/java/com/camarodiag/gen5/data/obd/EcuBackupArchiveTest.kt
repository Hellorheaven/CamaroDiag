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
        assertEquals(null, result.manifest.vehicleVin)
        assertEquals(EcuBackupCoverage.Status.COMPLETE, result.manifest.coverage.status)
        assertArrayEquals(capture().firstRead, result.captures.single().firstRead)
        assertArrayEquals(capture().independentRead, result.captures.single().independentRead)
    }

    @Test fun windowsPortableArchiveCanBeImportedWithoutClaimingCompleteEcuCoverage() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val hash = EcuBenchExpertPlan.sha256Hex(bytes).uppercase()
        val manifest = """
            format=CamaroDiag ECU offline archive v2
            vin=1G1FA1RXXR0123456
            controller=
            created=1795320000000
            count=1
            required=
            capture.0.region=OTHER
            capture.0.start=4096
            capture.0.expected=4
            capture.0.origin=REPORTED_HARDWARE_READ
            capture.0.sha256=$hash
            capture.0.second=true
        """.trimIndent().toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, data) in listOf(
                "manifest.properties" to manifest,
                "capture/0/first.bin" to bytes,
                "capture/0/second.bin" to bytes.copyOf()
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        val imported = EcuBackupArchive.import(output.toByteArray().inputStream())
        assertTrue(imported.verification.consistent)
        assertEquals("1G1FA1RXXR0123456", imported.manifest.vehicleVin)
        assertEquals(EcuBackupCoverage.Status.PARTIAL, imported.manifest.coverage.status)
        assertEquals(false, imported.manifest.hardwareProvenanceVerified)
        assertEquals(EcuBackupCoverage.Region.OTHER, imported.captures.single().region)
        assertArrayEquals(bytes, imported.captures.single().firstRead)
        assertArrayEquals(bytes, imported.captures.single().independentRead)
    }

    @Test fun malformedVinIsRejectedBeforeCreatingArchive() {
        val output = ByteArrayOutputStream()
        val failure = runCatching {
            EcuBackupArchive.export(output, listOf(capture()), setOf(capture().region), vehicleVin = "unknown")
        }
        assertTrue(failure.isFailure)
        assertEquals(0, output.size())
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

    @Test fun identicalBufferReferenceIsRejectedBeforeAnyArchiveOutput() {
        val primary = capture()
        val output = ByteArrayOutputStream()
        assertTrue(runCatching {
            EcuBackupArchive.export(output, listOf(primary.copy(independentRead = primary.firstRead)),
                setOf(primary.region))
        }.isFailure)
        assertEquals(0, output.size())
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

    @Test fun duplicateRequiredRegionsAndNonpositiveTimestampsAreRejected() {
        val output = ByteArrayOutputStream()
        assertTrue(runCatching {
            EcuBackupArchive.export(output, listOf(capture()),
                setOf(EcuBackupCoverage.Region.CALIBRATION), createdAtUtcMillis = 0)
        }.isFailure)
        assertEquals(0, output.size())

        val original = ByteArrayOutputStream()
        EcuBackupArchive.export(original, listOf(capture()),
            setOf(EcuBackupCoverage.Region.CALIBRATION))
        fun repackage(change: (String) -> String): ByteArray {
            val modified = ByteArrayOutputStream()
            java.util.zip.ZipInputStream(original.toByteArray().inputStream()).use { source ->
                ZipOutputStream(modified).use { target ->
                    while (true) {
                        val entry = source.nextEntry ?: break
                        val data = source.readBytes()
                        target.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == "manifest.properties") {
                            target.write(change(String(data, Charsets.ISO_8859_1))
                                .toByteArray(Charsets.ISO_8859_1))
                        } else target.write(data)
                        target.closeEntry()
                        source.closeEntry()
                    }
                }
            }
            return modified.toByteArray()
        }
        val duplicated = repackage {
            it.replace("required=CALIBRATION", "required=CALIBRATION,CALIBRATION")
        }
        assertTrue(runCatching { EcuBackupArchive.import(duplicated.inputStream()) }.isFailure)
        val invalidTime = repackage {
            it.replace(Regex("(?m)^created=[0-9]+$"), "created=0")
        }
        assertTrue(runCatching { EcuBackupArchive.import(invalidTime.inputStream()) }.isFailure)
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

    @Test fun importRejectsEmptyPrimaryReadEvenWithMatchingChecksum() {
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
                        val text = String(bytes, Charsets.ISO_8859_1)
                        val originalHash = EcuBenchExpertPlan.sha256Hex(capture().firstRead)
                        val emptyHash = EcuBenchExpertPlan.sha256Hex(byteArrayOf())
                        target.write(text.replace(originalHash, emptyHash).toByteArray(Charsets.ISO_8859_1))
                    } else if (entry.name != "capture/0/first.bin") {
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

    @Test fun importRejectsMissingSecondReadEvenIfManifestSaysAbsent() {
        val original = ByteArrayOutputStream()
        EcuBackupArchive.export(original, listOf(capture()), setOf(capture().region))
        val altered = ByteArrayOutputStream()
        java.util.zip.ZipInputStream(ByteArrayInputStream(original.toByteArray())).use { source ->
            ZipOutputStream(altered).use { target ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    val bytes = source.readBytes()
                    if (entry.name != "capture/0/second.bin") {
                        target.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == "manifest.properties") {
                            target.write(String(bytes, Charsets.ISO_8859_1)
                                .replace("capture.0.second=true", "capture.0.second=false")
                                .toByteArray(Charsets.ISO_8859_1))
                        } else {
                            target.write(bytes)
                        }
                        target.closeEntry()
                    }
                    source.closeEntry()
                }
            }
        }
        assertTrue(runCatching {
            EcuBackupArchive.import(ByteArrayInputStream(altered.toByteArray()))
        }.isFailure)
    }

    @Test fun mobileImportBudgetCountsTheManifestAndBothReads() {
        val source = ByteArrayOutputStream()
        EcuBackupArchive.export(source, listOf(capture()), setOf(capture().region))
        val archive = source.toByteArray()
        val size = java.util.zip.ZipInputStream(archive.inputStream()).use { zip ->
            var total = 0L
            while (zip.nextEntry != null) total += zip.readBytes().size
            total
        }
        assertTrue(EcuBackupArchive.import(archive.inputStream(), size).verification.consistent)
        assertTrue(runCatching { EcuBackupArchive.import(archive.inputStream(), size - 1) }.isFailure)
    }

    @Test fun archiveSizeIncludesManifestAndCannotCrossImportLimit() {
        val limit = 256L * 1024 * 1024
        assertTrue(EcuBackupArchive.fitsUncompressedLimit(limit - 64L, 64L))
        assertTrue(!EcuBackupArchive.fitsUncompressedLimit(limit - 64L, 65L))
        assertTrue(!EcuBackupArchive.fitsUncompressedLimit(limit, 1L))
        assertTrue(!EcuBackupArchive.fitsUncompressedLimit(-1L, 0L))
        assertTrue(!EcuBackupArchive.fitsUncompressedLimit(0L, -1L))
        assertTrue(!EcuBackupArchive.fitsUncompressedLimit(Long.MAX_VALUE, 1L))
    }

    @Test fun refusesAnInterruptedZipMissingItsCentralDirectoryOrFooter() {
        val original = ByteArrayOutputStream()
        EcuBackupArchive.export(original, listOf(capture()), setOf(capture().region))
        val bytes = original.toByteArray()
        val central = bytes.indices.firstOrNull { index ->
            index + 3 < bytes.size && bytes[index] == 0x50.toByte() &&
                bytes[index + 1] == 0x4B.toByte() &&
                bytes[index + 2] == 0x01.toByte() &&
                bytes[index + 3] == 0x02.toByte()
        } ?: error("Expected ZIP central directory")
        // ZipInputStream can decode local files even if this entire section
        // is missing; a verified ECU backup must reject such a truncated ZIP.
        for (truncated in listOf(bytes.copyOf(central), bytes.copyOf(bytes.size - 22),
            bytes.copyOf(bytes.size - 1))) {
            assertTrue(runCatching {
                EcuBackupArchive.import(truncated.inputStream())
            }.isFailure)
        }
        assertTrue(EcuBackupArchive.import(bytes.inputStream()).verification.consistent)
    }

    @Test fun refusesCentralDirectoryNameThatDisagreesWithLocalVerifiedFiles() {
        val original = ByteArrayOutputStream()
        EcuBackupArchive.export(original, listOf(capture()), setOf(capture().region))
        val altered = original.toByteArray().copyOf()
        val signature = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
        val index = altered.indices.firstOrNull { i ->
            i + signature.size <= altered.size &&
                altered.copyOfRange(i, i + signature.size).contentEquals(signature)
        } ?: error("Missing central file header")
        // Central name begins 46 bytes after its header.
        altered[index + 46] = 'X'.code.toByte()
        assertTrue(runCatching { EcuBackupArchive.import(altered.inputStream()) }.isFailure)
    }

    @Test fun acceptsCompleteZipWithAStandardEndOfCentralDirectoryComment() {
        val original = ByteArrayOutputStream()
        EcuBackupArchive.export(original, listOf(capture()), setOf(capture().region))
        val commented = ByteArrayOutputStream()
        java.util.zip.ZipInputStream(original.toByteArray().inputStream()).use { source ->
            ZipOutputStream(commented).use { target ->
                target.setComment("CamaroDiag complete ECU archive")
                while (true) {
                    val entry = source.nextEntry ?: break
                    target.putNextEntry(ZipEntry(entry.name))
                    target.write(source.readBytes())
                    target.closeEntry()
                    source.closeEntry()
                }
            }
        }
        val read = EcuBackupArchive.import(commented.toByteArray().inputStream())
        assertTrue(read.verification.consistent)
        assertEquals(EcuBackupCoverage.Status.COMPLETE, read.manifest.coverage.status)
    }

    @Test fun refusesCentralDirectoryWithForgedEntryCountOrOffset() {
        val archive = ByteArrayOutputStream()
        EcuBackupArchive.export(archive, listOf(capture()), setOf(capture().region))
        val original = archive.toByteArray()
        val end = original.size - 22
        assertTrue(original[end] == 0x50.toByte() && original[end + 1] == 0x4B.toByte())
        for (offset in listOf(10, 16)) {
            val altered = original.copyOf()
            altered[end + offset] = (altered[end + offset].toInt() xor 1).toByte()
            assertTrue(runCatching {
                EcuBackupArchive.import(altered.inputStream())
            }.isFailure)
        }
    }

    @Test fun rejectsBytesAppendedAfterTheCompletedZipFooter() {
        val archive = ByteArrayOutputStream()
        EcuBackupArchive.export(archive, listOf(capture()), setOf(capture().region))
        val original = archive.toByteArray()
        val extraneous = original + byteArrayOf(0x00, 0x50, 0x4B, 0x05, 0x06)
        assertTrue(runCatching {
            EcuBackupArchive.import(extraneous.inputStream())
        }.isFailure)
        assertTrue(EcuBackupArchive.import(original.inputStream()).verification.consistent)
    }

}
