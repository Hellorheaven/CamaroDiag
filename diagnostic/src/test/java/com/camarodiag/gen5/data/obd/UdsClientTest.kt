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
            readableMemoryRegions = memoryRegions,
            writableMemoryRegions = emptyList(),
            maxTransferBytes = maxTransferBytes,
            requireSecurityAccess = requireSecurity,
            evidenceReference = "Synthetic unit-test policy"
        )
    }

    @Test fun callerCannotExpandVerifiedWritePermissionsOrSessionsAfterAdmission() = runBlocking {
        val writes = mutableSetOf(0x1234)
        val sessions = mutableSetOf(0x01, 0x02)
        val policy = verifiedPolicy(writeDids = writes, sessions = sessions)
        writes.add(0x5678)
        sessions.add(0x03)
        assertEquals(setOf(0x1234), policy.writeDids)
        assertEquals(setOf(0x01, 0x02), policy.allowedSessions)
        assertTrue(runCatching {
            (policy.writeDids as MutableSet<Int>).add(0x5678)
        }.isFailure)
        val transport = FakeTransport(emptyList())
        val client = UdsClient(transport, 0x7E0, 0x7E8, policy = policy)
        assertTrue(runCatching {
            client.writeDataByIdentifier(0x5678, byteArrayOf(0x01))
        }.isFailure)
        assertTrue(runCatching { client.diagnosticSessionControl(0x03) }.isFailure)
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun readOnlyDidAllowlistIsAnImmutableSnapshot() = runBlocking {
        val source = mutableSetOf(0xF190)
        val policy = UdsAccessPolicy.readOnly(source)
        source.add(0x1234)
        assertEquals(setOf(0xF190), policy.readDids)
        assertTrue(runCatching { (policy.readDids as MutableSet<Int>).add(0x1234) }.isFailure)
        val transport = FakeTransport(emptyList())
        val client = UdsClient(transport, 0x7E0, 0x7E8, policy = policy)
        assertTrue(runCatching { client.readDataByIdentifier(0x1234) }.isFailure)
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun nestedRoutineAndIoPermissionsRemainFrozenAfterCallerMutation() {
        val allowedRoutineTypes = mutableSetOf(0x01)
        val allowedIoTypes = mutableSetOf(0x03)
        val routines = mutableMapOf<Int, Set<Int>>(0x9000 to allowedRoutineTypes)
        val ioControls = mutableMapOf<Int, Set<Int>>(0xF100 to allowedIoTypes)
        val identity = UdsEcuIdentity("ECM", "HW-1", "OS-1", "CAL-1",
            "A1B2", "1G1FA1RXXR0123456")
        val policy = UdsAccessPolicy.verified(
            vehicleVin = identity.vin!!, identity = identity,
            expectedVin = identity.vin!!, expectedCalId = "CAL-1",
            expectedCvn = "A1B2", expectedControllerFamily = "ECM",
            expectedHardwarePartNumber = "HW-1", expectedOperatingSystemId = "OS-1",
            readDids = emptySet(), writeDids = emptySet(),
            allowedSessions = setOf(0x01, 0x02),
            routineControlPermissions = routines, ioControlPermissions = ioControls,
            readableMemoryRegions = emptyList(), writableMemoryRegions = emptyList(),
            maxTransferBytes = 0, requireSecurityAccess = true,
            evidenceReference = "Synthetic identity-bound policy test"
        )
        allowedRoutineTypes.add(0x02)
        allowedIoTypes.add(0x04)
        routines[0x9001] = setOf(0x01)
        ioControls[0xF101] = setOf(0x03)
        assertEquals(mapOf(0x9000 to setOf(0x01)), policy.routineControlPermissions)
        assertEquals(mapOf(0xF100 to setOf(0x03)), policy.ioControlPermissions)
        assertTrue(runCatching {
            (policy.ioControlPermissions[0xF100] as MutableSet<Int>).add(0x04)
        }.isFailure)
        assertTrue(runCatching {
            (policy.routineControlPermissions as MutableMap<Int, Set<Int>>)[0x9002] =
                setOf(0x01)
        }.isFailure)
    }

    @Test fun placeholderIdentityCannotCreateVerifiedProgrammingGrants() {
        val vin = "1G1FA1RXXR0123456"
        fun grant(cvn: String, hardware: String, os: String): UdsAccessPolicy {
            val identity = UdsEcuIdentity("ECM", hardware, os, "CAL-1", cvn, vin)
            return UdsAccessPolicy.verified(
                vehicleVin = vin, identity = identity, expectedVin = vin,
                expectedCalId = "CAL-1", expectedCvn = cvn,
                expectedControllerFamily = "ECM", expectedHardwarePartNumber = hardware,
                expectedOperatingSystemId = os,
                readDids = emptySet(), writeDids = setOf(0x1234),
                allowedSessions = setOf(0x01, 0x02),
                routineControlPermissions = emptyMap(),
                readableMemoryRegions = emptyList(), writableMemoryRegions = emptyList(),
                maxTransferBytes = 0, requireSecurityAccess = true,
                evidenceReference = "Synthetic ECU identity fail-closed regression"
            )
        }
        for (placeholder in listOf(
                "UNKNOWN", "FFFFFFFF", "00000000", "PENDING", "N/A", "NO DATA",
                "TEST", "SIMULATED"
            )) {
            assertTrue("Rejected CVN: $placeholder", runCatching {
                grant(placeholder, "HW-1", "OS-1")
            }.isFailure)
        }
        assertTrue(runCatching { grant("A1B2", "00000000", "OS-1") }.isFailure)
        assertTrue(runCatching { grant("A1B2", "HW-1", "PENDING") }.isFailure)
        assertTrue(runCatching { grant("A1B2", "HW-1", "OS-1") }.isSuccess)
    }

    @Test fun sessionControlAndTesterPresentValidatePositiveResponses() = runBlocking {
        val transport = FakeTransport(listOf(
            response(0x50, 0x02, 0x00, 0x32, 0x01, 0xF4),
            response(0x7E, 0x00)
        ))
        val client = UdsClient(transport, 0x7E0, 0x7E8, policy = verifiedPolicy())

        val timing = client.diagnosticSessionControl(0x02)
        client.testerPresent()

        assertArrayEquals(byteArrayOf(0x00, 0x32, 0x01, 0xF4.toByte()), timing)
        assertEquals(0x02, client.activeDiagnosticSession())
        assertEquals(0x10, transport.sent[0].data[1].toInt() and 0xFF)
        assertEquals(0x3E, transport.sent[1].data[1].toInt() and 0xFF)
    }

    @Test fun invalidSessionAcknowledgementCannotEnterProgrammingState() = runBlocking {
        for (ack in listOf(
                response(0x50),
                response(0x50, 0x02, 0x00),
                response(0x50, 0x02, 0x00, 0x32),
                response(0x50, 0x02, 0x00, 0x32, 0x01, 0xF4, 0x00),
                response(0x50, 0x82)
            )) {
            val transport = FakeTransport(listOf(ack))
            val client = UdsClient(transport, 0x7E0, 0x7E8,
                policy = verifiedPolicy(writeDids = setOf(0x1234)))
            assertTrue(runCatching { client.diagnosticSessionControl(0x02) }.isFailure)
            assertEquals(0x01, client.activeDiagnosticSession())
            assertFalse(client.isSecurityUnlocked())
            assertTrue(runCatching {
                client.writeDataByIdentifier(0x1234, byteArrayOf(0x01))
            }.isFailure)
            assertEquals(1, transport.sent.size)
        }
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

    @Test fun parsesDtcRecordsUsingAvailabilityMaskFormatIdentifierAndFourByteRecords() = runBlocking {
        val transport = FakeTransport(
            listOf(
                CanFrame(0x7E8, byteArrayOf(0x10, 0x08, 0x59, 0x02, 0x05, 0x00, 0x12, 0x34)),
                CanFrame(0x7E8, byteArrayOf(0x21, 0x56, 0x09, 0x00, 0x00, 0x00, 0x00, 0x00))
            )
        )
        val client = UdsClient(transport, 0x7E0, 0x7E8)

        val dtcs = client.readDtcByStatusMask()

        assertEquals(1, dtcs.size)
        assertEquals(0x123456, dtcs.single().dtc)
        assertEquals(0x09, dtcs.single().status)
        assertEquals(6, dtcs.single().raw.size)
        assertEquals(0x05, dtcs.single().raw[0].toInt() and 0xFF)
        assertEquals(0x00, dtcs.single().raw[1].toInt() and 0xFF)
        assertTrue(transport.sent.any { (it.data[0].toInt() and 0xF0) == 0x30 })
    }

    @Test
    fun exposesDtcFormatIdentifierAndSaEFormattedCode() = runBlocking {
        val transport = FakeTransport(
            listOf(
                CanFrame(0x7E8, byteArrayOf(0x10, 0x08, 0x59, 0x02, 0xFF.toByte(), 0x00, 0x01, 0x01)),
                CanFrame(0x7E8, byteArrayOf(0x21, 0x01, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00))
            )
        )
        val client = UdsClient(transport, 0x7E0, 0x7E8)

        val report = client.readDtcReport()

        assertEquals(0xFF, report.statusAvailabilityMask)
        assertEquals(0x00, report.formatIdentifier)
        assertEquals(UdsDtcFormat.SAE_J2012_DA_FORMAT_00, report.format)
        assertEquals("P0101-01", report.records.single().code)
        assertTrue(report.records.single().status.confirmed)
        assertTrue(transport.sent.any { (it.data[0].toInt() and 0xF0) == 0x30 })
    }

    @Test fun memoryAccessMustStayInsideVerifiedRegion() = runBlocking {
        val transport = FakeTransport(emptyList())
        val policy = verifiedPolicy(memoryRegions = listOf(UdsMemoryRegion(0x1000, 0x100)), maxTransferBytes = 0x100, requireSecurity = false)
        val client = UdsClient(transport, 0x7E0, 0x7E8, policy = policy)

        assertTrue(runCatching { client.readMemoryByAddressAndLength(0x0FFF, 4) }.isFailure)
        assertTrue(runCatching { client.readMemoryByAddressAndLength(0x10F0, 0x20) }.isFailure)
        assertTrue(runCatching { client.writeMemoryByAddressAndLength(0x1000, byteArrayOf(0x01)) }.isFailure)
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
                readableMemoryRegions = emptyList(),
                writableMemoryRegions = emptyList(),
                maxTransferBytes = 0,
                requireSecurityAccess = true,
                evidenceReference = "synthetic"
            )
        }.isFailure)
    }
}
