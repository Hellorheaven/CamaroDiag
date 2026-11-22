package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class EcuExternalDumpPairTest {
    private val bytes = byteArrayOf(1, 2, 3, 4)

    @Test fun boardAndChipDumpsArePortableButNotAttestedPhysicalReads() {
        for (method in EcuBenchExpertPlan.AccessMethod.values()) {
            val region = EcuBenchExpertPlan.MemoryRegion.values().first(method::permitsRegion)
            val output = ByteArrayOutputStream()
            EcuExternalDumpPair.export(output, bytes.inputStream(), bytes.copyOf().inputStream(),
                method, region, 0, bytes.size, "test-board", "2G1TEST0000000001", 1)
            val archive = EcuBackupArchive.import(output.toByteArray().inputStream())
            assertTrue(archive.verification.consistent)
            assertEquals(EcuBackupCoverage.Status.PARTIAL, archive.manifest.coverage.status)
            assertFalse(archive.manifest.hardwareProvenanceVerified)
            assertEquals(EcuBackupManifest.Origin.IMPORTED_FILE, archive.captures.single().origin)
            assertEquals(EcuExternalDumpPair.regionFor(region), archive.captures.single().region)
            assertArrayEquals(bytes, archive.captures.single().firstRead)
            assertArrayEquals(bytes, archive.captures.single().independentRead)
        }
    }

    @Test fun invalidInputsDoNotEmitAnyZipBytes() {
        val method = EcuBenchExpertPlan.AccessMethod.EXTERNAL_EEPROM_ISP
        val region = EcuBenchExpertPlan.MemoryRegion.EXTERNAL_EEPROM
        val cases = listOf(
            byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3, 5), byteArrayOf(1, 2, 3, 4, 5)
        )
        for (other in cases) {
            val output = ByteArrayOutputStream()
            assertTrue(runCatching {
                EcuExternalDumpPair.export(output, bytes.inputStream(), other.inputStream(),
                    method, region, 0, bytes.size)
            }.isFailure)
            assertEquals(0, output.size())
        }
        val shared = bytes.inputStream()
        val output = ByteArrayOutputStream()
        assertTrue(runCatching {
            EcuExternalDumpPair.export(output, shared, shared, method, region, 0, bytes.size)
        }.isFailure)
        assertTrue(runCatching {
            EcuExternalDumpPair.export(output, bytes.inputStream(), bytes.inputStream(),
                method, EcuBenchExpertPlan.MemoryRegion.PFLASH, 0, bytes.size)
        }.isFailure)
        assertTrue(runCatching {
            EcuExternalDumpPair.export(output, bytes.inputStream(), bytes.inputStream(),
                method, region, Long.MAX_VALUE, bytes.size)
        }.isFailure)
        assertEquals(0, output.size())
    }
}
