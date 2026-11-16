package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationBinaryComparerTest {

    @Test
    fun identifiesDifferentByteRangesWithoutChangingInputArrays() {
        val reference = byteArrayOf(0, 1, 2, 3, 4, 5)
        val modified = byteArrayOf(0, 1, 9, 3, 8, 5)

        val result = CalibrationBinaryComparer.compare(reference, modified)

        assertEquals(6L, result.comparedBytes)
        assertEquals(2L, result.changedBytes)
        assertEquals(
            listOf(CalibrationDiffRange(2, 2), CalibrationDiffRange(4, 4)),
            result.changedRanges
        )
        assertFalse(result.identical)
        assertEquals(2, reference[2].toInt())
        assertEquals(9, modified[2].toInt())
    }

    @Test
    fun reportsAppendedAndMissingBytesWhenSizesDiffer() {
        val longer = CalibrationBinaryComparer.compare(
            byteArrayOf(1, 2, 3),
            byteArrayOf(1, 2, 3, 4, 5)
        )
        assertEquals(0L, longer.changedBytes)
        assertEquals(2L, longer.appendedBytes)
        assertEquals(0L, longer.missingBytes)
        assertFalse(longer.identical)

        val shorter = CalibrationBinaryComparer.compare(
            byteArrayOf(1, 2, 3, 4, 5),
            byteArrayOf(1, 2, 3)
        )
        assertEquals(2L, shorter.missingBytes)
        assertEquals(0L, shorter.appendedBytes)
    }

    @Test
    fun recognizesIdenticalImages() {
        val result = CalibrationBinaryComparer.compare(
            byteArrayOf(0x10, 0x20, 0x30),
            byteArrayOf(0x10, 0x20, 0x30)
        )
        assertTrue(result.identical)
        assertEquals(0L, result.changedBytes)
        assertTrue(result.changedRanges.isEmpty())
    }

    @Test
    fun limitsReportedRangesForVeryFragmentedFiles() {
        val reference = ByteArray(1500)
        val modified = ByteArray(1500) { index -> if (index % 2 == 0) 1 else 0 }

        val result = CalibrationBinaryComparer.compare(reference, modified)

        assertEquals(750L, result.changedBytes)
        assertEquals(500, result.changedRanges.size)
        assertEquals(250, result.omittedRangeCount)
    }
}
