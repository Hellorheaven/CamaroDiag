package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class CamaroModuleDtcScannerTest {
    @Test
    fun manualTransmissionDoesNotClaimTcmAndNeverOpensTransportWithoutProfiles() = runBlocking {
        val scanner = CamaroModuleDtcScanner { error("No transport may be created for unconfigured modules.") }
        val result = scanner.scan(CamaroTransmission.MANUAL, emptyList())
        assertEquals(CamaroControlModule.entries.size, result.size)
        assertTrue(result.all { it is ModuleDiagnosticResult.NotConfigured })
        assertTrue(result.any {
            it.module == CamaroControlModule.TCM &&
                (it as ModuleDiagnosticResult.NotConfigured).reason.contains("Manual")
        })
    }

    @Test
    fun missingVerifiedEvidenceOrInvalidCanAddressIsRejected() {
        try {
            VerifiedModuleReadAddress(CamaroControlModule.BCM, 0x700, 0x708, evidenceReference = "")
            throw AssertionError("Missing evidence must be rejected.")
        } catch (_: IllegalArgumentException) { }
        try {
            VerifiedModuleReadAddress(CamaroControlModule.SDM, 0x800, 0x708, evidenceReference = "OEM")
            throw AssertionError("Invalid standard CAN ID must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun duplicateModuleProfilesAreRejectedBeforeBusAccess() = runBlocking {
        val scanner = CamaroModuleDtcScanner { error("No transport expected.") }
        val profile = VerifiedModuleReadAddress(CamaroControlModule.IPC, 0x700, 0x708, evidenceReference = "Test fixture")
        try {
            scanner.scan(CamaroTransmission.UNKNOWN, listOf(profile, profile))
            throw AssertionError("Ambiguous profiles must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun operatorCancellationStopsBeforeAnyLaterModuleIsProbed() = runBlocking {
        var opened = 0
        val scanner = CamaroModuleDtcScanner {
            opened++
            throw CancellationException("Operator stopped scanning")
        }
        val profiles = listOf(
            VerifiedModuleReadAddress(CamaroControlModule.BCM, 0x700, 0x708,
                evidenceReference = "synthetic"),
            VerifiedModuleReadAddress(CamaroControlModule.TCM, 0x720, 0x728,
                evidenceReference = "synthetic")
        )
        val result = runCatching { scanner.scan(CamaroTransmission.AUTOMATIC, profiles) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, opened)
    }

    @Test
    fun invalidOrExcessiveTimeoutCannotStartModuleProbe() = runBlocking {
        var opened = 0
        val scanner = CamaroModuleDtcScanner { opened++; error("No CAN access") }
        val profile = VerifiedModuleReadAddress(CamaroControlModule.BCM,
            0x700, 0x708, evidenceReference = "synthetic")
        for (timeout in listOf(-1L, 0L, 30_001L, Long.MAX_VALUE)) {
            assertTrue(runCatching {
                scanner.scan(CamaroTransmission.UNKNOWN, listOf(profile), timeout)
            }.isFailure)
        }
        assertEquals(0, opened)
    }

    @Test
    fun transportFailureIsNotReportedAsNoFaults() = runBlocking {
        val scanner = CamaroModuleDtcScanner { error("Adapter unavailable") }
        val address = VerifiedModuleReadAddress(CamaroControlModule.BCM, 0x700, 0x708, evidenceReference = "Test fixture")
        val results = scanner.scan(CamaroTransmission.UNKNOWN, listOf(address))
        assertTrue(results.first() is ModuleDiagnosticResult.ReadFailure)
        assertEquals(4, results.count { it is ModuleDiagnosticResult.NotConfigured })
    }
}
