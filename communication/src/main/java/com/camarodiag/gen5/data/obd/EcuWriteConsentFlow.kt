package com.camarodiag.gen5.data.obd

/**
 * Two separate operator actions are required before an ECU write or an
 * arbitrary raw CAN command which might mutate an ECU:
 * (1) acknowledge the liability/risk warning with OK;
 * (2) explicitly answer YES, still on the same physical connection.
 *
 * This is a user-confirmation workflow, not an invented OEM key,
 * license or service allowlist. Cancelling or declining cannot transmit.
 */
object EcuWriteConsentFlow {
    enum class Step { WARNING, CONFIRMATION }

    fun afterWarningOk(current: Step): Step =
        if (current == Step.WARNING) Step.CONFIRMATION else current

    // Approval is issued ONLY after the warning was acknowledged and YES was
    // selected while the original physical connection was still current.
    data class Approval internal constructor(val warningAcknowledged: Boolean, val confirmedYes: Boolean)

    fun permitsWrite(step: Step, confirmedYes: Boolean, sameConnection: Boolean): Boolean =
        step == Step.CONFIRMATION && confirmedYes && sameConnection

    fun approval(step: Step, confirmedYes: Boolean, sameConnection: Boolean): Approval? =
        if (permitsWrite(step, confirmedYes, sameConnection)) Approval(true, true) else null
}
