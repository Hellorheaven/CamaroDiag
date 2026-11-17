package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdsDtcDecoderTest {
    @Test
    fun decodesPowertrainChassisBodyAndNetworkDtcFamilies() {
        val payload = byteArrayOf(
            0xFF.toByte(),
            0x01, 0x01, 0x01, 0x08, // P010101, confirmed
            0x41, 0x23, 0x45, 0x04, // C012345, pending
            0x80.toByte(), 0x12, 0x34, 0x01, // B001234, failed
            0xC0.toByte(), 0x00, 0x01, 0x80.toByte() // U000001, warning requested
        )

        val report = UdsDtcDecoder.decodeReadDtcInformation(payload)

        assertEquals(0xFF, report.statusAvailabilityMask)
        assertEquals(listOf("P010101", "C012345", "B001234", "U000001"), report.records.map { it.code })
        assertTrue(report.records[0].status.confirmed)
        assertTrue(report.records[1].status.pending)
        assertTrue(report.records[2].status.testFailed)
        assertTrue(report.records[3].status.warningIndicatorRequested)
    }

    @Test
    fun preservesRawCodeAndAllStatusBits() {
        val report = UdsDtcDecoder.decodeReadDtcInformation(
            byteArrayOf(0xFF.toByte(), 0x01, 0x23, 0x45, 0xFF.toByte())
        )
        val record = report.records.single()

        assertEquals(0x012345, record.rawDtc)
        assertEquals("P012345", record.code)
        assertEquals(0xFF, record.statusByte)
        assertTrue(record.status.testFailed)
        assertTrue(record.status.testFailedThisOperationCycle)
        assertTrue(record.status.pending)
        assertTrue(record.status.confirmed)
        assertTrue(record.status.testNotCompletedSinceLastClear)
        assertTrue(record.status.testFailedSinceLastClear)
        assertTrue(record.status.testNotCompletedThisOperationCycle)
        assertTrue(record.status.warningIndicatorRequested)
    }

    @Test
    fun acceptsEmptyDtcListWhenAvailabilityMaskIsPresent() {
        val report = UdsDtcDecoder.decodeReadDtcInformation(byteArrayOf(0x7F))
        assertEquals(0x7F, report.statusAvailabilityMask)
        assertTrue(report.records.isEmpty())
    }

    @Test
    fun rejectsMissingAvailabilityMaskAndTruncatedRecords() {
        assertTrue(runCatching { UdsDtcDecoder.decodeReadDtcInformation(byteArrayOf()) }.isFailure)
        assertTrue(runCatching {
            UdsDtcDecoder.decodeReadDtcInformation(byteArrayOf(0xFF.toByte(), 0x01, 0x02))
        }.isFailure)
    }

    @Test
    fun unsetStatusFlagsRemainFalse() {
        val record = UdsDtcDecoder.decodeReadDtcInformation(
            byteArrayOf(0xFF.toByte(), 0x01, 0x01, 0x01, 0x00)
        ).records.single()
        assertFalse(record.status.testFailed)
        assertFalse(record.status.pending)
        assertFalse(record.status.confirmed)
        assertFalse(record.status.warningIndicatorRequested)
    }
}
