package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class ObdJ1979ExtendedVehicleInfoTest {
    private fun can(type:Int, words:List<Int>) = listOf(0x49,type,words.size) +
        words.flatMap { listOf(it shr 8,it and 255) }
    private fun fragments(type:Int,data:List<Int>) = data.chunked(4).mapIndexed { i,chunk ->
        listOf(0x49,type,i+1)+chunk
    }
    @Test fun unsignedCountersAreDistinctFromCompliance() {
        val words=listOf(65535,40000,12,6,1,0)+List(10){0}
        val report=requireNotNull(ObdJ1979ExtendedVehicleInfo.can(8,can(8,words)))
        assertEquals(65535,report.generalDenominator)
        assertEquals(40000,report.ignitionCycles)
        assertEquals(16,report.counterCount)
        assertEquals("catalyst-bank-1",report.monitors.first().monitor)
        assertEquals(2.0,requireNotNull(report.monitors.first().ratio),0.0)
        assertNull(report.monitors[1].ratio)
        val modern=requireNotNull(ObdJ1979ExtendedVehicleInfo.can(8,can(8,words+List(4){0})))
        assertEquals("secondary-oxygen-bank-2",modern.monitors.last().monitor)
        val diesel=requireNotNull(ObdJ1979ExtendedVehicleInfo.can(11,can(11,words+listOf(1,2))))
        assertEquals("fuel-system",diesel.monitors.last().monitor)
        assertEquals(0.5,requireNotNull(diesel.monitors.last().ratio),0.0)
        assertNull(ObdJ1979ExtendedVehicleInfo.can(8,can(8,List(18){0})))
        assertNull(ObdJ1979ExtendedVehicleInfo.can(11,can(11,List(20){0})))
        assertNull(ObdJ1979ExtendedVehicleInfo.can(8,can(8,words).dropLast(1)))
        assertNull(ObdJ1979ExtendedVehicleInfo.can(8,can(11,words)))
    }
    @Test fun nativeRawDoesNotPromoteStatusOrAcceptMismatchedPayload() {
        val hex=can(11,List(18){it}).joinToString("") { "%02X".format(it) }
        val decoded=ObdJ1979ExtendedVehicleInfo.tryRaw("090B",hex,"RAW")
        assertEquals(18,decoded?.counterCount)
        assertNull(ObdJ1979ExtendedVehicleInfo.tryRaw("0908",hex,"RAW"))
        assertNull(ObdJ1979ExtendedVehicleInfo.tryRaw("090B",hex,"PASSED"))
        assertNull(ObdJ1979ExtendedVehicleInfo.tryRaw("090B",hex.dropLast(2),"RAW"))
        assertNull(ObdJ1979ExtendedVehicleInfo.tryRaw("090B",hex+"00","RAW"))
        assertNull(ObdJ1979ExtendedVehicleInfo.tryRaw("090B",null,"RAW"))
        assertNull(ObdJ1979ExtendedVehicleInfo.tryRaw("090B","49ZZ","RAW"))
    }
    @Test fun legacyRequiresExactNumberedRecordsFromSelectedSource() {
        val response=can(11,List(18){it})
        val rows=fragments(11,response.drop(3))
        val expected=ObdJ1979ExtendedVehicleInfo.can(11,response)
        assertNotNull(expected)
        assertEquals(expected,ObdJ1979ExtendedVehicleInfo.legacy(11,9,rows))
        assertNull(ObdJ1979ExtendedVehicleInfo.legacy(11,9,rows.reversed()))
        assertNull(ObdJ1979ExtendedVehicleInfo.legacy(11,9,rows.dropLast(1)))
        assertNull(ObdJ1979ExtendedVehicleInfo.legacy(11,9,listOf(rows.first())+rows.dropLast(1)))
        assertEquals(7,ObdJ1979ExtendedVehicleInfo.countType(11))
        assertFalse(11 in ObdJ1979ExtendedVehicleInfo.legacyCountTypes)
        assertTrue(12 in ObdJ1979ExtendedVehicleInfo.legacyCountTypes)
    }
    @Test fun identifiersHaveStrictPaddingAndTransportSpecificLength() {
        val serial=List(11){0}+"SER123".map(Char::code)
        val canReply=listOf(0x49,13,1)+serial
        val record=requireNotNull(ObdJ1979ExtendedVehicleInfo.can(13,canReply))
        assertEquals(listOf("SER123"),record.values)
        assertEquals(record,ObdJ1979ExtendedVehicleInfo.legacy(13,5,fragments(13,listOf(0,0,0)+serial)))
        assertNull(ObdJ1979ExtendedVehicleInfo.can(13,canReply.dropLast(1)))
        assertNull(ObdJ1979ExtendedVehicleInfo.can(13,canReply.dropLast(1)+0))
        assertNull(ObdJ1979ExtendedVehicleInfo.legacy(13,5,fragments(13,listOf(0,1,0)+serial)))
        val name=listOf(69,67,77,0,45)+"Engine Control".map(Char::code)+0
        assertEquals(listOf("ECM-Engine Control"),ObdJ1979ExtendedVehicleInfo.legacy(10,5,fragments(10,name))?.values)
    }
}
