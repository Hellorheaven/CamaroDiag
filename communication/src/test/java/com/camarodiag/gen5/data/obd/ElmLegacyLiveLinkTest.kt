package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ElmLegacyLiveLinkTest {
    private val vin="1G1FA1RXXR0123456"
    private fun frame(protocol:Int,source:Int,payload:List<Int>):String{
        val header=if(protocol>=4)listOf(0x80 or payload.size,0xF1,source)
            else listOf(if(protocol==1)0x41 else 0x48,0x6B,source)
        val raw=header+payload
        return (raw+ElmLegacyResponseFrames.checksum(protocol,raw)).joinToString("",transform=ElmLegacyResponseFrames::hex)
    }
    private fun replies(protocol:Int):MutableMap<String,String>{
        fun row(value:String,source:Int=0x10)=frame(protocol,source,value.chunked(2).map { it.toInt(16) })
        val data=(listOf(0,0,0)+vin.map(Char::code)).chunked(4)
        return mutableMapOf("0100" to row("410008100000"),"0900" to row("490001C0000000"),
            "0901" to row("490105"),"0902" to (data.mapIndexed { i,x->frame(protocol,0x10,listOf(0x49,2,i+1)+x) }+
                row("49020100000041",0x11)).joinToString("\n"),
            "0105" to listOf(row("41055A"),row("41057F",0x11)).joinToString("\n"),
            "010C" to row("410C0FA0"))
    }
    @Test fun allLegacyVariantsReuseNumericRecorderWithoutClaimingPhysicalAddress()=runBlocking{
        for(protocol in 1..5){
            var now=0L
            val target=J1979LiveTarget(protocol,0x10,vin)
            val plan=J1979LivePlan(target,listOf(5,12),2,100,30000)
            val replies=replies(protocol)
            val sent=mutableListOf<String>()
            val link=ElmLegacyLiveLink(target,{req,_->
                sent+=req;now+=20;replies.getValue(req)
            },{protocol.toString()},{},{now})
            val report=J1979LiveRecorder.run(plan,link::query,link::protocolResponse,{},
                clock={now},sleep={now+=it},requestTimeout={if(it[0]==9.toByte())20000 else 4000})
                .copy(legacyExchanges=link.traces)
            assertTrue(report.completed)
            assertFalse(report.physicallyAddressed)
            assertEquals("FUNCTIONAL_SELECTED_RESPONSE_LIVE",report.scope)
            assertNull(target.requestId)
            assertEquals("10",target.idHex(target.responseId))
            assertEquals(vin,report.observedVin)
            assertEquals(protocol,report.observedProtocol)
            assertEquals(50.0,report.summaries[0].mean!!,0.0)
            assertEquals(1000.0,report.summaries[1].mean!!,0.0)
            assertEquals(listOf("0100","0900","0901","0902","0100"),sent.take(5))
            assertEquals(6,report.legacyExchanges.first { it.requestHex=="0902" }.frames.size)
            assertEquals(5,report.legacyExchanges.first { it.requestHex=="0902" }.selectedRecordsHex.size)
            assertFalse("04" in sent);assertFalse("08010000000000" in sent)
        }
    }
    @Test fun malformedSelectedVinCannotUseAnotherSource()=runBlocking{
        val target=J1979LiveTarget(2,0x10,vin)
        val replies=replies(2)
        replies["0902"]=replies.getValue("0902").lines().drop(1).joinToString("\n")
        val link=ElmLegacyLiveLink(target,{req,_->replies.getValue(req)},{"2"},{})
        val report=J1979LiveRecorder.run(J1979LivePlan(target,listOf(5),1),link::query,link::protocolResponse,{})
        assertFalse(report.completed)
        assertTrue(report.observations.isEmpty())
        assertEquals("VIN_FRAGMENT_SEQUENCE_INVALID",link.traces.last().detail)
    }
    @Test fun wrongFixedProtocolDoesNotProceedToVin()=runBlocking{
        val target=J1979LiveTarget(2,0x10,vin)
        val sent=mutableListOf<String>()
        val link=ElmLegacyLiveLink(target,{req,_->sent+=req;replies(2).getValue(req)},{"3"},{})
        val report=J1979LiveRecorder.run(J1979LivePlan(target,listOf(5),1),link::query,link::protocolResponse,{})
        assertFalse(report.completed)
        assertEquals(listOf("0100"),sent)
        assertEquals("PROTOCOL_MISMATCH",link.traces.last().detail)
    }
    @Test fun anotherSourceDoesNotFillGap()=runBlocking{
        val target=J1979LiveTarget(2,0x10,vin)
        val replies=replies(2)
        var count=0;var now=0L
        val link=ElmLegacyLiveLink(target,{req,_->
            if(req=="0105"&&++count>1)frame(2,0x11,listOf(0x41,5,0x7F)) else replies.getValue(req)
        },{"2"},{},{now})
        val report=J1979LiveRecorder.run(J1979LivePlan(target,listOf(5),2,100),link::query,link::protocolResponse,{},
            clock={now},sleep={now+=it})
        assertTrue(report.completed)
        assertEquals(listOf("PASSED","TIMEOUT"),report.observations.map { it.status })
        assertNull(report.observations.last().value)
        assertEquals(50.0,report.summaries.single().mean!!,0.0)
    }
}
