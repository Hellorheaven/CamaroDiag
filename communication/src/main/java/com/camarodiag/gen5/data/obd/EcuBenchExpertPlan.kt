package com.camarodiag.gen5.data.obd

import java.security.MessageDigest

/**
 * Preparation for direct ECU memory work on a removed controller.
 *
 * BOOT/BAM and Nexus/JTAG access to an MCU with internal flash is not equivalent
 * to removing an external EEPROM. No transport driver or programming routine
 * is registered in CamaroDiag yet; these checks must never enable a write.
 */
object EcuBenchExpertPlan {
    enum class AccessMethod {
        OBD, BENCH, BOOT_BAM, NEXUS_JTAG, EXTERNAL_CHIP_OFF,
        CAN_DIRECT, BDM, EXTERNAL_EEPROM_ISP, SWD;

        fun permitsRegion(region: MemoryRegion): Boolean =
            when (this) {
                EXTERNAL_CHIP_OFF, EXTERNAL_EEPROM_ISP -> region == MemoryRegion.EXTERNAL_EEPROM
                OBD, BENCH, BOOT_BAM, NEXUS_JTAG, CAN_DIRECT, BDM, SWD ->
                    region != MemoryRegion.EXTERNAL_EEPROM
            }

        /** Candidate driver interfaces, not advertised live capabilities. */
        val candidateTransports: Set<EcuProgrammingDriverContract.Transport>
            get() = when (this) {
                OBD -> setOf(EcuProgrammingDriverContract.Transport.ELM_DIAGNOSTICS,
                    EcuProgrammingDriverContract.Transport.J2534)
                BENCH -> setOf(EcuProgrammingDriverContract.Transport.BENCH,
                    EcuProgrammingDriverContract.Transport.J2534)
                BOOT_BAM -> setOf(EcuProgrammingDriverContract.Transport.BOOT_BAM)
                NEXUS_JTAG -> setOf(EcuProgrammingDriverContract.Transport.NEXUS_JTAG)
                EXTERNAL_CHIP_OFF -> setOf(EcuProgrammingDriverContract.Transport.CHIP_OFF)
                CAN_DIRECT -> setOf(EcuProgrammingDriverContract.Transport.CAN_DIRECT,
                    EcuProgrammingDriverContract.Transport.J2534)
                BDM -> setOf(EcuProgrammingDriverContract.Transport.BDM)
                SWD -> setOf(EcuProgrammingDriverContract.Transport.SWD)
                EXTERNAL_EEPROM_ISP -> setOf(EcuProgrammingDriverContract.Transport.EEPROM_ISP)
            }
    }
    enum class MemoryRegion { PFLASH, DFLASH, EXTERNAL_EEPROM }
    enum class Blocker {
        CONTROLLER_NOT_IDENTIFIED,
        CHIP_NOT_IDENTIFIED,
        ACCESS_REGION_INCOMPATIBLE,
        PROGRAMMER_NOT_IDENTIFIED,
        IMAGE_MISSING,
        IMAGE_NOT_RAW_BINARY,
        IMAGE_INTEGRITY_MISSING,
        ORIGINAL_READBACK_NOT_VERIFIED,
        RECOVERY_PATH_NOT_VERIFIED,
        MEMORY_MAP_NOT_VERIFIED,
        HARDWARE_PROGRAMMING_DRIVER_UNAVAILABLE
    }

    data class CandidateImage(
        val fileName: String?,
        val format: String?,
        val sizeBytes: Long?,
        val sha256: String?
    )

    data class Target(
        val controllerFamily: String?,
        val chipMarking: String?,
        val programmerName: String?,
        val accessMethod: AccessMethod,
        val region: MemoryRegion
    )

    data class Evidence(
        val originalReadbackVerified: Boolean = false,
        val recoveryPathVerified: Boolean = false,
        val memoryMapVerified: Boolean = false
    )

    /**
     * A filename, SHA-256 or user-entered chip name cannot confirm that an image
     * belongs to this ECU, contains a full flash or has a valid ECU checksum.
     * Even all declared evidence cannot authorize a write without a validated driver.
     */
    fun blockers(target: Target, image: CandidateImage?, evidence: Evidence = Evidence()): List<Blocker> {
        val missing = mutableListOf<Blocker>()
        val family = target.controllerFamily?.trim().orEmpty()
        if (family.isBlank() || family.equals("unknown", ignoreCase = true))
            missing += Blocker.CONTROLLER_NOT_IDENTIFIED
        if (target.chipMarking.isNullOrBlank() ||
            target.chipMarking.trim().equals("unknown", ignoreCase = true)
        ) missing += Blocker.CHIP_NOT_IDENTIFIED
        if (!target.accessMethod.permitsRegion(target.region))
            missing += Blocker.ACCESS_REGION_INCOMPATIBLE
        if (target.programmerName.isNullOrBlank()) missing += Blocker.PROGRAMMER_NOT_IDENTIFIED
        if (image?.fileName.isNullOrBlank() || (image?.sizeBytes ?: 0L) <= 0L) {
            missing += Blocker.IMAGE_MISSING
        } else {
            if (image?.format != "RAW_BINARY") missing += Blocker.IMAGE_NOT_RAW_BINARY
            if (!isSha256(image?.sha256)) missing += Blocker.IMAGE_INTEGRITY_MISSING
        }
        if (!evidence.originalReadbackVerified) missing += Blocker.ORIGINAL_READBACK_NOT_VERIFIED
        if (!evidence.recoveryPathVerified) missing += Blocker.RECOVERY_PATH_NOT_VERIFIED
        if (!evidence.memoryMapVerified) missing += Blocker.MEMORY_MAP_NOT_VERIFIED
        // No family-specific hard stop: permit any actual, registered driver
        // that supports this ECU family and selected hardware transport.
        // The current registry is empty, so availability is not fabricated.
        val driverAvailable = target.accessMethod.candidateTransports.any { transport ->
            EcuProgrammingDriverContract.hasValidatedWriteDriver(family, transport)
        }
        if (!driverAvailable) missing += Blocker.HARDWARE_PROGRAMMING_DRIVER_UNAVAILABLE
        return missing
    }

    fun isProgrammingEnabled(target: Target, image: CandidateImage?, evidence: Evidence = Evidence()): Boolean =
        blockers(target, image, evidence).isEmpty()

    /**
     * Offline file integrity check using actual bytes, not just user-supplied
     * metadata. A matching checksum does not prove that these bytes were
     * read from this specific ECU or authorize flashing them.
     */
    fun verifyCandidateImage(image: CandidateImage?, rawBytes: ByteArray?): Boolean {
        if (image == null || rawBytes == null || rawBytes.isEmpty()) return false
        if (image.fileName.isNullOrBlank() || image.format != "RAW_BINARY") return false
        if (image.sizeBytes != rawBytes.size.toLong() || !isSha256(image.sha256)) return false
        return sha256Hex(rawBytes).equals(image.sha256, ignoreCase = true)
    }

    /** Compare two actual byte arrays, not merely two filenames or reported hashes. */
    fun verifiedReadback(sourceBytes: ByteArray, readbackBytes: ByteArray): Boolean =
        sourceBytes !== readbackBytes && sourceBytes.isNotEmpty() && sourceBytes.contentEquals(readbackBytes)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun isSha256(value: String?): Boolean =
        value?.matches(Regex("[0-9a-fA-F]{64}")) == true
}
