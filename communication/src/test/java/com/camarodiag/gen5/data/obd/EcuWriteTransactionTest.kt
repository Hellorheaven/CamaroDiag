package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuWriteTransactionTest {
    private val original = ByteArray(16) { it.toByte() }
    private val expectedIdentity = EcuProgrammingDriverContract.Identity(
        controllerId = "E39-test", firmwareId = "firmware-test",
        vin = "2G1TEST0000000001"
    )
    private val originalLayout = EcuPartialWritePlanner.Layout(
        regionStartAddress = 0x1000L, regionSize = 16L,
        eraseBlockBytes = 8, programBlockBytes = 2,
        mapVerifiedForTargetEcu = true
    )

    private fun request(
        updated: ByteArray = original.copyOf().also { it[2] = 100 },
        evidence: EcuWriteTransaction.SafetyEvidence = EcuWriteTransaction.SafetyEvidence(
            oemChecksumValidated = true,
            independentHardwareBackupVerified = true,
            recoveryProcedureValidated = true
        )
    ) = EcuWriteTransaction.Request(
        controllerFamily = "E39", transport = EcuProgrammingDriverContract.Transport.J2534,
        expectedIdentity = expectedIdentity, region = EcuBackupCoverage.Region.CALIBRATION,
        layout = originalLayout, originalImage = original.copyOf(),
        updatedImage = updated, evidence = evidence
    )

    private class FakeDriver(
        initial: ByteArray,
        private val identity: EcuProgrammingDriverContract.Identity,
        validated: Boolean = true
    ) : EcuProgrammingDriverContract {
        val memory = initial.copyOf()
        var eraseCalls = 0
        var writeCalls = 0
        var resetCalls = 0
        var corruptAfterWrite = false
        var failOnErase = false
        override val descriptor = EcuProgrammingDriverContract.Descriptor(
            id = "in-memory-test-only",
            transport = EcuProgrammingDriverContract.Transport.J2534,
            capabilities = EcuProgrammingDriverContract.Capability.values().toSet(),
            controllerFamilies = setOf("E39"),
            physicalHardwareValidated = validated
        )

        private fun start(request: EcuProgrammingDriverContract.RegionRequest): Int {
            val start = (request.address - 0x1000L).toInt()
            require(start >= 0 && request.length >= 0 && start + request.length <= memory.size)
            return start
        }

        override suspend fun identify() = identity
        override suspend fun read(request: EcuProgrammingDriverContract.RegionRequest): ByteArray {
            val start = start(request)
            return memory.copyOfRange(start, start + request.length.toInt())
        }
        override suspend fun erase(request: EcuProgrammingDriverContract.RegionRequest) {
            if (failOnErase) error("Lost connection before erasure")
            val start = start(request)
            memory.fill(0.toByte(), start, start + request.length.toInt())
            eraseCalls++
        }
        override suspend fun write(
            request: EcuProgrammingDriverContract.RegionRequest,
            bytes: ByteArray
        ) {
            require(bytes.size.toLong() == request.length)
            bytes.copyInto(memory, destinationOffset = start(request))
            writeCalls++
        }
        override suspend fun independentReadback(
            request: EcuProgrammingDriverContract.RegionRequest
        ): ByteArray {
            val result = read(request)
            if (corruptAfterWrite && writeCalls > 0) result[0] = (result[0] + 1).toByte()
            return result
        }
        override suspend fun resetController() {
            resetCalls++
        }
    }

    private fun journal(records: MutableList<EcuWriteTransaction.Record>) =
        EcuWriteTransaction.Journal { records += it }

    @Test fun realEntryPointCannotWriteWithoutRegisteredHardwareDriver() = runBlocking {
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteTransaction.execute(
            request(), journal(records), EcuWriteTransaction.PowerMonitor { true }
        )
        assertEquals(EcuWriteTransaction.Status.BLOCKED, outcome.status)
        assertTrue(EcuWriteTransaction.Blocker.NO_VALIDATED_DRIVER in outcome.blockers)
        assertTrue(records.isEmpty())
    }

    @Test fun preparationRejectsElmMissingChecksAndUnverifiedMap() {
        val invalid = request(evidence = EcuWriteTransaction.SafetyEvidence()).copy(
            transport = EcuProgrammingDriverContract.Transport.ELM_DIAGNOSTICS,
            layout = originalLayout.copy(mapVerifiedForTargetEcu = false)
        )
        val result = EcuWriteTransaction.prepare(invalid)
        assertFalse(result.canAttemptWithValidatedHardware)
        assertTrue(EcuWriteTransaction.Blocker.DIAGNOSTIC_ONLY_TRANSPORT in result.blockers)
        assertTrue(EcuWriteTransaction.Blocker.UNVERIFIED_MEMORY_MAP in result.blockers)
        assertTrue(EcuWriteTransaction.Blocker.UNVERIFIED_OEM_CHECKSUM in result.blockers)
        assertTrue(EcuWriteTransaction.Blocker.UNVERIFIED_HARDWARE_BACKUP in result.blockers)
        assertTrue(EcuWriteTransaction.Blocker.NO_VALIDATED_RECOVERY in result.blockers)
    }

    @Test fun simulatedSectorSequencePreservesNeighborsAndVerifiesEveryWrittenSector() = runBlocking {
        val updated = original.copyOf().also { it[2] = 100; it[10] = 110 }
        val device = FakeDriver(original, expectedIdentity)
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteExecutionEngine(
            device, journal(records), EcuWriteTransaction.PowerMonitor { true }
        ).run(request(updated))
        assertEquals(EcuWriteTransaction.Status.COMPLETED, outcome.status)
        assertEquals(2, outcome.verifiedSectors)
        assertArrayEquals(updated, device.memory)
        assertEquals(2, device.eraseCalls)
        assertEquals(2, device.writeCalls)
        assertEquals(0, device.resetCalls)
        assertEquals(4, records.count { it.stage == EcuWriteTransaction.Stage.BASELINE_CHECKED ||
            it.stage == EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE })
        assertEquals(2, records.count { it.stage == EcuWriteTransaction.Stage.BEFORE_ERASE })
    }

    @Test fun baselineMismatchStopsBeforeAnyErase() = runBlocking {
        val liveMemory = original.copyOf().also { it[5] = 42 }
        val device = FakeDriver(liveMemory, expectedIdentity)
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {},
            EcuWriteTransaction.PowerMonitor { true }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(0, device.eraseCalls)
        assertEquals(0, device.writeCalls)
    }

    @Test fun mismatchedVinOrFirmwareStopsBeforeAnyErase() = runBlocking {
        val device = FakeDriver(
            original, expectedIdentity.copy(firmwareId = "different-firmware")
        )
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {},
            EcuWriteTransaction.PowerMonitor { true }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(0, device.eraseCalls)
    }

    @Test fun lossOfPowerAfterEraseNeverCausesBlindWriteRetry() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        var checks = 0
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteExecutionEngine(
            device, journal(records),
            EcuWriteTransaction.PowerMonitor { ++checks < 3 }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(1, device.eraseCalls)
        assertEquals(0, device.writeCalls)
        assertTrue(records.any { it.stage == EcuWriteTransaction.Stage.BEFORE_ERASE })
        assertEquals(0, outcome.verifiedSectors)
    }

    @Test fun readbackMismatchStopsWithoutProgrammingNextSector() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        device.corruptAfterWrite = true
        val updated = original.copyOf().also { it[2] = 100; it[10] = 110 }
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {},
            EcuWriteTransaction.PowerMonitor { true }
        ).run(request(updated))
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(1, device.eraseCalls)
        assertEquals(1, device.writeCalls)
        assertEquals(0, outcome.verifiedSectors)
    }

    @Test fun failingJournalBeforeErasePreventsAllDestructiveOperations() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal { record ->
                if (record.stage == EcuWriteTransaction.Stage.BEFORE_ERASE) error("storage full")
            },
            EcuWriteTransaction.PowerMonitor { true }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(0, device.eraseCalls)
        assertEquals(0, device.writeCalls)
    }

    @Test fun unvalidatedFakeDriverIsBlockedByInternalEngineAsWell() = runBlocking {
        val device = FakeDriver(original, expectedIdentity, validated = false)
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {},
            EcuWriteTransaction.PowerMonitor { true }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.BLOCKED, outcome.status)
        assertEquals(0, device.eraseCalls)
    }

    @Test fun invalidLayoutAndNoChangeNeverAttemptAnErase() = runBlocking {
        val invalid = EcuWriteTransaction.prepare(
            request().copy(layout = originalLayout.copy(eraseBlockBytes = 3))
        )
        assertTrue(EcuWriteTransaction.Blocker.INVALID_IMAGE_OR_LAYOUT in invalid.blockers)
        val device = FakeDriver(original, expectedIdentity)
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {},
            EcuWriteTransaction.PowerMonitor { true }
        ).run(request(original.copyOf()))
        assertEquals(EcuWriteTransaction.Status.NO_CHANGE, outcome.status)
        assertEquals(0, device.eraseCalls)
    }
}
