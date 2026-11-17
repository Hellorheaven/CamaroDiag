package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdsDtcDecoderTest {
    @Test
    fun decodesJ2012Format00AcrossPowertrainChassisBodyAndNetworkFamilies() {
        val payload = byteArrayOf(
            0xFF.toByte(), 0x00,
            0x01, 0x01, 0x01, 0x08, // P0101-01, confirmed
            0x92.toByte(), 0x34, 0x11, 0x04, // B1234-11, pending
            0x41, 0x23, 0x45, 0x01, // C0123-45, failed
            0xC0.toByte(), 0x00, 0x01, 0x80.toByte() // U0000-01, warning requested
        )

        val report = UdsDtcDecoder.decodeReadDtcInformation(payload)

        assertEquals(0xFF, report.statusAvailabilityMask)
        assertEquals(0x00, report.formatIdentifier)
        assertEquals(UdsDtcFormat.SAE_J2012_DA_FORMAT_00, report.format)
        assertEquals(
            listOf("P0101-01", "B1234-11", "C0123-45", "U0000-01"),
            report.records.map { it.code }
        )
        assertTrue(report.records[0].status.confirmed)
        assertTrue(report.records[1].status.pending)
        assertTrue(report.records[2].status.testFailed)
        assertTrue(report.records[3].status.warningIndicatorRequested)
    }

    @Test
    fun keepsIso14229ManufacturerSpecificDtcOpaque() {
        val report = UdsDtcDecoder.decodeReadDtcInformation(
            byteArrayOf(0xFF.toByte(), 0x01, 0x01, 0x23, 0x45, 0xFF.toByte())
        )
        val record = report.records.single()

        assertEquals(UdsDtcFormat.ISO_14229_1, report.format)
        assertEquals(0x012345, record.rawDtc)
        assertEquals("0x012345", record.code)
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
    fun keepsSaeJ2012Format04OpaqueUntilItsDistinctBaseCodeTableIsAvailable() {
        val report = UdsDtcDecoder.decodeReadDtcInformation(
            byteArrayOf(0xFF.toByte(), 0x04, 0x01, 0x23, 0x45, 0x08)
        )
        assertEquals(UdsDtcFormat.SAE_J2012_DA_FORMAT_04, report.format)
        assertEquals("0x012345", report.records.single().code)
    }

    @Test
    fun keepsUnknownDtcFormatOpaqueInsteadOfGuessing() {
        val report = UdsDtcDecoder.decodeReadDtcInformation(
            byteArrayOf(0xFF.toByte(), 0x7F, 0x01, 0x02, 0x03, 0x00)
        )
        assertNull(report.format)
        assertEquals("0x010203", report.records.single().code)
    }

    @Test
    fun acceptsEmptyDtcListWhenBothHeaderBytesArePresent() {
        val report = UdsDtcDecoder.decodeReadDtcInformation(byteArrayOf(0x7F, 0x01))
        assertEquals(0x7F, report.statusAvailabilityMask)
        assertEquals(0x01, report.formatIdentifier)
        assertTrue(report.records.isEmpty())
    }

    @Test
    fun rejectsMissingHeaderBytesAndTruncatedRecords() {
        assertTrue(runCatching { UdsDtcDecoder.decodeReadDtcInformation(byteArrayOf()) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.decodeReadDtcInformation(byteArrayOf(0xFF.toByte())) }.isFailure)
        assertTrue(runCatching {
            UdsDtcDecoder.decodeReadDtcInformation(byteArrayOf(0xFF.toByte(), 0x01, 0x01, 0x02))
        }.isFailure)
    }

    @Test
    fun mapsUdsDtcToSharedModelWithoutInventingOemMeaning() {
        val record = UdsDtcDecoder.decodeReadDtcInformation(
            byteArrayOf(0xFF.toByte(), 0x01, 0x01, 0x23, 0x45, 0x04)
        ).records.single()

        val diagnostic = record.toDiagnosticDtc("BCM")

        assertEquals("BCM", diagnostic.module)
        assertEquals("0x012345", diagnostic.code)
        assertTrue(diagnostic.isPending)
        assertTrue(diagnostic.descEn.contains("verified OEM definition"))
        assertTrue(diagnostic.descFr.contains("Aucune définition constructeur vérifiée"))
    }

    @Test
    fun unsetStatusFlagsRemainFalse() {
        val record = UdsDtcDecoder.decodeReadDtcInformation(
            byteArrayOf(0xFF.toByte(), 0x01, 0x01, 0x01, 0x01, 0x00)
        ).records.single()
        assertFalse(record.status.testFailed)
        assertFalse(record.status.pending)
        assertFalse(record.status.confirmed)
        assertFalse(record.status.warningIndicatorRequested)
    }
}
