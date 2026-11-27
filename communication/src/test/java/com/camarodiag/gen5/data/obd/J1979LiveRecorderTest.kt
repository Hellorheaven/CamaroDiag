package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class J1979LiveRecorderTest {
    private val vin="1G1FA1RXXR0123456"
    private val plan=J1979LivePlan(J1979LiveTarget(6,0x7E8,vin),listOf(5,12),3,500,30000)
    private fun bytes(hex:String)=hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun normal(req:ByteArray):J1979LiveRecorder.Reply {
        val payload=when(ElmJ1979EvapTest.hex(req)){
            "0902"->byteArrayOf(0x49,2,1)+vin.toByteArray(Charsets.US_ASCII)
            "0100"->bytes("410008100000")
            "0105"->bytes("41055A")
            "010C"->bytes("410C0FA0")
            else->error("Unexpected request")
        }
        return J1979LiveRecorder.Reply(payload,ElmJ1979EvapTest.hex(payload))
    }
    @Test fun recordsSelectedPidsAndChecksIdentityBeforeAndAfter()=runBlocking {
        var now=0L
        val sent=mutableListOf<String>()
        val result=J1979LiveRecorder.run(plan,{req,_->
            sent+=ElmJ1979EvapTest.hex(req);now+=50;normal(req)
        },{"6"},{},clock={now},sleep={now+=it})
        assertTrue(result.completed)
        assertEquals(3,result.completedCycles)
        assertEquals(listOf("0902","0100"),sent.take(2))
        assertEquals("0902",sent.last())
        assertEquals(6,result.observations.size)
        assertEquals(50.0,result.summaries[0].mean!!,0.0)
        assertEquals(1000.0,result.summaries[1].minimum!!,0.0)
        assertTrue(result.observations[2].startedElapsedMs-result.observations[0].startedElapsedMs>=500)
        assertFalse(result.wholeVehicleCoverageVerified)
    }
    @Test fun unadvertisedSelectionNeverTransmits()=runBlocking {
        val sent=mutableListOf<String>()
        val result=J1979LiveRecorder.run(plan.copy(pids=listOf(5,13)),{req,_->
            sent+=ElmJ1979EvapTest.hex(req);normal(req)
        },{"6"},{})
        assertEquals("SELECTED_PID_NOT_VERIFIED",result.stopReason)
        assertEquals(listOf("0902","0100"),sent)
    }
    @Test fun gapsDoNotReusePreviousValues()=runBlocking {
        var count=0
        var now=0L
        val result=J1979LiveRecorder.run(plan,{req,_->
            if(req.contentEquals(byteArrayOf(1,5))) {
                count++
                when(count){
                    2->J1979LiveRecorder.Reply(null,"NO DATA")
                    3->J1979LiveRecorder.Reply(bytes("7F0111"),"7F0111")
                    else->normal(req)
                }
            }else normal(req)
        },{"6"},{},clock={now},sleep={now+=it})
        val rows=result.observations.filter { it.pid==5 }
        assertEquals(listOf("PASSED","TIMEOUT","NEGATIVE"),rows.map { it.status })
        assertNull(rows[1].value);assertNull(rows[2].value)
        assertEquals(50.0,result.summaries[0].mean!!,0.0)
    }
    @Test fun wrongProtocolAndFinalVinStopCampaign()=runBlocking {
        val mismatch=J1979LiveRecorder.run(plan,{req,_->normal(req)},{"A6"},{})
        assertEquals("PROTOCOL_UNVERIFIED",mismatch.stopReason)
        assertTrue(mismatch.observations.isEmpty())
        var identity=0
        val final=J1979LiveRecorder.run(plan.copy(cycles=1),{req,_->
            if(req[0]==9.toByte()&&++identity>1)
                J1979LiveRecorder.Reply(byteArrayOf(0x49,2,1)+"1FA6P8TH0L5100000".toByteArray(),null)
            else normal(req)
        },{"6"},{})
        assertEquals("VIN_MISMATCH",final.stopReason)
        assertEquals(2,final.observations.size)
    }
    @Test fun lateAndCancelledResponsesNeverBecomeValues()=runBlocking {
        var now=0L
        val late=J1979LiveRecorder.run(plan.copy(budgetMs=100),{req,timeout->
            if(req.contentEquals(byteArrayOf(1,5)))now+=timeout
            normal(req)
        },{"6"},{},clock={now})
        assertEquals("BUDGET_EXHAUSTED",late.stopReason)
        assertNull(late.observations.single().value)
        val stopped=J1979LiveRecorder.run(plan,{req,_->
            if(req.contentEquals(byteArrayOf(1,12)))throw CancellationException()
            normal(req)
        },{"6"},{})
        assertEquals("CANCELLED",stopped.stopReason)
        assertEquals(2,stopped.observations.size)
        assertEquals(0,stopped.completedCycles)
    }
    @Test fun trimLayoutAdmissionAndIndependentBankStatistics()=runBlocking {
        var now=0L
        var reads=0
        val sent=mutableListOf<String>()
        val result=J1979LiveRecorder.run(plan.copy(pids=listOf(6),cycles=2),{req,_->
            val command=ElmJ1979EvapTest.hex(req)
            sent+=command
            now+=10
            when(command){
                "0100"->J1979LiveRecorder.Reply(bytes("410004000008"),null)
                "011D"->J1979LiveRecorder.Reply(bytes("411D30"),null)
                "0106"->J1979LiveRecorder.Reply(bytes(if(++reads==1)"41068090" else "410690"),null)
                else->normal(req)
            }
        },{"6"},{},clock={now},sleep={now+=it})
        assertTrue(result.completed)
        assertEquals(listOf("0902","0100","011D","0106","0106","0902"),sent)
        assertEquals(2,result.multiSignalSummary.size)
        assertEquals(6.25,result.multiSignalSummary[0].mean!!,0.0)
        assertEquals(12.5,result.multiSignalSummary[1].mean!!,0.0)
        assertEquals(1,result.multiSignalSummary[1].missing)
    }
    @Test fun validatesPhysicalAddressAndNumericSelection() {
        assertEquals(0x18DA10F1,J1979LiveTarget(7,0x18DAF110,vin).requestId)
        assertNull(J1979LiveTarget(7,0x7E8,vin).requestId)
        assertNull(J1979LiveTarget(7,0x18DAF1F1,vin).requestId)
        for(pid in listOf(0,1,0x13,0x20,255))assertFalse(J1979LiveRecorder.numericPid(pid))
        assertFalse(plan.copy(pids=listOf(5,5)).valid)
        assertFalse(J1979LiveRecorder.allowed(bytes("04")))
        assertFalse(J1979LiveRecorder.allowed(bytes("0801")))
    }
}
