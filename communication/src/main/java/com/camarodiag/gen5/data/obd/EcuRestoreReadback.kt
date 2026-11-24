package com.camarodiag.gen5.data.obd

/**
 * Compares a saved ECU region against a separately obtained post-restore memory read.
 * This performs no transport, erase or programming operation.
 *
 * A match validates only the supplied bytes and region coordinates. A device
 * driver must independently prove ECU identity, provenance of the readback and
 * the manufacturer's programming checksums.
 */
object EcuRestoreReadback {
    data class Result(
        val matched: Boolean,
        val expectedAddress: Long,
        val expectedLength: Long,
        val observedLength: Int,
        val firstMismatchAddress: Long?
    )

    fun compare(
        saved: EcuRestoreImage.RegionImage,
        observedRegion: EcuRestoreImage.MemoryRegion,
        observedBytes: ByteArray
    ): Result {
        val geometry = saved.geometry
        if (observedRegion != geometry || observedBytes.size.toLong() != geometry.length) {
            return Result(
                matched = false,
                expectedAddress = geometry.startAddress,
                expectedLength = geometry.length,
                observedLength = observedBytes.size,
                firstMismatchAddress = null
            )
        }
        val original = saved.bytes()
        val mismatch = original.indices.firstOrNull { original[it] != observedBytes[it] }
        return Result(
            matched = mismatch == null,
            expectedAddress = geometry.startAddress,
            expectedLength = geometry.length,
            observedLength = observedBytes.size,
            firstMismatchAddress = mismatch?.let { geometry.startAddress + it.toLong() }
        )
    }

    /** All image regions must be present exactly once in the readback set. */
    fun comparePackage(
        saved: EcuRestorePackage.Contents,
        observations: List<Pair<EcuRestoreImage.MemoryRegion, ByteArray>>
    ): List<Result> {
        require(saved.regions.size == observations.size) {
            "Missing or additional ECU restore readback regions"
        }
        require(saved.regions.map { it.geometry }.distinct().size == saved.regions.size) {
            "Duplicate stored ECU restoration region"
        }
        val regions = observations.map { it.first }
        require(regions.distinct().size == regions.size) {
            "Duplicate ECU restore readback region"
        }
        require(saved.regions.map { it.geometry }.toSet() == regions.toSet()) {
            "ECU restore readback does not match the original memory map"
        }
        return saved.regions.map { image ->
            val observation = observations.single { it.first == image.geometry }
            compare(image, observation.first, observation.second)
        }
    }
}
