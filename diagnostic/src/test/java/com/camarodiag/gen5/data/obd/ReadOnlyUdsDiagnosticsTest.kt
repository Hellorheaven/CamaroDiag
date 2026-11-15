package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.CamaroEngine
import com.camarodiag.gen5.domain.model.CamaroGeneration
import com.camarodiag.gen5.domain.model.VehicleProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

        val vin = client.readVin()

        assertEquals("1G1FA1RXXR0123456", vin)
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
    fun readsUdsDtcInformationWithoutSendingAnyClearRequest() = runBlocking {
        val transport = FakeCanTransport(
            listOf(CanFrame(0x7E8, byteArrayOf(0x05, 0x59, 0x02, 0xFF.toByte(), 0x01, 0x02)))
        )
        val client = UdsReadOnlyClient(transport, 0x7E0, 0x7E8)

        val data = client.readDtcInformation()

        assertArrayEquals(byteArrayOf(0x02, 0xFF.toByte(), 0x01, 0x02), data)
        assertArrayEquals(byteArrayOf(0x03, 0x19, 0x02, 0xFF.toByte()), transport.sent.single().data)
    }

    @Test
    fun refusesReadProfileUnlessVehicleAndAllEcuIdentifiersMatch() {
        val profile = DocumentedUdsReadProfile(
            profileId = "camaro-gen5-lfx-bcm-example",
            moduleId = "BCM",
            generation = CamaroGeneration.GEN5,
            modelYears = 2012..2015,
            engineRpo = "LFX",
            controllerFamily = "BCM-family-example",
            requestCanId = 0x7A0,
            responseCanId = 0x7A8,
            extendedCanId = false,
            hardwarePartNumber = "HW-123",
            operatingSystemId = "OS-123",
            calibrationId = "CAL-123",
            calibrationVerificationNumber = "CVN-123",
            readableDids = setOf(0xF190),
            verifiedVin = "1G1FA1RXXR0123456",
            evidenceReference = "GM service document reference supplied for this exact vehicle"
        )
        val vehicle = VehicleProfile(modelYear = 2013, engine = CamaroEngine.LFX, vin = "1G1FA1RXXR0123456")
        val matching = UdsEcuIdentity("BCM-family-example", "HW-123", "OS-123", "CAL-123", "CVN-123", "1G1FA1RXXR0123456")
        val mismatching = matching.copy(calibrationVerificationNumber = "OTHER-CVN")

        assertTrue(profile.compatibilityIssues(vehicle, matching).isEmpty())
        assertTrue(profile.compatibilityIssues(vehicle, mismatching).any { it.contains("CVN") })
        assertTrue(profile.allowsDid(0xF190))
        assertTrue(!profile.allowsDid(0xF187))
    }

    @Test
    fun rejectsInvalidCanIdentifiersAndPayloadLengths() {
        assertTrue(runCatching { CanFrame(0x800, byteArrayOf(1)) }.isFailure)
        assertTrue(runCatching { CanFrame(0x7E0, ByteArray(9)) }.isFailure)
        assertTrue(runCatching { CanFrame(0x1FFFFFFF, byteArrayOf(1), extendedId = true) }.isSuccess)
    }
}
