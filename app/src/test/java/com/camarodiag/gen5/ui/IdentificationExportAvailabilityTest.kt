package com.camarodiag.gen5.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentificationExportAvailabilityTest {
    @Test fun anAddressWithoutObservedIdentityCannotBeExported() {
        val state = CalibrationWorkspaceState(
            ecuNameResponse = "  ",
            respondingEcuAddresses = listOf("7E8")
        )
        assertFalse(state.canExportIdentification)
    }

    @Test fun ecuNameAloneIsExportableOnlyAfterBothReadsHaveFinished() {
        val observed = CalibrationWorkspaceState(ecuNameResponse = "E39")
        assertTrue(observed.canExportIdentification)
        assertFalse(observed.copy(isReading = true).canExportIdentification)
        assertFalse(observed.copy(isGmlanReading = true).canExportIdentification)
    }
}
