package com.camarodiag.gen5.data.obd

/**
 * Offline preview of changed bytes and affected flash erase sectors.
 *
 * It never writes to a controller. Sector geometry must be supplied from an
 * independently verified, ECU-specific memory map before ANY physical use.
 * A computed SHA-256 is not an OEM calibration checksum.
 */
object EcuPartialWritePlanner {
    data class Range(val start: Long, val endExclusive: Long)

    data class Layout(
        val regionStartAddress: Long,
        val regionSize: Long,
        val eraseBlockBytes: Int,
        val programBlockBytes: Int,
        val protectedRanges: List<Range> = emptyList(),
        val mapVerifiedForTargetEcu: Boolean = false
    )

    data class Sector(
        val absoluteAddress: Long,
        val changedBytes: Int,
        val originalSha256: String,
        val updatedSha256: String,
        /** Entire original sector, including neighboring bytes that must be preserved. */
        val originalBytes: ByteArray,
        /** Entire sector as it would appear after applying only the compared changes. */
        val patchedBytes: ByteArray
    )

    data class Preview(
        val changedBytes: Long,
        val affectedSectors: List<Sector>,
        val memoryMapVerified: Boolean,
        val hardwareWritePermitted: Boolean = false
    )

    fun preview(layout: Layout, original: ByteArray, modified: ByteArray): Preview {
        require(original.isNotEmpty() && original.size == modified.size) {
            "Offline sector planning requires equal-size, nonempty original and modified images."
        }
        require(layout.regionSize == original.size.toLong()) {
            "Both files must match the declared memory region exactly."
        }
        require(layout.regionStartAddress >= 0 &&
            layout.regionStartAddress <= Long.MAX_VALUE - layout.regionSize
        ) { "Invalid or overflowing ECU memory range." }
        require(layout.eraseBlockBytes > 0 && layout.programBlockBytes > 0 &&
            layout.eraseBlockBytes % layout.programBlockBytes == 0
        ) { "Programming units must divide a positive erase-sector size." }
        require(layout.regionStartAddress % layout.eraseBlockBytes == 0L &&
            layout.regionSize % layout.eraseBlockBytes == 0L
        ) { "Region boundaries must align with complete erase sectors." }
        val protected = layout.protectedRanges
        require(protected.all {
            it.start >= layout.regionStartAddress &&
                it.endExclusive <= layout.regionStartAddress + layout.regionSize &&
                it.endExclusive > it.start
        }) { "Invalid protected-region bounds." }

        val changedBySector = sortedMapOf<Int, Int>()
        for (offset in original.indices) {
            if (original[offset] == modified[offset]) continue
            val address = layout.regionStartAddress + offset.toLong()
            require(protected.none { address >= it.start && address < it.endExclusive }) {
                "Patch would touch protected ECU memory."
            }
            val sector = offset / layout.eraseBlockBytes
            changedBySector[sector] = (changedBySector[sector] ?: 0) + 1
        }
        val sectors = changedBySector.map { (index, count) ->
            val start = index * layout.eraseBlockBytes
            val finish = start + layout.eraseBlockBytes
            val before = original.copyOfRange(start, finish)
            val after = modified.copyOfRange(start, finish)
            Sector(
                absoluteAddress = layout.regionStartAddress + start.toLong(),
                changedBytes = count,
                originalSha256 = EcuBenchExpertPlan.sha256Hex(before),
                updatedSha256 = EcuBenchExpertPlan.sha256Hex(after),
                originalBytes = before,
                patchedBytes = after
            )
        }
        return Preview(
            changedBytes = changedBySector.values.sumOf(Int::toLong),
            affectedSectors = sectors,
            memoryMapVerified = layout.mapVerifiedForTargetEcu
        )
    }
}
