package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class ObdLegacyRecordsTest {
    @Test fun legacyDtcsNeverDecodeCountedCanOrMixedErrors() {
        val records = ObdLegacyRecords.dtcs("03", "03\r43 01 23 00 00 00 00\r>", "confirmed")
        assertEquals(listOf("P0123"), records?.map { it.code })
        assertEquals(emptyList<ObdDtc>(), ObdLegacyRecords.dtcs("03", "43000000000000", "confirmed"))
        for (raw in listOf("NO DATA", "4300", "43010123", "43000000000000\rCAN ERROR",
            "43000000000000\r7F0311", "47000000000000"))
            assertNull(raw, ObdLegacyRecords.dtcs("03", raw, "confirmed"))
    }

    @Test fun legacyInventoryRequiresMessageCountAndFinalPageBounds() {
        assertEquals(listOf(listOf(0x49, 0, 1, 0xC0, 0, 0, 0)),
            ObdLegacyRecords.infoPage(0, "490001C0000000"))
        assertNull(ObdLegacyRecords.infoPage(0, "4900C0000000"))
        assertNull(ObdLegacyRecords.infoPage(0, "490002C0000000"))
        assertNull(ObdLegacyRecords.infoPage(0xE0, "49E00100000001"))
        assertNull(ObdLegacyRecords.infoPage(0, "490001C0000000\r7F0911"))
    }

    @Test fun anonymousVinNeedsExactlyOneCompleteOrderedSequence() {
        val vin = "1G1FA1RXXR0123456"
        val fragments = (listOf(0,0,0) + vin.map(Char::code)).chunked(4)
            .mapIndexed { index, bytes -> listOf(0x49, 2, index + 1) + bytes }
        val count = listOf(listOf(0x49, 1, 5))
        assertEquals(vin, ObdLegacyRecords.anonymousVin(count, fragments))
        assertNull(ObdLegacyRecords.anonymousVin(emptyList(), fragments))
        assertNull(ObdLegacyRecords.anonymousVin(count + count, fragments))
        assertNull(ObdLegacyRecords.anonymousVin(count, fragments.dropLast(1)))
        assertNull(ObdLegacyRecords.anonymousVin(count, fragments.reversed()))
        assertNull(ObdLegacyRecords.anonymousVin(count, fragments + fragments))
        assertNull(ObdLegacyRecords.anonymousVin(count, fragments.toMutableList().apply {
            this[0] = this[0].toMutableList().apply { this[3] = 1 }
        }))
    }
}
