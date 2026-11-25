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

    @Test fun legacyMirrorAndEmissionsStaySeparate() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        for (sub in listOf(0x0F, 0x13)) {
            val request = UdsDtcDecoder.legacyRequest(sub, 8, 0, 1)
            assertArrayEquals(hex("19%02X08".format(sub)), request)
            val report = UdsDtcDecoder.decodeLegacy(request, hex("59%02X0801020308".format(sub)))
            assertEquals(sub, report.subfunction)
            assertEquals(0x010203, report.list!!.records.single().rawDtc)
            assertTrue(runCatching { UdsDtcDecoder.decodeLegacy(request, hex("59%02X0801020301".format(sub))) }.isFailure)
        }
        for (sub in listOf(0x11, 0x12)) {
            val request = UdsDtcDecoder.legacyRequest(sub, 8, 0, 1)
            assertEquals(2, UdsDtcDecoder.decodeLegacy(request, hex("59%02X08010002".format(sub))).count!!.count)
            assertTrue(runCatching { UdsDtcDecoder.decodeLegacy(request, hex("59%02X0801".format(sub))) }.isFailure)
        }
        val detail = UdsDtcDecoder.legacyRequest(0x10, 255, 0x010203, 1)
        assertArrayEquals(hex("191001020301"), detail)
        assertArrayEquals(hex("01AABB"), UdsDtcDecoder.decodeLegacy(detail, hex("59100102030801AABB")).detail!!.records)
        assertTrue(runCatching { UdsDtcDecoder.decodeLegacy(detail, hex("59100102040801")) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.decodeLegacy(detail, hex("59100102030802")) }.isFailure)
    }

    @Test fun storedRecordChecksEchoAndMinimumDidStructure() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(hex("190501"), UdsDtcDecoder.storedRecordRequest(1))
        assertEquals(null, UdsDtcDecoder.decodeStoredRecord(1, hex("590501")).detail)
        val result = UdsDtcDecoder.decodeStoredRecord(1, hex("5905010102030801F190AA"))
        assertEquals(0x010203, result.detail!!.dtc)
        assertArrayEquals(hex("01F190AA"), result.detail!!.records)
        for (bad in listOf("590502", "590501010203", "5905010102030802F190", "5905010102030800AA"))
            assertTrue(runCatching { UdsDtcDecoder.decodeStoredRecord(1, hex(bad)) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.storedRecordRequest(255) }.isFailure)
    }

    @Test fun decodedMemoryRecordsPreserveOriginalResponseAndMemoryScope() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val snapshot = hex("591802010203080101F40D78")
        val extended = hex("5919020102030801123490AA")
        val didLengths = mapOf(0xF40D to 1)
        val recordLengths = mapOf(1 to 2, 0x90 to 1)
        val snapshots = UdsDtcDecoder.decodeUserMemorySnapshot(2, 0x010203, 255, snapshot, didLengths)
        assertArrayEquals(snapshot, snapshots.rawResponse)
        assertArrayEquals(hex("78"), snapshots.records.single().dids.single().data)
        val records = UdsDtcDecoder.decodeUserMemoryExtended(2, 0x010203, 255, extended, recordLengths)
        assertArrayEquals(extended, records.rawResponse)
        assertArrayEquals(hex("1234"), records.records.first().data)
        assertTrue(runCatching { UdsDtcDecoder.decodeUserMemorySnapshot(3, 0x010203, 255, snapshot, didLengths) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.decodeUserMemoryExtended(3, 0x010203, 255, extended, recordLengths) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.decodeUserMemoryExtended(2, 0x010203, 255, extended.copyOf(extended.size - 1), recordLengths) }.isFailure)
    }

    @Test fun decodedExtendedRecordsRespectSelectorsAndLengths() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val catalog = UdsDtcDecoder.parseExtendedRecordLengths("01:2,90:1,91:2")
        assertArrayEquals(hex("1906010203FF"), UdsDtcDecoder.extendedRecordsRequest(0x010203, 255, catalog))
        val response = hex("59060102030801123490AA91BBCC")
        val report = UdsDtcDecoder.decodeExtendedRecords(0x010203, 255, response, catalog)
        assertEquals(listOf(1, 0x90, 0x91), report.records.map { it.number })
        assertArrayEquals(hex("BBCC"), report.records.last().data)
        assertArrayEquals(response, report.rawResponse)
        assertEquals(2, UdsDtcDecoder.decodeExtendedRecords(0x010203, 0xFE,
            hex("59060102030890AA91BBCC"), catalog).records.size)
        assertTrue(UdsDtcDecoder.decodeExtendedRecords(0x010203, 1, hex("590601020308"), catalog).records.isEmpty())
        for ((selector, bad) in listOf(0xFE to "590601020308011234", 1 to "59060102030801123490AA",
            255 to "59060102030890AA90BB", 255 to "5906010203080112",
            255 to "59060102030802AA", 255 to "59060102040890AA"))
            assertTrue(runCatching { UdsDtcDecoder.decodeExtendedRecords(0x010203, selector, hex(bad), catalog) }.isFailure)
        for (invalid in listOf("00:1", "FE:1", "01:0", "01:4091", "01:1,01:2"))
            assertTrue(runCatching { UdsDtcDecoder.parseExtendedRecordLengths(invalid) }.isFailure)
    }

    @Test fun snapshotRecordsDecodeHistoricalDidsAndRejectWrongSelectors() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val catalog = UdsDtcDecoder.parseStoredDidLengths("F40C:2,F40D:1")
        val response = hex("5904010203080102F40C1234F40D560201F40D78")
        assertArrayEquals(hex("1904010203FF"), UdsDtcDecoder.snapshotRecordsRequest(0x010203, 255))
        val report = UdsDtcDecoder.decodeSnapshotRecords(0x010203, 255, response, catalog)
        assertEquals(8, report.status)
        assertEquals(listOf(1, 2), report.records.map { it.record })
        assertArrayEquals(hex("1234"), report.records.first().dids.first().data)
        assertArrayEquals(hex("78"), report.records.last().dids.single().data)
        assertArrayEquals(response, report.rawResponse)
        assertTrue(UdsDtcDecoder.decodeSnapshotRecords(0x010203, 0, hex("590401020308"), catalog).records.isEmpty())
        assertTrue(runCatching { UdsDtcDecoder.decodeSnapshotRecords(0x010203, 1, response, catalog) }.isFailure)
        for (invalid in listOf("59040102030801", "5904010203080101F40C12",
            "5904010203080101FFFFAA", "59040102030801000100", "5904010204080100"))
            assertTrue(runCatching { UdsDtcDecoder.decodeSnapshotRecords(0x010203, 255, hex(invalid), catalog) }.isFailure)
    }

    @Test fun allStoredRecordsUseDocumentedDidLengths() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val catalog = UdsDtcDecoder.parseStoredDidLengths("F40C:2,F40D:1")
        assertArrayEquals(hex("1905FF"), UdsDtcDecoder.allStoredRecordsRequest())
        val reply = hex("5905010102030802F40C1234F40D56020405060001F40D78")
        val report = UdsDtcDecoder.decodeAllStoredRecords(reply, catalog)
        assertEquals(listOf(1, 2), report.records.map { it.record })
        assertArrayEquals(hex("1234"), report.records.first().dids.first().data)
        assertArrayEquals(hex("78"), report.records.last().dids.single().data)
        assertArrayEquals(reply, report.rawResponse)
        assertTrue(UdsDtcDecoder.decodeAllStoredRecords(hex("5905FF"), catalog).records.isEmpty())
        for (bad in listOf("590501", "5905010102030801F40C12", "5905010102030801FFFFAA",
            "5905010102030800010405060000", "590501010203080000"))
            assertTrue(runCatching { UdsDtcDecoder.decodeAllStoredRecords(hex(bad), catalog) }.isFailure)
        for (bad in listOf("F40D:0", "F40D:1,F40D:2", "F40D:4097", "F40G:1"))
            assertTrue(runCatching { UdsDtcDecoder.parseStoredDidLengths(bad) }.isFailure)
    }

    @Test fun extendedRecordUsesDocumentedWidthAndEcho() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(hex("191A01"), UdsDtcDecoder.extendedRecordRequest(1, null))
        assertArrayEquals(hex("191601"), UdsDtcDecoder.extendedRecordRequest(1, 2))
        val supported = UdsDtcDecoder.decodeExtendedRecord(1, null, hex("591A08010102030001020408"))
        assertEquals(8, supported.availability)
        assertEquals(0, supported.entries.first().status)
        val data = UdsDtcDecoder.decodeExtendedRecord(1, 2, hex("59160101020308AABB01020400CCDD"))
        assertEquals(2, data.entries.size)
        assertArrayEquals(hex("CCDD"), data.entries.last().records)
        assertTrue(UdsDtcDecoder.decodeExtendedRecord(1, 2, hex("591601")).entries.isEmpty())
        for (bad in listOf("591A0802", "591A080101020301", "591A08010102030801020300"))
            assertTrue(runCatching { UdsDtcDecoder.decodeExtendedRecord(1, null, hex(bad)) }.isFailure)
        for (bad in listOf("591602", "59160101020308AA", "59160101020308AABB01020308CCDD"))
            assertTrue(runCatching { UdsDtcDecoder.decodeExtendedRecord(1, 2, hex(bad)) }.isFailure)
        assertTrue(runCatching { UdsDtcDecoder.extendedRecordRequest(0xFF, 2) }.isFailure)
    }

    @Test fun explicitUserMemoryCorrelatesListAndRawRecords() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val list = UdsDtcDecoder.userMemoryRequest(0x17, 2, 8, 0, 1)
        assertArrayEquals(hex("19170802"), list)
        assertTrue(UdsDtcDecoder.decodeUserMemory(list, hex("59170208")).list!!.records.isEmpty())
        assertEquals(0x010203, UdsDtcDecoder.decodeUserMemory(list, hex("5917020801020308")).list!!.records.single().rawDtc)
        for (bad in listOf("59170308", "5917020801020301", "5917020801", "591702080102030801020308"))
            assertTrue(runCatching { UdsDtcDecoder.decodeUserMemory(list, hex(bad)) }.isFailure)
        for (sub in 0x18..0x19) {
            val request = UdsDtcDecoder.userMemoryRequest(sub, 2, 255, 0x010203, 1)
            assertArrayEquals(hex("19%02X0102030102".format(sub)), request)
            val result = UdsDtcDecoder.decodeUserMemory(request, hex("59%02X020102030801AABB".format(sub)))
            assertEquals(2, result.memory)
            assertArrayEquals(hex("01AABB"), result.detail!!.records)
            assertTrue(runCatching { UdsDtcDecoder.decodeUserMemory(request, hex("59%02X030102030801".format(sub))) }.isFailure)
            assertTrue(runCatching { UdsDtcDecoder.decodeUserMemory(request, hex("59%02X020102030802".format(sub))) }.isFailure)
        }
        assertTrue(runCatching { UdsDtcDecoder.userMemoryRequest(0x19, 2, 255, 1, 0xF0) }.isFailure)
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
