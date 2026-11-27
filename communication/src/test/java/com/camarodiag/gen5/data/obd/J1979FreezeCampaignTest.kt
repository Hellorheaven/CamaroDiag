package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class J1979FreezeCampaignTest {
    private val vin = "1G1FA1E30D1234567"
    private val target = J1979LiveTarget(6,0x7e8,vin)
    private val plan = J1979FreezePlan(target,listOf(0,1),listOf(12,13,42),60000)
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(data: ByteArray) = data.joinToString("") { "%02X".format(it.toInt() and 255) }
    private fun vinReply(value: String=vin) = bytes("490201")+value.toByteArray(Charsets.US_ASCII)
    private fun reply(req: ByteArray): ByteArray {
        if (req.contentEquals(bytes("0902"))) return vinReply()
        val body = when(req[1].toInt() and 255) {
            0 -> bytes("40180000")
            2 -> bytes("0301")
            12 -> bytes("1F40")
            13 -> bytes("50")
            else -> error("Unexpected fixture request")
        }
        return byteArrayOf(0x42,req[1],req[2])+body
    }
    private fun received(payload: ByteArray) = J1979FreezeCampaign.Read("RECEIVED",payload,5,true)

    @Test fun explicitFramesAndSelectedMeasurements() = runBlocking {
        val requests=mutableListOf<String>()
        val result=J1979FreezeCampaign.execute(plan,observe={ req ->
            requests += hex(req)
            received(reply(req))
        },elapsed={ requests.size*5L })
        assertEquals(listOf("0902","020000","020200","020C00","020D00","020200",
            "020001","020201","020C01","020D01","020201","0902"),requests)
        assertTrue(result.completed)
        assertTrue(result.identityRechecked)
        assertTrue(result.allFramesStable)
        assertEquals(listOf("COMPLETE","COMPLETE"),result.frames.map { it.status })
        assertEquals(listOf(42),result.frames[0].unadvertisedSelectedPids)
        assertEquals("PASSED",result.steps.first { it.requestHex=="020C00" }.status)
        assertEquals("RAW",result.steps.first { it.requestHex=="020C01" }.status)
    }

    @Test fun vinMismatchPreventsFrameReads() = runBlocking {
        val result=J1979FreezeCampaign.execute(plan,observe={
            received(vinReply("1G1FA1E30D7654321"))
        })
        assertEquals("VIN_MISMATCH",result.stopReason)
        assertEquals(1,result.steps.size)
        assertEquals(listOf(0,1),result.unvisitedFrames)
    }

    @Test fun zeroTriggerAndChangedTrigger() = runBlocking {
        val absent=J1979FreezeCampaign.execute(plan,observe={ req ->
            received(if(hex(req)=="020200") bytes("4202000000") else reply(req))
        })
        assertEquals("ABSENT",absent.frames[0].status)
        assertTrue(absent.frames[0].readPids.isEmpty())
        var count=0
        val changed=J1979FreezeCampaign.execute(plan,observe={ req ->
            received(if(hex(req)=="020200" && ++count==2) bytes("4202000302") else reply(req))
        })
        assertEquals("CHANGED",changed.frames[0].status)
        assertFalse(changed.allFramesStable)
    }

    @Test fun budgetAndTransportFailureAreNotSuccess() = runBlocking {
        val result=J1979FreezeCampaign.execute(plan,observe={
            J1979FreezeCampaign.Read("BUDGET",null,0,false)
        })
        assertFalse(result.completed)
        assertEquals("BUDGET_EXHAUSTED",result.stopReason)
        assertFalse(result.steps.single().transmissionAttempted)
        val failure=J1979FreezeCampaign.execute(plan,observe={
            J1979FreezeCampaign.Read("TRANSPORT_ERROR",null,10,true)
        })
        assertEquals("TRANSPORT_ERROR",failure.stopReason)
        assertFalse(failure.allFramesStable)
    }

    @Test fun preflightTimeoutDoesNotAdvertiseTransmittedMode02Request() = runBlocking {
        var exchanges = 0
        val rejected = J1979FreezeCampaign.run(plan,exchange={ _,_ ->
            exchanges++
            J1979LiveRecorder.Reply(vinReply(),null)
        },guard={},requestTimeout={0})
        assertEquals(0,exchanges)
        assertEquals("CONTEXT_CHANGED",rejected.stopReason)
        assertFalse(rejected.steps.single().transmissionAttempted)
        assertNull(rejected.initialVin)
    }

    @Test fun releasedVehicleOrEcuDiscardsResponseAfterExchange() = runBlocking {
        var active = true
        val revoked = J1979FreezeCampaign.run(plan,exchange={ _,_ ->
            active = false
            J1979LiveRecorder.Reply(vinReply(),"old device response")
        },guard={if(!active)error("Vehicle or ECU generation revoked")})
        assertEquals("CONTEXT_CHANGED",revoked.stopReason)
        assertTrue(revoked.steps.single().transmissionAttempted)
        assertNull(revoked.steps.single().responseHex)
        assertNull(revoked.initialVin)
        assertFalse(revoked.completed)
    }

    @Test fun revokedTimeoutAndOrdinaryTimeoutRemainDistinct() = runBlocking {
        var active = true
        val revoked = J1979FreezeCampaign.run(plan,exchange={ _,_ ->
            active = false
            throw java.util.concurrent.TimeoutException("Old ECU response window elapsed")
        },guard={if(!active)error("Connection ownership changed")})
        assertEquals("CONTEXT_CHANGED",revoked.stopReason)
        assertTrue(revoked.steps.single().transmissionAttempted)
        val timeout = J1979FreezeCampaign.run(plan,exchange={ _,_ ->
            throw java.util.concurrent.TimeoutException("ECU did not answer")
        },guard={})
        assertEquals("TIMEOUT",timeout.steps.single().transportOutcome)
        assertTrue(timeout.steps.single().transmissionAttempted)
    }

    @Test fun frameEchoAndProtocolEvidenceMustMatch() {
        val result=J1979FreezeCampaign.classify(bytes("020D01"),received(bytes("420D0050")))
        assertEquals("FAILED",result.status)
        assertNull(result.evidence)
        assertEquals(6,J1979FreezeCampaign.fixedProtocol("ATDPN\r6\r>"))
        for(raw in listOf("A6\r>","6\r7\r>","OK\r>",""))
            assertNull(J1979FreezeCampaign.fixedProtocol(raw))
    }

    @Test fun explicitPlanRejectsWrongScope() {
        assertTrue(plan.valid)
        assertFalse(plan.copy(frames=listOf(0,0)).valid)
        assertFalse(plan.copy(frames=emptyList()).valid)
        assertFalse(plan.copy(selectedPids=listOf(2)).valid)
        assertFalse(plan.copy(selectedPids=listOf(32)).valid)
        assertTrue(plan.copy(target=J1979LiveTarget(2,0x10,vin)).valid)
        for (req in listOf("04","0800","22F190","020D02"))
            assertFalse(J1979FreezeCampaign.allowed(bytes(req),plan.frames))
    }
}
