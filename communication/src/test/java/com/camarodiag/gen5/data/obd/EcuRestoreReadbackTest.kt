package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuRestoreReadbackTest {
    private val region = EcuRestoreImage.MemoryRegion(
        EcuBackupCoverage.Region.CALIBRATION, 0x2000, 4
    )
    private fun image() = EcuRestoreImage.RegionImage(region, byteArrayOf(1, 2, 3, 4))

    @Test fun exactIndependentBytesMatchAndWrongDataIdentifiesAbsoluteAddress() {
        val original = image()
        val good = EcuRestoreReadback.compare(original, region, byteArrayOf(1, 2, 3, 4))
        assertTrue(good.matched)
        assertEquals(null, good.firstMismatchAddress)

        val bad = EcuRestoreReadback.compare(original, region, byteArrayOf(1, 9, 3, 4))
        assertFalse(bad.matched)
        assertEquals(0x2001L, bad.firstMismatchAddress)
    }

    @Test fun wrongPhysicalAddressOrLengthCanNeverBeReportedAsMatch() {
        val image = image()
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertFalse(EcuRestoreReadback.compare(
            image, region.copy(startAddress = 0x3000), bytes
        ).matched)
        assertFalse(EcuRestoreReadback.compare(
            image, region, byteArrayOf(1, 2, 3)
        ).matched)
    }

    @Test fun packageRequiresEveryRegionExactlyOnce() {
        val other = EcuRestoreImage.MemoryRegion(
            EcuBackupCoverage.Region.EXTERNAL_EEPROM, 0x3000, 2
        )
        val packageBytes = EcuRestorePackage.Contents(
            EcuRestoreImageFile.Identity("1G1FA1RXXR0123456", "ECM", "FW"),
            listOf(image(), EcuRestoreImage.RegionImage(other, byteArrayOf(8, 9)))
        )
        val observations = listOf(
            other to byteArrayOf(8, 9),
            region to byteArrayOf(1, 2, 3, 4)
        )
        assertTrue(EcuRestoreReadback.comparePackage(
            packageBytes, observations
        ).all { it.matched })
        assertTrue(runCatching {
            EcuRestoreReadback.comparePackage(packageBytes, observations.take(1))
        }.isFailure)
        assertTrue(runCatching {
            EcuRestoreReadback.comparePackage(packageBytes, listOf(
                region to byteArrayOf(1, 2, 3, 4),
                region to byteArrayOf(1, 2, 3, 4)
            ))
        }.isFailure)
    }
}
