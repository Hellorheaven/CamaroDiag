package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class StandardObdClearEvidenceTest {
    @Test fun confirmsOnlyExactPositiveMode04Response() {
        for (raw in listOf("44", "04\r44\r>", "SEARCHING...\r7E8 01 44\r>",
            "18 DA F1 10 01 44", "7E8 44\r7E9 01 44")) {
            assertTrue(raw,StandardObdClearEvidence.acknowledged(raw))
        }
        for (raw in listOf("", "OK", "NO DATA", "7F 04 44", "44 00", "41 44 01",
            "7E0 01 44", "44\r7F 04 22", "7E8 02 44 01", "04\r>")) {
            assertFalse(raw,StandardObdClearEvidence.acknowledged(raw))
        }
    }
}
