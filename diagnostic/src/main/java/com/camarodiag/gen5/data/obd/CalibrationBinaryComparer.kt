package com.camarodiag.gen5.data.obd

data class CalibrationDiffRange(
    val startOffset: Long,
    val endOffsetInclusive: Long
) {
    val changedBytes: Long get() = endOffsetInclusive - startOffset + 1
}

data class CalibrationComparison(
    val referenceSize: Long,
    val modifiedSize: Long,
    val comparedBytes: Long,
    val changedBytes: Long,
    val appendedBytes: Long,
    val missingBytes: Long,
    val changedRanges: List<CalibrationDiffRange>,
    val omittedRangeCount: Int
) {
    val identical: Boolean get() = changedBytes == 0L && appendedBytes == 0L && missingBytes == 0L
}

/**
 * Compares two local binary images. It reports byte offsets only and never
 * modifies either file or claims that a changed image is safe to flash.
 */
object CalibrationBinaryComparer {

    private const val MAX_REPORTED_RANGES = 500

    fun compare(reference: ByteArray, modified: ByteArray): CalibrationComparison {
        val compared = minOf(reference.size, modified.size)
        var changedBytes = 0L
        val ranges = mutableListOf<CalibrationDiffRange>()
        var activeStart = -1

        for (index in 0 until compared) {
            val changed = reference[index] != modified[index]
            if (changed) {
                changedBytes++
                if (activeStart < 0) activeStart = index
            } else if (activeStart >= 0) {
                ranges += CalibrationDiffRange(activeStart.toLong(), index.toLong() - 1)
                activeStart = -1
            }
        }
        if (activeStart >= 0) {
            ranges += CalibrationDiffRange(activeStart.toLong(), compared.toLong() - 1)
        }

        val appended = (modified.size - compared).coerceAtLeast(0).toLong()
        val missing = (reference.size - compared).coerceAtLeast(0).toLong()
        return CalibrationComparison(
            referenceSize = reference.size.toLong(),
            modifiedSize = modified.size.toLong(),
            comparedBytes = compared.toLong(),
            changedBytes = changedBytes,
            appendedBytes = appended,
            missingBytes = missing,
            changedRanges = ranges.take(MAX_REPORTED_RANGES),
            omittedRangeCount = (ranges.size - MAX_REPORTED_RANGES).coerceAtLeast(0)
        )
    }
}
