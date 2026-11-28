package com.camaradiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class ObdJ1979StatusTest {
    @Test fun fuelStateRetainsUnknownEncoding() {
        val values = ObdJ1979Status.decodeData(3,listOf(2,3))!!
        assertEquals(listOf("CLOSED_FEEDBACK","UNKNOWN"),values.map { it.code })
        assertEquals(listOf(2,3),values.map { it.rawEncoded })
        assertEquals("DIESEL",ObdJ1979Status.decodeData(0x51,listOf(4))!!.single().code)
    }

    @Test fun mixedRecordDoesNotInventAnUnsupportedStatus() {
        val response = listOf(0x41,0x70,4,0,0,0,0,0,0,0,0,14)
        val values = ObdJ1979Status.decodeResponse(listOf(1,0x70),response)!!
        assertEquals(1,values.size)
        assertEquals("boost-control-a",values.single().name)
        assertEquals("CLOSED_LOOP",values.single().code)
        assertNull(ObdJ1979Status.decodeResponse(listOf(1,0x71),response))
        assertNull(ObdJ1979Status.decodeResponse(listOf(1,0x70),response.dropLast(1)+0xFE))
    }

    @Test fun regenerationAndInducementAreSeparateStates() {
        val states = ObdJ1979Status.decodeData(0x8B,listOf(7,7,0,0,0,0,0))!!
        assertEquals(listOf("ACTIVE","ACTIVE","ACTIVE"),states.map { it.code })
        assertTrue(ObdJ1979Status.decodeData(0x8B,listOf(0,15,0,0,0,0,0))!!.isEmpty())
        val nox = ObdJ1979Status.decodeData(0x94,listOf(1,0x73)+List(10){0})!!
        assertEquals(listOf("ACTIVE","ENABLED","ACTIVE","UNSUPPORTED"),nox.map { it.code })
    }

    @Test fun oxygenBanksAreNotInterchangeable() {
        assertEquals("oxygen-bank-2-sensor-1",ObdJ1979Status.decodeData(0x13,listOf(16))!!
            .single { it.code=="PRESENT" }.name)
        assertEquals("oxygen-bank-3-sensor-1",ObdJ1979Status.decodeData(0x1D,listOf(16))!!
            .single { it.code=="PRESENT" }.name)
        assertNull(ObdJ1979Status.decodeData(0x1E,listOf(2)))
        assertNull(ObdJ1979Status.decodeData(0x51,listOf(256)))
    }
}
