package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
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
        var identity: EcuProgrammingDriverContract.Identity,
        validated: Boolean = true
    ) : EcuProgrammingDriverContract {
        val memory = initial.copyOf()
        var eraseCalls = 0
        var identityAfterErases: Pair<Int, EcuProgrammingDriverContract.Identity>? = null
        var identityAfterWrites: Pair<Int, EcuProgrammingDriverContract.Identity>? = null
        var identityAfterReads: Pair<Int, EcuProgrammingDriverContract.Identity>? = null
        val writes = mutableListOf<EcuProgrammingDriverContract.RegionRequest>()
        val writeCalls: Int get() = writes.size
        var failWriteAt: Long? = null
        var resetCalls = 0
        var corruptAfterWrite = false
        var failOnErase = false
        var disturbEarlierSector = false
        var disturbNextSector = false
        var finalReadbackCount = 0
        var reads = 0
        var afterRead: (() -> Unit)? = null
        override val descriptor = EcuProgrammingDriverContract.Descriptor(
            id = "in-memory-test-only",
            transport = EcuProgrammingDriverContract.Transport.J2534,
            capabilities = EcuProgrammingDriverContract.Capability.values().toSet(),
            controllerFamilies = setOf("E39"),
            physicalHardwareValidated = validated,
            writeTargets = setOf(
                EcuProgrammingDriverContract.FirmwareTarget("E39", "E39-test", "firmware-test"),
                EcuProgrammingDriverContract.FirmwareTarget("E39", "E39-test", "firmware-update")
            ),
            writeQualifications = setOf("firmware-test", "firmware-update").map { firmware ->
                EcuProgrammingDriverContract.WriteQualification(
                    EcuProgrammingDriverContract.FirmwareTarget("E39", "E39-test", firmware),
                    EcuBackupCoverage.Region.CALIBRATION, EcuBenchExpertPlan.AccessMethod.OBD
                )
            }.toSet()
        )

        private fun start(request: EcuProgrammingDriverContract.RegionRequest): Int {
            val start = (request.address - 0x1000L).toInt()
            require(start >= 0 && request.length >= 0 && start + request.length <= memory.size)
            return start
        }

        override suspend fun identify(): EcuProgrammingDriverContract.Identity =
            identityAfterReads?.takeIf { reads >= it.first }?.second
                ?: identityAfterWrites?.takeIf { writeCalls >= it.first }?.second
                ?: identityAfterErases?.takeIf { eraseCalls >= it.first }?.second ?: identity
        override suspend fun read(request: EcuProgrammingDriverContract.RegionRequest): ByteArray {
            val start = start(request)
            reads++
            return memory.copyOfRange(start, start + request.length.toInt()).also { afterRead?.invoke() }
        }
        override suspend fun erase(request: EcuProgrammingDriverContract.RegionRequest) {
            if (failOnErase) error("Lost connection before erasure")
            val start = start(request)
            memory.fill(0.toByte(), start, start + request.length.toInt())
            eraseCalls++
            if (disturbNextSector && eraseCalls == 1) memory[8] = (memory[8] + 1).toByte()
        }
        override suspend fun write(
            request: EcuProgrammingDriverContract.RegionRequest,
            bytes: ByteArray
        ) {
            require(bytes.size.toLong() == request.length)
            writes += request
            if (request.address == failWriteAt) error("Connection lost during write")
            bytes.copyInto(memory, destinationOffset = start(request))
            if (disturbEarlierSector && request.address == 0x100EL) {
                memory[0] = (memory[0] + 1).toByte()
            }
        }
        override suspend fun independentReadback(
            request: EcuProgrammingDriverContract.RegionRequest
        ): ByteArray {
            val result = read(request)
            if (writeCalls == 8) finalReadbackCount++
            if (corruptAfterWrite && writeCalls > 0) result[0] = (result[0] + 1).toByte()
            return result
        }
        override suspend fun resetController() {
            resetCalls++
        }
    }

    private fun journal(records: MutableList<EcuWriteTransaction.Record>) =
        EcuWriteTransaction.Journal { records += it }

    @Test fun concurrentEnginesOnOneDriverNeverInterleaveOrErase() = runBlocking {
        val shared = FakeDriver(original, expectedIdentity)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ownerRecords = mutableListOf<EcuWriteTransaction.Record>()
        // Hold the first owner at a journal boundary after live baseline
        // acquisition but before any possible erase on the shared driver.
        val pausedJournal = EcuWriteTransaction.Journal { record ->
            ownerRecords += record
            entered.complete(Unit)
            release.await()
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            EcuWriteExecutionEngine(
                shared, pausedJournal, EcuWriteTransaction.PowerMonitor { true }
            ).run(request())
        }
        entered.await()
        try {
            val competingRecords = mutableListOf<EcuWriteTransaction.Record>()
            val readsBefore = shared.reads
            val second = EcuWriteExecutionEngine(
                shared, journal(competingRecords), EcuWriteTransaction.PowerMonitor { true }
            ).run(request())
            assertEquals(EcuWriteTransaction.Status.BLOCKED, second.status)
            assertTrue(EcuWriteTransaction.Blocker.DRIVER_BUSY in second.blockers)
            assertTrue(competingRecords.isEmpty())
            assertEquals(readsBefore, shared.reads)
            assertEquals(0, shared.eraseCalls)

            // The reservation is *not global*: unrelated ECU links proceed
            // while the first link is waiting for its durable evidence sink.
            val independent = FakeDriver(original, expectedIdentity)
            val elsewhere = EcuWriteExecutionEngine(
                independent, EcuWriteTransaction.Journal {},
                EcuWriteTransaction.PowerMonitor { true }
            ).run(request())
            assertEquals(EcuWriteTransaction.Status.COMPLETED, elsewhere.status)
            assertEquals(1, independent.eraseCalls)
        } finally {
            first.cancelAndJoin()
        }
        assertEquals(0, shared.eraseCalls)
        // Cancellation releases the exact driver; a fresh operation starts
        // with full preflight rather than silently resuming the aborted one.
        val retried = EcuWriteExecutionEngine(
            shared, EcuWriteTransaction.Journal {},
            EcuWriteTransaction.PowerMonitor { true }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.COMPLETED, retried.status)
        assertEquals(1, shared.eraseCalls)
    }

    @Test fun failedJournalAlsoReleasesTheDriverReservation() = runBlocking {
        val driver = FakeDriver(original, expectedIdentity)
        val failure = EcuWriteExecutionEngine(
            driver, EcuWriteTransaction.Journal { throw java.io.IOException("Journal unavailable") },
            EcuWriteTransaction.PowerMonitor { true }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, failure.status)
        assertEquals(0, driver.eraseCalls)
        val resumed = EcuWriteExecutionEngine(
            driver, EcuWriteTransaction.Journal {}, EcuWriteTransaction.PowerMonitor { true }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.COMPLETED, resumed.status)
        assertEquals(1, driver.eraseCalls)
    }

    @Test fun cancelledSupplyObservationNeverProceedsToEcuIdentity() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            EcuWriteExecutionEngine(
                device, EcuWriteTransaction.Journal {},
                EcuWriteTransaction.PowerMonitor {
                    currentCoroutineContext().cancel()
                    true // Deliberately ignore cancellation and return a valid-looking voltage.
                }
            ).run(request())
        }
        try {
            operation.await()
            org.junit.Assert.fail("Cancelled power observation must abort before ECU traffic")
        } catch (_: CancellationException) {
            // Operation cancellation is deliberately propagated, never converted to success.
        }
        assertEquals(0, device.reads)
        assertEquals(0, device.eraseCalls)
        assertEquals(0, device.writeCalls)
    }

    @Test fun cancelledLatePhysicalReadNeverStartsNextEcuReadOrErase() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            val context = currentCoroutineContext()
            device.afterRead = { context.cancel() }
            EcuWriteExecutionEngine(
                device, EcuWriteTransaction.Journal {},
                EcuWriteTransaction.PowerMonitor { true }
            ).run(request())
        }
        try {
            operation.await()
            org.junit.Assert.fail("Cancelled physical read must not resume ECU preflight")
        } catch (_: CancellationException) {
            // First physical read returned data; no further ECU operation is allowed.
        }
        assertEquals(1, device.reads)
        assertEquals(0, device.eraseCalls)
        assertEquals(0, device.writeCalls)
    }

    @Test fun cancellationDuringBaselineEvidenceDoesNotStartNextSectorOrErase() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        val phases = mutableListOf<EcuWriteTransaction.Stage>()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            EcuWriteExecutionEngine(
                device,
                EcuWriteTransaction.Journal { record ->
                    phases += record.stage
                    if (record.stage == EcuWriteTransaction.Stage.BASELINE_CHECKED) {
                        currentCoroutineContext().cancel()
                    }
                    // I/O returns normally even after cancellation.
                },
                EcuWriteTransaction.PowerMonitor { true }
            ).run(request())
        }
        try {
            operation.await()
            org.junit.Assert.fail("Cancelled baseline journal cannot authorize erase")
        } catch (_: CancellationException) { }
        assertEquals(listOf(EcuWriteTransaction.Stage.BASELINE_CHECKED), phases)
        assertEquals(0, device.eraseCalls)
        assertEquals(0, device.writeCalls)
    }

    @Test fun cancellationReturnedByNonCooperativeJournalCannotErase() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        val phases = mutableListOf<EcuWriteTransaction.Stage>()
        val session = async(start = CoroutineStart.UNDISPATCHED) {
            EcuWriteExecutionEngine(
                device,
                EcuWriteTransaction.Journal { record ->
                    phases += record.stage
                    if (record.stage == EcuWriteTransaction.Stage.BEFORE_ERASE)
                        currentCoroutineContext().cancel()
                    // Deliberately return normally despite cancellation:
                    // the transaction engine must enforce cancellation itself.
                },
                EcuWriteTransaction.PowerMonitor { true }
            ).run(request())
        }
        try {
            session.await()
            org.junit.Assert.fail("A cancelled destructive transaction must not return success")
        } catch (_: CancellationException) {
            // The in-flight transaction must stop before the erase packet.
        }
        assertEquals(0, device.eraseCalls)
        assertEquals(0, device.writeCalls)
        assertTrue(EcuWriteTransaction.Stage.BEFORE_ERASE in phases)
        assertFalse(EcuWriteTransaction.Stage.TRANSACTION_COMPLETED in phases)
    }

    @Test fun cancellationAfterFinalReadbackCannotPublishCompletedJournal() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        val phases = mutableListOf<EcuWriteTransaction.Stage>()
        val session = async(start = CoroutineStart.UNDISPATCHED) {
            EcuWriteExecutionEngine(
                device,
                EcuWriteTransaction.Journal { record ->
                    phases += record.stage
                    if (record.stage == EcuWriteTransaction.Stage.FINAL_READBACK_VERIFIED)
                        currentCoroutineContext().cancel()
                },
                EcuWriteTransaction.PowerMonitor { true }
            ).run(request())
        }
        try {
            session.await()
            org.junit.Assert.fail("Cancellation must prevent final completion evidence")
        } catch (_: CancellationException) {
            // Physically verified sectors remain subject to manual assessment.
        }
        assertEquals(1, device.eraseCalls)
        assertEquals(EcuWriteTransaction.Stage.FINAL_READBACK_VERIFIED, phases.last())
        assertFalse(EcuWriteTransaction.Stage.TRANSACTION_COMPLETED in phases)
        assertEquals(0, device.resetCalls)
    }

    @Test fun realEntryPointCannotWriteWithoutRegisteredHardwareDriver() = runBlocking {
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteTransaction.execute(
            request(), journal(records), EcuWriteTransaction.PowerMonitor { true }
        )
        assertEquals(EcuWriteTransaction.Status.BLOCKED, outcome.status)
        assertTrue(EcuWriteTransaction.Blocker.NO_VALIDATED_DRIVER in outcome.blockers)
        assertTrue(records.isEmpty())
    }

    @Test fun publicWriteEntryPointRejectsUnvalidatedHardwareBeforeCreatingJournal() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("camarodiag-ecu-write")
        try {
            val file = directory.resolve("write-journal.log").toFile()
            val outcome = EcuWriteTransaction.execute(
                request(), file, EcuWriteTransaction.PowerMonitor { true }
            )
            assertEquals(EcuWriteTransaction.Status.BLOCKED, outcome.status)
            assertTrue(EcuWriteTransaction.Blocker.NO_VALIDATED_DRIVER in outcome.blockers)
            assertFalse("No journal should be created for an unvalidated driver", file.exists())
        } finally {
            java.nio.file.Files.deleteIfExists(directory)
        }
    }

    @Test fun transportLabelDoesNotInventWriteBanButMissingPhysicalEvidenceStillStopsFlash() {
        val invalid = request(evidence = EcuWriteTransaction.SafetyEvidence()).copy(
            transport = EcuProgrammingDriverContract.Transport.ELM_DIAGNOSTICS,
            layout = originalLayout.copy(mapVerifiedForTargetEcu = false)
        )
        val result = EcuWriteTransaction.prepare(invalid)
        assertFalse(result.canAttemptWithValidatedHardware)
        assertFalse(EcuWriteTransaction.Blocker.DIAGNOSTIC_ONLY_TRANSPORT in result.blockers)
        assertTrue(EcuWriteTransaction.Blocker.UNVERIFIED_MEMORY_MAP in result.blockers)
        assertTrue(EcuWriteTransaction.Blocker.UNVERIFIED_OEM_CHECKSUM in result.blockers)
        assertTrue(EcuWriteTransaction.Blocker.UNVERIFIED_HARDWARE_BACKUP in result.blockers)
        assertTrue(EcuWriteTransaction.Blocker.NO_VALIDATED_RECOVERY in result.blockers)
    }

    @Test fun programmingTransportCannotSubstituteForAQualifiedConnectionRoute() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {}, EcuWriteTransaction.PowerMonitor { true }
        ).run(request().copy(accessMethod = EcuBenchExpertPlan.AccessMethod.BENCH))
        assertEquals(EcuWriteTransaction.Status.BLOCKED, outcome.status)
        assertTrue(EcuWriteTransaction.Blocker.NO_VALIDATED_DRIVER in outcome.blockers)
        assertEquals(0, device.eraseCalls)
        assertTrue(EcuWriteTransaction.Blocker.INCOMPATIBLE_ACCESS_METHOD in
            EcuWriteTransaction.prepare(request().copy(accessMethod = EcuBenchExpertPlan.AccessMethod.BDM)).blockers)
    }

    @Test fun externalEepromCannotEnterFlashSectorEraseEngine() {
        for ((transport, method) in listOf(
            EcuProgrammingDriverContract.Transport.EEPROM_ISP to EcuBenchExpertPlan.AccessMethod.EXTERNAL_EEPROM_ISP,
            EcuProgrammingDriverContract.Transport.CHIP_OFF to EcuBenchExpertPlan.AccessMethod.EXTERNAL_CHIP_OFF
        )) {
            val result = EcuWriteTransaction.prepare(request().copy(
                transport = transport, accessMethod = method,
                region = EcuBackupCoverage.Region.EXTERNAL_EEPROM
            ))
            assertTrue(EcuWriteTransaction.Blocker.SEPARATE_EEPROM_WORKFLOW_REQUIRED in result.blockers)
            assertFalse(result.canAttemptWithValidatedHardware)
        }
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
        assertEquals((0x1000L until 0x1010L step 2).toList(), device.writes.map { it.address })
        assertTrue(device.writes.all { it.length == 2L })
        assertEquals(0, device.resetCalls)
        assertEquals(4, records.count { it.stage == EcuWriteTransaction.Stage.BASELINE_CHECKED ||
            it.stage == EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE })
        assertEquals(2, records.count { it.stage == EcuWriteTransaction.Stage.BEFORE_ERASE })
        assertEquals(listOf(0x1000L, 0x1008L),
            records.filter { it.stage == EcuWriteTransaction.Stage.FINAL_READBACK_VERIFIED }
                .map { it.address })
        assertEquals(EcuWriteTransaction.Stage.TRANSACTION_COMPLETED, records.last().stage)
        assertEquals(0x1008L, records.last().address)
    }

    @Test fun sameDriverAcceptsBothDocumentedFirmwareVersions() = runBlocking {
        for (firmware in listOf("firmware-test", "firmware-update")) {
            val identity = expectedIdentity.copy(firmwareId = firmware)
            val device = FakeDriver(original, identity)
            val req = request().copy(expectedIdentity = identity)
            val result = EcuWriteExecutionEngine(
                device, EcuWriteTransaction.Journal {}, EcuWriteTransaction.PowerMonitor { true }
            ).run(req)
            assertEquals(EcuWriteTransaction.Status.COMPLETED, result.status)
            assertArrayEquals(req.updatedImage, device.memory)
        }
    }

    @Test fun matchingLiveIdentityStillRequiresACoveredControllerAndFirmwarePair() = runBlocking {
        for (identity in listOf(
            expectedIdentity.copy(firmwareId = "unlisted-firmware"),
            expectedIdentity.copy(controllerId = "other-controller")
        )) {
            val device = FakeDriver(original, identity)
            val result = EcuWriteExecutionEngine(
                device, EcuWriteTransaction.Journal {}, EcuWriteTransaction.PowerMonitor { true }
            ).run(request().copy(expectedIdentity = identity))
            assertEquals(EcuWriteTransaction.Status.BLOCKED, result.status)
            assertTrue(EcuWriteTransaction.Blocker.NO_VALIDATED_DRIVER in result.blockers)
            assertEquals(0, device.eraseCalls)
            assertEquals(0, device.writeCalls)
        }
    }

    @Test fun invalidTargetVinIsBlockedDuringPreflight() {
        for (vin in listOf(null, "", "NOT_A_VIN", "2G1I0000000000001")) {
            val result = EcuWriteTransaction.prepare(
                request().copy(expectedIdentity = expectedIdentity.copy(vin = vin))
            )
            assertTrue(EcuWriteTransaction.Blocker.UNKNOWN_TARGET in result.blockers)
        }
    }

    @Test fun controllerIdentityChangedAfterFirstSectorStopsBeforeNextErase() = runBlocking {
        val updated = original.copyOf().also { it[2] = 100; it[10] = 110 }
        val device = FakeDriver(original, expectedIdentity)
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal { record ->
                records += record
                if (record.stage == EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE) {
                    device.identity = expectedIdentity.copy(vin = "2G1TEST0000000002")
                }
            }, EcuWriteTransaction.PowerMonitor { true }
        ).run(request(updated))
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(1, outcome.verifiedSectors)
        assertEquals(0x1008L, outcome.failedAddress)
        assertEquals(1, device.eraseCalls)
        assertEquals(4, device.writeCalls)
        assertEquals(1, records.count { it.stage == EcuWriteTransaction.Stage.BEFORE_ERASE })
    }

    @Test fun controllerSwitchDuringSectorProgrammingStopsBeforeNextUnit() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        device.identityAfterWrites = 1 to expectedIdentity.copy(vin = "2G1TEST0000000002")
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteExecutionEngine(
            device, journal(records), EcuWriteTransaction.PowerMonitor { true }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(1, device.eraseCalls)
        assertEquals(1, device.writeCalls)
        assertEquals(0x1002L, outcome.failedAddress)
        assertFalse(records.any { it.stage == EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE })
        assertEquals(0, device.resetCalls)
    }

    @Test fun controllerSwitchAfterLastUnitPreventsSectorVerification() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        device.identityAfterWrites = 4 to expectedIdentity.copy(vin = "2G1TEST0000000002")
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteExecutionEngine(
            device, journal(records), EcuWriteTransaction.PowerMonitor { true }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(1, device.eraseCalls)
        assertEquals(4, device.writeCalls)
        assertEquals(0x1000L, outcome.failedAddress)
        assertFalse(records.any { it.stage == EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE })
        assertEquals(0, device.resetCalls)
    }

    @Test fun lossOfPowerDuringBaselineReadNeverStartsSecondAcquisitionOrErase() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        var stable = true
        device.afterRead = { stable = false }
        val both = original.copyOf().also { it[2] = 100; it[10] = 110 }
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {}, EcuWriteTransaction.PowerMonitor { stable }
        ).run(request(both))
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(1, device.reads)
        assertEquals(0, device.eraseCalls)
    }

    @Test fun lossOfPowerDuringUntouchedBaselineReadPreventsSecondAcquisitionAndErase() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        var stable = true
        device.afterRead = { stable = false }
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {}, EcuWriteTransaction.PowerMonitor { stable }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(0x1008L, outcome.failedAddress)
        assertEquals(1, device.reads)
        assertEquals(0, device.eraseCalls)
    }

    @Test fun lossOfPowerDuringSecondBaselineAcquisitionStopsBeforeJournalAndErase() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        var stable = true
        device.afterRead = {
            if (device.reads == 4) stable = false
        }
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteExecutionEngine(
            device, journal(records), EcuWriteTransaction.PowerMonitor { stable }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(4, device.reads)
        assertEquals(0, device.eraseCalls)
        assertEquals(0, device.writeCalls)
        assertTrue(records.isEmpty())
    }

    @Test fun mutatingCallerImageDuringReadDoesNotChangeFrozenProgrammingPlan() = runBlocking {
        val req = request()
        val expected = req.updatedImage.copyOf()
        val device = FakeDriver(original, expectedIdentity)
        device.afterRead = { req.updatedImage.fill(0); req.originalImage.fill(0) }
        val result = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {}, EcuWriteTransaction.PowerMonitor { true }
        ).run(req)
        assertEquals(EcuWriteTransaction.Status.COMPLETED, result.status)
        assertArrayEquals(expected, device.memory)
    }

    @Test fun liveEcuSectorBytesAreCapturedBeforeAnyErase() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        val snapshots = InMemoryEcuPhysicalBlockBackup()
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {}, EcuWriteTransaction.PowerMonitor { true },
            snapshots
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.COMPLETED, outcome.status)
        assertEquals(1, snapshots.blocks.size)
        assertArrayEquals(
            original.copyOfRange(0, 8),
            snapshots.blocks[EcuBackupCoverage.Region.CALIBRATION to 0x1000L]
        )
        assertEquals(1, device.eraseCalls)
    }

    @Test fun changedSavedOriginalAfterJournalMarkerPreventsErase() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        val snapshots = InMemoryEcuPhysicalBlockBackup()
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {
                if (it.stage == EcuWriteTransaction.Stage.BEFORE_ERASE)
                    snapshots.blocks.values.first()[0] = 99
            }, EcuWriteTransaction.PowerMonitor { true }, snapshots
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT,outcome.status)
        assertEquals(0,device.eraseCalls)
        assertEquals(0,device.writeCalls)
    }

    @Test fun diskBackupFailureCannotEraseOrWriteEcu() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        val refusingBackup = EcuPhysicalBlockBackup { _, _, _ ->
            throw java.io.IOException("Physical block backup cannot be persisted.")
        }
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteExecutionEngine(
            device, journal(records), EcuWriteTransaction.PowerMonitor { true },
            refusingBackup
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(0, device.eraseCalls)
        assertEquals(0, device.writeCalls)
        assertFalse(records.any { it.stage == EcuWriteTransaction.Stage.BEFORE_ERASE })
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

    @Test fun baselineMismatchInUntouchedSectorPreventsAnyErase() = runBlocking {
        val device = FakeDriver(original.copyOf().also { it[12] = 99 }, expectedIdentity)
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {}, EcuWriteTransaction.PowerMonitor { true }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(0x1008L, outcome.failedAddress)
        assertEquals(0, device.eraseCalls)
        assertEquals(0, device.writeCalls)
    }

    @Test fun collateralDamageToUnchangedSectorPreventsCompletion() = runBlocking {
        val device = FakeDriver(original, expectedIdentity).also { it.disturbNextSector = true }
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteExecutionEngine(
            device, journal(records), EcuWriteTransaction.PowerMonitor { true }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(0x1008L, outcome.failedAddress)
        assertEquals(1, device.eraseCalls)
        assertEquals(4, device.writeCalls)
        assertFalse(records.any { it.stage == EcuWriteTransaction.Stage.TRANSACTION_COMPLETED })
        assertEquals(0, device.resetCalls)
    }

    @Test fun unchangedSectorIsPreservedWithoutBeingErased() = runBlocking {
        val req = request()
        val device = FakeDriver(original, expectedIdentity)
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {}, EcuWriteTransaction.PowerMonitor { true }
        ).run(req)
        assertEquals(EcuWriteTransaction.Status.COMPLETED, outcome.status)
        assertArrayEquals(req.updatedImage, device.memory)
        assertEquals(1, device.eraseCalls)
        assertTrue(device.writes.all { it.address < 0x1008L })
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
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteExecutionEngine(
            device, journal(records),
            EcuWriteTransaction.PowerMonitor { device.eraseCalls == 0 }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(1, device.eraseCalls)
        assertEquals(0, device.writeCalls)
        assertTrue(records.any { it.stage == EcuWriteTransaction.Stage.BEFORE_ERASE })
        assertEquals(0, outcome.verifiedSectors)
    }

    @Test fun stopsAtFailedProgrammingUnitWithoutErasingTheNextSector() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        device.failWriteAt = 0x1002L
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val updated = original.copyOf().also { it[2] = 100; it[10] = 110 }
        val outcome = EcuWriteExecutionEngine(
            device, journal(records), EcuWriteTransaction.PowerMonitor { true }
        ).run(request(updated))
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(0x1002L, outcome.failedAddress)
        assertEquals(listOf(0x1000L, 0x1002L), device.writes.map { it.address })
        assertEquals(1, device.eraseCalls)
        assertEquals(0, outcome.verifiedSectors)
        assertFalse(records.any { it.stage == EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE })
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
        assertEquals(4, device.writeCalls)
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

    @Test fun laterSectorDisturbanceIsDetectedByFinalIndependentReadback() = runBlocking {
        val updated = original.copyOf().also { it[2] = 100; it[10] = 110 }
        val device = FakeDriver(original, expectedIdentity).also { it.disturbEarlierSector = true }
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteExecutionEngine(
            device, journal(records), EcuWriteTransaction.PowerMonitor { true }
        ).run(request(updated))
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(0x1000L, outcome.failedAddress)
        assertEquals(2, device.eraseCalls)
        assertEquals(2, records.count { it.stage == EcuWriteTransaction.Stage.VERIFIED_AFTER_WRITE })
        assertEquals(2, device.finalReadbackCount)
        assertEquals(0, device.resetCalls)
    }

    @Test fun changedNextSectorAfterPreflightCannotBeErased() = runBlocking {
        val updated = original.copyOf().also { it[2] = 100; it[10] = 110 }
        val device = FakeDriver(original, expectedIdentity).also { it.disturbNextSector = true }
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteExecutionEngine(
            device, journal(records), EcuWriteTransaction.PowerMonitor { true }
        ).run(request(updated))
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(1, device.eraseCalls)
        assertEquals(1, outcome.verifiedSectors)
        assertEquals(0x1008L, outcome.failedAddress)
        assertEquals(1, records.count { it.stage == EcuWriteTransaction.Stage.BEFORE_ERASE })
    }

    @Test fun powerLossWhilePersistingEraseMarkerStopsBeforeErase() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        var stable = true
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {
                if (it.stage == EcuWriteTransaction.Stage.BEFORE_ERASE) stable = false
            }, EcuWriteTransaction.PowerMonitor { stable }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(0, device.eraseCalls)
        assertEquals(0, device.writeCalls)
    }

    @Test fun powerLossDuringFinalReadbackDoesNotReportCompletion() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {},
            EcuWriteTransaction.PowerMonitor { device.writeCalls < 4 }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(1, device.eraseCalls)
        assertEquals(4, device.writeCalls)
        assertEquals(0, device.resetCalls)
    }

    @Test fun powerLossDuringFinalIndependentReadbackCannotBeJournaledAsVerified() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        var stable = true
        // Two reads of untouched baseline, two reads of the changed baseline,
        // one pre-erase read, one post-write read, then the final readback.
        device.afterRead = {
            if (device.reads == 7) stable = false
        }
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteExecutionEngine(
            device, journal(records), EcuWriteTransaction.PowerMonitor { stable }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(1, device.eraseCalls)
        assertEquals(4, device.writeCalls)
        assertEquals(0x1000L, outcome.failedAddress)
        assertFalse(records.any { it.stage == EcuWriteTransaction.Stage.FINAL_READBACK_VERIFIED })
        assertFalse(records.any { it.stage == EcuWriteTransaction.Stage.TRANSACTION_COMPLETED })
    }

    @Test fun controllerSwitchDuringFinalIndependentReadbackCannotMarkCompletion() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        device.identityAfterReads = 7 to expectedIdentity.copy(vin = "2G1TEST0000000002")
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteExecutionEngine(
            device, journal(records), EcuWriteTransaction.PowerMonitor { true }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(1, device.eraseCalls)
        assertEquals(4, device.writeCalls)
        assertEquals(0x1000L, outcome.failedAddress)
        assertFalse(records.any { it.stage == EcuWriteTransaction.Stage.FINAL_READBACK_VERIFIED })
        assertFalse(records.any { it.stage == EcuWriteTransaction.Stage.TRANSACTION_COMPLETED })
    }

    @Test fun controllerSwitchJustAfterEraseStopsBeforeFirstWrite() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        device.identityAfterErases = 1 to expectedIdentity.copy(vin = "2G1TEST0000000002")
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {}, EcuWriteTransaction.PowerMonitor { true }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(1, device.eraseCalls)
        assertEquals(0, device.writeCalls)
        assertEquals(0, outcome.verifiedSectors)
        assertEquals(0, device.resetCalls)
    }

    @Test fun failedCompletionJournalWriteNeverReportsSuccess() = runBlocking {
        val device = FakeDriver(original, expectedIdentity)
        val records = mutableListOf<EcuWriteTransaction.Record>()
        val outcome = EcuWriteExecutionEngine(
            device, EcuWriteTransaction.Journal {
                if (it.stage == EcuWriteTransaction.Stage.TRANSACTION_COMPLETED) error("disk full")
                records += it
            }, EcuWriteTransaction.PowerMonitor { true }
        ).run(request())
        assertEquals(EcuWriteTransaction.Status.STOPPED_REQUIRES_ASSESSMENT, outcome.status)
        assertEquals(1, outcome.verifiedSectors)
        assertEquals(EcuWriteTransaction.Stage.FINAL_READBACK_VERIFIED, records.last().stage)
        assertEquals(1, device.eraseCalls)
        assertEquals(0, device.resetCalls)
    }
    @Test fun placeholderEcuAndFirmwareCannotSatisfyWritePreparation() {
        val invalid = listOf("unknown", "demo", "simulation", "mock", "pending",
            "00000000", "FFFFFFFF", "0x000000", "N/A",
            "unknown-ecu", "ECU-DEMO", "sample-firmware", "fake-controller")
        for (value in invalid) {
            for (target in listOf(
                request().copy(controllerFamily = value),
                request().copy(expectedIdentity = expectedIdentity.copy(controllerId = value)),
                request().copy(expectedIdentity = expectedIdentity.copy(firmwareId = value))
            )) {
                val result = EcuWriteTransaction.prepare(target)
                assertTrue("Unsafe identity must be refused: $value",
                    EcuWriteTransaction.Blocker.UNKNOWN_TARGET in result.blockers)
                assertFalse(result.canAttemptWithValidatedHardware)
            }
        }
    }

    @Test fun unchangedImageDoesNotBypassUnsafeTargetPreflight() = runBlocking {
        val unsafe = request(updated = original.copyOf()).copy(
            expectedIdentity = expectedIdentity.copy(controllerId = "unknown-ecu"))
        val result = EcuWriteTransaction.execute(
            unsafe,
            EcuWriteTransaction.Journal {},
            EcuWriteTransaction.PowerMonitor { true }
        )
        assertEquals(EcuWriteTransaction.Status.BLOCKED, result.status)
        assertTrue(EcuWriteTransaction.Blocker.UNKNOWN_TARGET in result.blockers)
    }

}
