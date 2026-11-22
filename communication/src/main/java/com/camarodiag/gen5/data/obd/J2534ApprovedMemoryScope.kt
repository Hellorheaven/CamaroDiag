package com.camarodiag.gen5.data.obd

/**
 * Exact allowlist declared by the authenticated Windows J2534 bridge.
 * The bridge separately verifies the physical ECU VIN and authorizes 0x23.
 * These descriptive fields are not an OEM firmware/restore certificate.
 */
internal data class J2534ApprovedMemoryScope(
    val vin: String,
    val region: EcuBackupCoverage.Region,
    val startAddress: Long,
    val lengthBytes: Long,
    val maxReadBytes: Int,
    val diagnosticSession: Int
) {
    init {
        require(vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) { "Malformed approved ECU VIN." }
        require(startAddress in 0L..0xFFFF_FFFFL &&
            lengthBytes in 1L..(16L * 1024 * 1024) &&
            startAddress + lengthBytes <= 0x1_0000_0000L
        ) { "Invalid approved ECU memory range." }
        require(maxReadBytes in 1..256 && diagnosticSession in setOf(1, 3)) {
            "Invalid approved ECU block size or session."
        }
    }

    fun permits(request: J2534LiveMemoryBackup.Request): Boolean =
        request.expectedVin == vin &&
            request.region == region &&
            request.diagnosticSession == diagnosticSession &&
            request.chunkBytes in 1..maxReadBytes &&
            request.lengthBytes in 1..(4 * 1024 * 1024) &&
            request.address >= startAddress &&
            request.address <= 0xFFFF_FFFFL &&
            request.address + request.lengthBytes.toLong() <= startAddress + lengthBytes
}
