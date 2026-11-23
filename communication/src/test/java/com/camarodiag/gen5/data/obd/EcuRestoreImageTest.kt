package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class EcuRestoreImageTest {
    private val region = EcuBackupCoverage.Region.PROGRAM_FLASH
    private val vin = "1G1FA1RXXR0123456"

    private fun verifiedArchive(
        segments: List<EcuBackupManifest.Capture>
    ): EcuBackupArchive.Imported {
        val output = ByteArrayOutputStream()
        EcuBackupArchive.export(
            output, segments, emptySet(), vehicleVin = vin
        )
        return EcuBackupArchive.import(output.toByteArray().inputStream())
    }

    private fun segment(start: Long, vararg content: Byte) =
        EcuBackupManifest.Capture(
            region, start, content.size.toLong(),
            content.copyOf(), content.copyOf(),
            EcuBackupManifest.Origin.REPORTED_HARDWARE_READ
        )

    @Test fun exactContiguousRegionsReassembleOriginalBytesWithoutMutation() {
        val archive = verifiedArchive(listOf(
            segment(0x1000, 1, 2), segment(0x1002, 3, 4)
        ))
        val image = EcuRestoreImage.assemble(
            archive, listOf(EcuRestoreImage.MemoryRegion(region, 0x1000, 4))
        ).single()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), image.bytes())
        assertEquals(4, image.byteCount)
        val detached = image.bytes()
        detached[0] = 99
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), image.bytes())
        val output = ByteArrayOutputStream()
        image.writeTo(output)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
        assertEquals(EcuBenchExpertPlan.sha256Hex(output.toByteArray()), image.sha256)
    }

    @Test fun missingPhysicalBytesAndIncorrectGeometryNeverProduceCompleteImage() {
        val archive = verifiedArchive(listOf(
            segment(0x1000, 1, 2), segment(0x1004, 3, 4)
        ))
        val invalidMaps = listOf(
            listOf(EcuRestoreImage.MemoryRegion(region, 0x1000, 6)),
            listOf(EcuRestoreImage.MemoryRegion(region, 0x1000, 4)),
            listOf(EcuRestoreImage.MemoryRegion(region, 0x1002, 4)),
            listOf(EcuRestoreImage.MemoryRegion(region, 0x1000, 2),
                EcuRestoreImage.MemoryRegion(region, 0x1001, 2))
        )
        invalidMaps.forEach { memoryMap ->
            assertTrue(runCatching {
                EcuRestoreImage.assemble(archive, memoryMap)
            }.isFailure)
        }
    }

    @Test fun unavailableRegionAndEmptyMapAreRejected() {
        val archive = verifiedArchive(listOf(segment(0x1000, 1, 2)))
        assertTrue(runCatching { EcuRestoreImage.assemble(archive, emptyList()) }.isFailure)
        assertTrue(runCatching {
            EcuRestoreImage.assemble(
                archive, listOf(
                    EcuRestoreImage.MemoryRegion(
                        EcuBackupCoverage.Region.EXTERNAL_EEPROM, 0x1000, 2)
                )
            )
        }.isFailure)
    }
}
