package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertThrows
import org.junit.Test

class EcuWriteBlockVerifierTest {
    private val baseline = ByteArray(16) { it.toByte() }
    private val target = baseline.copyOf().also { it[3] = 99 }
    private val layout = EcuPartialWritePlanner.Layout(
        regionStartAddress = 0x4000L, regionSize = 16L,
        eraseBlockBytes = 8, programBlockBytes = 2,
        mapVerifiedForTargetEcu = true
    )
    private val region = EcuBackupCoverage.Region.CALIBRATION
    private val sector = EcuPartialWritePlanner.preview(layout, baseline, target)
        .affectedSectors.single()
    private val erase = EcuProgrammingDriverContract.RegionRequest(region, 0x4000L, 8L)

    @Test fun exactTargetSectorAndProgramUnitsPass() {
        EcuWriteBlockVerifier.requireExactSector(layout, region, baseline, target, sector, erase)
        for (offset in 0 until 8 step 2) {
            EcuWriteBlockVerifier.requireExactUnit(
                layout, region, target, sector,
                EcuProgrammingDriverContract.RegionRequest(region, 0x4000L + offset, 2L),
                offset, target.copyOfRange(offset, offset + 2))
        }
    }

    @Test fun wrongEcuAddressSizeOrRegionNeverMatchesEraseTarget() {
        for (wrong in listOf(
            erase.copy(address = 0x4008L),
            erase.copy(length = 16L),
            erase.copy(region = EcuBackupCoverage.Region.PROGRAM_FLASH)
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                EcuWriteBlockVerifier.requireExactSector(layout, region, baseline, target, sector, wrong)
            }
        }
    }

    @Test fun mismatchedSectorOrTargetIsRejectedBeforeAnyErase() {
        assertThrows(IllegalArgumentException::class.java) {
            EcuWriteBlockVerifier.requireExactSector(
                layout, region, baseline, baseline, sector, erase)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EcuWriteBlockVerifier.requireExactSector(
                layout, region, baseline, target, sector.copy(absoluteAddress = 0x4008L), erase)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EcuWriteBlockVerifier.requireExactSector(
                layout, region, baseline, target,
                sector.copy(patchedBytes = ByteArray(8)), erase)
        }
    }

    @Test fun wrongProgramPayloadAddressLengthOrOffsetCannotReachDriver() {
        val correct = EcuProgrammingDriverContract.RegionRequest(region, 0x4002L, 2L)
        val bytes = target.copyOfRange(2, 4)
        fun rejected(unit: EcuProgrammingDriverContract.RegionRequest, offset: Int,
                     payload: ByteArray) {
            assertThrows(IllegalArgumentException::class.java) {
                EcuWriteBlockVerifier.requireExactUnit(
                    layout, region, target, sector, unit, offset, payload)
            }
        }
        rejected(correct.copy(address = 0x4004L), 2, bytes)
        rejected(correct.copy(length = 1L), 2, bytes)
        rejected(correct.copy(region = EcuBackupCoverage.Region.PROGRAM_FLASH), 2, bytes)
        rejected(correct, 4, bytes)
        rejected(correct, 2, byteArrayOf(2, 3)) // wrong target byte at offset 3
        rejected(correct, 2, byteArrayOf(2))
        rejected(correct, 8, bytes)
    }
}
