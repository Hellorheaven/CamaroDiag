package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuProgrammingDriverContractTest {
    @Test fun noRealControllerCanBeWrittenWithoutHardwareValidatedDriver() {
        val contract = EcuProgrammingDriverContract
        assertTrue(contract.registeredHardwareDrivers.isEmpty())
        for (transport in EcuProgrammingDriverContract.Transport.values()) {
            assertFalse(contract.hasValidatedWriteDriver("E39", transport))
            assertFalse(contract.hasValidatedWriteDriver("E39A", transport))
        }
    }

    @Test fun qualifiedDriverMustMatchTransportFirmwareMemoryRegionAndRoute() {
        val target = EcuProgrammingDriverContract.FirmwareTarget("E39", "board-test", "os-test")
        val otherTarget = target.copy(firmwareId = "other")
        val region = EcuBackupCoverage.Region.PROGRAM_FLASH
        val route = EcuBenchExpertPlan.AccessMethod.BOOT_BAM
        val driver = EcuProgrammingDriverContract.Descriptor(
            id = "model-only", transport = EcuProgrammingDriverContract.Transport.BOOT_BAM,
            capabilities = EcuProgrammingDriverContract.Capability.values().toSet(),
            controllerFamilies = setOf("E39"), physicalHardwareValidated = true,
            writeTargets = setOf(target, otherTarget),
            writeQualifications = setOf(
                EcuProgrammingDriverContract.WriteQualification(target, region, route),
                EcuProgrammingDriverContract.WriteQualification(otherTarget, EcuBackupCoverage.Region.DATA_FLASH, route)
            )
        )
        assertTrue(driver.supportsWrite(target, driver.transport, region, route))
        assertFalse(driver.supportsWrite(otherTarget, driver.transport, region, route))
        assertFalse(driver.supportsWrite(target, EcuProgrammingDriverContract.Transport.NEXUS_JTAG, region, route))
        assertFalse(driver.supportsWrite(target, driver.transport, EcuBackupCoverage.Region.DATA_FLASH, route))
        assertFalse(driver.copy(writeQualifications = emptySet()).supportsWrite(target, driver.transport))
        val elm = driver.copy(transport = EcuProgrammingDriverContract.Transport.ELM_DIAGNOSTICS)
        assertFalse(elm.supportsWrite(target, elm.transport, region, EcuBenchExpertPlan.AccessMethod.OBD))
        for ((transport, method) in listOf(
            EcuProgrammingDriverContract.Transport.CHIP_OFF to EcuBenchExpertPlan.AccessMethod.EXTERNAL_CHIP_OFF,
            EcuProgrammingDriverContract.Transport.EEPROM_ISP to EcuBenchExpertPlan.AccessMethod.EXTERNAL_EEPROM_ISP
        )) {
            val qualifiedChip = driver.copy(transport = transport,
                writeQualifications = setOf(EcuProgrammingDriverContract.WriteQualification(
                    target, EcuBackupCoverage.Region.EXTERNAL_EEPROM, method)))
            assertTrue(qualifiedChip.supportsWrite(target, transport, EcuBackupCoverage.Region.EXTERNAL_EEPROM, method))
            assertFalse(qualifiedChip.supportsWrite(target, transport, region, method))
        }
    }

    @Test fun swdQualificationCannotAuthorizeJtagBdmCanOrExternalChipWrites() {
        val target = EcuProgrammingDriverContract.FirmwareTarget("example-family", "board", "os")
        val region = EcuBackupCoverage.Region.PROGRAM_FLASH
        val route = EcuBenchExpertPlan.AccessMethod.SWD
        val driver = EcuProgrammingDriverContract.Descriptor(
            id = "model-swd-only", transport = EcuProgrammingDriverContract.Transport.SWD,
            capabilities = EcuProgrammingDriverContract.Capability.values().toSet(),
            controllerFamilies = setOf(target.controllerFamily), physicalHardwareValidated = true,
            writeTargets = setOf(target),
            writeQualifications = setOf(EcuProgrammingDriverContract.WriteQualification(target, region, route))
        )
        assertTrue(driver.supportsWrite(target, driver.transport, region, route))
        for (other in EcuBenchExpertPlan.AccessMethod.values().filter { it != route }) {
            assertFalse(driver.supportsWrite(target, driver.transport, region, other))
        }
        assertFalse(driver.supportsWrite(target, driver.transport, EcuBackupCoverage.Region.EXTERNAL_EEPROM, route))
        assertFalse(driver.supportsWrite(target.copy(firmwareId = "other"), driver.transport, region, route))
    }

    @Test fun diagnosticElmTransportIsDistinctFromProgrammingHardware() {
        assertFalse(
            EcuProgrammingDriverContract.Transport.ELM_DIAGNOSTICS ==
                EcuProgrammingDriverContract.Transport.BOOT_BAM
        )
        assertFalse(
            EcuProgrammingDriverContract.Transport.J2534 ==
                EcuProgrammingDriverContract.Transport.NEXUS_JTAG
        )
    }
}
