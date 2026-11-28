package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import com.camarodiag.gen5.domain.model.CamaroGeneration
import com.camarodiag.gen5.domain.model.CamaroEngine
import com.camarodiag.gen5.domain.model.VehicleProfile
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

    private fun documentedBcmProfile(): DocumentedUdsReadProfile =
        DocumentedUdsReadProfile(
            profileId = "synthetic-bcm", moduleId = "BCM",
            generation = CamaroGeneration.GEN5, modelYears = 2012..2015,
            engineRpo = "LFX", controllerFamily = "BCM",
            requestCanId = 0x7A0, responseCanId = 0x7A8, extendedCanId = false,
            hardwarePartNumber = "HW1", operatingSystemId = "OS1",
            calibrationId = "CAL1", calibrationVerificationNumber = "A1B2",
            readableDids = (0x0101..0x0106).toSet(),
            identityDids = listOf(
                UdsIdentityDidDefinition(UdsIdentityField.CONTROLLER_FAMILY, 0x0101, UdsIdentityEncoding.ASCII),
                UdsIdentityDidDefinition(UdsIdentityField.HARDWARE_PART_NUMBER, 0x0102, UdsIdentityEncoding.ASCII),
                UdsIdentityDidDefinition(UdsIdentityField.OPERATING_SYSTEM_ID, 0x0103, UdsIdentityEncoding.ASCII),
                UdsIdentityDidDefinition(UdsIdentityField.CALIBRATION_ID, 0x0104, UdsIdentityEncoding.ASCII),
                UdsIdentityDidDefinition(UdsIdentityField.CALIBRATION_VERIFICATION_NUMBER, 0x0105, UdsIdentityEncoding.HEX),
                UdsIdentityDidDefinition(UdsIdentityField.VIN, 0x0106, UdsIdentityEncoding.ASCII)
            ),
            verifiedVin = "2G1TEST00C0000001",
            evidenceReference = "Synthetic test profile; not vehicle validated"
        )

    @Test
    fun physicallyAnsweredProfiledDtcReportCarriesVerifiedEcuIdentity() = runBlocking {
        val vehicle = VehicleProfile(modelYear = 2012, engine = CamaroEngine.LFX,
            vin = "2G1TEST00C0000001")
        val vin = vehicle.vin.toByteArray(Charsets.US_ASCII)
        fun response(did: Int, payload: ByteArray): CanFrame {
            val data = byteArrayOf(0x62, (did shr 8).toByte(), did.toByte()) + payload
            return CanFrame(0x7A8, byteArrayOf(data.size.toByte()) + data)
        }
        val incoming = java.util.ArrayDeque(listOf(
            response(0x0101, "BCM".toByteArray()),
            response(0x0102, "HW1".toByteArray()),
            response(0x0103, "OS1".toByteArray()),
            response(0x0104, "CAL1".toByteArray()),
            response(0x0105, byteArrayOf(0xA1.toByte(), 0xB2.toByte())),
            CanFrame(0x7A8, byteArrayOf(0x10, 0x14, 0x62, 0x01, 0x06,
                vin[0], vin[1], vin[2])),
            CanFrame(0x7A8, byteArrayOf(0x21, vin[3], vin[4], vin[5], vin[6],
                vin[7], vin[8], vin[9])),
            CanFrame(0x7A8, byteArrayOf(0x22, vin[10], vin[11], vin[12],
                vin[13], vin[14], vin[15], vin[16])),
            CanFrame(0x7A8, byteArrayOf(0x03, 0x59, 0x02, 0xFF.toByte()))
        ))
        val sent = mutableListOf<CanFrame>()
        val channel = object : RawCanFrameTransport {
            override suspend fun send(frame: CanFrame) { sent += frame }
            override suspend fun receive(timeoutMs: Long): CanFrame? =
                if (incoming.isEmpty()) null else incoming.removeFirst()
        }
        val scanner = CamaroModuleDtcScanner { error("Legacy scan path unused") }
        val results = scanner.scanProfiled(vehicle,
            listOf(documentedBcmProfile()), { channel })
        val bcm = results.single { it.module == CamaroControlModule.BCM }
            as ModuleDiagnosticResult.DtcRead
        assertTrue(bcm.identityVerified)
        assertEquals(vehicle.vin, bcm.verifiedIdentity?.vin)
        assertTrue(bcm.report.records.isEmpty())
        assertEquals(0xFF, bcm.report.statusAvailabilityMask)
        assertTrue(sent.isNotEmpty())
        assertTrue(sent.all {
            (it.data[0].toInt() and 0xF0) == 0x30 ||
                (it.data.size >= 2 && (it.data[1].toInt() and 0xFF) in listOf(0x22, 0x19))
        })
        assertTrue(results.filter { it.module != CamaroControlModule.BCM }
            .all { it is ModuleDiagnosticResult.NotConfigured })
    }

    @Test
    fun ecmProfileIsEligibleForReadOnlyIdentityCheckWithoutClaimingAnyDtc() = runBlocking {
        val vehicle = VehicleProfile(modelYear = 2012, engine = CamaroEngine.LFX,
            vin = "2G1TEST00C0000001")
        val ecm = documentedBcmProfile().copy(
            moduleId = "ECM", profileId = "synthetic-ecm", controllerFamily = "E39"
        )
        var opened = 0
        val scanner = CamaroModuleDtcScanner { error("Legacy scanner is not used.") }
        val results = scanner.scanProfiled(vehicle, listOf(ecm), {
            opened++
            throw IllegalStateException("Hardware unavailable")
        })
        assertEquals(1, opened)
        assertTrue(results.single { it.module == CamaroControlModule.ECM }
            is ModuleDiagnosticResult.ReadFailure)
        assertTrue(results.filter { it.module != CamaroControlModule.ECM }
            .all { it is ModuleDiagnosticResult.NotConfigured })
    }

    @Test
    fun profiledDtcScannerNeverOpensBusForMissingOrMismatchedEcuProfiles() = runBlocking {
        val vehicle = VehicleProfile(modelYear = 2012, engine = CamaroEngine.LFX,
            vin = "2G1TEST00C0000001")
        val scanner = CamaroModuleDtcScanner { error("Legacy raw address route unused.") }
        var opened = 0
        val open: suspend (DocumentedUdsReadProfile) -> RawCanFrameTransport = {
            opened++
            error("No transport for missing ECU evidence.")
        }
        val missing = scanner.scanProfiled(vehicle, emptyList(), open)
        assertTrue(missing.all { it is ModuleDiagnosticResult.NotConfigured })
        val mismatched = scanner.scanProfiled(vehicle,
            listOf(documentedBcmProfile().copy(verifiedVin = "1G1TEST00C0000001")), open)
        assertTrue(mismatched.all { it is ModuleDiagnosticResult.NotConfigured })
        val duplicates = scanner.scanProfiled(vehicle,
            listOf(documentedBcmProfile(), documentedBcmProfile()), open)
        assertTrue(duplicates.all { it is ModuleDiagnosticResult.NotConfigured })
        assertEquals(0, opened)
    }

    @Test
    fun profiledDtcScannerPropagatesCancellationBeforeProbingOtherModules() = runBlocking {
        val vehicle = VehicleProfile(modelYear = 2012, engine = CamaroEngine.LFX,
            vin = "2G1TEST00C0000001")
        val scanner = CamaroModuleDtcScanner { error("Legacy path unused") }
        var opened = 0
        val outcome = runCatching {
            scanner.scanProfiled(vehicle, listOf(documentedBcmProfile()), {
                opened++
                throw CancellationException("Operator cancelled verified DTC scan")
            })
        }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertEquals(1, opened)
    }

    @Test
    fun transportFailureIsNotReportedAsNoFaults() = runBlocking {
        val scanner = CamaroModuleDtcScanner { error("Adapter unavailable") }
        val address = VerifiedModuleReadAddress(CamaroControlModule.BCM, 0x700, 0x708, evidenceReference = "Test fixture")
        val results = scanner.scan(CamaroTransmission.UNKNOWN, listOf(address))
        assertTrue(results.single { it.module == CamaroControlModule.BCM }
            is ModuleDiagnosticResult.ReadFailure)
        assertEquals(5, results.count { it is ModuleDiagnosticResult.NotConfigured })
    }
}
