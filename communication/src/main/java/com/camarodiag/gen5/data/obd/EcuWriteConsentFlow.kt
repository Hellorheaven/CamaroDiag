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
    class Approval internal constructor(val warningAcknowledged: Boolean, val confirmedYes: Boolean)

    fun permitsWrite(step: Step, confirmedYes: Boolean, sameConnection: Boolean): Boolean =
        step == Step.CONFIRMATION && confirmedYes && sameConnection

    fun approval(step: Step, confirmedYes: Boolean, sameConnection: Boolean): Approval? =
        if (permitsWrite(step, confirmedYes, sameConnection)) Approval(true, true) else null

    /** A consent starts with one physical/adapter session. An adapter switch,
     * logout or disconnection invalidates its YES even if another channel is
     * already connected by the time the user presses the button.
     */
    fun approvalForConnection(
        step: Step,
        confirmedYes: Boolean,
        warningConnection: Long?,
        currentConnection: Long
    ): Approval? = approval(
        step, confirmedYes,
        sameConnection = warningConnection != null &&
            warningConnection == currentConnection
    )

    /** The selected vehicle/profile must also remain identical from the
     * warning until the separate YES. Equality is checked again before TX.
     */
    fun approvalForContext(
        step: Step,
        confirmedYes: Boolean,
        warningConnection: Long?,
        currentConnection: Long,
        sameVehicle: Boolean
    ): Approval? = if (sameVehicle) {
        approvalForConnection(step, confirmedYes, warningConnection, currentConnection)
    } else null

    /**
     * A confirmation is not a perpetual permission. The protected transport
     * invokes this immediately before sending a mutating command, after all
     * command queues and network mutexes have been acquired.
     */
    fun requireCurrentForTransmit(approval: Approval, isContextCurrent: () -> Boolean) {
        check(approval.warningAcknowledged && approval.confirmedYes) {
            "ECU risk notice OK and separate YES are required."
        }
        check(isContextCurrent()) {
            "ECU operation cancelled: account, adapter or vehicle changed before transmission."
        }
    }
}
