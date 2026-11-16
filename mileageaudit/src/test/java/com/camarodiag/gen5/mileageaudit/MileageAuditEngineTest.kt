package com.camarodiag.gen5.mileageaudit

import com.camarodiag.gen5.domain.model.ConfidenceLevel
import com.camarodiag.gen5.domain.model.MileageSourceReading
import org.junit.Assert.*
import org.junit.Test

class MileageAuditEngineTest {
    private fun reading(
        id: String,
        km: Double?,
        confidence: ConfidenceLevel,
        profileEvidenceReference: String? = null
    ) = MileageSourceReading(
        id, id, id, "test response", km, confidence, "test", "test", profileEvidenceReference
    )

    @Test fun twoVerifiedIndependentModulesFlagLargeDifference() {
        val report = MileageAuditEngine.evaluate("VIN", listOf(
            reading("BCM", 100000.0, ConfidenceLevel.HIGH, profileEvidenceReference = "GM-SI-verified-profile"),
            reading("IPC", 120000.0, ConfidenceLevel.HIGH, profileEvidenceReference = "GM-SI-verified-profile")
        ))
        assertTrue(report.hasDiscrepancy)
        assertEquals(20000.0, report.maxDeltaKm, 0.01)
    }

    @Test fun highConfidenceWithoutVerifiedVehicleProfileDoesNotCountAsIndependent() {
        val report = MileageAuditEngine.evaluate("VIN", listOf(
            reading("BCM", 100000.0, ConfidenceLevel.HIGH),
            reading("IPC", 120000.0, ConfidenceLevel.HIGH)
        ))
        assertFalse(report.hasDiscrepancy)
    }

    @Test fun twoPidsFromPowertrainDoNotCountAsIndependentProof() {
        val report = MileageAuditEngine.evaluate("VIN", listOf(
            reading("ECM_A6", 100000.0, ConfidenceLevel.MODERATE),
            reading("ECM_D3", 120000.0, ConfidenceLevel.MODERATE)
        ))
        assertFalse(report.hasDiscrepancy)
    }

    @Test fun nonFiniteOdometerValuesAreIgnored() {
        val report = MileageAuditEngine.evaluate("VIN", listOf(
            reading("BCM", Double.POSITIVE_INFINITY, ConfidenceLevel.HIGH, profileEvidenceReference = "GM-SI-verified-profile"),
            reading("IPC", 120000.0, ConfidenceLevel.HIGH, profileEvidenceReference = "GM-SI-verified-profile")
        ))
        assertFalse(report.hasDiscrepancy)
        assertEquals(0.0, report.maxDeltaKm, 0.0)
    }

    @Test fun noDataDoesNotInventMileage() {
        val report = MileageAuditEngine.evaluate("VIN", listOf(reading("BCM", null, ConfidenceLevel.UNAVAILABLE)))
        assertFalse(report.hasDiscrepancy)
        assertEquals(0.0, report.maxDeltaKm, 0.0)
    }
}
