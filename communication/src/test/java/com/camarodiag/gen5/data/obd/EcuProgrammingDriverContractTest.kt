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
