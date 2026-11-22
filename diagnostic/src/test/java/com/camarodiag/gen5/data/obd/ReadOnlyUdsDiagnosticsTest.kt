package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.CamaroEngine
import com.camarodiag.gen5.domain.model.CamaroGeneration
import com.camarodiag.gen5.domain.model.VehicleProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class ReadOnlyUdsDiagnosticsTest {

    private class FakeCanTransport(
        responses: List<CanFrame> = emptyList()
    ) : RawCanFrameTransport {
        val sent = mutableListOf<CanFrame>()
        private val incoming = ArrayDeque(responses)

        override suspend fun send(frame: CanFrame) {
            sent += frame
        }

        override suspend fun receive(timeoutMs: Long): CanFrame? =
            if (incoming.isEmpty()) null else incoming.removeFirst()
    }

    @Test
    fun sendsReadDataByIdentifierAsIsoTpSingleFrame() = runBlocking {
        val transport = FakeCanTransport(
            listOf(CanFrame(0x7E8, byteArrayOf(0x05, 0x62, 0x12, 0x34, 0x55, 0x66)))
        )
        val client = UdsReadOnlyClient(transport, 0x7E0, 0x7E8)

        val result = client.readDataByIdentifier(0x1234)

        assertArrayEquals(byteArrayOf(0x55, 0x66), result)
        assertEquals(1, transport.sent.size)
        assertEquals(0x7E0, transport.sent.single().arbitrationId)
        assertArrayEquals(byteArrayOf(0x03, 0x22, 0x12, 0x34), transport.sent.single().data)
    }

    @Test
    fun receivesMultiFrameVinAndSendsFlowControl() = runBlocking {
        val transport = FakeCanTransport(
            listOf(
                CanFrame(0x7E8, byteArrayOf(0x10, 0x14, 0x62, 0xF1.toByte(), 0x90.toByte(), 0x31, 0x47, 0x31)),
                CanFrame(0x7E8, byteArrayOf(0x21, 0x46, 0x41, 0x31, 0x52, 0x58, 0x58, 0x52)),
                CanFrame(0x7E8, byteArrayOf(0x22, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36))
            )
        )
        val client = UdsReadOnlyClient(transport, 0x7E0, 0x7E8)

        assertEquals("1G1FA1RXXR0123456", client.readVin())
        assertTrue(
            transport.sent.any {
                it.arbitrationId == 0x7E0 &&
                    it.data.contentEquals(byteArrayOf(0x30, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            }
        )
    }

    @Test
    fun exposesUdsNegativeResponseCodeWithoutTreatingItAsData() = runBlocking {
        val transport = FakeCanTransport(
            listOf(CanFrame(0x7E8, byteArrayOf(0x03, 0x7F, 0x22, 0x31)))
        )
        val client = UdsReadOnlyClient(transport, 0x7E0, 0x7E8)

        val error = runCatching { client.readDataByIdentifier(0x1234) }.exceptionOrNull()

        assertTrue(error is UdsNegativeResponseException)
        assertEquals(0x22, (error as UdsNegativeResponseException).requestedService)
        assertEquals(0x31, error.negativeResponseCode)
    }

    @Test
    fun readsUdsDtcInformationIncludingTheDeclaredFormatIdentifier() = runBlocking {
        val transport = FakeCanTransport(
            listOf(CanFrame(0x7E8, byteArrayOf(0x04, 0x59, 0x02, 0xFF.toByte(), 0x01)))
        )
        val client = UdsReadOnlyClient(transport, 0x7E0, 0x7E8)

        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x01), client.readDtcInformation())
        assertArrayEquals(byteArrayOf(0x03, 0x19, 0x02, 0xFF.toByte()), transport.sent.single().data)
    }

    @Test
    fun readOnlyUdsClientReturnsDecodedDtcReportFromMultiframeResponse() = runBlocking {
        val transport = FakeCanTransport(
            listOf(
                CanFrame(0x7E8, byteArrayOf(0x10, 0x08, 0x59, 0x02, 0xFF.toByte(), 0x00, 0x01, 0x01)),
                CanFrame(0x7E8, byteArrayOf(0x21, 0x01, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00))
            )
        )
        val client = UdsReadOnlyClient(transport, 0x7E0, 0x7E8)

        val report = client.readDtcReport()

        assertEquals(UdsDtcFormat.SAE_J2012_DA_FORMAT_00, report.format)
        assertEquals("P0101-01", report.records.single().code)
        assertTrue(report.records.single().status.confirmed)
        assertTrue(transport.sent.any { (it.data[0].toInt() and 0xF0) == 0x30 })
    }

    @Test
    fun checksExactVehicleAndEcuIdentifiersBeforeAllowingProfileDids() {
        val profile = syntheticReadProfile()
        val vehicle = VehicleProfile(modelYear = 2013, engine = CamaroEngine.LFX, vin = "1G1FA1RXXR0123456")
        val matching = UdsEcuIdentity("BCM", "HW1", "OS1", "CAL1", "A1B2", "1G1FA1RXXR0123456")
        val mismatching = matching.copy(calibrationVerificationNumber = "A1B3")

        assertTrue(profile.compatibilityIssues(vehicle, matching).isEmpty())
        assertTrue(profile.compatibilityIssues(vehicle, mismatching).any { it.contains("CVN") })
        assertTrue(profile.allowsDid(0x0106))
        assertTrue(!profile.allowsDid(0x0108))
    }

    @Test
    fun placeholderEcuIdentityLabelsCannotEnableAnyEnhancedModuleRead() = runBlocking {
        val vehicle = VehicleProfile(
            modelYear = 2013, engine = CamaroEngine.LFX, vin = "1G1FA1RXXR0123456")
        val original = syntheticReadProfile()
        val profiles = listOf(
            original.copy(hardwarePartNumber = "unknown"),
            original.copy(operatingSystemId = "0x00000000"),
            original.copy(calibrationId = "PENDING"),
            original.copy(calibrationVerificationNumber = "FFFFFFFF"),
            original.copy(controllerFamily = "n/a")
        )
        for (profile in profiles) {
            val transport = FakeCanTransport()
            val issues = profile.compatibilityDefinitionIssues(vehicle)
            assertTrue(issues.isNotEmpty())
            assertTrue(runCatching {
                ProfiledUdsReadSession.open(transport, vehicle, profile)
            }.isFailure)
            assertTrue("Rejected module profile must send no CAN frames.",
                transport.sent.isEmpty())
        }
    }

    @Test
    fun refusesToOpenProfileWhenSelectedVehicleVinDoesNotMatch() = runBlocking {
        val transport = FakeCanTransport()
        val otherVin = "1G1FA1RXXR0123457"

        val error = runCatching {
            ProfiledUdsReadSession.open(
                transport = transport,
                vehicle = VehicleProfile(modelYear = 2013, engine = CamaroEngine.LFX, vin = otherVin),
                profile = syntheticReadProfile()
            )
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("VIN") == true)
        assertTrue("No CAN frame may be sent for a mismatched vehicle VIN.", transport.sent.isEmpty())
    }

    @Test
    fun opensProfiledSessionOnlyAfterReadingAndMatchingEveryIdentityDid() = runBlocking {
        val vin = "1G1FA1RXXR0123456".toByteArray(Charsets.US_ASCII)
        val responses = listOf(
            singleFrameResponse(0x0101, byteArrayOf(0x42, 0x43, 0x4D, 0x00)),
            singleFrameResponse(0x0102, "HW1".toByteArray(Charsets.US_ASCII)),
            singleFrameResponse(0x0103, "OS1".toByteArray(Charsets.US_ASCII)),
            singleFrameResponse(0x0104, "CAL1".toByteArray(Charsets.US_ASCII)),
            singleFrameResponse(0x0105, byteArrayOf(0xA1.toByte(), 0xB2.toByte())),
            CanFrame(0x7A8, byteArrayOf(0x10, 0x14, 0x62, 0x01, 0x06, vin[0], vin[1], vin[2])),
            CanFrame(0x7A8, byteArrayOf(0x21, vin[3], vin[4], vin[5], vin[6], vin[7], vin[8], vin[9])),
            CanFrame(0x7A8, byteArrayOf(0x22, vin[10], vin[11], vin[12], vin[13], vin[14], vin[15], vin[16])),
            singleFrameResponse(0x0107, byteArrayOf(0x55))
        )
        val transport = FakeCanTransport(responses)

        val mutableAllowedDids = syntheticReadProfile().readableDids.toMutableSet()
        val mutableIdentityDids = syntheticReadProfile().identityDids.toMutableList()
        val callerProfile = syntheticReadProfile().copy(
            readableDids = mutableAllowedDids, identityDids = mutableIdentityDids
        )
        val session = ProfiledUdsReadSession.open(
            transport = transport,
            vehicle = VehicleProfile(modelYear = 2013, engine = CamaroEngine.LFX, vin = "1G1FA1RXXR0123456"),
            profile = callerProfile
        )
        // Simulate UI/protocol-pack edits after authorization. The frozen
        // session cannot acquire fresh DID permissions or lose identity fields.
        mutableAllowedDids.add(0x0108)
        mutableIdentityDids.clear()
        assertFalse(session.profile.allowsDid(0x0108))
        assertEquals(6, session.profile.identityDids.size)

        assertEquals("BCM", session.identity.controllerFamily)
        assertEquals("HW1", session.identity.hardwarePartNumber)
        assertEquals("OS1", session.identity.operatingSystemId)
        assertEquals("CAL1", session.identity.calibrationId)
        assertEquals("A1B2", session.identity.calibrationVerificationNumber)
        assertEquals("1G1FA1RXXR0123456", session.identity.vin)
        assertArrayEquals(byteArrayOf(0x55), session.readDid(0x0107))
        assertTrue(runCatching { session.readDid(0x0108) }.isFailure)
    }

    @Test
    fun malformedAsciiIdentityDidsCannotAuthorizeProfiledReads() = runBlocking {
        val vin = "1G1FA1RXXR0123456".toByteArray(Charsets.US_ASCII)
        for (corrupt in listOf(
                byteArrayOf(0x01, 0x43, 0x4D),
                byteArrayOf(0x7F, 0x43, 0x4D),
                byteArrayOf(0xFF.toByte(), 0x43, 0x4D),
                byteArrayOf(0x00, 0x42, 0x43, 0x4D),
                byteArrayOf(0x42, 0x00, 0x43, 0x4D)
            )) {
            val responses = listOf(
                singleFrameResponse(0x0101, corrupt),
                singleFrameResponse(0x0102, "HW1".toByteArray()),
                singleFrameResponse(0x0103, "OS1".toByteArray()),
                singleFrameResponse(0x0104, "CAL1".toByteArray()),
                singleFrameResponse(0x0105, byteArrayOf(0xA1.toByte(), 0xB2.toByte())),
                CanFrame(0x7A8, byteArrayOf(0x10, 0x14, 0x62, 0x01, 0x06,
                    vin[0], vin[1], vin[2])),
                CanFrame(0x7A8, byteArrayOf(0x21, vin[3], vin[4], vin[5], vin[6],
                    vin[7], vin[8], vin[9])),
                CanFrame(0x7A8, byteArrayOf(0x22, vin[10], vin[11], vin[12],
                    vin[13], vin[14], vin[15], vin[16]))
            )
            val transport = FakeCanTransport(responses)
            val rejected = runCatching {
                ProfiledUdsReadSession.open(transport,
                    VehicleProfile(modelYear = 2013, engine = CamaroEngine.LFX,
                        vin = "1G1FA1RXXR0123456"),
                    syntheticReadProfile())
            }.exceptionOrNull()
            assertTrue(rejected is IllegalArgumentException)
            assertTrue(rejected?.message?.contains("identity") == true)
            assertTrue(transport.sent.none { it.data.size >= 2 &&
                (it.data[1].toInt() and 0xFF) == 0x19 })
        }
    }

    @Test
    fun rejectsProfileWhenEcuReportsDifferentCalibrationBeforeOpeningSession() = runBlocking {
        val vin = "1G1FA1RXXR0123456".toByteArray(Charsets.US_ASCII)
        val transport = FakeCanTransport(
            listOf(
                singleFrameResponse(0x0101, "BCM".toByteArray(Charsets.US_ASCII)),
                singleFrameResponse(0x0102, "HW1".toByteArray(Charsets.US_ASCII)),
                singleFrameResponse(0x0103, "OS1".toByteArray(Charsets.US_ASCII)),
                singleFrameResponse(0x0104, "CAL1".toByteArray(Charsets.US_ASCII)),
                singleFrameResponse(0x0105, byteArrayOf(0xFF.toByte(), 0xEE.toByte())),
                CanFrame(0x7A8, byteArrayOf(0x10, 0x14, 0x62, 0x01, 0x06, vin[0], vin[1], vin[2])),
                CanFrame(0x7A8, byteArrayOf(0x21, vin[3], vin[4], vin[5], vin[6], vin[7], vin[8], vin[9])),
                CanFrame(0x7A8, byteArrayOf(0x22, vin[10], vin[11], vin[12], vin[13], vin[14], vin[15], vin[16]))
            )
        )

        val error = runCatching {
            ProfiledUdsReadSession.open(
                transport = transport,
                vehicle = VehicleProfile(modelYear = 2013, engine = CamaroEngine.LFX, vin = "1G1FA1RXXR0123456"),
                profile = syntheticReadProfile()
            )
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("CVN") == true)
    }

    @Test
    fun repeatedInvalidFlowControlFramesCannotKeepIsoTpSendAliveForever() = runBlocking {
        val wrongFrames = List(9) { CanFrame(0x7E8, byteArrayOf(0x21, 0x00, 0x00)) }
        val transport = FakeCanTransport(wrongFrames)
        val channel = IsoTpChannel(transport, 0x7E0, 0x7E8)
        val error = runCatching {
            channel.sendOnly(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 100)
        }.exceptionOrNull()
        assertTrue(error is IsoTpException)
        assertTrue(error?.message?.contains("non-flow-control") == true)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun flowControlWaitFramesConsumeOneTotalTimeoutRatherThanRestartingIt() = runBlocking {
        val wait = CanFrame(0x7E8, byteArrayOf(0x31, 0x00, 0x00))
        val continueSending = CanFrame(0x7E8, byteArrayOf(0x30, 0x00, 0x00))
        val transport = FakeCanTransport(List(5) { wait } + continueSending)
        var elapsedNanos = -40_000_000L
        val channel = IsoTpChannel(transport, 0x7E0, 0x7E8,
            clock = { elapsedNanos += 40_000_000L; elapsedNanos })
        val failure = runCatching {
            channel.sendOnly(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 100L)
        }.exceptionOrNull()
        assertTrue(failure is IsoTpException)
        assertTrue(failure?.message?.contains("flow-control deadline") == true)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun invalidProfileTimeoutIsRejectedBeforeIdentityCanTraffic() = runBlocking {
        val profile = syntheticReadProfile()
        val vehicle = VehicleProfile(modelYear = 2013,
            engine = CamaroEngine.LFX, vin = "1G1FA1RXXR0123456")
        for (timeout in listOf(-1L, 0L, 30_001L, Long.MAX_VALUE)) {
            val transport = FakeCanTransport()
            assertTrue(runCatching {
                ProfiledUdsReadSession.open(transport, vehicle, profile, timeout)
            }.isFailure)
            assertTrue("Invalid deadline must not send a CAN frame", transport.sent.isEmpty())
        }
    }

    @Test
    fun acceptsExactlyFiveIsoTpFlowControlWaitFramesBeforeContinueToSend() = runBlocking {
        val flowControlWait = CanFrame(0x7E8, byteArrayOf(0x31, 0x00, 0x00))
        val flowControlContinue = CanFrame(0x7E8, byteArrayOf(0x30, 0x00, 0x00))
        val transport = FakeCanTransport(List(5) { flowControlWait } + flowControlContinue)
        val channel = IsoTpChannel(transport, 0x7E0, 0x7E8)

        channel.sendOnly(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 100)

        assertEquals(2, transport.sent.size)
        assertEquals(0x10, transport.sent.first().data[0].toInt() and 0xF0)
        assertEquals(0x21, transport.sent.last().data[0].toInt() and 0xFF)
    }

    @Test
    fun rejectsTheSixthIsoTpFlowControlWaitFrame() = runBlocking {
        val flowControlWait = CanFrame(0x7E8, byteArrayOf(0x31, 0x00, 0x00))
        val transport = FakeCanTransport(List(6) { flowControlWait })
        val channel = IsoTpChannel(transport, 0x7E0, 0x7E8)

        val error = runCatching {
            channel.sendOnly(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 100)
        }.exceptionOrNull()

        assertTrue(error is IsoTpException)
        assertTrue(error?.message?.contains("wait limit") == true)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun invalidIsoTpDeadlinesCannotSendAFirstCanFrame() = runBlocking {
        for (deadline in listOf(-1L, 0L, 30_001L, Long.MAX_VALUE)) {
            val transport = FakeCanTransport()
            val channel = IsoTpChannel(transport, 0x7E0, 0x7E8)
            assertTrue(runCatching {
                channel.sendOnly(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()), deadline)
            }.isFailure)
            assertTrue(runCatching {
                channel.exchange(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()), deadline)
            }.isFailure)
            assertTrue("Invalid timeout must send zero CAN frames", transport.sent.isEmpty())
        }
    }

    @Test
    fun rejectsInvalidCanIdentifiersAndPayloadLengths() {
        assertTrue(runCatching { CanFrame(0x800, byteArrayOf(1)) }.isFailure)
        assertTrue(runCatching { CanFrame(0x7E0, ByteArray(9)) }.isFailure)
        assertTrue(runCatching { CanFrame(0x1FFFFFFF, byteArrayOf(1), extendedId = true) }.isSuccess)
    }

    @Test
    fun ignoresUnrelatedCanTrafficUntilTheConfiguredResponseIdentifierArrives() = runBlocking {
        val transport = FakeCanTransport(
            listOf(
                CanFrame(0x7E9, byteArrayOf(0x05, 0x62, 0x12, 0x34, 0x11, 0x22)),
                CanFrame(0x7E8, byteArrayOf(0x05, 0x62, 0x12, 0x34, 0x33, 0x44))
            )
        )
        val client = UdsReadOnlyClient(transport, 0x7E0, 0x7E8)

        assertArrayEquals(byteArrayOf(0x33, 0x44), client.readDataByIdentifier(0x1234))
    }

    @Test
    fun rejectsPositiveReadDataResponseWhenTheEcuEchoesAnotherDid() = runBlocking {
        val transport = FakeCanTransport(
            listOf(CanFrame(0x7E8, byteArrayOf(0x05, 0x62, 0x12, 0x35, 0x55, 0x66)))
        )
        val client = UdsReadOnlyClient(transport, 0x7E0, 0x7E8)

        val error = runCatching { client.readDataByIdentifier(0x1234) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("instead of 0x1234") == true)
    }

    @Test
    fun rejectsUnexpectedPositiveUdsServiceInsteadOfTreatingItAsDidData() = runBlocking {
        val transport = FakeCanTransport(
            listOf(CanFrame(0x7E8, byteArrayOf(0x03, 0x59, 0x02, 0xFF.toByte())))
        )
        val client = UdsReadOnlyClient(transport, 0x7E0, 0x7E8)

        val error = runCatching { client.readDataByIdentifier(0x1234) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("Unexpected UDS positive service") == true)
    }

    private fun singleFrameResponse(did: Int, value: ByteArray): CanFrame {
        val payload = byteArrayOf(0x62, (did shr 8).toByte(), did.toByte()) + value
        require(payload.size <= 7)
        return CanFrame(0x7A8, byteArrayOf(payload.size.toByte()) + payload)
    }

    private fun syntheticReadProfile() = DocumentedUdsReadProfile(
        profileId = "synthetic-test-profile",
        moduleId = "BCM",
        generation = CamaroGeneration.GEN5,
        modelYears = 2012..2015,
        engineRpo = "LFX",
        controllerFamily = "BCM",
        requestCanId = 0x7A0,
        responseCanId = 0x7A8,
        extendedCanId = false,
        hardwarePartNumber = "HW1",
        operatingSystemId = "OS1",
        calibrationId = "CAL1",
        calibrationVerificationNumber = "A1B2",
        readableDids = setOf(0x0101, 0x0102, 0x0103, 0x0104, 0x0105, 0x0106, 0x0107),
        identityDids = listOf(
            UdsIdentityDidDefinition(UdsIdentityField.CONTROLLER_FAMILY, 0x0101, UdsIdentityEncoding.ASCII),
            UdsIdentityDidDefinition(UdsIdentityField.HARDWARE_PART_NUMBER, 0x0102, UdsIdentityEncoding.ASCII),
            UdsIdentityDidDefinition(UdsIdentityField.OPERATING_SYSTEM_ID, 0x0103, UdsIdentityEncoding.ASCII),
            UdsIdentityDidDefinition(UdsIdentityField.CALIBRATION_ID, 0x0104, UdsIdentityEncoding.ASCII),
            UdsIdentityDidDefinition(UdsIdentityField.CALIBRATION_VERIFICATION_NUMBER, 0x0105, UdsIdentityEncoding.HEX),
            UdsIdentityDidDefinition(UdsIdentityField.VIN, 0x0106, UdsIdentityEncoding.ASCII)
        ),
        verifiedVin = "1G1FA1RXXR0123456",
        evidenceReference = "Synthetic unit-test fixture; not a real vehicle definition"
    )
}
