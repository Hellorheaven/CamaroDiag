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

    @Test fun closeOrNoOrChangedPhysicalConnectionNeverWrites() {
        val confirmation = EcuWriteConsentFlow.Step.CONFIRMATION
        assertFalse(EcuWriteConsentFlow.permitsWrite(confirmation, false, false))
        assertFalse(EcuWriteConsentFlow.permitsWrite(confirmation, true, false))
        assertFalse(EcuWriteConsentFlow.permitsWrite(EcuWriteConsentFlow.Step.WARNING, true, true))
        // A new transmission always begins at the warning again.
        assertEquals(EcuWriteConsentFlow.Step.WARNING, EcuWriteConsentFlow.Step.WARNING)
    }
}
