package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class ObdStationaryEvidenceTest {
    @Test fun zeroRequiresCompletePositiveRecords() {
        assertTrue(ObdStationaryEvidence.provesZero(0x0D, "010D\r41 0D 00\r>"))
        assertTrue(ObdStationaryEvidence.provesZero(0x0C, "41 0C 00 00"))
        assertTrue(ObdStationaryEvidence.provesZero(0x0D, "410D00\r410D00"))
        assertTrue(ObdStationaryEvidence.provesZero(0x0C, "7E8 04 41 0C 00 00"))
    }

    @Test fun movingRunningOrUnknownNeverAuthorizesReset() {
        for (raw in listOf("NO DATA", "410D", "410D0000", "410D01",
            "410D00\r410D01", "410D00\r7F0111", "410C0000", "OK", "010D"))
            assertFalse(raw, ObdStationaryEvidence.provesZero(0x0D, raw))
        for (raw in listOf("410C0001", "410C0100", "410C00", "410C000000",
            "410C0000\r410C0001", "410C0000\rCAN ERROR"))
            assertFalse(raw, ObdStationaryEvidence.provesZero(0x0C, raw))
        assertFalse(ObdStationaryEvidence.provesZero(0x05, "410500"))
    }
}
