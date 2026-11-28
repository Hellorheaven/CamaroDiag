package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class ObdJ1979MultiSignalTest {
    private fun data(hex:String)=hex.chunked(2).map { it.toInt(16) }

    @Test fun supportMasksAndWidthsAreStrict() {
        val maf=requireNotNull(ObdJ1979MultiSignal.decodeData(0x66,data("010400FFFF")))
        assertEquals(listOf("maf-a"),maf.map { it.name })
        assertEquals(32.0,maf.single().number,0.0)
        assertEquals(emptyList<ObdJ1979MultiSignal.Signal>(),ObdJ1979MultiSignal.decodeData(0x66,data("00FFFFFFFF")))
        assertNull(ObdJ1979MultiSignal.decodeData(0x66,data("040400FFFF")))
        assertNull(ObdJ1979MultiSignal.decodeData(0x66,data("010400FF")))
        assertNull(ObdJ1979MultiSignal.decodeData(0x66,data("010400FFFF00")))
        val coolant=requireNotNull(ObdJ1979MultiSignal.decodeData(0x67,data("035A28")))
        assertEquals(listOf(50.0,0.0),coolant.map { it.number })
    }
    @Test fun signedAndUnsignedValuesAreNotTruncated() {
        val dpf=requireNotNull(ObdJ1979MultiSignal.decodeData(0x7A,data("07FF9C006400C8")))
        assertEquals(listOf(-1.0,1.0,2.0),dpf.map { it.number })
        val runtime=requireNotNull(ObdJ1979MultiSignal.decodeData(0x7F,data("01FFFFFFFF0000000000000000")))
        assertEquals(4294967295L,runtime.single().rawEncoded)
        assertEquals(4294967295.0,runtime.single().number,0.0)
    }
    @Test fun oxygenSensorSentinelAndOrdinal() {
        val narrow=requireNotNull(ObdJ1979MultiSignal.decodeData(0x14,data("C8FF")))
        assertEquals(1,narrow.size)
        assertEquals("o2-sensor-1-voltage",narrow.single().name)
        assertEquals(1.0,narrow.single().number,0.0)
        val wide=requireNotNull(ObdJ1979MultiSignal.decodeData(0x34,data("80007F00")))
        assertEquals(listOf(1.0,-1.0),wide.map { it.number })
        assertEquals(listOf("ratio","mA"),wide.map { it.unit })
    }
    @Test fun responseCorrelationDoesNotPromoteRawStatus() {
        val raw="4167035A28"
        assertEquals(2,ObdJ1979MultiSignal.tryRecord("0167",raw,"RAW")?.signals?.size)
        assertNull(ObdJ1979MultiSignal.tryRecord("0168",raw,"RAW"))
        assertNull(ObdJ1979MultiSignal.tryRecord("0167",raw,"FAILED"))
        assertNull(ObdJ1979MultiSignal.tryRecord("0167",raw+"00","RAW"))
        assertNull(ObdJ1979MultiSignal.tryRecord("0167",raw.dropLast(2),"RAW"))
        assertNull(ObdJ1979MultiSignal.tryRecord("026700",raw,"RAW"))
        assertNull(ObdJ1979MultiSignal.decodeData(0xFF,listOf(0)))
    }
}
