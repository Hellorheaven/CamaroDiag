package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class ObdbCamaroSignalCatalogTest {
    @Test fun decodesCommunityKnockRetardScalingFromKnownTestVector() {
        val json = """{"commands":[{"hdr":"7E0","rax":"7E8","cmd":{"22":"11A6"},"freq":1,"signals":[{"id":"CAMARO_KNOCK_RETARD","name":"Knock retard","path":"Engine","fmt":{"len":8,"max":56.1,"mul":22,"div":100,"unit":"degrees"}}]}]}"""
        val signal = ObdbCamaroSignalSet.fromJson(json).find("CAMARO_KNOCK_RETARD")!!
        val decoded = signal.decode(byteArrayOf(0x62))
        assertEquals(21.56, decoded.value!!, 0.0001)
        assertEquals("degrees", decoded.unit)
        assertEquals(byteArrayOf(0x22, 0x11, 0xA6.toByte()).toList(), signal.requestPayload().toList())
    }

    @Test fun handlesSignedValuesAndNullSentinels() {
        val signed = ObdbSignalFormat(bitLength = 16, signed = true, divide = 8.0, unit = "rpm")
        assertEquals(-1.0, signed.decode(byteArrayOf(0xFF.toByte(), 0xF8.toByte())).value!!, 0.0)
        val nullValue = ObdbSignalFormat(bitLength = 8, nullMaximum = 255.0, unit = "scalar")
        assertTrue(nullValue.decode(byteArrayOf(0xFF.toByte())).unavailable)
    }

    @Test fun filtersCommandsByModelYearAndDoesNotInventNonStandardCanIds() {
        val json = """{"commands":[{"hdr":"241","rax":"641","cmd":{"22":"5005"},"freq":15,"dbgfilter":{"to":2012,"from":2014},"signals":[{"id":"TP","name":"Tire pressure","path":"Tires","fmt":{"len":16,"div":16,"unit":"kilopascal"}}]},{"hdr":"DA40","rax":"40","cmd":{"22":"5005"},"freq":15,"signals":[{"id":"UNRESOLVED","name":"Unresolved addressing","path":"Body","fmt":{"len":16,"unit":"scalar","max":65535}}]}]}"""
        val catalog = ObdbCamaroSignalSet.fromJson(json)
        assertEquals(1, catalog.forYear(2011).size)
        assertTrue(catalog.find("TP")!!.hasSupportedPhysicalCanAddressing)
        assertFalse(catalog.find("UNRESOLVED")!!.hasSupportedPhysicalCanAddressing)
    }

    @Test fun supportsBitOffsetsAndLeastSignificantByteFirst() {
        val format = ObdbSignalFormat(bitIndex = 4, bitLength = 8, unit = "scalar")
        assertEquals(0x23.0, format.decode(byteArrayOf(0x12, 0x34)).value!!, 0.0)
        val littleEndian = ObdbSignalFormat(bitLength = 16, leastSignificantByteFirst = true, unit = "scalar")
        assertEquals(0x1234.0, littleEndian.decode(byteArrayOf(0x34, 0x12)).value!!, 0.0)
    }
}
