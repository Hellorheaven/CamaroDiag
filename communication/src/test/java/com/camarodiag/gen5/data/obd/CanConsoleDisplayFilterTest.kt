package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanConsoleDisplayFilterTest {
    private val traffic = listOf(
        ParsedCanConsoleRecord("RX", "CAN ID=7E8 DLC=3 DATA=41 0C 1A"),
        ParsedCanConsoleRecord("RX", "CAN ID=7E9 DLC=3 DATA=41 0C 1B"),
        ParsedCanConsoleRecord("RX", "CAN ID=18DAF110 DLC=3 DATA=62 F1 90"),
        ParsedCanConsoleRecord("SYSTEM", "CAN ERROR", succeeded = false),
        ParsedCanConsoleRecord("TX", "7E0 02 01 0C")
    )

    @Test fun discoveredOptionsAreFromActualFramesOnly() {
        assertEquals(listOf(0x7E8L, 0x7E9L, 0x18DAF110L),
            CanConsoleDisplayFilter.observedIds(traffic, listOf(0x7E8)))
        assertEquals(emptyList<Long>(), CanConsoleDisplayFilter.observedIds(
            listOf(ParsedCanConsoleRecord("SYSTEM", "NO DATA")), emptyList()))
        assertEquals("7E8", CanConsoleDisplayFilter.displayId(0x7E8))
        assertEquals("18DAF110", CanConsoleDisplayFilter.displayId(0x18DAF110))
    }

    @Test fun globalMonitorByDefaultPreservesAllCanRxTxAndSystem() {
        assertEquals(traffic, traffic.filter { CanConsoleDisplayFilter.visible(it, null) })
    }

    @Test fun selectingAnObservedIdOnlyFiltersPresentation() {
        val filtered = traffic.filter { CanConsoleDisplayFilter.visible(it, 0x7E8L) }
        assertEquals(listOf("RX", "SYSTEM"), filtered.map { it.direction })
        assertTrue(filtered.first().content.contains("ID=7E8"))
        assertFalse(CanConsoleDisplayFilter.visible(traffic[1], 0x7E8L))
        assertTrue(CanConsoleDisplayFilter.visibleId(0x18DAF110L, null))
        assertFalse(CanConsoleDisplayFilter.visibleId(0x18DAF110L, 0x7E8L))
    }

    @Test fun selectedFilterCannotChangeDiagnosticCorrelation() {
        val request = requireNotNull(RawCanFrameParser.parse("7E0 02 01 0C"))
        val other = requireNotNull(RawCanFrameParser.parse("7E9 03 41 0C 1A"))
        val expected = requireNotNull(RawCanFrameParser.parse("7E8 03 41 0C 1A"))
        assertFalse(CanConsoleEcuRouting.isSelectedResponder(request, other))
        assertTrue(CanConsoleEcuRouting.isSelectedResponder(request, expected))
        assertTrue(CanConsoleDisplayFilter.visible(traffic[1], null))
        assertFalse(CanConsoleDisplayFilter.visible(traffic[1], 0x7E8L))
        assertTrue(CanConsoleEcuRouting.isSelectedResponder(request, expected))
    }
}
