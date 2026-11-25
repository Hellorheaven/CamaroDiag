package com.camarodiag.gen5.ui

import com.camarodiag.gen5.domain.model.DiagnosticDtc
import org.junit.Assert.*
import org.junit.Test

class StandardObdDtcInventoryTest {
    private fun row(module: String, status: String, standard: Boolean = true) =
        DiagnosticDtc("P0300", module, status, status, "", "", standardObd = standard)

    @Test fun physicalRescanPreservesOtherEcusAndUnavailableModes() {
        val primary = row("OBD 0x7E8", "Confirmed")
        val secondary = row("OBD 0x7E9", "Confirmed")
        val pending = row("OBD 0x7E9", "Pending")
        val enhanced = row("OBD 0x7E9", "Confirmed", false)
        val updated = StandardObdDtcInventory.replace(listOf(primary, secondary, pending, enhanced),
            emptyList(), "OBD 0x7E9", setOf(7))
        assertEquals(listOf(primary, pending, enhanced), updated)
    }

    @Test fun clearOnlyRemovesConfirmedOrPendingStandardRecordsFromSelectedChannel() {
        val primary = row("OBD 0x7E8", "Confirmed")
        val permanent = row("OBD 0x7E8", "Permanent")
        val secondary = row("OBD 0x7E9", "Pending")
        val enhanced = row("OBD 0x7E8", "Confirmed", false)
        assertEquals(listOf(permanent, secondary, enhanced),
            StandardObdDtcInventory.afterClear(listOf(primary, permanent, secondary, enhanced), "OBD 0x7E8"))
    }
}
