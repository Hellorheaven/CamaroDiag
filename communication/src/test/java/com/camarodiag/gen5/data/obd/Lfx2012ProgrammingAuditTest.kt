package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Lfx2012ProgrammingAuditTest {
    private val before = Lfx2012ProgrammingAudit.Snapshot(
        vin = "2G1TEST00C0000001", ecuName = "E39",
        calibrationIds = listOf("12612345"), verificationNumbers = listOf("1234ABCD"),
        dtcs = emptyList(), voltage = 12.6, communicationConfirmed = true,
        functionalCheckPassed = true, oemRelearnReviewed = true, capturedAtUtcMillis = 1L
    )

    @Test fun absentPostProgrammingCaptureIsAlwaysNotPerformed() {
        val report = Lfx2012ProgrammingAudit.review(before, null)
        assertEquals(Lfx2012ProgrammingAudit.Overall.NOT_PERFORMED, report.overall)
        assertEquals(9, report.before.size)
        assertTrue(report.after.isEmpty())
        assertFalse(report.physicalValidationComplete)
    }

    @Test fun completeInputsOnlyQualifyForManualReviewNotVehicleApproval() {
        val report = Lfx2012ProgrammingAudit.review(before, before.copy(capturedAtUtcMillis = 2L))
        assertEquals(Lfx2012ProgrammingAudit.Overall.READY_FOR_MANUAL_REVIEW, report.overall)
        assertFalse(report.physicalValidationComplete)
    }

    @Test fun changedVinOrNonchronologicalEvidenceRequiresReview() {
        assertEquals(Lfx2012ProgrammingAudit.Overall.REQUIRES_REVIEW,
            Lfx2012ProgrammingAudit.review(before,
                before.copy(vin = "1G1TEST00C0000001", capturedAtUtcMillis = 2L)).overall)
        assertEquals(Lfx2012ProgrammingAudit.Overall.REQUIRES_REVIEW,
            Lfx2012ProgrammingAudit.review(before, before.copy(capturedAtUtcMillis = 1L)).overall)
    }

    @Test fun unavailableDtcOrSkippedFunctionalChecksDoNotCountAsComplete() {
        val report = Lfx2012ProgrammingAudit.review(
            before,
            before.copy(dtcs = null, functionalCheckPassed = null,
                oemRelearnReviewed = false, capturedAtUtcMillis = 2L)
        )
        assertEquals(Lfx2012ProgrammingAudit.Overall.INCOMPLETE, report.overall)
    }

    @Test fun malformedVinAndNonpositiveVoltageCannotPassManualReviewGate() {
        for (badVin in listOf("INVALID", "2G1I0000000000001", "2G1TEST00B0000001")) {
            val report = Lfx2012ProgrammingAudit.review(
                before, before.copy(vin = badVin, capturedAtUtcMillis = 2L)
            )
            assertEquals(Lfx2012ProgrammingAudit.Overall.REQUIRES_REVIEW, report.overall)
            assertTrue(report.after.any {
                it.item == Lfx2012ProgrammingAudit.Item.VEHICLE_VIN &&
                    it.status == Lfx2012ProgrammingAudit.CheckStatus.REQUIRES_REVIEW
            })
        }
        for (voltage in listOf(0.0, -1.0)) {
            val report = Lfx2012ProgrammingAudit.review(
                before, before.copy(voltage = voltage, capturedAtUtcMillis = 2L)
            )
            assertEquals(Lfx2012ProgrammingAudit.Overall.REQUIRES_REVIEW, report.overall)
            assertTrue(report.after.any {
                it.item == Lfx2012ProgrammingAudit.Item.VOLTAGE &&
                    it.status == Lfx2012ProgrammingAudit.CheckStatus.REQUIRES_REVIEW
            })
        }
    }

    @Test fun failedCommunicationCannotBeReportedAsPassing() {
        val report = Lfx2012ProgrammingAudit.review(
            before, before.copy(communicationConfirmed = false, capturedAtUtcMillis = 2L))
        assertEquals(Lfx2012ProgrammingAudit.Overall.REQUIRES_REVIEW, report.overall)
    }
}
