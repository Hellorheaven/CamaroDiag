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

    @Test fun qualifiedDriverMustMatchTransportFirmwareAndMemoryRegion() {
        val target = EcuProgrammingDriverContract.FirmwareTarget("E39", "board-test", "os-test")
        val region = EcuBackupCoverage.Region.PROGRAM_FLASH
        val driver = EcuProgrammingDriverContract.Descriptor(
            id = "model-only", transport = EcuProgrammingDriverContract.Transport.BOOT_BAM,
            capabilities = EcuProgrammingDriverContract.Capability.values().toSet(),
            controllerFamilies = setOf("E39"), physicalHardwareValidated = true,
            writeTargets = setOf(target), qualifiedWriteRegions = setOf(region)
        )
        assertTrue(driver.supportsWrite(target, driver.transport, region))
        assertFalse(driver.supportsWrite(target.copy(firmwareId = "other"), driver.transport, region))
        assertFalse(driver.supportsWrite(target, EcuProgrammingDriverContract.Transport.NEXUS_JTAG, region))
        assertFalse(driver.supportsWrite(target, driver.transport, EcuBackupCoverage.Region.DATA_FLASH))
        assertFalse(driver.copy(qualifiedWriteRegions = emptySet()).supportsWrite(target, driver.transport))
        val elm = driver.copy(transport = EcuProgrammingDriverContract.Transport.ELM_DIAGNOSTICS)
        assertFalse(elm.supportsWrite(target, elm.transport, region))
        for (transport in listOf(EcuProgrammingDriverContract.Transport.CHIP_OFF,
            EcuProgrammingDriverContract.Transport.EEPROM_ISP)) {
            val directChip = driver.copy(transport = transport)
            assertFalse(directChip.supportsWrite(target, transport, region))
            val qualifiedChip = directChip.copy(qualifiedWriteRegions = setOf(EcuBackupCoverage.Region.EXTERNAL_EEPROM))
            assertTrue(qualifiedChip.supportsWrite(target, transport, EcuBackupCoverage.Region.EXTERNAL_EEPROM))
            assertFalse(qualifiedChip.supportsWrite(target, transport, region))
        }
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
