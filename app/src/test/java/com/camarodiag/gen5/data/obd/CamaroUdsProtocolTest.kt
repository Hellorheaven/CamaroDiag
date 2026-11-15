package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.CamaroEngine
import com.camarodiag.gen5.domain.model.ConfidenceLevel
import com.camarodiag.gen5.domain.model.MileageSourceReading
import org.junit.Assert.*
import org.junit.Test

class CamaroUdsProtocolTest {
    @Test
    fun parsesStandardLivePid() {
        assertArrayEquals(intArrayOf(0x1A, 0xF8), CamaroUdsProtocol.parsePidBytes("41 0C 1A F8", "0C"))
    }

    @Test
    fun parsesOdometerPidsWithTenthKilometreResolution() {
        val pidA6 = requireNotNull(CamaroUdsProtocol.parseOdometerPidA6("41 A6 00 12 D6 87"))
        val pidD3 = requireNotNull(CamaroUdsProtocol.parseOdometerPidD3("41 D3 00 12 D6 87"))

        assertEquals(123456.7, pidA6, 0.01)
        assertEquals(123456.7, pidD3, 0.01)
    }

    @Test
    fun parsesConfirmedAndPendingDtcs() {
        val codes = CamaroUdsProtocol.parseDtcResponses("43 01 71 00 00", "47 01 33 00 00")
        assertTrue(codes.any { it.code == "P0171" && !it.isPending })
        assertTrue(codes.any { it.code == "P0133" && it.isPending })
    }

    @Test
    fun doesNotTreatSingleModerateReadingAsTamperingEvidence() {
        val report = CamaroUdsProtocol.evaluateMileageConsistency(
            "",
            listOf(MileageSourceReading("ECM_A6", "ECM", "ECM", "PID A6", 123456.0, ConfidenceLevel.MODERATE, "", ""))
        )
        assertFalse(report.hasDiscrepancy)
        assertTrue(report.summaryEn.contains("One odometer-related value"))
    }

    @Test
    fun gen5AndGen6EngineRangesDoNotOverlap() {
        assertTrue(2012 in CamaroEngine.LFX.years)
        assertTrue(2020 in CamaroEngine.LGX.years)
        assertFalse(2020 in CamaroEngine.LFX.years)
    }
}
