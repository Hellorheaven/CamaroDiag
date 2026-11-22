package com.camarodiag.gen5.data.obd

/**
 * Offline consistency check for a backup manifest and the bytes it describes.
 *
 * A passing result proves consistency of the supplied in-memory data only.
 * It does NOT prove that a hardware ECU was read, that the expected memory
 * map is exhaustive, or that this image can be flashed/restored.
 */
object EcuBackupIntegrity {
    enum class Problem {
        EMPTY_BACKUP,
        ENTRY_COUNT_MISMATCH,
        METADATA_MISMATCH,
        DIGEST_MISMATCH,
        INDEPENDENT_READ_MISMATCH,
        COVERAGE_MISMATCH,
        INVALID_COVERAGE,
        UNVERIFIABLE_HARDWARE_PROVENANCE,
        MISSING_INDEPENDENT_READ,
        DIVERGENT_INDEPENDENT_READ,
        ALIASED_INDEPENDENT_READ,
        EMPTY_CAPTURE,
        INVALID_VIN,
        PHYSICAL_ORIGIN_MISSING_VIN,
        PHYSICAL_CHANNEL_MISSING_VIN,
        INVALID_CAPTURE_TIMESTAMP
    }

    data class Result(val problems: Set<Problem>) {
        val consistent: Boolean get() = problems.isEmpty()
    }

    fun verify(
        manifest: EcuBackupManifest.Manifest,
        captures: List<EcuBackupManifest.Capture>,
        requiredRegions: Set<EcuBackupCoverage.Region>
    ): Result {
        val problems = mutableSetOf<Problem>()
        if (captures.isEmpty()) problems += Problem.EMPTY_BACKUP
        // An archive or manifest with no valid acquisition timestamp cannot
        // be treated as verified evidence of when these ECU bytes were captured.
        if (manifest.createdAtUtcMillis <= 0L) problems += Problem.INVALID_CAPTURE_TIMESTAMP
        if (manifest.vehicleVin != null && !manifest.vehicleVin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) {
            problems += Problem.INVALID_VIN
        }
        // A reported physical read must at least identify the ECU's vehicle.
        // This is still a declaration, not independent proof of provenance.
        if (captures.any { it.origin == EcuBackupManifest.Origin.REPORTED_HARDWARE_READ } &&
            manifest.vehicleVin == null
        ) {
            problems += Problem.PHYSICAL_ORIGIN_MISSING_VIN
        }
        if (manifest.channel != null && manifest.vehicleVin == null) {
            problems += Problem.PHYSICAL_CHANNEL_MISSING_VIN
        }
        if (manifest.hardwareProvenanceVerified) {
            // No hardware reader currently issues an independently authenticated attestation.
            problems += Problem.UNVERIFIABLE_HARDWARE_PROVENANCE
        }
        if (manifest.entries.size != captures.size) {
            problems += Problem.ENTRY_COUNT_MISMATCH
        }
        // Do not let an absent second read pass simply because the manifest also says false.
        captures.forEach { capture ->
            if (capture.firstRead.isEmpty()) problems += Problem.EMPTY_CAPTURE
            if (capture.independentRead == null) {
                problems += Problem.MISSING_INDEPENDENT_READ
            } else if (capture.firstRead === capture.independentRead) {
                // The same mutable object cannot represent two separate acquisitions.
                problems += Problem.ALIASED_INDEPENDENT_READ
            } else if (!capture.firstRead.contentEquals(capture.independentRead)) {
                problems += Problem.DIVERGENT_INDEPENDENT_READ
            }
        }
        manifest.entries.zip(captures).forEach { (entry, capture) ->
            if (entry.region != capture.region ||
                entry.startAddress != capture.startAddress ||
                entry.expectedBytes != capture.expectedBytes ||
                entry.bytesCaptured != capture.firstRead.size.toLong() ||
                entry.origin != capture.origin
            ) problems += Problem.METADATA_MISMATCH

            if (!entry.sha256.equals(EcuBenchExpertPlan.sha256Hex(capture.firstRead), ignoreCase = true)) {
                problems += Problem.DIGEST_MISMATCH
            }
            val identical = capture.independentRead != null &&
                capture.firstRead !== capture.independentRead &&
                EcuBenchExpertPlan.verifiedReadback(capture.firstRead, capture.independentRead)
            if (entry.readsIdentical != identical) {
                problems += Problem.INDEPENDENT_READ_MISMATCH
            }
        }
        val expected = EcuBackupManifest.inspect(
            controllerLabel = manifest.controllerLabel,
            createdAtUtcMillis = manifest.createdAtUtcMillis,
            captures = captures,
            expectedRegions = requiredRegions
        )
        if (expected.coverage.status == EcuBackupCoverage.Status.INCONSISTENT) {
            problems += Problem.INVALID_COVERAGE
        }
        if (manifest.coverage != expected.coverage ||
            manifest.entries.size != expected.entries.size
        ) problems += Problem.COVERAGE_MISMATCH
        return Result(problems)
    }
}
