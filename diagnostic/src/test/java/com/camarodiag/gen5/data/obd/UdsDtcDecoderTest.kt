package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdsDtcDecoderTest {
    @Test fun severityQueriesPreserveRawFieldsAndRejectMismatches() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val countRequest = UdsDtcDecoder.severityRequest(7, 0x80, 8, 0)
        assertArrayEquals(hex("19078008"), countRequest)
        assertEquals(2, UdsDtcDecoder.decodeSeverity(countRequest, hex("590708010002")).count)
        val listRequest = UdsDtcDecoder.severityRequest(8, 0x80, 8, 0)
        val report = UdsDtcDecoder.decodeSeverity(listRequest, hex("590808801212345608"))
        assertEquals(UdsSeverityRecord(0x123456, 0x80, 0x12, 8), report.records.single())
        val detailRequest = UdsDtcDecoder.severityRequest(9, 255, 255, 0x123456)
        assertArrayEquals(hex("1909123456"), detailRequest)
        assertTrue(UdsDtcDecoder.decodeSeverity(detailRequest, hex("590908")).records.isEmpty())
        assertEquals(0, UdsDtcDecoder.decodeSeverity(detailRequest, hex("590908401212345600")).records.single().status)
        assertTrue(runCatching { UdsDtcDecoder.decodeSeverity(detailRequest, hex("590908801212345708")) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.decodeSeverity(listRequest, hex("590808401212345608")) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.decodeSeverity(listRequest, hex("590808801212345601")) }.isFailure)
    }

    @Test fun faultCountersPreserveSignedByteWithoutStatusInference() {
        assertTrue(UdsDtcDecoder.decodeFaultCounters(byteArrayOf(0x59, 0x14)).isEmpty())
        val report = UdsDtcDecoder.decodeFaultCounters(byteArrayOf(0x59, 0x14,
            1, 2, 3, 127, 1, 2, 4, 0x80.toByte(), 1, 2, 5, 0xFF.toByte()))
        assertEquals(listOf(127, -128, -1), report.map { it.counter })
        assertTrue(runCatching { UdsDtcDecoder.decodeFaultCounters(byteArrayOf(0x59, 0x14, 1)) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.decodeFaultCounters(byteArrayOf(0x59, 0x14, 1, 2, 3, 1, 1, 2, 3, 2)) }.isFailure)
    }

    @Test fun supportedDtcInventoryIncludesInactiveCodes() {
        val report = UdsDtcDecoder.decodeSupportedDtcs(byteArrayOf(0x59, 0x0A, 0x08,
            0x12, 0x34, 0x56, 0, 0x12, 0x34, 0x57, 8))
        assertEquals(listOf(0, 8), report.records.map { it.statusByte })
        assertTrue(runCatching { UdsDtcDecoder.decodeSupportedDtcs(byteArrayOf(0x59, 0x0A, 8, 1, 2, 3, 1)) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.decodeSupportedDtcs(byteArrayOf(0x59, 0x0A, 8, 1, 2, 3, 0, 1, 2, 3, 8)) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.decodeSupportedDtcs(byteArrayOf(0x59, 2, 8)) }.isFailure)
    }

    @Test fun permanentDtcsPreserveCurrentStatusAndRejectWrongReports() {
        assertTrue(UdsDtcDecoder.decodePermanentDtcs(byteArrayOf(0x59, 0x15, 8)).records.isEmpty())
        val report = UdsDtcDecoder.decodePermanentDtcs(byteArrayOf(0x59, 0x15, 8, 1, 2, 3, 0, 1, 2, 4, 8))
        assertEquals(listOf(0, 8), report.records.map { it.statusByte })
        assertEquals(listOf(0x010203, 0x010204), report.records.map { it.rawDtc })
        for (reply in listOf(byteArrayOf(0x59, 0x0A, 8), byteArrayOf(0x59, 0x15),
            byteArrayOf(0x59, 0x15, 8, 1), byteArrayOf(0x59, 0x15, 8, 1, 2, 3, 1),
            byteArrayOf(0x59, 0x15, 8, 1, 2, 3, 0, 1, 2, 3, 8))) {
            assertTrue(runCatching { UdsDtcDecoder.decodePermanentDtcs(reply) }.isFailure)
        }
    }

    @Test fun historicalDtcCanBeAbsentOrHaveCurrentStatusZero() {
        for (sub in 0x0B..0x0E) {
            assertTrue(UdsDtcDecoder.decodeHistoricalDtc(sub, byteArrayOf(0x59, sub.toByte(), 8)).records.isEmpty())
            val report = UdsDtcDecoder.decodeHistoricalDtc(sub, byteArrayOf(0x59, sub.toByte(), 8, 1, 2, 3, 0))
            assertEquals(0x010203, report.records.single().rawDtc)
            assertEquals(0, report.records.single().statusByte)
            assertTrue(runCatching { UdsDtcDecoder.decodeHistoricalDtc(sub, byteArrayOf(0x59, sub.toByte(), 8, 1)) }.isFailure)
            assertTrue(runCatching { UdsDtcDecoder.decodeHistoricalDtc(sub, byteArrayOf(0x59, sub.toByte(), 8, 1, 2, 3, 1)) }.isFailure)
        }
        assertTrue(runCatching { UdsDtcDecoder.decodeHistoricalDtc(0x0B, byteArrayOf(0x59, 0x0C, 8)) }.isFailure)
    }

    @Test fun snapshotIndexPreservesDistinctRecordsPerDtc() {
        assertEquals(listOf(UdsSnapshotReference(0x123456, 1), UdsSnapshotReference(0x123456, 2)),
            UdsDtcDecoder.decodeSnapshotIndex(byteArrayOf(0x59, 3, 0x12, 0x34, 0x56, 1, 0x12, 0x34, 0x56, 2)))
        assertTrue(UdsDtcDecoder.decodeSnapshotIndex(byteArrayOf(0x59, 3)).isEmpty())
        assertTrue(runCatching { UdsDtcDecoder.decodeSnapshotIndex(byteArrayOf(0x59, 3, 1, 2)) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.decodeSnapshotIndex(byteArrayOf(0x59, 3, 1, 2, 3, 4, 1, 2, 3, 4)) }.isFailure)
    }

    @Test fun standardStatusReportAcceptsNoDtcAndRejectsInventedFormatByte() {
        val empty = UdsDtcDecoder.decodeStatusMaskReport(byteArrayOf(0x7F), 0xFF)
        assertNull(empty.formatIdentifier)
        assertTrue(empty.records.isEmpty())
        assertTrue(runCatching {
            UdsDtcDecoder.decodeStatusMaskReport(byteArrayOf(0x7F, 0x01), 0xFF)
        }.isFailure)
        val valid = UdsDtcDecoder.decodeStatusMaskReport(
            byteArrayOf(0x09, 0x12, 0x34, 0x56, 0x01), 0x01)
        assertEquals("0x123456", valid.records.single().code)
        assertTrue(runCatching {
            UdsDtcDecoder.decodeStatusMaskReport(byteArrayOf(0x09, 0x12, 0x34, 0x56, 0x08), 0x01)
        }.isFailure)
    }

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
    fun mapsAllEightUdsStatusBitsIntoTheHumanReadableSummary() {
        val record = UdsDtcDecoder.decodeReadDtcInformation(
            byteArrayOf(0xFF.toByte(), 0x01, 0x01, 0x23, 0x45, 0xFF.toByte())
        ).records.single()

        val diagnostic = record.toDiagnosticDtc("ECM")

        listOf(
            "failed",
            "failed this operation cycle",
            "pending",
            "confirmed",
            "test not completed since last clear",
            "failed since last clear",
            "test not completed this operation cycle",
            "warning indicator requested"
        ).forEach { state -> assertTrue("Missing English UDS state: $state", diagnostic.statusEn.contains(state)) }
        listOf(
            "défaut présent",
            "défaut présent pendant ce cycle de fonctionnement",
            "en attente",
            "confirmé",
            "test non terminé depuis le dernier effacement",
            "défaut détecté depuis le dernier effacement",
            "test non terminé pendant ce cycle de fonctionnement",
            "voyant demandé"
        ).forEach { state -> assertTrue("État UDS français absent : $state", diagnostic.statusFr.contains(state)) }
    }

    @Test
    fun ignoresStatusBitsNotAdvertisedByTheAvailabilityMask() {
        val record = UdsDtcDecoder.decodeReadDtcInformation(
            byteArrayOf(0x09, 0x01, 0x01, 0x23, 0x45, 0xFF.toByte())
        ).records.single()

        assertEquals(0xFF, record.statusByte)
        assertTrue(record.status.testFailed)
        assertTrue(record.status.confirmed)
        assertFalse(record.status.testFailedThisOperationCycle)
        assertFalse(record.status.pending)
        assertFalse(record.status.testNotCompletedSinceLastClear)
        assertFalse(record.status.testFailedSinceLastClear)
        assertFalse(record.status.testNotCompletedThisOperationCycle)
        assertFalse(record.status.warningIndicatorRequested)
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
    @Test
    fun countReportPreservesMaskFormatAndUnsignedCount() {
        val report = UdsDtcDecoder.decodeCountByStatusMask(byteArrayOf(0x59, 1, 0xFF.toByte(), 4, 0xFE.toByte(), 0xDC.toByte()))
        assertEquals(255, report.availabilityMask)
        assertEquals(4, report.formatIdentifier)
        assertEquals(0xFEDC, report.count)
        assertTrue(runCatching { UdsDtcDecoder.decodeCountByStatusMask(byteArrayOf(0x59, 2, 0, 0, 0, 0)) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.decodeCountByStatusMask(byteArrayOf(0x59, 1, 0, 0, 0)) }.isFailure)
    }

    @Test fun detailPreservesUnknownRecordsAndRejectsWrongFault() {
        assertArrayEquals(byteArrayOf(0x19, 4, 0x12, 0x34, 0x56, 1),
            UdsDtcDecoder.readDetailRequest(4, 0x123456, 1))
        val reply = byteArrayOf(0x59, 4, 0x12, 0x34, 0x56, 8, 1, 0x22)
        val detail = UdsDtcDecoder.decodeDetail(4, 0x123456, reply)
        assertEquals(8, detail.status)
        assertArrayEquals(byteArrayOf(1, 0x22), detail.records)
        assertTrue(runCatching { UdsDtcDecoder.decodeDetail(6, 0x123456, reply) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.decodeDetail(4, 0x123457, reply) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.readDetailRequest(6, 0x123456, 0) }.isFailure)
    }

}
