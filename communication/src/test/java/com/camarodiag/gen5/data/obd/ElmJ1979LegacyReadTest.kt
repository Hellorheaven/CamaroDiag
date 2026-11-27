package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ElmJ1979LegacyReadTest {
    private val vin="1G1FA1RXXR0123456"
    private fun frame(protocol:Int,source:Int,payload:List<Int>):String {
        val head=if(protocol>=4) listOf(0x80 or payload.size,0xF1,source)
            else listOf(if(protocol==1)0x41 else 0x48,0x6B,source)
        val bytes=head+payload
        return (bytes+ElmLegacyResponseFrames.checksum(protocol,bytes)).joinToString("",transform=ElmLegacyResponseFrames::hex)
    }
    private fun fixture(protocol:Int):MutableMap<String,String> {
        fun row(source:Int,value:String)=frame(protocol,source,value.chunked(2).map { it.toInt(16) })
        val fragments=(listOf(0,0,0)+vin.map(Char::code)).chunked(4).mapIndexed { i,bytes ->
            frame(protocol,0x10,listOf(0x49,2,i+1)+bytes)
        }
        return mutableMapOf(
            "0100" to listOf(row(0x10,"410088100000"),row(0x11,"410000080000")).joinToString("\n"),
            "0900" to row(0x10,"490001C0000000"),"0901" to row(0x10,"490105"),
            "0902" to (fragments+row(0x11,"49020100000041")).joinToString("\n"),
            "03" to listOf(row(0x10,"43012300000000"),row(0x11,"43045600000000")).joinToString("\n"),
            "07" to row(0x10,"47000000000000"),"0A" to row(0x10,"7F0A11"),
            "0101" to row(0x10,"410100000000"),"0105" to row(0x10,"41055A"),
            "010C" to row(0x10,"410C0FA0"),"020200" to row(0x10,"4202000000"),
            "0600" to row(0x10,"4600FF80000000"),"0601" to row(0x10,"46018100010002"),
            "08000000000000" to row(0x10,"48000080000000"))
    }

    @Test fun fiveVariantsAreScopedToSelectedHeader()=runBlocking {
        for(protocol in 1..5) {
            val sent=mutableListOf<String>()
            val replies=fixture(protocol)
            val result=ElmJ1979LegacyRead.run(LegacyReadTarget(protocol,0x10,vin),
                exchange={req,_ -> sent+=req;assertTrue(ElmJ1979LegacyRead.allowsRead(req));replies[req]?:"NO DATA"},
                protocolCheck={protocol.toString()},requireCurrent={})
            assertTrue(result.completed)
            assertEquals(vin,result.observedVin)
            assertEquals("MATCH",result.vinMatch)
            assertEquals(listOf("11"),result.otherSources)
            assertEquals(listOf("0100","0900","0901","0902"),sent.take(4))
            assertEquals(listOf("P0123"),result.dtcModes.first().codes)
            assertEquals(emptyList<String>(),result.dtcModes[1].codes)
            assertNull(result.dtcModes[2].codes)
            assertEquals(50.0,result.measurements.single { it.pid==5 }.number,0.0)
            assertEquals(1000.0,result.measurements.single { it.pid==12 }.number,0.0)
            assertFalse(result.readiness!!.milOn)
            assertFalse(result.freezeTrigger!!.present)
            assertEquals(1,result.monitors.single().valueEncoded)
            assertFalse(result.wholeVehicleCoverageVerified)
            assertFalse(result.physicalVehicleVerified)
            assertFalse("04" in sent)
            assertFalse("08010000000000" in sent)
            assertFalse("010D" in sent)
            assertEquals(2,result.steps.single { it.requestHex=="03" }.frames.size)
        }
    }
    @Test fun mismatchingVinStopsBeforeOtherServices()=runBlocking {
        val sent=mutableListOf<String>()
        val replies=fixture(2)
        val report=ElmJ1979LegacyRead.run(LegacyReadTarget(2,0x10,"1FA6P8TH0L5100000"),
            {req,_->sent+=req;replies.getValue(req)},{ "2" },{})
        assertEquals("VIN_MISMATCH",report.stopReason)
        assertEquals(listOf("0100","0900","0901","0902"),sent)
        assertTrue(report.measurements.isEmpty())
    }
    @Test fun incompleteSelectedVinCannotUseOtherSource()=runBlocking {
        val replies=fixture(2)
        replies["0902"]=replies.getValue("0902").lines().drop(1).joinToString("\n")
        val report=ElmJ1979LegacyRead.run(LegacyReadTarget(2,0x10,vin),
            {req,_->replies.getValue(req)},{"2"},{})
        assertEquals("VIN_UNVERIFIED",report.stopReason)
        assertNull(report.observedVin)
    }
    @Test fun latePid00NeverEstablishesProtocol()=runBlocking {
        var now=0L
        val report=ElmJ1979LegacyRead.run(LegacyReadTarget(2,0x10,vin),
            {_,timeout->now+=timeout;fixture(2).getValue("0100")},
            {fail("Late result must not advance");"2"},{},100,{now})
        assertEquals("BUDGET_EXHAUSTED",report.stopReason)
        assertEquals("LATE_RESPONSE",report.steps.single().detail)
        assertNull(report.observedProtocol)
    }
    @Test fun selectedSourceDuplicateAndChecksumFailureAreRejected()=runBlocking {
        for(raw in listOf(fixture(2).getValue("0100").lines().first().let { "$it\n$it" },
            fixture(2).getValue("0100").lines().first().let { it.dropLast(2)+"00" })) {
            val report=ElmJ1979LegacyRead.run(LegacyReadTarget(2,0x10,vin),
                {_,_->raw},{fail("Invalid initial source");"2"},{})
            assertFalse(report.completed)
            assertEquals("FAILED",report.steps.single().status)
        }
    }
    @Test fun allowlistDoesNotAdmitMutationsOrAdapterCommands() {
        for(command in listOf("04","08010000000000","ATZ","2E0101","020201","050103","08000100000000"))
            assertFalse(command,ElmJ1979LegacyRead.allowsRead(command))
        for(command in listOf("0100","020200","03","050001","0600","0700".take(2),"08000000000000","0902","0A"))
            assertTrue(command,ElmJ1979LegacyRead.allowsRead(command))
    }
}
