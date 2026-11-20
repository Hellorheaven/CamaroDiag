package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Lfx2012ProgrammingAuditTest {
    private val before = Lfx2012ProgrammingAudit.Snapshot(
        vin = "2G1FF3D36C9104199", ecuName = "E39",
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
                before.copy(vin = "1G1FF3D36C9104199", capturedAtUtcMillis = 2L)).overall)
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

    @Test fun failedCommunicationCannotBeReportedAsPassing() {
        val report = Lfx2012ProgrammingAudit.review(
            before, before.copy(communicationConfirmed = false, capturedAtUtcMillis = 2L))
        assertEquals(Lfx2012ProgrammingAudit.Overall.REQUIRES_REVIEW, report.overall)
    }
}
