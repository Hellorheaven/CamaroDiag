package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuBenchExpertPlanTest {
    private val target = EcuBenchExpertPlan.Target(
        controllerFamily = "E39A",
        chipMarking = "MPC5566",
        programmerName = "Verified bench adapter",
        accessMethod = EcuBenchExpertPlan.AccessMethod.NEXUS_JTAG,
        region = EcuBenchExpertPlan.MemoryRegion.PFLASH
    )

    private val image = EcuBenchExpertPlan.CandidateImage(
        fileName = "original.bin",
        format = "RAW_BINARY",
        sizeBytes = 4096L,
        sha256 = "ab".repeat(32)
    )

    @Test fun noHardwareDriverMeansNoWritesEvenWhenChecklistIsComplete() {
        val proof = EcuBenchExpertPlan.Evidence(
            originalReadbackVerified = true,
            recoveryPathVerified = true,
            memoryMapVerified = true
        )
        assertEquals(
            listOf(EcuBenchExpertPlan.Blocker.HARDWARE_PROGRAMMING_DRIVER_UNAVAILABLE),
            EcuBenchExpertPlan.blockers(target, image, proof)
        )
        assertFalse(EcuBenchExpertPlan.isProgrammingEnabled(target, image, proof))
    }

    @Test fun unknownControllerAndChipRequireRealIdentification() {
        val missing = EcuBenchExpertPlan.blockers(
            target.copy(controllerFamily = "E39X", chipMarking = "unknown", programmerName = ""),
            image
        )
        assertTrue(EcuBenchExpertPlan.Blocker.CONTROLLER_NOT_IDENTIFIED in missing)
        assertTrue(EcuBenchExpertPlan.Blocker.CHIP_NOT_IDENTIFIED in missing)
        assertTrue(EcuBenchExpertPlan.Blocker.PROGRAMMER_NOT_IDENTIFIED in missing)
    }

    @Test fun anImageNameOrAnImportHashDoesNotBecomeABackup() {
        val missing = EcuBenchExpertPlan.blockers(
            target,
            image.copy(format = "ZIP", sha256 = "not-a-sha256")
        )
        assertTrue(EcuBenchExpertPlan.Blocker.IMAGE_NOT_RAW_BINARY in missing)
        assertTrue(EcuBenchExpertPlan.Blocker.IMAGE_INTEGRITY_MISSING in missing)
        assertTrue(EcuBenchExpertPlan.Blocker.ORIGINAL_READBACK_NOT_VERIFIED in missing)
    }

    @Test fun offlineImageVerificationRequiresActualContentNotMetadataAlone() {
        val bytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val valid = image.copy(
            sizeBytes = bytes.size.toLong(),
            sha256 = EcuBenchExpertPlan.sha256Hex(bytes)
        )
        assertTrue(EcuBenchExpertPlan.verifyCandidateImage(valid, bytes))
        assertTrue(EcuBenchExpertPlan.verifyCandidateImage(
            valid.copy(sha256 = valid.sha256?.uppercase()), bytes
        ))
        assertFalse(EcuBenchExpertPlan.verifyCandidateImage(valid, null))
        assertFalse(EcuBenchExpertPlan.verifyCandidateImage(valid, byteArrayOf()))
        assertFalse(EcuBenchExpertPlan.verifyCandidateImage(valid, byteArrayOf(0x12, 0x34, 0x56, 0x79)))
        assertFalse(EcuBenchExpertPlan.verifyCandidateImage(valid.copy(sizeBytes = 4096L), bytes))
        assertFalse(EcuBenchExpertPlan.verifyCandidateImage(valid.copy(format = "ZIP"), bytes))
        assertFalse(EcuBenchExpertPlan.verifyCandidateImage(valid.copy(fileName = ""), bytes))
        assertFalse(EcuBenchExpertPlan.verifyCandidateImage(valid.copy(sha256 = "ab".repeat(32)), bytes))
        // A matching file hash must never enable a hardware write without a driver.
        assertFalse(EcuBenchExpertPlan.isProgrammingEnabled(target, valid))
    }

    @Test fun exactReadbackComparisonIsRequired() {
        val original = byteArrayOf(0, 1, -1, 4)
        assertTrue(EcuBenchExpertPlan.verifiedReadback(original, original.clone()))
        assertFalse(EcuBenchExpertPlan.verifiedReadback(original, byteArrayOf(0, 1, -1, 5)))
        assertFalse(EcuBenchExpertPlan.verifiedReadback(byteArrayOf(), byteArrayOf()))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            EcuBenchExpertPlan.sha256Hex(byteArrayOf())
        )
    }

    @Test fun chipOffCannotProgramAnMcuInternalFlash() {
        val invalid = EcuBenchExpertPlan.blockers(
            target.copy(accessMethod = EcuBenchExpertPlan.AccessMethod.EXTERNAL_CHIP_OFF),
            image
        )
        assertTrue(EcuBenchExpertPlan.Blocker.ACCESS_REGION_INCOMPATIBLE in invalid)
        val valid = EcuBenchExpertPlan.blockers(
            target.copy(
                accessMethod = EcuBenchExpertPlan.AccessMethod.EXTERNAL_CHIP_OFF,
                region = EcuBenchExpertPlan.MemoryRegion.EXTERNAL_EEPROM
            ),
            image
        )
        assertFalse(EcuBenchExpertPlan.Blocker.ACCESS_REGION_INCOMPATIBLE in valid)
    }

    @Test fun accessMethodsAndMemoryRegionsAreDistinct() {
        assertEquals(3, EcuBenchExpertPlan.AccessMethod.values().size)
        assertEquals(3, EcuBenchExpertPlan.MemoryRegion.values().size)
    }
}
