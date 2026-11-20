package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuRecoveryAssessmentTest {
    private val safe = EcuRecoveryAssessment.Context(
        programmingWasInterrupted = true,
        linkStatus = EcuRecoveryAssessment.LinkStatus.COMMUNICATING,
        powerStatus = EcuRecoveryAssessment.PowerStatus.VERIFIED_STABLE,
        ecuIdentityVerified = true,
        originalBackupAvailable = true,
        validatedRecoveryDriverAvailable = true
    )

    @Test fun communicatingEcuGetsReadOnlyAssessmentNotBlindRetry() {
        val result = EcuRecoveryAssessment.assess(safe)
        assertEquals(EcuRecoveryAssessment.Disposition.CHECK_ECU_WITHOUT_WRITING, result.disposition)
        assertFalse(result.automaticRetryAllowed)
        assertFalse(result.restoreGuaranteed)
    }

    @Test fun missingCommunicationSuggestsBenchTriageNotAnAutomaticFlash() {
        val result = EcuRecoveryAssessment.assess(safe.copy(
            linkStatus = EcuRecoveryAssessment.LinkStatus.NOT_RESPONDING
        ))
        assertEquals(EcuRecoveryAssessment.Disposition.ASSESS_BENCH_RECOVERY, result.disposition)
        assertFalse(result.automaticRetryAllowed)
    }

    @Test fun missingBackupUnstablePowerOrUnknownIdentityBlocksRecovery() {
        listOf(
            safe.copy(originalBackupAvailable = false),
            safe.copy(powerStatus = EcuRecoveryAssessment.PowerStatus.UNSTABLE),
            safe.copy(ecuIdentityVerified = false),
            safe.copy(validatedRecoveryDriverAvailable = false),
            safe.copy(linkStatus = EcuRecoveryAssessment.LinkStatus.NOT_CHECKED)
        ).forEach { context ->
            val result = EcuRecoveryAssessment.assess(context)
            assertEquals(EcuRecoveryAssessment.Disposition.STOP_AND_PRESERVE_EVIDENCE, result.disposition)
            assertTrue(EcuRecoveryAssessment.Warning.INTERRUPTION_REQUIRES_REASSESSMENT in result.warnings)
            assertFalse(result.automaticRetryAllowed)
        }
    }

    @Test fun noRecordedInterruptionMustNotBecomeARecoveryOperation() {
        val result = EcuRecoveryAssessment.assess(safe.copy(programmingWasInterrupted = false))
        assertEquals(EcuRecoveryAssessment.Disposition.NOT_AN_INTERRUPTED_SESSION, result.disposition)
        assertTrue(result.warnings.isEmpty())
    }
}
