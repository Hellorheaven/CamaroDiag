package com.camarodiag.gen5.data.obd

import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISO 14229 memory-service regression tests use exact UDS payload bytes.
 * No transport or physical ECU is programmed by these tests.
 */
class UdsMemoryWireFormatTest {
    private class ScriptedPayloadTransport(vararg answers: ByteArray) : UdsPayloadTransport {
        val sent = mutableListOf<ByteArray>()
        private val pending = ArrayDeque(answers.toList())
        override suspend fun exchange(payload: ByteArray, timeoutMs: Long): ByteArray {
            sent += payload.copyOf()
            return pending.removeFirst().copyOf()
        }
        override suspend fun sendOnly(payload: ByteArray, timeoutMs: Long) {
            sent += payload.copyOf()
        }
    }

    private fun policy(allowWrite: Boolean = false): UdsAccessPolicy {
        val vin = "1G1FA1RXXR0123456"
        val id = UdsEcuIdentity("ECM", "HW-1", "OS-1", "CAL-1", "A1B2", vin)
        return UdsAccessPolicy.verified(
            vehicleVin = vin,
            identity = id,
            expectedVin = vin,
            expectedCalId = id.calibrationId!!,
            expectedCvn = id.calibrationVerificationNumber!!,
            expectedControllerFamily = id.controllerFamily!!,
            expectedHardwarePartNumber = id.hardwarePartNumber!!,
            expectedOperatingSystemId = id.operatingSystemId!!,
            readDids = setOf(0xF190),
            writeDids = emptySet(),
            allowedSessions = setOf(0x01, 0x02),
            routineControlPermissions = emptyMap(),
            allowedSecurityLevels = if (allowWrite) setOf(0x01) else emptySet(),
            readableMemoryRegions = listOf(UdsMemoryRegion(0x1000, 0x100)),
            writableMemoryRegions = if (allowWrite)
                listOf(UdsMemoryRegion(0x1000, 0x100)) else emptyList(),
            maxTransferBytes = 128,
            requireSecurityAccess = allowWrite,
            evidenceReference = "Unit-test synthetic controller"
        )
    }

