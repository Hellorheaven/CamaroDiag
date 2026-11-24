package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuBackupIntegrityTest {
    private val region = EcuBackupCoverage.Region.CALIBRATION
    private val required = setOf(region)
    private fun capture(first: ByteArray = byteArrayOf(1, 2, 3)) =
        EcuBackupManifest.Capture(
            region = region,
            startAddress = 0L,
            expectedBytes = 3L,
            firstRead = first,
            independentRead = first.copyOf(),
            origin = EcuBackupManifest.Origin.IMPORTED_FILE
        )

    private fun manifest(captures: List<EcuBackupManifest.Capture>) =
        EcuBackupManifest.inspect("test", 1L, captures, required)

    @Test fun reportedHardwareReadCannotLackVehicleIdentity() {
        val hardware = listOf(capture().copy(
            origin = EcuBackupManifest.Origin.REPORTED_HARDWARE_READ
        ))
        val missingVin = manifest(hardware)
        val problems = EcuBackupIntegrity.verify(missingVin, hardware, required).problems
        assertTrue(EcuBackupIntegrity.Problem.PHYSICAL_ORIGIN_MISSING_VIN in problems)

        val identified = EcuBackupManifest.inspect(
            "test", 1L, hardware, required, "1G1FA1RXXR0123456"
        )
        assertTrue(EcuBackupIntegrity.verify(identified, hardware, required).consistent)
        assertFalse(identified.hardwareProvenanceVerified)
    }

    @Test fun firmwareEvidenceAndRoutedScopeCannotBeForgedByManifestCopy() {
        val vin = "1G1FA1RXXR0123456"
        val channel = EcuBackupManifest.Channel(0x7E0, 0x7E8, 33, 1)
        val firmware = EcuBackupManifest.FirmwareEvidence(0x123456, 0xC1)
        val sample = capture().copy(origin = EcuBackupManifest.Origin.REPORTED_HARDWARE_READ)
        val read = listOf(sample)
        val correct = EcuBackupManifest.inspect(
            "ECU", 1L, read, emptySet(), vin, channel, firmware
        )
        assertTrue(EcuBackupIntegrity.verify(correct, read, emptySet()).consistent)

        val unbound = EcuBackupIntegrity.verify(
            correct.copy(channel = null), read, emptySet()
        ).problems
        assertTrue(EcuBackupIntegrity.Problem.FIRMWARE_EVIDENCE_WITHOUT_CHANNEL in unbound)
        val simulated = listOf(sample.copy(origin = EcuBackupManifest.Origin.SIMULATION))
        val spoofed = EcuBackupManifest.inspect(
            "ECU", 1L, simulated, emptySet(), vin, channel, firmware
        )
        assertTrue(
            EcuBackupIntegrity.Problem.FIRMWARE_EVIDENCE_WITHOUT_REPORTED_HARDWARE_READ in
                EcuBackupIntegrity.verify(spoofed, simulated, emptySet()).problems
        )

        val secondScope = read + sample.copy(startAddress = 0x1000L)
        val twoScopes = EcuBackupManifest.inspect(
            "ECU", 1L, secondScope, emptySet(), vin, channel, firmware
        )
        assertTrue(EcuBackupIntegrity.Problem.ROUTED_SCOPE_INVALID in
            EcuBackupIntegrity.verify(twoScopes, secondScope, emptySet()).problems)

        assertTrue(EcuBackupIntegrity.Problem.ROUTED_SCOPE_INVALID in
            EcuBackupIntegrity.verify(correct, read, setOf(region)).problems)

        val incomplete = listOf(sample.copy(expectedBytes = 4L))
        val partial = EcuBackupManifest.inspect(
            "ECU", 1L, incomplete, emptySet(), vin, channel, firmware
        )
        assertTrue(EcuBackupIntegrity.Problem.ROUTED_SCOPE_INVALID in
            EcuBackupIntegrity.verify(partial, incomplete, emptySet()).problems)

        val beyond32Bits = listOf(sample.copy(startAddress = 0x1_0000_0000L))
        val impossible = EcuBackupManifest.inspect(
            "ECU", 1L, beyond32Bits, emptySet(), vin, channel, firmware
        )
        assertTrue(EcuBackupIntegrity.Problem.ROUTED_SCOPE_INVALID in
            EcuBackupIntegrity.verify(impossible, beyond32Bits, emptySet()).problems)
    }

    @Test fun consistentBytesAndManifestPassOfflineCheck() {
        val captures = listOf(capture())
        assertTrue(EcuBackupIntegrity.verify(manifest(captures), captures, required).consistent)
    }

    @Test fun tamperedBytesAreRejected() {
        val original = listOf(capture())
        val altered = listOf(capture(byteArrayOf(1, 2, 4)))
        val problems = EcuBackupIntegrity.verify(manifest(original), altered, required).problems
        assertTrue(EcuBackupIntegrity.Problem.DIGEST_MISMATCH in problems)
    }

    @Test fun alteredCoverageAndMetadataAreRejected() {
        val captures = listOf(capture())
        val m = manifest(captures)
        assertTrue(
            EcuBackupIntegrity.Problem.METADATA_MISMATCH in
                EcuBackupIntegrity.verify(
                    m.copy(entries = m.entries.map { it.copy(startAddress = 1L) }),
                    captures, required
                ).problems
        )
        assertTrue(
            EcuBackupIntegrity.Problem.COVERAGE_MISMATCH in
                EcuBackupIntegrity.verify(
                    m.copy(coverage = m.coverage.copy(capturedBytes = 999L)),
                    captures, required
                ).problems
        )
    }

    @Test fun missingIndependentReadAndExtraEntriesAreRejected() {
        val captures = listOf(capture())
        val m = manifest(captures)
        val changed = captures.map { it.copy(independentRead = null) }
        assertTrue(
            EcuBackupIntegrity.Problem.INDEPENDENT_READ_MISMATCH in
                EcuBackupIntegrity.verify(m, changed, required).problems
        )
        assertTrue(
            EcuBackupIntegrity.Problem.ENTRY_COUNT_MISMATCH in
                EcuBackupIntegrity.verify(
                    m.copy(entries = m.entries + m.entries.first()), captures, required
                ).problems
        )
    }

    @Test fun claimedHardwareProofIsNotAccepted() {
        val captures = listOf(capture())
        val result = EcuBackupIntegrity.verify(
            manifest(captures).copy(hardwareProvenanceVerified = true), captures, required
        )
        assertFalse(result.consistent)
        assertTrue(EcuBackupIntegrity.Problem.UNVERIFIABLE_HARDWARE_PROVENANCE in result.problems)
    }
    @Test fun mutationOfOriginalByteArrayAfterManifestCreationIsDetected() {
        val bytes = byteArrayOf(1, 2, 3)
        val captures = listOf(capture(bytes))
        val savedManifest = manifest(captures)
        bytes[0] = 9
        val result = EcuBackupIntegrity.verify(savedManifest, captures, required)
        assertTrue(EcuBackupIntegrity.Problem.DIGEST_MISMATCH in result.problems)
    }

    @Test fun missingRequiredRegionCannotBePassedOffAsComplete() {
        val captures = listOf(capture())
        val savedManifest = manifest(captures)
        val expandedRequirements = required + EcuBackupCoverage.Region.DATA_FLASH
        val problems = EcuBackupIntegrity.verify(
            savedManifest, captures, expandedRequirements
        ).problems
        assertTrue(EcuBackupIntegrity.Problem.COVERAGE_MISMATCH in problems)
    }

    @Test fun malformedAndOverlappingCapturesAreNotRestorableProof() {
        val first = capture()
        val second = capture(byteArrayOf(4, 5, 6)).copy(startAddress = 2L)
        val captures = listOf(first, second)
        val savedManifest = manifest(captures)
        assertTrue(savedManifest.coverage.status == EcuBackupCoverage.Status.INCONSISTENT)
        val problems = EcuBackupIntegrity.verify(savedManifest, captures, required).problems
        assertTrue(EcuBackupIntegrity.Problem.INVALID_COVERAGE in problems)
    }

    @Test fun absentSecondReadCannotBeHiddenByAConsistentManifest() {
        val captures = listOf(capture().copy(independentRead = null))
        val generated = manifest(captures)
        assertFalse(generated.entries.single().readsIdentical)
        val problems = EcuBackupIntegrity.verify(generated, captures, required).problems
        assertTrue(EcuBackupIntegrity.Problem.MISSING_INDEPENDENT_READ in problems)
    }

    @Test fun twoDifferentReadsAreRejectedEvenWhenManifestAgrees() {
        val capture = capture().copy(independentRead = byteArrayOf(1, 2, 9))
        val captures = listOf(capture)
        val savedManifest = manifest(captures)
        assertFalse(savedManifest.entries.single().readsIdentical)
        val problems = EcuBackupIntegrity.verify(savedManifest, captures, required).problems
        assertTrue(EcuBackupIntegrity.Problem.DIVERGENT_INDEPENDENT_READ in problems)
    }

    @Test fun sameBufferCannotServeAsTwoIndependentAcquisitions() {
        val bytes = byteArrayOf(1, 2, 3)
        val captures = listOf(capture(bytes).copy(independentRead = bytes))
        val result = EcuBackupIntegrity.verify(manifest(captures), captures, required)
        assertTrue(EcuBackupIntegrity.Problem.ALIASED_INDEPENDENT_READ in result.problems)
    }

    @Test fun emptyMemorySegmentCannotPassIntegrityCheck() {
        val captures = listOf(capture(byteArrayOf()))
        val result = EcuBackupIntegrity.verify(manifest(captures), captures, required)
        assertTrue(EcuBackupIntegrity.Problem.EMPTY_CAPTURE in result.problems)
    }

    @Test fun editedMalformedVinCannotPassIntegrityCheck() {
        val captures = listOf(capture())
        val edited = manifest(captures).copy(vehicleVin = "NOT-A-VIN")
        val result = EcuBackupIntegrity.verify(edited, captures, required)
        assertTrue(EcuBackupIntegrity.Problem.INVALID_VIN in result.problems)
    }

    @Test fun emptyOfflineManifestDoesNotCountAsValidBackup() {
        val captures = emptyList<EcuBackupManifest.Capture>()
        val emptyManifest = manifest(captures)
        val result = EcuBackupIntegrity.verify(emptyManifest, captures, required)
        assertFalse(result.consistent)
        assertTrue(EcuBackupIntegrity.Problem.EMPTY_BACKUP in result.problems)
    }

    @Test fun invalidAcquisitionTimestampCannotPassOfflineIntegrityCheck() {
        val captures = listOf(capture())
        for (timestamp in listOf(0L, -1L, Long.MIN_VALUE)) {
            val invalid = manifest(captures).copy(createdAtUtcMillis = timestamp)
            val result = EcuBackupIntegrity.verify(invalid, captures, required)
            assertFalse(result.consistent)
            assertTrue(EcuBackupIntegrity.Problem.INVALID_CAPTURE_TIMESTAMP in result.problems)
        }
    }

}
