package com.camarodiag.gen5.data.obd

/**
 * Offline coverage report against an explicitly supplied memory map. A map
 * describing only one segment cannot prove a whole ECU image, even when all
 * bytes within that supplied map have two matching reads.
 *
 * No hardware provenance is authenticated by this class and no ECU command
 * or write is performed.
 */
object EcuRestoreCoveragePreview {
    data class MissingSpan(
        val region: EcuBackupCoverage.Region,
        val startAddress: Long,
        val length: Long
    )

    data class Report(
        val requestedBytes: Long,
        val doubleReadBytes: Long,
        val missing: List<MissingSpan>,
        val unaccountedCaptureCount: Int
    ) {
        /** Complete only within the CALLER'S map, never proof of full ECU coverage. */
        val coversSuppliedMap: Boolean get() = missing.isEmpty() &&
            unaccountedCaptureCount == 0 && requestedBytes > 0L &&
            doubleReadBytes == requestedBytes
    }

    fun inspect(
        archive: EcuBackupArchive.Imported,
        suppliedMap: List<EcuRestoreImage.MemoryRegion>
    ): Report {
        require(archive.verification.consistent) { "ECU archive is inconsistent" }
        require(suppliedMap.size in 1..128) { "Missing or excessive ECU memory regions" }
        val ordered = suppliedMap.sortedWith(compareBy(
            { it.region.ordinal }, { it.startAddress }
        ))
        ordered.zipWithNext().forEach { (a, b) ->
            require(a.region != b.region ||
                a.startAddress + a.length <= b.startAddress) {
                "Overlapping supplied ECU memory map"
            }
        }
        var requested = 0L
        val maxBudget = 256L * 1024 * 1024
        for (region in ordered) {
            require(region.length <= 64L * 1024 * 1024 &&
                region.length <= maxBudget - requested) {
                "Memory map exceeds the supported restore package budget"
            }
            requested += region.length
        }

        val validCaptures = archive.captures.map { capture ->
            require(capture.startAddress >= 0L && capture.expectedBytes > 0L &&
                capture.startAddress <= Long.MAX_VALUE - capture.expectedBytes) {
                "Invalid ECU capture address span"
            }
            capture
        }

        val unmapped = validCaptures.count { capture ->
            val last = capture.startAddress + capture.expectedBytes
            ordered.none { region ->
                region.region == capture.region &&
                    capture.startAddress >= region.startAddress &&
                    last <= region.startAddress + region.length
            }
        }
        val missing = mutableListOf<MissingSpan>()
        var verifiedBytes = 0L
        for (region in ordered) {
            val end = region.startAddress + region.length
            var cursor = region.startAddress
            val captures = validCaptures.filter {
                it.region == region.region &&
                    it.startAddress < end &&
                    it.startAddress + it.expectedBytes > region.startAddress
            }.sortedBy { it.startAddress }
            for (capture in captures) {
                val first = maxOf(region.startAddress, capture.startAddress)
                val last = minOf(end, capture.startAddress + capture.expectedBytes)
                require(first >= cursor) { "Overlapping ECU capture segments" }
                if (first > cursor) missing += MissingSpan(region.region, cursor, first - cursor)
                val independentlyVerified =
                    capture.origin == EcuBackupManifest.Origin.REPORTED_HARDWARE_READ &&
                    capture.firstRead.size.toLong() == capture.expectedBytes &&
                    capture.independentRead != null &&
                    capture.firstRead !== capture.independentRead &&
                    capture.firstRead.contentEquals(capture.independentRead)
                if (independentlyVerified) verifiedBytes += last - first
                else missing += MissingSpan(region.region, first, last - first)
                cursor = last
            }
            if (cursor < end) missing += MissingSpan(region.region, cursor, end - cursor)
        }
        return Report(requested, verifiedBytes, missing, unmapped)
    }
}
