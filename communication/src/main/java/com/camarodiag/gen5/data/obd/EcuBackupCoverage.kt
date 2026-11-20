package com.camarodiag.gen5.data.obd

/**
 * Offline evidence about ECU memory snapshots.
 * A valid manifest is NOT evidence that the bytes originated from a physical ECU.
 * The hardware reader must separately prove the acquisition provenance.
 */
object EcuBackupCoverage {
    enum class Region { CALIBRATION, PROGRAM_FLASH, DATA_FLASH, EXTERNAL_EEPROM, OTHER }
    enum class Status { COMPLETE, PARTIAL, INCONSISTENT }
    data class Segment(
        val region: Region,
        val startAddress: Long,
        val expectedBytes: Long,
        val capturedBytes: Long,
        val sha256: String?,
        val verifiedIndependentReadback: Boolean
    )
    data class Report(val status: Status, val capturedBytes: Long, val missingBytes: Long)

    /**
     * Checks a single non-overlapping memory segment against the expected coverage.
     * The caller must not substitute a checksum reported by an untrusted device for
     * hashing the actual captured bytes.
     */
    fun inspect(segments: List<Segment>): Report {
        if (segments.isEmpty()) return Report(Status.PARTIAL, 0, 0)
        var captured = 0L
        var missing = 0L
        var inconsistent = false
        var allVerified = true
        val ordered = segments.sortedWith(compareBy<Segment>({ it.region.ordinal }, { it.startAddress }))
        var previous: Segment? = null
        for (segment in ordered) {
            if (segment.startAddress < 0 || segment.expectedBytes <= 0 ||
                segment.capturedBytes < 0 || segment.capturedBytes > segment.expectedBytes ||
                segment.startAddress > Long.MAX_VALUE - segment.expectedBytes
            ) {
                inconsistent = true
                continue
            }
            val prev = previous
            if (prev != null && prev.region == segment.region &&
                segment.startAddress < prev.startAddress + prev.expectedBytes
            ) inconsistent = true
            previous = segment
            if (Long.MAX_VALUE - captured < segment.capturedBytes ||
                Long.MAX_VALUE - missing < segment.expectedBytes - segment.capturedBytes
            ) {
                inconsistent = true
                continue
            }
            captured += segment.capturedBytes
            missing += segment.expectedBytes - segment.capturedBytes
            if (!segment.verifiedIndependentReadback ||
                segment.sha256?.matches(Regex("[0-9a-fA-F]{64}")) != true
            ) allVerified = false
        }
        val status = when {
            inconsistent -> Status.INCONSISTENT
            missing == 0L && allVerified -> Status.COMPLETE
            else -> Status.PARTIAL
        }
        return Report(status, captured, missing)
    }
}
