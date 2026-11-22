package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.CamaroEngine
import com.camarodiag.gen5.domain.model.CamaroGeneration
import com.camarodiag.gen5.domain.model.VehicleProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CancellationException

class ProfiledUdsModuleInventoryTest {
    private val vehicle = VehicleProfile(
        modelYear = 2013, engine = CamaroEngine.LFX, vin = "1G1FA1RXXR0123456"
    )

    private fun profile() = DocumentedUdsReadProfile(
        profileId = "test-bcm-profile", moduleId = "BCM",
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
        verifiedVin = vehicle.vin,
        evidenceReference = "Synthetic, hardware-free module inventory test"
    )

    private class Frames(responses: List<CanFrame> = emptyList()) : RawCanFrameTransport {
        val sent = mutableListOf<CanFrame>()
        private val incoming = ArrayDeque(responses)
        override suspend fun send(frame: CanFrame) { sent += frame }
        override suspend fun receive(timeoutMs: Long): CanFrame? =
            if (incoming.isEmpty()) null else incoming.removeFirst()
    }

    private fun did(did: Int, value: ByteArray): CanFrame {
        val payload = byteArrayOf(0x62, (did shr 8).toByte(), did.toByte()) + value
        require(payload.size <= 7)
        return CanFrame(0x7A8, byteArrayOf(payload.size.toByte()) + payload)
    }

    @Test fun missingAndConflictingProfilesNeverProbeECUs() = runBlocking {
        var opened = 0
        val open: suspend (DocumentedUdsReadProfile) -> RawCanFrameTransport = {
            opened++
            Frames()
        }
        val missing = ProfiledUdsModuleInventory.scan(
            vehicle, emptyList(), open, moduleIds = listOf("BCM", "IPC", "SDM"))
        assertEquals(3, missing.size)
        assertTrue(missing.all {
            it.status == ProfiledUdsModuleInventory.Status.PROFILE_REQUIRED
                && it.identity == null && !it.programmingAuthorized
        })
        val ambiguous = ProfiledUdsModuleInventory.scan(
            vehicle, listOf(profile(), profile()), open, moduleIds = listOf("BCM"))
        assertEquals(ProfiledUdsModuleInventory.Status.PROFILE_REQUIRED,
            ambiguous.single().status)
        assertEquals(0, opened)
    }

    @Test fun allSixCoreModulesAreListedWithoutGuessingTheirCanAddresses() = runBlocking {
        var hardwareProbes = 0
        val results = ProfiledUdsModuleInventory.scan(vehicle, emptyList(), {
            hardwareProbes++
            Frames()
        })
        assertEquals(listOf("ECM", "TCM", "BCM", "IPC", "EBCM", "SDM"),
            results.map { it.moduleId })
        assertEquals(ProfiledUdsModuleInventory.Status.NOT_EQUIPPED,
            results.single { it.moduleId == "TCM" }.status)
        assertTrue(results.filter { it.moduleId != "TCM" }.all {
            it.status == ProfiledUdsModuleInventory.Status.PROFILE_REQUIRED
        })
        assertEquals(0, hardwareProbes)
    }

    @Test fun manualCamaroDoesNotProbeAnUnfittedTcm() = runBlocking {
        var opened = 0
        val result = ProfiledUdsModuleInventory.scan(
            vehicle, emptyList(), transportFor = { opened++; Frames() },
            moduleIds = listOf("TCM"))
        assertEquals(ProfiledUdsModuleInventory.Status.NOT_EQUIPPED,
            result.single().status)
        assertEquals(0, opened)
    }

    @Test fun incompatibleOrUnresponsiveProfileNeverClaimsModuleIsAccessible() = runBlocking {
        val rejected = profile().copy(verifiedVin = "1G1FA1RXXR0123457")
        var opened = 0
        val invalid = ProfiledUdsModuleInventory.scan(
            vehicle, listOf(rejected), { opened++; Frames() }, listOf("BCM"))
        assertEquals(ProfiledUdsModuleInventory.Status.PROFILE_REQUIRED, invalid.single().status)
        assertEquals(0, opened)
        val noResponse = ProfiledUdsModuleInventory.scan(
            vehicle, listOf(profile()), { opened++; Frames() }, listOf("BCM"))
        assertEquals(ProfiledUdsModuleInventory.Status.IDENTITY_UNVERIFIED,
            noResponse.single().status)
        assertEquals(1, opened)
        assertFalse(noResponse.single().hardwareConfirmed)
    }

    @Test fun completeProfiledBcmIdentityCanBeMarkedReadOnlyButNeverFlashQualified() = runBlocking {
        val vinBytes = vehicle.vin.toByteArray(Charsets.US_ASCII)
        val frames = Frames(listOf(
            did(0x0101, "BCM".toByteArray()),
            did(0x0102, "HW1".toByteArray()),
            did(0x0103, "OS1".toByteArray()),
            did(0x0104, "CAL1".toByteArray()),
            did(0x0105, byteArrayOf(0xA1.toByte(), 0xB2.toByte())),
            CanFrame(0x7A8, byteArrayOf(0x10, 0x14, 0x62, 0x01, 0x06,
                vinBytes[0], vinBytes[1], vinBytes[2])),
            CanFrame(0x7A8, byteArrayOf(0x21, vinBytes[3], vinBytes[4], vinBytes[5],
                vinBytes[6], vinBytes[7], vinBytes[8], vinBytes[9])),
            CanFrame(0x7A8, byteArrayOf(0x22, vinBytes[10], vinBytes[11], vinBytes[12],
                vinBytes[13], vinBytes[14], vinBytes[15], vinBytes[16]))
        ))
        val result = ProfiledUdsModuleInventory.scan(
            vehicle, listOf(profile()), { frames }, moduleIds = listOf("BCM", "SDM"))
        assertEquals(ProfiledUdsModuleInventory.Status.IDENTIFIED_READ_ONLY,
            result[0].status)
        assertEquals(vehicle.vin, result[0].identity?.vin)
        assertFalse(result[0].hardwareConfirmed)
        assertFalse(result[0].programmingAuthorized)
        assertEquals(ProfiledUdsModuleInventory.Status.PROFILE_REQUIRED,
            result[1].status)
        assertTrue(frames.sent.isNotEmpty())
        assertTrue(frames.sent.all { (it.data[1].toInt() and 0xFF) == 0x22 ||
            (it.data[0].toInt() and 0xF0) == 0x30 })
    }

    @Test fun cancellationIsPropagatedInsteadOfReportingAnUnverifiedModule() = runBlocking {
        try {
            ProfiledUdsModuleInventory.scan(
                vehicle, listOf(profile()),
                { throw CancellationException("User stopped scan") }, listOf("BCM"))
            fail("Cancellation was not propagated")
        } catch (expected: CancellationException) {
            assertTrue(expected.message!!.contains("User stopped"))
        }
    }
}
