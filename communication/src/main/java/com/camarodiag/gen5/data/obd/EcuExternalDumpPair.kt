package com.camarodiag.gen5.data.obd

import java.io.InputStream
import java.io.OutputStream

/**
 * Import two externally acquired raw dumps from a bench/board programmer.
 * Their equality proves file consistency, not independent physical acquisition.
 * No programming or hardware access is performed here.
 */
object EcuExternalDumpPair {
    // The Android offline ZIP verifier accepts 32 MiB uncompressed in total.
    // Reserve room for its manifest and both complete copies.
    const val MAX_DUMP_BYTES = 15 * 1024 * 1024

    fun regionFor(region: EcuBenchExpertPlan.MemoryRegion): EcuBackupCoverage.Region =
        when (region) {
            EcuBenchExpertPlan.MemoryRegion.PFLASH -> EcuBackupCoverage.Region.PROGRAM_FLASH
            EcuBenchExpertPlan.MemoryRegion.DFLASH -> EcuBackupCoverage.Region.DATA_FLASH
            EcuBenchExpertPlan.MemoryRegion.EXTERNAL_EEPROM -> EcuBackupCoverage.Region.EXTERNAL_EEPROM
        }

    private fun readExact(input: InputStream, expected: Int): ByteArray {
        val bytes = ByteArray(expected)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            require(count >= 0) { "External dump is truncated" }
            if (count == 0) {
                val value = input.read()
                require(value >= 0) { "External dump is truncated" }
                bytes[offset++] = value.toByte()
            } else offset += count
        }
        require(input.read() == -1) { "External dump exceeds the declared size" }
        return bytes
    }

    fun export(
        output: OutputStream,
        first: InputStream,
        second: InputStream,
        method: EcuBenchExpertPlan.AccessMethod,
        region: EcuBenchExpertPlan.MemoryRegion,
        startAddress: Long,
        expectedBytes: Int,
        controllerLabel: String? = null,
        vehicleVin: String? = null,
        createdAtUtcMillis: Long = System.currentTimeMillis()
    ) {
        require(first !== second) { "Select two distinct dump inputs" }
        require(method.permitsRegion(region)) { "Access method and memory region do not match" }
        require(expectedBytes in 1..MAX_DUMP_BYTES && startAddress >= 0 &&
            startAddress <= Long.MAX_VALUE - expectedBytes.toLong()) { "Invalid external dump size or address" }
        val primary = readExact(first, expectedBytes)
        val repeated = readExact(second, expectedBytes)
        require(EcuBenchExpertPlan.verifiedReadback(primary, repeated)) { "External dumps differ" }
        EcuBackupArchive.export(
            output = output,
            captures = listOf(EcuBackupManifest.Capture(
                regionFor(region), startAddress, expectedBytes.toLong(), primary, repeated,
                EcuBackupManifest.Origin.IMPORTED_FILE
            )),
            // No exact ECU memory map was established by importing two files.
            requiredRegions = emptySet(),
            controllerLabel = controllerLabel,
            vehicleVin = vehicleVin,
            createdAtUtcMillis = createdAtUtcMillis
        )
    }
}
