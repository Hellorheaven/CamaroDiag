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

    @Test fun qualifiedHardwareSelectionRequiresExactFirmwareRegionAndAccess() {
        val firmware = EcuProgrammingDriverContract.FirmwareTarget(
            "E39A", "controller-live", "os-observed")
        val descriptor = EcuProgrammingDriverContract.Descriptor(
            id = "qualified-fake-descriptor-only",
            transport = EcuProgrammingDriverContract.Transport.J2534,
            capabilities = EcuProgrammingDriverContract.Capability.values().toSet(),
            controllerFamilies = setOf("E39A"),
            physicalHardwareValidated = true,
            writeTargets = setOf(firmware),
            writeQualifications = setOf(
                EcuProgrammingDriverContract.WriteQualification(
                    firmware, EcuBackupCoverage.Region.CALIBRATION,
                    EcuBenchExpertPlan.AccessMethod.BENCH)
            )
        )
        val qualified = target.copy(
            accessMethod = EcuBenchExpertPlan.AccessMethod.BENCH,
            observedControllerId = firmware.controllerId,
            observedFirmwareId = firmware.firmwareId,
            qualifiedWriteRegion = EcuBackupCoverage.Region.CALIBRATION
        )
        fun ids(candidate: EcuBenchExpertPlan.Target,
                desc: EcuProgrammingDriverContract.Descriptor = descriptor) =
            EcuBenchExpertPlan.matchingQualifiedDriverIds(candidate, listOf(desc))
        assertEquals(listOf(descriptor.id), ids(qualified))
        assertTrue(ids(qualified.copy(observedFirmwareId = null)).isEmpty())
        assertTrue(ids(qualified.copy(observedFirmwareId = "other-os")).isEmpty())
        assertTrue(ids(qualified.copy(observedControllerId = "other-hardware")).isEmpty())
        assertTrue(ids(qualified.copy(qualifiedWriteRegion = EcuBackupCoverage.Region.PROGRAM_FLASH)).isEmpty())
        assertTrue(ids(qualified.copy(region = EcuBenchExpertPlan.MemoryRegion.DFLASH)).isEmpty())
        assertTrue(ids(qualified.copy(accessMethod = EcuBenchExpertPlan.AccessMethod.OBD)).isEmpty())
        assertTrue(ids(qualified, descriptor.copy(physicalHardwareValidated = false)).isEmpty())
        assertTrue(ids(qualified, descriptor.copy(capabilities =
            descriptor.capabilities - EcuProgrammingDriverContract.Capability.READBACK_VERIFY)).isEmpty())
        // Descriptors alone do not register a hardware driver in production.
        assertFalse(EcuBenchExpertPlan.isProgrammingEnabled(
            qualified, image, EcuBenchExpertPlan.Evidence(true, true, true)))
        assertTrue(EcuProgrammingDriverContract.registeredHardwareDrivers.isEmpty())
    }

    @Test fun externalEepromQualificationIsNeverAFlashDriver() {
        val firmware = EcuProgrammingDriverContract.FirmwareTarget("unknown-family", "hardware-id", "os-id")
        val descriptor = EcuProgrammingDriverContract.Descriptor(
            id = "chip-model",
            transport = EcuProgrammingDriverContract.Transport.EEPROM_ISP,
            capabilities = EcuProgrammingDriverContract.Capability.values().toSet(),
            controllerFamilies = setOf(firmware.controllerFamily),
            physicalHardwareValidated = true,
            writeTargets = setOf(firmware),
            writeQualifications = setOf(EcuProgrammingDriverContract.WriteQualification(
                firmware, EcuBackupCoverage.Region.EXTERNAL_EEPROM,
                EcuBenchExpertPlan.AccessMethod.EXTERNAL_EEPROM_ISP))
        )
        val external = target.copy(controllerFamily = firmware.controllerFamily,
            accessMethod = EcuBenchExpertPlan.AccessMethod.EXTERNAL_EEPROM_ISP,
            region = EcuBenchExpertPlan.MemoryRegion.EXTERNAL_EEPROM,
            observedControllerId = firmware.controllerId,
            observedFirmwareId = firmware.firmwareId,
            qualifiedWriteRegion = EcuBackupCoverage.Region.EXTERNAL_EEPROM)
        assertEquals(listOf("chip-model"),
            EcuBenchExpertPlan.matchingQualifiedDriverIds(external, listOf(descriptor)))
        assertTrue(EcuBenchExpertPlan.matchingQualifiedDriverIds(
            external.copy(region = EcuBenchExpertPlan.MemoryRegion.PFLASH),
            listOf(descriptor)).isEmpty())
        assertTrue(EcuBenchExpertPlan.matchingQualifiedDriverIds(
            external.copy(qualifiedWriteRegion = EcuBackupCoverage.Region.PROGRAM_FLASH),
            listOf(descriptor)).isEmpty())
    }

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

    @Test fun unknownControllerIsRejectedButUserTypedChipAndProgrammerLabelsAreNotKeys() {
        val missing = EcuBenchExpertPlan.blockers(
            target.copy(controllerFamily = "unknown", chipMarking = "unknown", programmerName = ""),
            image
        )
        assertTrue(EcuBenchExpertPlan.Blocker.CONTROLLER_NOT_IDENTIFIED in missing)
        val known = EcuBenchExpertPlan.blockers(
            target.copy(chipMarking = null, programmerName = null), image
        )
        assertFalse(EcuBenchExpertPlan.Blocker.CHIP_NOT_IDENTIFIED in known)
        assertFalse(EcuBenchExpertPlan.Blocker.PROGRAMMER_NOT_IDENTIFIED in known)
        assertTrue(EcuBenchExpertPlan.Blocker.HARDWARE_PROGRAMMING_DRIVER_UNAVAILABLE in known)
    }

    @Test fun knownOtherEcuFamiliesAreNotArtificiallyRestrictedToE39() {
        for (family in listOf("E38", "E69", "E67", "E80", "E92", "E93", "E39X")) {
            val blockers = EcuBenchExpertPlan.blockers(target.copy(controllerFamily = family), image)
            assertFalse(family, EcuBenchExpertPlan.Blocker.CONTROLLER_NOT_IDENTIFIED in blockers)
            assertTrue(family, EcuBenchExpertPlan.Blocker.HARDWARE_PROGRAMMING_DRIVER_UNAVAILABLE in blockers)
        }
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
        assertFalse(EcuBenchExpertPlan.verifiedReadback(original, original))
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

    @Test fun chipLevelRoutesCannotBeInterchangedWithCanOrInternalFlash() {
        val methods = EcuBenchExpertPlan.AccessMethod.values()
        val regions = EcuBenchExpertPlan.MemoryRegion.values()
        val transport = EcuProgrammingDriverContract.Transport.values()
        assertEquals(transport.toSet(), methods.flatMap { it.candidateTransports }.toSet())
        for (method in methods) {
            for (region in regions) {
                val external = method == EcuBenchExpertPlan.AccessMethod.EXTERNAL_CHIP_OFF ||
                    method == EcuBenchExpertPlan.AccessMethod.EXTERNAL_EEPROM_ISP
                assertEquals(method.name + "/" + region.name,
                    external == (region == EcuBenchExpertPlan.MemoryRegion.EXTERNAL_EEPROM),
                    method.permitsRegion(region))
            }
            val blockers = EcuBenchExpertPlan.blockers(
                target.copy(accessMethod = method, region = regions.first(method::permitsRegion)),
                image, EcuBenchExpertPlan.Evidence(true, true, true)
            )
            assertEquals(listOf(EcuBenchExpertPlan.Blocker.HARDWARE_PROGRAMMING_DRIVER_UNAVAILABLE), blockers)
        }
        assertTrue(EcuBenchExpertPlan.AccessMethod.BDM.candidateTransports.intersect(
            EcuBenchExpertPlan.AccessMethod.NEXUS_JTAG.candidateTransports).isEmpty())
        assertTrue(EcuBenchExpertPlan.AccessMethod.EXTERNAL_EEPROM_ISP.candidateTransports.intersect(
            EcuBenchExpertPlan.AccessMethod.EXTERNAL_CHIP_OFF.candidateTransports).isEmpty())
    }

    @Test fun swdIsNotInterchangeableWithOtherDebugOrCanRoutes() {
        val swd = EcuBenchExpertPlan.AccessMethod.SWD
        assertEquals(setOf(EcuProgrammingDriverContract.Transport.SWD), swd.candidateTransports)
        for (other in EcuBenchExpertPlan.AccessMethod.values().filter { it != swd }) {
            assertTrue(swd.candidateTransports.intersect(other.candidateTransports).isEmpty())
        }
        assertFalse(swd.permitsRegion(EcuBenchExpertPlan.MemoryRegion.EXTERNAL_EEPROM))
        assertFalse(EcuBenchExpertPlan.isProgrammingEnabled(target.copy(accessMethod = swd), image))
    }

    @Test fun accessMethodsAndMemoryRegionsAreDistinct() {
        assertEquals(9, EcuBenchExpertPlan.AccessMethod.values().size)
        assertEquals(3, EcuBenchExpertPlan.MemoryRegion.values().size)
    }
}
