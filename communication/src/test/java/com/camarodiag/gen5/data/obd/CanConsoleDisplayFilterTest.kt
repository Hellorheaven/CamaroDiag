package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanConsoleDisplayFilterTest {
    private val standard = ObservedCanId(0x7E8, false)
    private val extendedSameNumber = ObservedCanId(0x7E8, true)
    private val extended = ObservedCanId(0x18DAF110, true)
    private val traffic = listOf(
        ParsedCanConsoleRecord("RX", "CAN ID=7E8 DLC=3 DATA=41 0C 1A"),
        ParsedCanConsoleRecord("RX", "CAN ID=7E9 DLC=3 DATA=41 0C 1B"),
        ParsedCanConsoleRecord("RX", "CAN ID=000007E8 DLC=3 DATA=41 0C 1C"),
        ParsedCanConsoleRecord("RX", "CAN ID=18DAF110 DLC=3 DATA=62 F1 90"),
        ParsedCanConsoleRecord("SYSTEM", "CAN ERROR", succeeded = false),
        ParsedCanConsoleRecord("TX", "7E0 02 01 0C")
    )

    @Test fun observedOptionsKeepCanWidthAndDoNotInventIdsFromTransmitOrErrors() {
        assertEquals(
            listOf(standard, extendedSameNumber, ObservedCanId(0x7E9, false), extended),
            CanConsoleDisplayFilter.observedIds(traffic, listOf(0x18DAF110.toInt()))
        )
        assertEquals(emptyList<ObservedCanId>(), CanConsoleDisplayFilter.observedIds(
            listOf(ParsedCanConsoleRecord("SYSTEM", "NO DATA")), emptyList()))
        assertEquals("7E8", CanConsoleDisplayFilter.displayId(standard))
        assertEquals("000007E8", CanConsoleDisplayFilter.displayId(extendedSameNumber))
        assertEquals("18DAF110", CanConsoleDisplayFilter.displayId(extended))
    }

    @Test fun globalMonitorByDefaultPreservesAllCanRxTxAndSystem() {
        assertEquals(traffic, traffic.filter { CanConsoleDisplayFilter.visible(it, null) })
    }

    @Test fun selectedIdDoesNotConfuseStandardAndExtendedWithSameNumericValue() {
        assertEquals(
            listOf(traffic[0], traffic[4], traffic[5]),
            traffic.filter { CanConsoleDisplayFilter.visible(it, standard) }
        )
        assertEquals(
            listOf(traffic[2], traffic[4]),
            traffic.filter { CanConsoleDisplayFilter.visible(it, extendedSameNumber) }
        )
        assertTrue(CanConsoleDisplayFilter.visibleId(0x18DAF110L, null))
        assertTrue(CanConsoleDisplayFilter.visibleId(0x18DAF110L, extended))
        assertFalse(CanConsoleDisplayFilter.visibleId(0x7E8L, extendedSameNumber))
        assertFalse(CanConsoleDisplayFilter.visibleId(0x18DAF110L, standard))
    }

    @Test fun untypedTraceDoesNotInventElevenBitEcuAddress() {
        assertEquals(emptyList<ObservedCanId>(), CanConsoleDisplayFilter.observedIds(
            emptyList(), listOf(0x7E8, 0x7E9)
        ))
    }

    @Test fun physicalTxFramesFollowTheSelectedEcuWithoutClaimingBroadcastTraffic() {
        val physical11 = ParsedCanConsoleRecord("TX", "7E0 02 01 0C")
        val physical29 = ParsedCanConsoleRecord("TX", "18DA10F1 03 22 F1 90")
        val broadcast = ParsedCanConsoleRecord("TX", "7DF 02 01 0C")
        assertTrue(CanConsoleDisplayFilter.visible(physical11, standard))
        assertFalse(CanConsoleDisplayFilter.visible(physical11, extendedSameNumber))
        assertTrue(CanConsoleDisplayFilter.visible(physical29, extended))
        assertFalse(CanConsoleDisplayFilter.visible(physical29, standard))
        assertFalse(CanConsoleDisplayFilter.visible(broadcast, standard))
        assertTrue(CanConsoleDisplayFilter.visible(broadcast, null))
        assertTrue(CanConsoleDisplayFilter.visible(
            ParsedCanConsoleRecord("SYSTEM", "CAN ERROR", succeeded = false), extended
        ))
    }

    @Test fun selectionNeverChangesDiagnosticCorrelation() {
        val request = requireNotNull(RawCanFrameParser.parse("7E0 02 01 0C"))
        val other = requireNotNull(RawCanFrameParser.parse("7E9 03 41 0C 1A"))
        val expected = requireNotNull(RawCanFrameParser.parse("7E8 03 41 0C 1A"))
        assertFalse(CanConsoleEcuRouting.isSelectedResponder(request, other))
        assertTrue(CanConsoleEcuRouting.isSelectedResponder(request, expected))
        assertTrue(CanConsoleDisplayFilter.visible(traffic[1], null))
        assertFalse(CanConsoleDisplayFilter.visible(traffic[1], standard))
        assertTrue(CanConsoleEcuRouting.isSelectedResponder(request, expected))
    }

    @Test fun typedTraceFramesRetainExactIdentifierWidthInDropdownAndList() {
        val frames = listOf(
            CanTraceFrame(1, null, 0x7E8, CanTraceDirection.RX, listOf(0x03), false),
            CanTraceFrame(2, null, 0x7E8, CanTraceDirection.RX, listOf(0x03), true),
            CanTraceFrame(3, null, 0x7E8, CanTraceDirection.RX, listOf(0x03), null),
            CanTraceFrame(4, null, 0x18DAF110, CanTraceDirection.RX, listOf(0x03), true)
        )
        assertEquals(listOf(standard, extendedSameNumber, extended),
            CanConsoleDisplayFilter.observedFrameIds(emptyList(), frames))
        assertEquals(listOf(frames[0]),
            frames.filter { CanConsoleDisplayFilter.visibleTrace(it, standard) })
        assertEquals(listOf(frames[1]),
            frames.filter { CanConsoleDisplayFilter.visibleTrace(it, extendedSameNumber) })
        assertEquals(frames, frames.filter { CanConsoleDisplayFilter.visibleTrace(it, null) })
    }

    @Test fun observedEcusRemainSelectableAfterRecentHistoryRollsOver() {
        val initial = listOf(
            ParsedCanConsoleRecord("RX", "CAN ID=7E8 DLC=3 DATA=41 0C 1A"),
            ParsedCanConsoleRecord("RX", "CAN ID=000007E8 DLC=3 DATA=41 0C 1B")
        )
        val known = CanConsoleDisplayFilter.accumulateObserved(emptySet(), initial)
        val last300 = listOf(
            ParsedCanConsoleRecord("RX", "CAN ID=7E9 DLC=3 DATA=41 0C 1C")
        )
        val retained = CanConsoleDisplayFilter.accumulateObserved(known, last300)
        assertEquals(
            listOf(standard, extendedSameNumber, ObservedCanId(0x7E9, false)),
            CanConsoleDisplayFilter.observedFrameIds(last300, emptyList(), retained)
        )
        assertEquals(3, retained.size)
        assertEquals(emptySet<ObservedCanId>(),
            CanConsoleDisplayFilter.accumulateObserved(emptySet(),
                listOf(ParsedCanConsoleRecord("SYSTEM", "NO DATA"))))
    }

    @Test fun observedIdCatalogueIsBoundedAgainstUntrustedCanTraffic() {
        val full = (0 until 4096).map { ObservedCanId(it.toLong(), true) }.toSet()
        val ignored = CanConsoleDisplayFilter.accumulateObserved(
            full, listOf(ParsedCanConsoleRecord("RX", "CAN ID=18DAF110 DLC=0 DATA=(empty)"))
        )
        assertEquals(full, ignored)
    }
}
