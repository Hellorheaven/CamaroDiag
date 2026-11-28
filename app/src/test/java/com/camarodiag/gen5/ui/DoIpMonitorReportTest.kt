package com.camarodiag.gen5.ui

import org.junit.Assert.*
import org.junit.Test

class DoIpMonitorReportTest {
    private val vin = "1G1FA1RX0J0100001"
    private val initial = DoIpMonitorReport(vin, 0x1000, 0x0E80, 0x1234,
        0xF40D, false, 100, emptyList(), complete = false, requestedSamples = 2)

    @Test fun partialCaptureKeepsOnlyVerifiedOrderedRows() {
        val first = initial.appendVerified(DoIpMonitorRow(1, 10, "12"), vin, 0x1000, 200)
        assertFalse(first.complete)
        assertEquals(1, first.samples.size)
        assertEquals(2, first.requestedSamples)
        assertEquals(200L, first.completedAtUtcMillis)
        assertTrue(runCatching { first.appendVerified(DoIpMonitorRow(2, 20, "34"),
            vin.dropLast(1) + "2", 0x1000, 300) }.isFailure)
        assertTrue(runCatching { first.appendVerified(DoIpMonitorRow(2, 20, "34"),
            vin, 0x1001, 300) }.isFailure)
        assertTrue(runCatching { first.appendVerified(DoIpMonitorRow(1, 20, "34"),
            vin, 0x1000, 300) }.isFailure)
        assertTrue(runCatching { first.appendVerified(DoIpMonitorRow(2, 9, "34"),
            vin, 0x1000, 300) }.isFailure)
        val full = first.appendVerified(DoIpMonitorRow(2, 20, "34"), vin, 0x1000, 300)
            .copy(complete = true)
        assertEquals(2, full.samples.size)
        assertTrue(runCatching { full.appendVerified(DoIpMonitorRow(3, 30, "56"),
            vin, 0x1000, 400) }.isFailure)
    }
}
