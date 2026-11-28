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

    @Test fun clearReadbackKeepsUnverifiedAndPermanentRecordsRatherThanTrustingAck() {
        val module = "OBD 0x7E8"
        val confirmed = row(module, "Confirmed")
        val pending = row(module, "Pending")
        val permanent = row(module, "Permanent")
        val unrelated = row("OBD 0x7E9", "Confirmed")
        val enhanced = row(module, "Confirmed", false)
        val previous = listOf(confirmed, pending, permanent, unrelated, enhanced)
        assertEquals(listOf(pending, permanent, unrelated, enhanced),
            StandardObdDtcInventory.replace(previous, emptyList(), module, setOf(7, 10)))
        assertEquals(previous,
            StandardObdDtcInventory.replace(previous, emptyList(), module, setOf(3, 7, 10)))
        val stillPresent = row(module, "Confirmed")
        assertTrue(StandardObdDtcInventory.replace(previous, listOf(stillPresent), module,
            setOf(7, 10)).contains(stillPresent))
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
