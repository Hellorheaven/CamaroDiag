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

    @Test fun updateChangesOnlyTheTarget() {
        val rows = FunctionalTestPlanner.initial(modules, "BLE", true)
        val updated = FunctionalTestPlanner.updated(
            rows, "module:ECM", FunctionalTestOutcome.FAILED, "No response", "Sans réponse"
        )
        assertEquals(FunctionalTestOutcome.FAILED, updated.first { it.key == "module:ECM" }.outcome)
        assertEquals(FunctionalTestOutcome.PASSED, updated.first { it.key == "connector:BLE" }.outcome)
    }
}