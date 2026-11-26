package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test
class DoIpWwhDtcTest {
    private fun bytes(vararg v: Int) = v.map { it.toByte() }.toByteArray()
    @Test fun maskRequestAndResponse() {
        val kind = DoIpWwhDtc.Kind.MASK
        assertArrayEquals(bytes(0x19, 0x42, 0x33, 0x40, 0x08), DoIpWwhDtc.request(kind, 8, 64))
        val report = DoIpWwhDtc.decode(kind, bytes(0x59,0x42,0x33,0xFF,0xE0,4,0x40,0x12,0x34,0x56,8))
        assertEquals(DoIpWwhDtc.Record(0x123456,8,0x40),report.records.single())
        assertEquals(0xE0,report.severityAvailability ?: -1)
    }
    @Test fun maskCoverageUsesOnlyTransmittedFilters() {
        val kind = DoIpWwhDtc.Kind.MASK
        val response = bytes(0x59, 0x42, 0x33, 0x01, 0x40, 4)
        val unavailable = DoIpWwhDtc.decode(kind, response, statusMask = 8, severityMask = 0x80)
        assertEquals(0, unavailable.effectiveStatusMask)
        assertEquals(0, unavailable.effectiveSeverityMask)
        assertEquals(false, unavailable.filterAvailable)
        val available = DoIpWwhDtc.decode(kind, response, statusMask = 0xFF, severityMask = 0xFF)
        assertEquals(1, available.effectiveStatusMask)
        assertEquals(0x40, available.effectiveSeverityMask)
        assertEquals(true, available.filterAvailable)
        val permanent = DoIpWwhDtc.decode(DoIpWwhDtc.Kind.PERMANENT, bytes(0x59,0x55,0x33,0xFF,4))
        assertNull(permanent.filterAvailable)
        assertTrue(runCatching { DoIpWwhDtc.decode(DoIpWwhDtc.Kind.PERMANENT,
            bytes(0x59,0x55,0x33,0xFF,4), statusMask=8,severityMask=64) }.isFailure)
    }
    @Test fun permanentAndReadinessResponses() {
        val permanent = DoIpWwhDtc.Kind.PERMANENT
        val readiness = DoIpWwhDtc.Kind.READINESS
        assertArrayEquals(bytes(0x19,0x55,0x33),DoIpWwhDtc.request(permanent))
        assertArrayEquals(bytes(0x19,0x56,0x33,7),DoIpWwhDtc.request(readiness,readinessGroup=7))
        assertTrue(DoIpWwhDtc.decode(permanent,bytes(0x59,0x55,0x33,0xFF,4)).records.isEmpty())
        val report = DoIpWwhDtc.decode(readiness,bytes(0x59,0x56,0x33,0xFF,4,7,0x12,0x34,0x56,8),7)
        assertEquals(DoIpWwhDtc.Record(0x123456,8,null),report.records.single())
        for (invalid in listOf(bytes(0x59,0x56,0x33,0xFF,4,8),
            bytes(0x59,0x56,0x34,0xFF,4,7), bytes(0x59,0x56,0x33,0xFF,4,7,1)))
            assertTrue(runCatching { DoIpWwhDtc.decode(readiness,invalid,7) }.isFailure)
    }
}
