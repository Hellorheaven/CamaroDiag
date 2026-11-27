package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class LegacyLiveExchange(val logicalQuery: Int,val requestHex: String,val rawResponse: String?,
    val frames: List<LegacyReadFrame>,val selectedRecordsHex: List<String>,val status: String,val detail: String?)

/** Legacy live samples use the existing checked ATH1 framing, and a logical
 * VIN query expands to several bounded legacy requests. Original evidence
 * remains separate from the normalized application bytes. */
internal class ElmLegacyLiveLink(
    private val target: J1979LiveTarget,
    private val exchange: suspend (String,Long)->String,
    private val probe: suspend (Long)->String,
    private val requireCurrent: ()->Unit,
    private val clock: ()->Long={System.nanoTime()/1000000}
) {
    init {require(target.legacy&&target.valid)}
    private val trace=mutableListOf<LegacyLiveExchange>()
    val traces: List<LegacyLiveExchange> get()=trace.toList()
    private var logical=0
    private var confirmed: String?=null
    private fun hex(rows: List<Int>)=rows.joinToString("",transform=ElmLegacyResponseFrames::hex)
    private suspend fun active(){
        currentCoroutineContext().ensureActive()
        requireCurrent()
    }
    private suspend fun remaining(deadline:Long,maximum:Long):Long {
        active()
        val left=deadline-clock()
        if(left<=0)throw LogicalTimeout()
        return minOf(left,maximum)
    }
    private class LogicalTimeout: Exception()
    private fun mark(status:String,detail:String?=null){
        trace[trace.lastIndex]=trace.last().copy(status=status,detail=detail)
    }
    private fun allows(command:String):Boolean =
        command in setOf("0100","0900","0901","0902") ||
            command.matches(Regex("01[0-9A-F]{2}"))&&command.takeLast(2).toInt(16).let {
                it%32==0||ObdJ1979ScalingContext.isConfiguration(it)||J1979LiveRecorder.legacyNumericPid(it)
            }
    private suspend fun read(command:String,deadline:Long,index:Int):List<List<Int>>{
        check(allows(command))
        trace+=LegacyLiveExchange(index,command,null,emptyList(),emptyList(),"NOT_TESTED",null)
        try{
            val raw=exchange(command,remaining(deadline,4000))
            trace[trace.lastIndex]=trace.last().copy(rawResponse=raw)
            active()
            if(clock()>=deadline){mark("NOT_TESTED","LATE_RESPONSE");throw LogicalTimeout()}
            val parsed=ElmLegacyResponseFrames.decode(target.protocol,command,raw)
            val selected=parsed.filter { it.source==target.responseId }.map { it.payload }
            trace[trace.lastIndex]=trace.last().copy(frames=parsed.map {
                LegacyReadFrame(ElmLegacyResponseFrames.hex(it.source),it.rawHex,hex(it.payload))
            },selectedRecordsHex=selected.map(::hex))
            if(selected.isEmpty()){
                mark("TIMEOUT","NO_SELECTED_RESPONSE")
                return emptyList()
            }
            mark("CORRELATED")
            return selected
        }catch(cancelled:CancellationException){mark("NOT_TESTED","CANCELLED");throw cancelled}
        catch(timeout:LogicalTimeout){if(trace.last().detail==null)mark("TIMEOUT","TIMEOUT");throw timeout}
        catch(error:Exception){if(trace.last().detail==null)mark("FAILED",error.javaClass.simpleName);throw error}
    }
    private suspend fun single(command:String,deadline:Long,index:Int):List<Int>? {
        val rows=read(command,deadline,index)
        if(rows.isEmpty())return null
        if(rows.size!=1){mark("FAILED","AMBIGUOUS_SELECTED_SOURCE");error("Multiple selected legacy records.")}
        return rows[0]
    }
    private fun negative(payload:List<Int>,mode:Int)=payload.size==3&&
        payload.take(2)==listOf(0x7F,mode)&&payload[2] !in setOf(0,0x78)
    private fun bytes(payload:List<Int>)=payload.map(Int::toByte).toByteArray()
    private suspend fun verify(deadline:Long,index:Int) {
        trace+=LegacyLiveExchange(index,"ATDPN",null,emptyList(),emptyList(),"NOT_TESTED",null)
        try{
            val raw=probe(remaining(deadline,1500))
            trace[trace.lastIndex]=trace.last().copy(rawResponse=raw)
            active()
            if(clock()>=deadline){mark("NOT_TESTED","LATE_PROTOCOL");throw LogicalTimeout()}
            if(ElmLegacyResponseFrames.fixedProtocol(raw)!=target.protocol){
                mark("FAILED","PROTOCOL_MISMATCH");error("Legacy protocol changed.")
            }
            confirmed=raw
            mark("PASSED")
        }catch(cancelled:CancellationException){mark("NOT_TESTED","CANCELLED");throw cancelled}
        catch(error:Exception){if(trace.last().detail==null)mark("FAILED",error.javaClass.simpleName);throw error}
    }
    private suspend fun identity(deadline:Long,index:Int):ByteArray?{
        confirmed=null
        val initial=single("0100",deadline,index)?:return null
        if(initial.size!=6||initial.take(2)!=listOf(0x41,0)){
            mark("FAILED","INITIAL_PID00_UNVERIFIED");error("Initial legacy PID00 unverified.")
        }
        mark("PASSED")
        verify(deadline,index)
        val page=single("0900",deadline,index)?:return null
        if(negative(page,9)){mark("NEGATIVE");return bytes(page)}
        if(page.size!=7||page.take(3)!=listOf(0x49,0,1)||page[3] and 0xC0 != 0xC0){
            mark("FAILED","VIN_INFO_TYPES_NOT_VERIFIED");error("Legacy VIN types not advertised.")
        }
        mark("PASSED")
        val count=single("0901",deadline,index)?:return null
        if(negative(count,9)){mark("NEGATIVE");return bytes(count)}
        if(count!=listOf(0x49,1,5)){
            mark("FAILED","VIN_COUNT_INVALID");error("Expected five VIN fragments.")
        }
        mark("PASSED")
        val rows=read("0902",deadline,index)
        if(rows.isEmpty())return null
        if(rows.size==1&&negative(rows[0],9)){mark("NEGATIVE");return bytes(rows[0])}
        if(rows.size!=5||rows.withIndex().any { (i,row)->row.size!=7||row.take(3)!=listOf(0x49,2,i+1) }){
            mark("FAILED","VIN_FRAGMENT_SEQUENCE_INVALID");error("Incomplete selected VIN fragments.")
        }
        val payload=rows.flatMap { it.drop(3) }
        val vin=payload.drop(3).map(Int::toChar).joinToString("")
        if(payload.take(3)!=listOf(0,0,0)||!vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))){
            mark("FAILED","VIN_PADDING_OR_ASCII_INVALID");error("Invalid legacy VIN.")
        }
        mark("PASSED")
        return byteArrayOf(0x49,2,1)+vin.toByteArray(Charsets.US_ASCII)
    }
    suspend fun query(request:ByteArray,timeout:Long):J1979LiveRecorder.Reply {
        require(timeout in 1..20000)
        check(J1979LiveRecorder.allowed(request))
        val index=logical++
        val deadline=clock()+timeout
        val start=trace.size
        val payload=try{
            if(request.contentEquals(byteArrayOf(9,2)))identity(deadline,index)
            else single(ElmJ1979EvapTest.hex(request),deadline,index)?.let(::bytes)
        }catch(_:LogicalTimeout){null}
        val raw=trace.drop(start).lastOrNull()?.rawResponse
        return J1979LiveRecorder.Reply(payload,raw)
    }
    suspend fun protocolResponse(timeout:Long):String {
        active()
        require(timeout>0)
        // This is the actual ATDPN response captured within the most recent
        // bounded identity query, not a new probe after its deadline.
        return confirmed?:error("No confirmed legacy protocol response.")
    }
}
