package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class EcuPartialWritePlannerTest {
    private val original = ByteArray(16) { it.toByte() }
    private val layout = EcuPartialWritePlanner.Layout(
        regionStartAddress = 0x1000L,
        regionSize = 16L,
        eraseBlockBytes = 8,
        programBlockBytes = 2
    )

    @Test fun oneChangedBytePreservesEveryNeighborWithinItsEraseSector() {
        val modified = original.clone()
        modified[3] = 99
        val result = EcuPartialWritePlanner.preview(layout, original, modified)
        assertEquals(1L, result.changedBytes)
        assertEquals(1, result.affectedSectors.size)
        val sector = result.affectedSectors.single()
        assertEquals(0x1000L, sector.absoluteAddress)
        assertEquals(1, sector.changedBytes)
        assertArrayEquals(original.copyOfRange(0, 8), sector.originalBytes)
        assertArrayEquals(modified.copyOfRange(0, 8), sector.patchedBytes)
        assertFalse(result.hardwareWritePermitted)
    }

    @Test fun twoChangesInSameEraseBlockDoNotProduceTwoErases() {
        val modified = original.clone()
        modified[1] = 101
        modified[7] = 107
        val result = EcuPartialWritePlanner.preview(layout, original, modified)
        assertEquals(2L, result.changedBytes)
        assertEquals(1, result.affectedSectors.size)
    }

    @Test fun changesAcrossEraseBoundaryGenerateDistinctSectors() {
        val modified = original.clone()
        modified[7] = 107
        modified[8] = 108
        val result = EcuPartialWritePlanner.preview(layout, original, modified)
        assertEquals(2L, result.changedBytes)
        assertEquals(listOf(0x1000L, 0x1008L),
            result.affectedSectors.map { it.absoluteAddress })
    }

    @Test fun unchangedImagesRequireNoSectorOperation() {
        val result = EcuPartialWritePlanner.preview(layout, original, original.clone())
        assertEquals(0L, result.changedBytes)
        assertEquals(0, result.affectedSectors.size)
    }

    @Test fun protectedMemoryIsRejectedWithoutModifyingSourceImage() {
        val modified = original.clone()
        modified[4] = 101
        val guarded = layout.copy(protectedRanges = listOf(
            EcuPartialWritePlanner.Range(0x1004L, 0x1006L)
        ))
        assertThrows(IllegalArgumentException::class.java) {
            EcuPartialWritePlanner.preview(guarded, original, modified)
        }
        assertEquals(4.toByte(), original[4])
    }

    @Test fun rejectsIncompleteOrMismatchedSectorGeometryAndImageSizes() {
        val modified = original.clone()
        assertThrows(IllegalArgumentException::class.java) {
            EcuPartialWritePlanner.preview(layout, original, modified.copyOf(15))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EcuPartialWritePlanner.preview(layout.copy(eraseBlockBytes = 6), original, modified)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EcuPartialWritePlanner.preview(layout.copy(programBlockBytes = 3), original, modified)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EcuPartialWritePlanner.preview(layout.copy(regionStartAddress = 0x1001L), original, modified)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EcuPartialWritePlanner.preview(layout.copy(regionSize = 15L), original, modified)
        }
    }
}
