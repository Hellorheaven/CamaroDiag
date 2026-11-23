package com.camarodiag.gen5.data.obd

import java.io.OutputStream
import java.security.MessageDigest

/**
 * Reconstructs exact offline region images from independently verified ECU captures.
 *
 * The complete address map is supplied by the caller from ECU-specific evidence.
 * A matching ZIP checksum is not proof of provenance, nor of a working flash driver.
 * This component sends NO commands to the ECU.
 */
object EcuRestoreImage {
    data class MemoryRegion(
        val region: EcuBackupCoverage.Region,
        val startAddress: Long,
        val length: Long
    ) {
        init {
            require(startAddress >= 0L && length > 0L &&
                startAddress <= Long.MAX_VALUE - length) { "Invalid ECU memory region" }
        }
    }

    class RegionImage internal constructor(
        val geometry: MemoryRegion,
        source: ByteArray
    ) {
        private val original = source.copyOf()
        val sha256: String = digest(original)
        val byteCount: Int get() = original.size

        /** A fresh copy; callers cannot mutate the verified internal image. */
        fun bytes(): ByteArray = original.copyOf()

        fun writeTo(output: OutputStream) {
            output.write(original)
        }
    }

    /**
     * Requires precise contiguous coverage of every requested physical region.
     * Partial archives remain valuable backups but cannot be labelled as a
     * complete region image. Geometry is NOT inferred from archive labels.
     */
    fun assemble(
        archive: EcuBackupArchive.Imported,
        knownMemoryMap: List<MemoryRegion>
    ): List<RegionImage> {
        require(archive.verification.consistent) { "ECU backup archive integrity is not verified" }
        require(knownMemoryMap.isNotEmpty()) { "Missing known ECU memory map" }
        require(knownMemoryMap.distinct().size == knownMemoryMap.size) {
            "Duplicate ECU memory region"
        }
        val sortedGeometry = knownMemoryMap.sortedWith(
            compareBy<MemoryRegion>({ it.region.ordinal }, { it.startAddress })
        )
        sortedGeometry.zipWithNext().forEach { (left, right) ->
            require(left.region != right.region ||
                left.startAddress + left.length <= right.startAddress) {
                "Overlapping ECU memory regions"
            }
        }
        // A restore package must not silently discard captures that belong to
        // another address/region. Every source byte needs an explicit mapping.
        val capturedKeys = archive.captures.map { it.region to it.startAddress }
        require(capturedKeys.distinct().size == capturedKeys.size) {
            "Duplicate physical ECU capture address"
        }
        require(archive.captures.all { capture ->
            sortedGeometry.any { geometry ->
                capture.region == geometry.region &&
                    capture.startAddress >= geometry.startAddress &&
                    capture.expectedBytes > 0L &&
                    capture.startAddress <= Long.MAX_VALUE - capture.expectedBytes &&
                    capture.startAddress + capture.expectedBytes <=
                        geometry.startAddress + geometry.length
            }
        }) { "A captured ECU memory segment has no matching restore image region" }
        return sortedGeometry.map { geometry ->
            require(geometry.length <= Int.MAX_VALUE.toLong()) {
                "Region image exceeds the in-memory assembler capacity"
            }
            val relevant = archive.captures
                .filter { it.region == geometry.region }
                .sortedBy { it.startAddress }
            require(relevant.isNotEmpty()) { "No ECU capture for requested memory region" }
            // Archive entries are bounded to 64 MiB; reject an oversized supplied map
            // before attempting an allocation or copying any captured bytes.
            require(geometry.length <= 64L * 1024 * 1024) {
                "ECU image region exceeds the supported archive segment budget"
            }
            val result = ByteArray(geometry.length.toInt())
            var position = geometry.startAddress
            relevant.forEach { capture ->
                require(capture.startAddress == position &&
                    capture.expectedBytes == capture.firstRead.size.toLong() &&
                    capture.independentRead != null &&
                    capture.firstRead !== capture.independentRead &&
                    capture.firstRead.contentEquals(capture.independentRead)) {
                    "Gap, overlap, partial capture or inconsistent ECU reads"
                }
                require(capture.startAddress <= Long.MAX_VALUE - capture.expectedBytes &&
                    capture.startAddress + capture.expectedBytes <=
                    geometry.startAddress + geometry.length) {
                    "Capture outside the physical memory region"
                }
                capture.firstRead.copyInto(result, (position - geometry.startAddress).toInt())
                position += capture.expectedBytes
            }
            require(position == geometry.startAddress + geometry.length) {
                "Requested ECU memory region is not fully captured"
            }
            RegionImage(geometry, result)
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
