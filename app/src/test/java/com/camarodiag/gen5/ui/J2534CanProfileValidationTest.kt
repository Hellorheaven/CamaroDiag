package com.camarodiag.gen5.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class J2534CanProfileValidationTest {
    @Test fun acceptsDistinctValidElevenBitCanIds() {
        assertTrue(J2534CanProfileValidation.isValid("7E0", "7E8", "33"))
        assertTrue(J2534CanProfileValidation.isValid("000", "7FF", "35"))
        assertTrue(J2534CanProfileValidation.isValid("7e0", "7e8", "63"))
    }

    @Test fun acceptsDistinctValidTwentyNineBitCanIds() {
        assertTrue(J2534CanProfileValidation.isValid("18DAF110", "18DA10F1", "34"))
        assertTrue(J2534CanProfileValidation.isValid("00000000", "1FFFFFFF", "36"))
        assertTrue(J2534CanProfileValidation.isValid("18DAF110", "18DA10F1", "64"))
    }

    @Test fun rejectsNonHexadecimalOverflowAndIdenticalIds() {
        assertFalse(J2534CanProfileValidation.isValid("ZZZ", "7E8", "33"))
        assertFalse(J2534CanProfileValidation.isValid("7E0", "7E0", "33"))
        assertFalse(J2534CanProfileValidation.isValid("800", "7E8", "33"))
        assertFalse(J2534CanProfileValidation.isValid("FFFFFFFF", "18DA10F1", "34"))
        assertFalse(J2534CanProfileValidation.isValid("18DAF110", "18DAF110", "34"))
    }

    @Test fun rejectsMismatchedWidthWrongProtocolAndInputForms() {
        assertFalse(J2534CanProfileValidation.isValid("7E0", "18DA10F1", "33"))
        assertFalse(J2534CanProfileValidation.isValid("18DAF110", "18DA10F1", "33"))
        assertFalse(J2534CanProfileValidation.isValid("7E0", "7E8", "34"))
        assertFalse(J2534CanProfileValidation.isValid("7E0", "7E8", "99"))
        assertFalse(J2534CanProfileValidation.isValid("0x7E0", "0x7E8", "33"))
        assertFalse(J2534CanProfileValidation.isValid("7E0 ", "7E8", "33"))
    }
}