    private fun bytes(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()

    private suspend fun unlockForSyntheticWrite(client: UdsClient) {
        assertArrayEquals(bytes(0x12, 0x34), client.requestSecuritySeed(0x01))
        client.submitSecurityKey(0x02, bytes(0xAB, 0xCD))
    }

    @Test fun readMemoryEncodesSizeWidthHighAndAddressWidthLow() = runBlocking {
        val transport = ScriptedPayloadTransport(bytes(0x50, 0x02), bytes(0x63, 0x11, 0x22, 0x33))
        val uds = UdsClient(transport, policy())
        uds.diagnosticSessionControl(0x02)
        val data = uds.readMemoryByAddressAndLength(0x1000, 3, addressLength = 4, sizeLength = 2)
        assertArrayEquals(bytes(0x11, 0x22, 0x33), data)
        assertArrayEquals(bytes(0x23, 0x24, 0x00, 0x00, 0x10, 0x00, 0x00, 0x03), transport.sent[1])
    }

    @Test fun memoryReadRejectsTruncatedPhysicalResponse() = runBlocking {
        val transport = ScriptedPayloadTransport(bytes(0x50, 0x02), bytes(0x63, 0x11, 0x22))
        val uds = UdsClient(transport, policy())
        uds.diagnosticSessionControl(0x02)
        assertTrue(runCatching { uds.readMemoryByAddressAndLength(0x1000, 3) }.isFailure)
    }

    @Test fun memoryWriteUsesSameAlfiEncoding() = runBlocking {
        val transport = ScriptedPayloadTransport(
            bytes(0x50, 0x02), bytes(0x67, 0x01, 0x12, 0x34),
            bytes(0x67, 0x02), bytes(0x7D))
        val uds = UdsClient(transport, policy(allowWrite = true))
        uds.diagnosticSessionControl(0x02)
        unlockForSyntheticWrite(uds)
        uds.writeMemoryByAddressAndLength(0x1000, bytes(0xA5, 0x5A), addressLength = 4, sizeLength = 2)
        assertArrayEquals(bytes(0x3D, 0x24, 0, 0, 0x10, 0, 0, 2, 0xA5, 0x5A), transport.sent[3])
    }

    @Test fun requestDownloadAndTransferDecodeHighNibbleBlockLength() = runBlocking {
        val transport = ScriptedPayloadTransport(
            bytes(0x50, 0x02), bytes(0x67, 0x01, 0x12, 0x34),
            bytes(0x67, 0x02), bytes(0x74, 0x20, 0x00, 0x20),
            bytes(0x76, 0x01), bytes(0x77)
        )
        val uds = UdsClient(transport, policy(allowWrite = true))
        uds.diagnosticSessionControl(0x02)
        unlockForSyntheticWrite(uds)
        uds.requestDownload(0x1000, 4, addressLength = 4, sizeLength = 2)
        assertTrue(uds.isDownloadActive())
        uds.transferData(bytes(1, 2, 3, 4))
        uds.requestTransferExit()
        assertFalse(uds.isDownloadActive())
        assertArrayEquals(bytes(0x34, 0, 0x24, 0, 0, 0x10, 0, 0, 4), transport.sent[3])
        assertArrayEquals(bytes(0x36, 1, 1, 2, 3, 4), transport.sent[4])
        assertEquals(6, transport.sent.size)
    }

    @Test fun requestUploadDecodesHighNibbleLengthAndReadsCompleteBlock() = runBlocking {
        val transport = ScriptedPayloadTransport(
            bytes(0x50, 0x02), bytes(0x75, 0x20, 0x00, 0x20),
            bytes(0x76, 0x01, 0x91, 0x92, 0x93, 0x94), bytes(0x77)
        )
        val uds = UdsClient(transport, policy())
        uds.diagnosticSessionControl(0x02)
        uds.requestUpload(0x1000, 4, addressLength = 4, sizeLength = 2)
        assertArrayEquals(bytes(0x91, 0x92, 0x93, 0x94), uds.requestUploadData())
        uds.requestTransferExit()
        assertArrayEquals(bytes(0x35, 0, 0x24, 0, 0, 0x10, 0, 0, 4), transport.sent[1])
        assertArrayEquals(bytes(0x36, 1), transport.sent[2])
    }

    @Test fun downloadRejectsAppendedOrTruncatedBlockLengthBeforeTransfer() = runBlocking {
        for (invalid in listOf(
                bytes(0x74, 0x20, 0x00),
                bytes(0x74, 0x20, 0x00, 0x20, 0xAA),
                bytes(0x74, 0x10, 0x20, 0xAA)
            )) {
            val transport = ScriptedPayloadTransport(
                bytes(0x50, 0x02), bytes(0x67, 0x01, 0x12, 0x34),
                bytes(0x67, 0x02), invalid
            )
            val client = UdsClient(transport, policy(allowWrite = true))
            client.diagnosticSessionControl(0x02)
            unlockForSyntheticWrite(client)
            assertTrue(runCatching { client.requestDownload(0x1000, 4) }.isFailure)
            assertFalse(client.isDownloadActive())
            assertEquals(4, transport.sent.size)
        }
    }

    @Test fun uploadRejectsUnexpectedBlockLengthFieldsBeforeReadingData() = runBlocking {
        for (invalid in listOf(
                bytes(0x75, 0x20, 0x00),
                bytes(0x75, 0x20, 0x00, 0x20, 0xAA)
            )) {
            val transport = ScriptedPayloadTransport(bytes(0x50, 0x02), invalid)
            val client = UdsClient(transport, policy())
            client.diagnosticSessionControl(0x02)
            assertTrue(runCatching { client.requestUpload(0x1000, 4) }.isFailure)
            assertFalse(client.isUploadActive())
            assertEquals(2, transport.sent.size)
        }
    }

    @Test fun requestDownloadRejectsReservedLengthFormatBits() = runBlocking {
        val transport = ScriptedPayloadTransport(
            bytes(0x50, 0x02), bytes(0x67, 0x01, 0x12, 0x34),
            bytes(0x67, 0x02), bytes(0x74, 0x02, 0x00, 0x20)
        )
        val uds = UdsClient(transport, policy(allowWrite = true))
        uds.diagnosticSessionControl(0x02)
        unlockForSyntheticWrite(uds)
        assertTrue(runCatching {
            uds.requestDownload(0x1000, 4, addressLength = 4, sizeLength = 2)
        }.isFailure)
        assertFalse(uds.isDownloadActive())
    }
}
