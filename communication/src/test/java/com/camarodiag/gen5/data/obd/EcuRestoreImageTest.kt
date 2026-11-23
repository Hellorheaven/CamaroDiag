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
    @Test fun disjointRegionsOfSameMemoryTypeCanBeAssembledIndependently() {
        val archive = verifiedArchive(listOf(
            segment(0x1000, 1, 2), segment(0x2000, 3, 4)
        ))
        val images = EcuRestoreImage.assemble(
            archive, listOf(
                EcuRestoreImage.MemoryRegion(region, 0x1000, 2),
                EcuRestoreImage.MemoryRegion(region, 0x2000, 2)
            )
        )
        assertEquals(2, images.size)
        assertArrayEquals(byteArrayOf(1, 2), images[0].bytes())
        assertArrayEquals(byteArrayOf(3, 4), images[1].bytes())
    }

    @Test fun validSubregionCannotSilentlyDiscardCapturedBytes() {
        val archive = verifiedArchive(listOf(
            segment(0x1000, 1, 2), segment(0x2000, 3, 4)
        ))
        assertTrue(runCatching {
            EcuRestoreImage.assemble(
                archive, listOf(EcuRestoreImage.MemoryRegion(region, 0x1000, 2))
            )
        }.isFailure)
    }

    @Test fun mutationOfImportedCaptureAfterVerificationIsRejected() {
        val archive = verifiedArchive(listOf(segment(0x1000, 1, 2, 3, 4)))
        archive.captures.single().firstRead[0] = 99
        assertTrue(runCatching {
            EcuRestoreImage.assemble(
                archive, listOf(EcuRestoreImage.MemoryRegion(region, 0x1000, 4))
            )
        }.isFailure)
    }

    @Test fun mutationOfSecondPassAfterVerificationIsRejected() {
        val archive = verifiedArchive(listOf(segment(0x1000, 1, 2, 3, 4)))
        archive.captures.single().independentRead!![2] = 99
        assertTrue(runCatching {
            EcuRestoreImage.assemble(
                archive, listOf(EcuRestoreImage.MemoryRegion(region, 0x1000, 4))
            )
        }.isFailure)
    }

    @Test fun directImageCannotClaimMoreOrFewerBytesThanItsPhysicalRegion() {
        val geometry = EcuRestoreImage.MemoryRegion(region, 0x1000, 4)
        assertTrue(runCatching {
            EcuRestoreImage.RegionImage(geometry, byteArrayOf(1, 2, 3))
        }.isFailure)
        assertTrue(runCatching {
            EcuRestoreImage.RegionImage(geometry, byteArrayOf(1, 2, 3, 4, 5))
        }.isFailure)
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4),
            EcuRestoreImage.RegionImage(geometry, byteArrayOf(1, 2, 3, 4)).bytes()
        )
    }

}
