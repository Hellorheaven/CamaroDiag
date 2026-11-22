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

    /** Operator-selected physical J2534 route; never by itself a hardware attestation. */
    data class Channel(
        val requestCanId: Int,
        val responseCanId: Int,
        val protocolNumber: Int,
        val diagnosticSession: Int
    ) {
        init {
            require(protocolNumber in setOf(33, 34, 35, 36, 63, 64)) {
                "Invalid ECU backup J2534 protocol."
            }
            val maximumCanId = if (protocolNumber in setOf(34, 36, 64)) 0x1FFFFFFF else 0x7FF
            require(requestCanId in 0..maximumCanId &&
                responseCanId in 0..maximumCanId && requestCanId != responseCanId) {
                "Backup CAN request and response IDs do not match the protocol."
            }
            require(diagnosticSession == 1 || diagnosticSession == 3) {
                "Invalid ECU backup diagnostic session."
            }
        }
    }

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
        val vehicleVin: String? = null,
        /** Only a declared read route, not independently authenticated provenance. */
        val channel: Channel? = null
    )

    fun inspect(
        controllerLabel: String?,
        createdAtUtcMillis: Long,
        captures: List<Capture>,
        expectedRegions: Set<EcuBackupCoverage.Region>,
        vehicleVin: String? = null,
        channel: Channel? = null
    ): Manifest {
        require(vehicleVin == null || vehicleVin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) {
            "Invalid VIN in ECU backup manifest"
        }
        require(channel == null || vehicleVin != null) {
            "A physical channel cannot be recorded without a VIN."
        }
        val entries = captures.map { capture ->
            Entry(
                region = capture.region,
                startAddress = capture.startAddress,
                expectedBytes = capture.expectedBytes,
                bytesCaptured = capture.firstRead.size.toLong(),
                sha256 = EcuBenchExpertPlan.sha256Hex(capture.firstRead),
                readsIdentical = capture.independentRead != null &&
                    capture.firstRead !== capture.independentRead &&
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
            vehicleVin = vehicleVin,
            channel = channel
        )
    }
}
