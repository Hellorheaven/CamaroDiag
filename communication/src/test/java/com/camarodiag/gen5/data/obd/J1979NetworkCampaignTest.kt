package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class J1979NetworkCampaignTest {
    private val vin="1G1FF3D36C9104199"
    private val plan=J1979NetworkPlan(6,vin,listOf(0x7E8,0x7E9))
    private fun bytes(hex:String)=hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun reply(raw:String,protocol:String="6")=J1979NetworkCampaign.DiscoveryReply(raw,protocol)
    private val two="7E8 06 41 00 08 00 00 00 CC\r7E9 06 41 00 08 00 00 00 CC"

    @Test fun discoveryPreservesAllAndRejectsConflicts(){
        val r=J1979NetworkCampaign.decodeDiscovery(plan,reply(two+"\r7E8 03 7F 01 11 00 00 00 00"))
        assertEquals(listOf(0x7E9),r.eligibleResponseIds)
        assertEquals("CONFLICTING_RESPONSES",r.responders.first().detail)
        assertEquals(3,r.frames.size)
        assertEquals(listOf(5),r.responders.last().supportedPids)
    }
    @Test fun extendedHeaderAndPendingResponse(){
        val r=J1979NetworkCampaign.decodeDiscovery(J1979NetworkPlan(7,vin),reply(
            "18 DA F1 10 03 7F 01 78 CC CC CC CC\r18DAF110 06 41 00 08 00 00 00 CC","7"))
        assertEquals(listOf(0x18DAF110),r.eligibleResponseIds)
        assertEquals(0x18DA10F1,r.responders.single().requestId)
        assertEquals(2,r.frames.size)
    }
    @Test fun wrongProtocolAndAdapterErrorAreNotEligible(){
        assertTrue(J1979NetworkCampaign.decodeDiscovery(plan,reply(two,"A6")).eligibleResponseIds.isEmpty())
        assertTrue(J1979NetworkCampaign.decodeDiscovery(plan,reply(two+"\rBUFFER FULL")).eligibleResponseIds.isEmpty())
        assertFalse(J1979NetworkCampaign.decodeDiscovery(plan,reply("NO DATA")).completed)
    }
    @Test fun explicitSelectionAndIndependentPhysicalReads()=runBlocking {
        val opened=mutableListOf<Int>()
        val reads=mutableListOf<Pair<Int,String>>()
        val report=J1979NetworkCampaign.run(plan,{reply(two)},
            {t,_->opened+=t.responseId},
            {t,request,_->
                val command=ElmJ1979EvapTest.hex(request)
                reads+=t.responseId to command
                val data=when(command){
                    "0100"->bytes("410008000000")
                    "0902"->bytes("490201")+vin.toByteArray()
                    "0105"->bytes(if(t.responseId==0x7E8)"410564" else "410550")
                    "03"->bytes("4300")
                    "07"->bytes("4700")
                    "0A"->bytes("4A00")
                    "020200"->bytes("4202000000")
                    "0600"->bytes("460000000000")
                    "0800"->bytes("480080000000")
                    "0900"->bytes("490040000000")
                    else->error("Unexpected request $command")
                }
                J1979LiveRecorder.Reply(data,ElmJ1979EvapTest.hex(data))
            },{})
        assertTrue(report.completed)
        assertEquals(listOf(0x7E8,0x7E9),opened)
        assertEquals(listOf(60.0,40.0),report.ecus.map { ecu->ecu.steps.single { it.requestHex=="0105" }.application!!.measurement()!!.number })
        assertEquals(2,report.ecus.first().steps.count { it.requestHex=="0902" })
        assertFalse(reads.any { it.second=="0801" || it.second=="04" })
    }
    @Test fun unverifiedSelectionDoesNotOpenAnyEcu()=runBlocking {
        val report=J1979NetworkCampaign.run(plan.copy(selectedResponseIds=listOf(0x7EA)),{reply(two)},
            {_,_->error("Must not open")},{_,_,_->error("Must not query")},{})
        assertEquals("SELECTED_RESPONDER_UNVERIFIED",report.stopReason)
        assertEquals("NOT_TESTED",report.ecus.single().status)
    }
    @Test fun mismatchedVinStopsLaterEcus()=runBlocking {
        val opened=mutableListOf<Int>()
        val r=J1979NetworkCampaign.run(plan,{reply(two)},{t,_->opened+=t.responseId},{_,req,_->
            J1979LiveRecorder.Reply(if(ElmJ1979EvapTest.hex(req)=="0100")bytes("410008000000")
                else bytes("490201")+"1G1FF3D36C9104198".toByteArray(),null)
        },{})
        assertEquals("VIN_MISMATCH",r.stopReason)
        assertEquals(listOf(0x7E8),opened)
        assertEquals("NOT_TESTED",r.ecus.last().status)
    }
    @Test fun cancellationRetainsPrefixAndNoNewChannels()=runBlocking {
        val opened=mutableListOf<Int>()
        val r=J1979NetworkCampaign.run(plan,{reply(two)},{t,_->opened+=t.responseId},{_,req,_->
            if(ElmJ1979EvapTest.hex(req)=="0100")J1979LiveRecorder.Reply(bytes("410008000000"),null)
            else throw CancellationException("stop")
        },{})
        assertEquals("CANCELLED",r.stopReason)
        assertEquals(2,r.ecus.first().steps.size)
        assertEquals(listOf(0x7E8),opened)
    }
    @Test fun discoveryOnlyDoesNotOpenOrRead()=runBlocking {
        val r=J1979NetworkCampaign.run(plan.copy(selectedResponseIds=emptyList()),{reply(two)},
            {_,_->error("Must not open")},{_,_,_->error("Must not read")},{})
        assertTrue(r.completed)
        assertTrue(r.ecus.isEmpty())
        assertEquals(2,r.discovery.eligibleResponseIds.size)
    }
}
