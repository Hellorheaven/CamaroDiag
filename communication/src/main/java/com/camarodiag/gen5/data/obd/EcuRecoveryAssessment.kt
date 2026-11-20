package com.camarodiag.gen5.data.obd

/**
 * Non-destructive assessment of an interrupted or uncertain ECU programming session.
 * Never attempts to erase, reflash, reset, unlock or retry an ECU.
 */
object EcuRecoveryAssessment {
    enum class LinkStatus { COMMUNICATING, NOT_RESPONDING, NOT_CHECKED }
    enum class PowerStatus { VERIFIED_STABLE, UNSTABLE, NOT_CHECKED }
    enum class Disposition {
        NOT_AN_INTERRUPTED_SESSION,
        CHECK_ECU_WITHOUT_WRITING,
        ASSESS_BENCH_RECOVERY,
        STOP_AND_PRESERVE_EVIDENCE
    }
    enum class Warning {
        ECU_IDENTITY_NOT_VERIFIED,
        ORIGINAL_BACKUP_MISSING,
        RECOVERY_DRIVER_NOT_VALIDATED,
        POWER_NOT_VERIFIED,
        ECU_COMMUNICATION_UNKNOWN,
        INTERRUPTION_REQUIRES_REASSESSMENT
    }

    data class Context(
        val programmingWasInterrupted: Boolean,
        val linkStatus: LinkStatus,
        val powerStatus: PowerStatus,
        val ecuIdentityVerified: Boolean,
        val originalBackupAvailable: Boolean,
        val validatedRecoveryDriverAvailable: Boolean
    )

    data class Assessment(
        val disposition: Disposition,
        val warnings: Set<Warning>,
        val automaticRetryAllowed: Boolean = false,
        val restoreGuaranteed: Boolean = false
    )

    fun assess(context: Context): Assessment {
        if (!context.programmingWasInterrupted) {
            return Assessment(Disposition.NOT_AN_INTERRUPTED_SESSION, emptySet())
        }
        val warnings = mutableSetOf(Warning.INTERRUPTION_REQUIRES_REASSESSMENT)
        if (!context.ecuIdentityVerified) warnings += Warning.ECU_IDENTITY_NOT_VERIFIED
        if (!context.originalBackupAvailable) warnings += Warning.ORIGINAL_BACKUP_MISSING
        if (!context.validatedRecoveryDriverAvailable) warnings += Warning.RECOVERY_DRIVER_NOT_VALIDATED
        if (context.powerStatus != PowerStatus.VERIFIED_STABLE) warnings += Warning.POWER_NOT_VERIFIED
        if (context.linkStatus == LinkStatus.NOT_CHECKED) {
            warnings += Warning.ECU_COMMUNICATION_UNKNOWN
        }
        val disposition = when {
            context.powerStatus != PowerStatus.VERIFIED_STABLE ||
                !context.ecuIdentityVerified || !context.originalBackupAvailable ||
                !context.validatedRecoveryDriverAvailable ->
                Disposition.STOP_AND_PRESERVE_EVIDENCE
            context.linkStatus == LinkStatus.NOT_RESPONDING ->
                Disposition.ASSESS_BENCH_RECOVERY
            context.linkStatus == LinkStatus.COMMUNICATING ->
                Disposition.CHECK_ECU_WITHOUT_WRITING
            else -> Disposition.STOP_AND_PRESERVE_EVIDENCE
        }
        return Assessment(disposition, warnings)
    }
}
