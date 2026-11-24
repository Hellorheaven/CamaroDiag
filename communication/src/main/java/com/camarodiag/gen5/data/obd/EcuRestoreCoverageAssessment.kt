package com.camarodiag.gen5.data.obd

/**
 * Offline coverage assessment against a caller-supplied ECU memory map.
 *
 * "COVERS_SUPPLIED_MAP" is NOT a whole-controller certification. Missing OEM
 * regions, protected areas and hardware provenance cannot be inferred here.
 * No transport or ECU write access is involved.
 */
object EcuRestoreCoverageAssessment {
    enum class Status { UNKNOWN_MAP, INVALID, PARTIAL, COVERS_SUPPLIED_MAP }

    data class Gap(
        val region: EcuBackupCoverage.Region,
        val address: Long,
        val length: Long
    )

    data class Report(
        val status: Status,
        val gaps: List<Gap>,
        val expectedBytes: Long,
        val capturedBytes: Long,
        val reason: String? = null
    )

    fun inspect(
        archive: EcuBackupArchive.Imported,
        map: List<EcuRestoreImage.MemoryRegion>
    ): Report {
        fun invalid(message: String) = Report(Status.INVALID, emptyList(), 0, 0, message)
        if (map.isEmpty()) {
            return Report(Status.UNKNOWN_MAP, emptyList(), 0, 0,
                "An ECU-specific physical memory map has not been supplied")
        }
        if (!archive.verification.consistent || !EcuBackupIntegrity.verify(
                archive.manifest, archive.captures, archive.requiredRegions
            ).consistent
        ) return invalid("ECU capture integrity is not verified")
        if (map.size > 128 || map.any { it.length > 64L * 1024 * 1024 } ||
            map.fold(0L) { sum, r ->
                if (sum > 256L * 1024 * 1024 - r.length) Long.MAX_VALUE
                else sum + r.length
            } > 256L * 1024 * 1024) {
            return invalid("ECU restoration memory map exceeds supported archive bounds")
        }
        val ordered = map.sortedWith(
            compareBy<EcuRestoreImage.MemoryRegion>({ it.region.ordinal }, { it.startAddress })
        )
        if (ordered.zipWithNext().any { (a, b) ->
                a.region == b.region && a.startAddress + a.length > b.startAddress
            }
        ) return invalid("The supplied ECU memory map overlaps")
        val expected = ordered.sumOf { it.length }
        val captured = archive.captures.sumOf { it.firstRead.size.toLong() }
        if (archive.captures.any { capture ->
                capture.expectedBytes != capture.firstRead.size.toLong() ||
                    capture.independentRead == null ||
                    !capture.firstRead.contentEquals(capture.independentRead) ||
                    ordered.none { r ->
                        capture.region == r.region &&
                            capture.startAddress >= r.startAddress &&
                            capture.startAddress <= Long.MAX_VALUE - capture.expectedBytes &&
                            capture.startAddress + capture.expectedBytes <= r.startAddress + r.length
                    }
            }
        ) return invalid("Invalid or unmapped ECU acquisition")
        val gaps = mutableListOf<Gap>()
        ordered.forEach { region ->
            var cursor = region.startAddress
            val captures = archive.captures.filter {
                it.region == region.region &&
                    it.startAddress >= region.startAddress &&
                    it.startAddress < region.startAddress + region.length
            }.sortedBy { it.startAddress }
            captures.forEach { capture ->
                if (capture.startAddress < cursor) return invalid("Overlapping ECU captures")
                if (capture.startAddress > cursor) {
                    gaps += Gap(region.region, cursor, capture.startAddress - cursor)
                }
                cursor = capture.startAddress + capture.expectedBytes
            }
            val end = region.startAddress + region.length
            if (cursor < end) gaps += Gap(region.region, cursor, end - cursor)
        }
        return Report(
            if (gaps.isEmpty()) Status.COVERS_SUPPLIED_MAP else Status.PARTIAL,
            gaps, expected, captured
        )
    }
}
