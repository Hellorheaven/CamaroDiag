package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardObdClearEvidenceTest {
    @Test fun completeAcknowledgmentsInSupportedElmFormats() {
        for (raw in listOf("44", "04\r44\r>", "7E8 01 44",
            "18DAF110 01 44", "7E8 2 01 44",
            "7E8 8 01 44 00 00 00 00 00 00",
            "7E8 01 44 00 00 00 00 00 00",
            "7E8 01 44\r7E9 01 44")) {
            assertTrue(raw, StandardObdClearEvidence.acknowledged(raw))
        }
    }

    @Test fun malformedOrMixedAcknowledgmentsDoNotProveClear() {
        for (raw in listOf("04", "NO DATA", "OK", "7F0411",
            "7E8 3 01 44", "7E8 02 44 00", "7E8 01 44 FF",
            "7E8 8 01 44 00 00 00 00 00 FF",
            "7E0 01 44", "44\r7F0411", "44\rCAN ERROR")) {
            assertFalse(raw, StandardObdClearEvidence.acknowledged(raw))
        }
    }
}
