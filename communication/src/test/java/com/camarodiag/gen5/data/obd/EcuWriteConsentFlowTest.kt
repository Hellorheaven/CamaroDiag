package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuWriteConsentFlowTest {
    @Test fun warningOkNeverStartsEcuWrite() {
        val notice = EcuWriteConsentFlow.Step.WARNING
        assertFalse(EcuWriteConsentFlow.permitsWrite(notice, true, true))
        val confirmation = EcuWriteConsentFlow.afterWarningOk(notice)
        assertEquals(EcuWriteConsentFlow.Step.CONFIRMATION, confirmation)
        assertFalse(EcuWriteConsentFlow.permitsWrite(confirmation, false, true))
        assertTrue(EcuWriteConsentFlow.permitsWrite(confirmation, true, true))
        assertTrue(EcuWriteConsentFlow.approval(confirmation, true, true) != null)
        assertEquals(null, EcuWriteConsentFlow.approval(notice, true, true))
        assertEquals(null, EcuWriteConsentFlow.approval(confirmation, false, true))
        assertEquals(null, EcuWriteConsentFlow.approval(confirmation, true, false))
    }

    @Test fun warningAndYesMustReferToTheSameConnectionGeneration() {
        val warningEpoch = 42L
        assertEquals(null, EcuWriteConsentFlow.approvalForConnection(
            EcuWriteConsentFlow.Step.WARNING, true, warningEpoch, warningEpoch
        ))
        assertEquals(null, EcuWriteConsentFlow.approvalForConnection(
            EcuWriteConsentFlow.Step.CONFIRMATION, false, warningEpoch, warningEpoch
        ))
        assertEquals(null, EcuWriteConsentFlow.approvalForConnection(
            EcuWriteConsentFlow.Step.CONFIRMATION, true, warningEpoch, warningEpoch + 1
        ))
        assertEquals(null, EcuWriteConsentFlow.approvalForConnection(
            EcuWriteConsentFlow.Step.CONFIRMATION, true, null, warningEpoch
        ))
        assertTrue(EcuWriteConsentFlow.approvalForConnection(
            EcuWriteConsentFlow.Step.CONFIRMATION, true, warningEpoch, warningEpoch
        ) != null)
    }

    @Test fun changedVinOrVehicleProfileInvalidatesConsentBeforeDtcClear() {
        val step = EcuWriteConsentFlow.Step.CONFIRMATION
        assertEquals(null, EcuWriteConsentFlow.approvalForContext(
            step, true, 17L, 17L, sameVehicle = false
        ))
        assertEquals(null, EcuWriteConsentFlow.approvalForContext(
            step, true, 17L, 18L, sameVehicle = true
        ))
        assertEquals(null, EcuWriteConsentFlow.approvalForContext(
            EcuWriteConsentFlow.Step.WARNING, true, 17L, 17L, sameVehicle = true
        ))
        assertTrue(EcuWriteConsentFlow.approvalForContext(
            step, true, 17L, 17L, sameVehicle = true
        ) != null)
    }

    @Test fun operatorAuthorizationIsRecheckedWhenQueuedWriteActuallyStarts() {
        val approval = EcuWriteConsentFlow.approvalForContext(
            EcuWriteConsentFlow.Step.CONFIRMATION, true, 21L, 21L, sameVehicle = true
        )!!
        var accountActive = true
        var adapterGeneration = 21L
        var vehicleStillSelected = true
        var checks = 0
        val verify = {
            EcuWriteConsentFlow.requireCurrentForTransmit(approval) {
                checks++
                accountActive && adapterGeneration == 21L && vehicleStillSelected
            }
        }
        // The closure must read fresh state on every invocation, not capture
        // an initially true boolean when the user clicks YES.
        verify()
        assertEquals(1, checks)
        adapterGeneration++
        assertTrue(runCatching { verify() }.isFailure)
        vehicleStillSelected = false
        assertTrue(runCatching { verify() }.isFailure)
        adapterGeneration = 21L
        assertTrue(runCatching { verify() }.isFailure)
        vehicleStillSelected = true
        accountActive = false
        assertTrue(runCatching { verify() }.isFailure)
        assertEquals(5, checks)
    }

    @Test fun transmitGateRejectsAnAbsentYesAndNeverConsultsTheContext() {
        val warningOnly = EcuWriteConsentFlow.Approval(true, false)
        var called = false
        assertTrue(runCatching {
            EcuWriteConsentFlow.requireCurrentForTransmit(warningOnly) {
                called = true
                true
            }
        }.isFailure)
        assertFalse(called)
    }

    @Test fun closeOrNoOrChangedPhysicalConnectionNeverWrites() {
        val confirmation = EcuWriteConsentFlow.Step.CONFIRMATION
        assertFalse(EcuWriteConsentFlow.permitsWrite(confirmation, false, false))
        assertFalse(EcuWriteConsentFlow.permitsWrite(confirmation, true, false))
        assertFalse(EcuWriteConsentFlow.permitsWrite(EcuWriteConsentFlow.Step.WARNING, true, true))
        // A new transmission always begins at the warning again.
        assertEquals(EcuWriteConsentFlow.Step.WARNING, EcuWriteConsentFlow.Step.WARNING)
    }
}
