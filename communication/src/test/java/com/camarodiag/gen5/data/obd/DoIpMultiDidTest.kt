package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class DoIpMultiDidTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun orderedMultiDidRequestAndResponse() {
        val layout = DoIpMultiDid.parse("F40C:2,F40D:1")
        assertArrayEquals(hex("22F40CF40D"), DoIpMultiDid.request(layout))
        val values = DoIpMultiDid.decode(layout, hex("62F40C1234F40D56"))
        assertEquals(0xF40C, values.first().did)
        assertArrayEquals(hex("1234"), values.first().data)
        assertArrayEquals(hex("56"), values.last().data)
        for (bad in listOf("62F40D56F40C1234", "62F40C1234F40D", "62F40C1234F40D5600"))
            assertTrue(runCatching { DoIpMultiDid.decode(layout, hex(bad)) }.isFailure)
    }
    @Test fun monitoringBoundsTheWholeSample() {
        assertEquals(2, DoIpMultiDid.validateMonitor(DoIpMultiDid.parse("F40C:2,F40D:1")).size)
        assertTrue(runCatching { DoIpMultiDid.validateMonitor(DoIpMultiDid.parse("F40C:4096,F40D:1")) }.isFailure)
    }
    @Test fun invalidLayoutsRejectedBeforeNetwork() {
        for (bad in listOf("F40C:1,F40C:2", "F40C:0,F40D:1", "F40C:1", "F40C:4097,F40D:1",
            "F40C:1,F40D:1,", "F40C:1,XYZ1:2"))
            assertTrue(runCatching { DoIpMultiDid.parse(bad) }.isFailure)
    }
}
