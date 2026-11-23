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

    fun permitsWrite(step: Step, confirmedYes: Boolean, sameConnection: Boolean): Boolean =
        step == Step.CONFIRMATION && confirmedYes && sameConnection
}
