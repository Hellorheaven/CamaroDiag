package com.camarodiag.gen5.data.obd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class J2534LiveMemoryBackupTest {
    private val vin = "1G1FA1RXXR0123456"
    private val baseline = ByteArray(300) { (it and 0xFF).toByte() }

    private fun request(
        vinOverride: String = vin,
        session: Int = 1,
        length: Int = baseline.size
    ) = J2534LiveMemoryBackup.Request(
        expectedVin = vinOverride,
        region = EcuBackupCoverage.Region.CALIBRATION,
        address = 0x1000L,
        lengthBytes = length,
        chunkBytes = 128,
        diagnosticSession = session,
        controllerLabel = "Unit-test ECU"
    )

    private class ScriptedEcu(
        private val memory: ByteArray,
        private val vin: String,
        private val alterSecondPass: Boolean = false
    ) : UdsPayloadTransport {
        var reads = 0
        val sent = mutableListOf<ByteArray>()
        override suspend fun exchange(payload: ByteArray, timeoutMs: Long): ByteArray {
            sent += payload.copyOf()
            return when (payload[0].toInt() and 0xFF) {
                0x22 -> byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte()) +
                    vin.toByteArray(Charsets.US_ASCII)
                0x10 -> byteArrayOf(0x50, payload[1])
                0x3E -> byteArrayOf(0x7E, 0x00)
                0x23 -> {
                    require(payload.size == 8 && payload[1] == 0x24.toByte())
                    val address = ((payload[2].toLong() and 0xFF) shl 24) or
                        ((payload[3].toLong() and 0xFF) shl 16) or
                        ((payload[4].toLong() and 0xFF) shl 8) or
                        (payload[5].toLong() and 0xFF)
                    val offset = (address - 0x1000L).toInt()
                    val length = ((payload[6].toInt() and 0xFF) shl 8) or
                        (payload[7].toInt() and 0xFF)
                    require(offset >= 0 && offset + length <= memory.size)
                    val body = memory.copyOfRange(offset, offset + length)
                    if (alterSecondPass && reads >= 3) body[0] = (body[0] + 1).toByte()
                    reads++
                    byteArrayOf(0x63) + body
                }
                else -> error("Unexpected UDS service")
            }
        }
        override suspend fun sendOnly(payload: ByteArray, timeoutMs: Long) =
            error("Unexpected sendOnly")
    }

    @Test fun twoIndependentReadPassesBecomeAConsistentOfflineArchive() = runBlocking {
        val fake = ScriptedEcu(baseline, vin)
        val result = J2534LiveMemoryBackup.captureForTest(fake, request())
        assertArrayEquals(baseline, result.capture.firstRead)
        assertArrayEquals(baseline, result.capture.independentRead)
        assertTrue(result.verification.consistent)
        assertFalse(result.manifest.hardwareProvenanceVerified)
        assertEquals(EcuBackupManifest.Origin.SIMULATION, result.capture.origin)
        assertEquals(6, fake.reads)
        val output = ByteArrayOutputStream()
        EcuBackupArchive.export(output, listOf(result.capture),
            setOf(EcuBackupCoverage.Region.CALIBRATION))
        assertTrue(EcuBackupArchive.import(ByteArrayInputStream(output.toByteArray())).verification.consistent)
    }

    @Test fun aDifferentLiveVinStopsBeforeAnyMemoryRequest() = runBlocking {
        val fake = ScriptedEcu(baseline, vin)
        val invalid = request("1G1FA1RXXR0123457")
        assertTrue(runCatching { J2534LiveMemoryBackup.captureForTest(fake, invalid) }.isFailure)
        assertEquals(0, fake.reads)
    }

    @Test fun divergentSecondPassRejectsTheEntireCapture() = runBlocking {
        val fake = ScriptedEcu(baseline, vin, alterSecondPass = true)
        assertTrue(runCatching {
            J2534LiveMemoryBackup.captureForTest(fake, request())
        }.isFailure)
        assertEquals(6, fake.reads)
    }

    @Test fun extendedReadSessionIsClosedAfterDoubleCapture() = runBlocking {
        val fake = ScriptedEcu(baseline, vin)
        J2534LiveMemoryBackup.captureForTest(fake, request(session = 3))
        val sessions = fake.sent.filter { it[0] == 0x10.toByte() }.map { it[1].toInt() and 0xFF }
        assertEquals(listOf(3, 1), sessions)
        assertEquals(6, fake.reads)
    }

    @Test fun illegalMemoryRangesFailBeforeSendingAnyHardwareCommand() = runBlocking {
        val fake = ScriptedEcu(baseline, vin)
        assertTrue(runCatching { J2534LiveMemoryBackup.captureForTest(
            fake, request().copy(address = 0xFFFFFFFFL)
        ) }.isFailure)
        assertTrue(runCatching { J2534LiveMemoryBackup.captureForTest(
            fake, request().copy(chunkBytes = 512)
        ) }.isFailure)
        assertTrue(runCatching { J2534LiveMemoryBackup.captureForTest(
            fake, request().copy(diagnosticSession = 2)
        ) }.isFailure)
        assertTrue(fake.sent.isEmpty())
    }
}
