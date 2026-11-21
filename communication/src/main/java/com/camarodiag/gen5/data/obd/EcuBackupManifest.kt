package com.camarodiag.gen5.data.obd

/**
 * Builds a non-destructive, offline inventory from actual ECU image bytes.
 *
 * This manifest is not a hardware acquisition certificate or a valid cloning
 * image. A real programmer must separately verify controller provenance,
 * memory mapping, protected areas and the device's own checksums.
 */
object EcuBackupManifest {
    enum class Origin { IMPORTED_FILE, SIMULATION, REPORTED_HARDWARE_READ }

    data class Capture(
        val region: EcuBackupCoverage.Region,
        val startAddress: Long,
        val expectedBytes: Long,
        val firstRead: ByteArray,
        val independentRead: ByteArray?,
        val origin: Origin
    )

    data class Entry(
        val region: EcuBackupCoverage.Region,
        val startAddress: Long,
        val expectedBytes: Long,
        val bytesCaptured: Long,
        val sha256: String,
        val readsIdentical: Boolean,
        val origin: Origin
    )

    data class Manifest(
        val controllerLabel: String?,
        val createdAtUtcMillis: Long,
        val entries: List<Entry>,
        val coverage: EcuBackupCoverage.Report,
        /** Always false until a separately validated hardware reader can attest provenance. */
        val hardwareProvenanceVerified: Boolean = false,
        /** VIN reported during acquisition, when available. It is not an attestation. */
        val vehicleVin: String? = null
    )

    fun inspect(
        controllerLabel: String?,
        createdAtUtcMillis: Long,
        captures: List<Capture>,
        expectedRegions: Set<EcuBackupCoverage.Region>,
        vehicleVin: String? = null
    ): Manifest {
        require(vehicleVin == null || vehicleVin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) {
            "Invalid VIN in ECU backup manifest"
        }
        val entries = captures.map { capture ->
            Entry(
                region = capture.region,
                startAddress = capture.startAddress,
                expectedBytes = capture.expectedBytes,
                bytesCaptured = capture.firstRead.size.toLong(),
                sha256 = EcuBenchExpertPlan.sha256Hex(capture.firstRead),
                readsIdentical = capture.independentRead != null &&
                    EcuBenchExpertPlan.verifiedReadback(capture.firstRead, capture.independentRead),
                origin = capture.origin
            )
        }
        val evidence = entries.map { entry ->
            EcuBackupCoverage.Segment(
                region = entry.region,
                startAddress = entry.startAddress,
                expectedBytes = entry.expectedBytes,
                capturedBytes = entry.bytesCaptured,
                sha256 = entry.sha256,
                verifiedIndependentReadback = entry.readsIdentical
            )
        }
        // An unspecified target map can never be advertised as a complete backup.
        val coverage = EcuBackupCoverage.inspect(evidence, expectedRegions)
        val conservativeCoverage = if (expectedRegions.isEmpty() &&
            coverage.status == EcuBackupCoverage.Status.COMPLETE
        ) coverage.copy(status = EcuBackupCoverage.Status.PARTIAL) else coverage
        return Manifest(
            controllerLabel = controllerLabel?.takeIf(String::isNotBlank),
            createdAtUtcMillis = createdAtUtcMillis,
            entries = entries,
            coverage = conservativeCoverage,
            vehicleVin = vehicleVin
        )
    }
}
