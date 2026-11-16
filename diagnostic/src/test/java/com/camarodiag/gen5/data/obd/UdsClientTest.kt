package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class UdsClientTest {
    private class FakeTransport(frames: List<CanFrame>) : RawCanFrameTransport {
        val sent = mutableListOf<CanFrame>()
        private val incoming = ArrayDeque(frames)
        override suspend fun send(frame: CanFrame) { sent += frame }
        override suspend fun receive(timeoutMs: Long): CanFrame? =
            if (incoming.isEmpty()) null else incoming.removeFirst()
    }

    private fun response(vararg payload: Int) =
        CanFrame(0x7E8, byteArrayOf(payload.size.toByte()) + payload.map { it.toByte() }.toByteArray())

    private fun verifiedPolicy(
        writeDids: Set<Int> = emptySet(),
        sessions: Set<Int> = setOf(0x01, 0x02),
        memoryRegions: List<UdsMemoryRegion> = emptyList(),
        maxTransferBytes: Int = 0,
        requireSecurity: Boolean = true,
        allowClear: Boolean = false,
        ioDids: Set<Int> = emptySet()
    ): UdsAccessPolicy {
        val identity = UdsEcuIdentity("ECM", "HW-1", "OS-1", "CAL-1", "A1B2", "1G1FA1RXXR0123456")
        return UdsAccessPolicy.verified(
            vehicleVin = "1G1FA1RXXR0123456",
            identity = identity,
            expectedVin = identity.vin!!,
            expectedCalId = "CAL-1",
            expectedCvn = "A1B2",
            expectedControllerFamily = "ECM",
            expectedHardwarePartNumber = "HW-1",
            expectedOperatingSystemId = "OS-1",
            readDids = setOf(0xF190, 0x1234),
            writeDids = writeDids,
            allowedSessions = sessions,
            routineControlPermissions = emptyMap(),
            ioControlPermissions = ioDids.associateWith { setOf(0x03) },
            allowedSecurityLevels = setOf(0x01),
            allowedDtcGroups = if (allowClear) setOf(0xFFFFFF) else emptySet(),
            memoryRegions = memoryRegions,
            maxTransferBytes = maxTransferBytes,
            requireSecurityAccess = requireSecurity,
            evidenceReference = "Synthetic unit-test policy"
        )
    }

    @Test fun sessionControlAndTesterPresentValidatePositiveResponses() = runBlocking {
        val transport = FakeTransport(listOf(response(0x50, 0x02, 0x00, 0x32), response(0x7E, 0x00)))
        val client = UdsClient(transport, 0x7E0, 0x7E8, policy = verifiedPolicy())

        val timing = client.diagnosticSessionControl(0x02)
        client.testerPresent()

        assertArrayEquals(byteArrayOf(0x00, 0x32), timing)
        assertEquals(0x02, client.activeDiagnosticSession())
        assertEquals(0x10, transport.sent[0].data[1].toInt() and 0xFF)
        assertEquals(0x3E, transport.sent[1].data[1].toInt() and 0xFF)
    }

    @Test fun suppressPositiveResponseTesterPresentDoesNotWaitForAResponse() = runBlocking {
        val transport = FakeTransport(emptyList())
        val client = UdsClient(transport, 0x7E0, 0x7E8)

        client.testerPresent(suppressPositiveResponse = true)

        assertArrayEquals(byteArrayOf(0x02.toByte(), 0x3E.toByte(), 0x80.toByte()), transport.sent.single().data.copyOf(3))
    }

    @Test fun waitsForResponsePendingBeforeReturningFinalPositiveResponse() = runBlocking {
        val transport = FakeTransport(listOf(
            response(0x7F, 0x22, 0x78),
            response(0x62, 0x12, 0x34, 0x55)
        ))
        val client = UdsClient(transport, 0x7E0, 0x7E8)

        assertArrayEquals(byteArrayOf(0x55), client.readDataByIdentifier(0x1234))
        assertEquals(1, transport.sent.size)
    }

    @Test fun refusesWriteWithDefaultReadOnlyPolicyBeforeSendingAnything() = runBlocking {
        val transport = FakeTransport(emptyList())
        val client = UdsClient(transport, 0x7E0, 0x7E8)

        val error = runCatching { client.writeDataByIdentifier(0x1234, byteArrayOf(0x01)) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun writeDidRequiresSessionSecurityAndAllowlistedDid() = runBlocking {
        val transport = FakeTransport(listOf(
            response(0x50, 0x02),
            response(0x67, 0x01, 0x12, 0x34),
            response(0x67, 0x02),
            response(0x6E, 0x12, 0x34)
        ))
        val client = UdsClient(transport, 0x7E0, 0x7E8, policy = verifiedPolicy(writeDids = setOf(0x1234)))

        assertTrue(runCatching { client.writeDataByIdentifier(0x1234, byteArrayOf(0x01)) }.isFailure)
        client.diagnosticSessionControl(0x02)
        assertArrayEquals(byteArrayOf(0x12, 0x34), client.requestSecuritySeed(0x01))
        client.submitSecurityKey(0x02, byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
        client.writeDataByIdentifier(0x1234, byteArrayOf(0x55))

        assertTrue(client.isSecurityUnlocked())
        assertEquals(4, transport.sent.size)
        assertArrayEquals(byteArrayOf(0x04, 0x2E, 0x12, 0x34, 0x55), transport.sent.last().data.copyOf(5))
    }

    @Test fun refusesDtcClearUnlessExplicitlyAllowed() = runBlocking {
        val transport = FakeTransport(emptyList())
        val client = UdsClient(transport, 0x7E0, 0x7E8)

        assertTrue(runCatching { client.clearDiagnosticInformation() }.isFailure)
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun parsesDtcRecordsUsingAvailabilityMaskAndFourByteRecords() = runBlocking {
        val transport = FakeTransport(listOf(response(0x59, 0x02, 0x05, 0x12, 0x34, 0x56, 0x09)))
        val client = UdsClient(transport, 0x7E0, 0x7E8)

        val dtcs = client.readDtcByStatusMask()

        assertEquals(1, dtcs.size)
        assertEquals(0x123456, dtcs.single().dtc)
        assertEquals(0x09, dtcs.single().status)
    }

    @Test fun memoryAccessMustStayInsideVerifiedRegion() = runBlocking {
        val transport = FakeTransport(emptyList())
        val policy = verifiedPolicy(memoryRegions = listOf(UdsMemoryRegion(0x1000, 0x100)), requireSecurity = false)
        val client = UdsClient(transport, 0x7E0, 0x7E8, policy = policy)

        assertTrue(runCatching { client.readMemoryByAddressAndLength(0x0FFF, 4) }.isFailure)
        assertTrue(runCatching { client.readMemoryByAddressAndLength(0x10F0, 0x20) }.isFailure)
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun inputOutputControlRequiresAnExplicitDidAllowlist() = runBlocking {
        val transport = FakeTransport(emptyList())
        val client = UdsClient(transport, 0x7E0, 0x7E8)

        assertTrue(runCatching { client.inputOutputControlByIdentifier(0xF100, 0x03) }.isFailure)
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun rejectsInvalidMemoryRegionAndUnverifiedGrant() {
        assertTrue(runCatching { UdsMemoryRegion(-1, 4) }.isFailure)
        val identity = UdsEcuIdentity("ECM", "HW-1", "OS-1", "CAL-1", "A1B2", "1G1FA1RXXR0123456")
        assertTrue(runCatching {
            UdsAccessPolicy.verified(
                vehicleVin = "1G1FA1RXXR0123456",
                identity = identity,
                expectedVin = "1G1FA1RXXR0123456",
                expectedCalId = "CAL-1",
                expectedCvn = "FFFF",
                expectedControllerFamily = "ECM",
                expectedHardwarePartNumber = "HW-1",
                expectedOperatingSystemId = "OS-1",
                readDids = emptySet(),
                writeDids = setOf(0x1234),
                allowedSessions = setOf(1),
                routineControlPermissions = emptyMap(),
                memoryRegions = emptyList(),
                maxTransferBytes = 0,
                requireSecurityAccess = true,
                evidenceReference = "synthetic"
            )
        }.isFailure)
    }
}
