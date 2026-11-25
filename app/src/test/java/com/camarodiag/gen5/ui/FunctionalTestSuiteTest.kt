package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.CamaroUdsProtocol
import com.camarodiag.gen5.domain.model.AdapterCapabilities
import com.camarodiag.gen5.domain.model.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionalTestSuiteTest {
    private val modules = CamaroUdsProtocol.buildTopology(VehicleProfile(), AdapterCapabilities())

    @Test fun allKnownTransportsAndEveryModuleAreListed() {
        val rows = FunctionalTestPlanner.initial(modules, "USB", physicalConnection = true)
        assertEquals(5, rows.count { it.group == "connector" })
        assertEquals(modules.size, rows.count { it.group == "ecu" })
        assertEquals(4, rows.count { it.group == "service" })
        assertEquals(1, rows.count { it.group == "connector" && it.outcome == FunctionalTestOutcome.PASSED })
        assertTrue(rows.filter { it.group == "ecu" }.all { it.outcome == FunctionalTestOutcome.NOT_TESTED })
    }

    @Test fun demoOrOfflineCannotClaimPass() {
        for (kind in listOf("BLUETOOTH", "BLE", "USB", "WIFI", "J2534_BRIDGE")) {
            val rows = FunctionalTestPlanner.initial(modules, kind, physicalConnection = false)
            assertTrue(rows.all { it.outcome == FunctionalTestOutcome.NOT_TESTED })
        }
    }

    @Test fun exportRequiresCompletedRunWithCapturedEvidenceContext() {
        val context = FunctionalTestContext("1G1FA1RXXR0123456","Test adapter","J2534_BRIDGE",42)
        val rows = FunctionalTestPlanner.initial(modules,"J2534_BRIDGE",true)
        val started = FunctionalTestState(running = true, startedAtMillis = 1,
            checks = rows, context = context)
        assertTrue(!started.canExport)
        val completed = started.copy(running = false, finishedAtMillis = 2)
        assertTrue(completed.canExport)
        assertEquals(context,completed.context)
        assertTrue(!completed.copy(context = null).canExport)
        assertTrue(!completed.copy(checks = emptyList()).canExport)
        assertTrue(!completed.copy(finishedAtMillis = null).canExport)
        // Interrupted evidence may be exported, but does not become a PASS.
        val interrupted = completed.copy(error = "Disconnected")
        assertTrue(interrupted.canExport)
        assertTrue(interrupted.checks.filter { it.group == "ecu" }
            .all { it.outcome == FunctionalTestOutcome.NOT_TESTED })
    }

    @Test fun genericObdResponderDoesNotProveCamaroEcu() {
        val rows = FunctionalTestPlanner.initial(emptyList(), "BLUETOOTH", true)
        val observed = FunctionalTestPlanner.withStandardResponders(rows, listOf("7E8", "7E8", "18DAF110"))
        assertEquals(2, observed.count { it.group == "obd_responder" && it.outcome == FunctionalTestOutcome.PASSED })
        assertTrue(observed.none { it.group == "ecu" })
        val checked = FunctionalTestPlanner.withDtcModeEvidence(observed, mapOf(3 to 0, 7 to null, 10 to 2))
        assertEquals(FunctionalTestOutcome.PASSED, checked.first { it.key == "service:dtc-mode:03" }.outcome)
        assertEquals(FunctionalTestOutcome.NOT_TESTED, checked.first { it.key == "service:dtc-mode:07" }.outcome)
        assertEquals(FunctionalTestOutcome.PASSED, checked.first { it.key == "service:dtc-mode:0A" }.outcome)
    }

    @Test fun foreignVinIsNotCamaroCandidate() {
        val matcher = com.camarodiag.gen5.domain.model.VehicleVinMatcher
        assertTrue(matcher.isCamaroCandidate("2G1FF3D30C9000001"))
        assertTrue(matcher.isCamaroCandidate("1G1FA1RXXR0123456"))
        assertTrue(!matcher.isCamaroCandidate("3FMTK3SU0PMA00001"))
        assertTrue(!matcher.isCamaroCandidate(null))
        val context = FunctionalTestContext("", "OBDII", "BLUETOOTH", 3, observedVin = "3FMTK3SU0PMA00001")
        assertEquals("", context.selectedVin)
        assertEquals("3FMTK3SU0PMA00001", context.observedVin)
    }

    @Test fun updateChangesOnlyTheTarget() {
        val rows = FunctionalTestPlanner.initial(modules, "BLE", true)
        val updated = FunctionalTestPlanner.updated(
            rows, "module:ECM", FunctionalTestOutcome.FAILED, "No response", "Sans réponse"
        )
        assertEquals(FunctionalTestOutcome.FAILED, updated.first { it.key == "module:ECM" }.outcome)
        assertEquals(FunctionalTestOutcome.PASSED, updated.first { it.key == "connector:BLE" }.outcome)
    }
}