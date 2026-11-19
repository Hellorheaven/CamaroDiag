package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanTraceFilterTest {
    private val rxFrame = CanTraceFrame(
        lineNumber = 12,
        timestampSeconds = 1.25,
        canId = 0x7E8,
        direction = CanTraceDirection.RX,
        data = listOf(0x03, 0x41, 0x0C, 0x1A, 0xF8)
    )

    private val txFrame = CanTraceFrame(
        lineNumber = 13,
        timestampSeconds = 1.50,
        canId = 0x7E0,
        direction = CanTraceDirection.TX,
        data = listOf(0x02, 0x01, 0x0C)
    )

    @Test
    fun filtersByIdentifierDirectionAndDataPrefix() {
        val filter = CanTraceFilter(
            canId = 0x7E8,
            direction = CanTraceDirection.RX,
            dataPrefix = listOf(0x03, 0x41)
        )

        assertTrue(filter.matches(rxFrame))
        assertFalse(filter.matches(txFrame))
    }

    @Test
    fun searchesIdentifierPayloadAndDirection() {
        assertTrue(CanTraceFilter(searchText = "7E8").matches(rxFrame))
        assertTrue(CanTraceFilter(searchText = "03 41 0C").matches(rxFrame))
        assertTrue(CanTraceFilter(searchText = "RX").matches(rxFrame))
        assertFalse(CanTraceFilter(searchText = "DE AD").matches(rxFrame))
    }

    @Test
    fun appliesTimestampBoundsAndRejectsUnknownTimestampWhenBounded() {
        assertTrue(
            CanTraceFilter(fromTimestampSeconds = 1.0, toTimestampSeconds = 1.5)
                .matches(rxFrame)
        )
        assertFalse(
            CanTraceFilter(fromTimestampSeconds = 1.3).matches(rxFrame)
        )
        val unknownTime = rxFrame.copy(timestampSeconds = null)
        assertFalse(
            CanTraceFilter(fromTimestampSeconds = 0.0).matches(unknownTime)
        )
    }

    @Test
    fun parsesCanIdentifiersAndRejectsInvalidInput() {
        assertEquals(0x7E8, CanTraceFilter.parseCanId("0x7E8"))
        assertEquals(0x18DAF110, CanTraceFilter.parseCanId("18DAF110"))
        assertNull(CanTraceFilter.parseCanId("7G8"))
        assertNull(CanTraceFilter.parseCanId("123456789"))
    }

    @Test
    fun parsesBytePrefixesAndRejectsMalformedBytes() {
        assertEquals(
            listOf(0x03, 0x22, 0xF1),
            CanTraceFilter.parseDataPrefix("0x03, 22 F1")
        )
        assertEquals(emptyList<Int>(), CanTraceFilter.parseDataPrefix(""))
        assertNull(CanTraceFilter.parseDataPrefix("03 2  F1"))
        assertNull(CanTraceFilter.parseDataPrefix("GG"))
    }

    @Test
    fun preservesCaptureOrderAndHonorsResultLimit() {
        val filtered = listOf(txFrame, rxFrame).filterCanTrace(
            CanTraceFilter(direction = CanTraceDirection.RX),
            maxResults = 1
        )

        assertEquals(listOf(rxFrame), filtered)
        assertTrue(listOf(rxFrame).filterCanTrace(CanTraceFilter(), 0).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvertedTimestampRange() {
        CanTraceFilter(fromTimestampSeconds = 2.0, toTimestampSeconds = 1.0)
    }
}
