package com.camarodiag.gen5.data.obd

import com.camarodiag.connectors.J1979CampaignSemantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/** PID00 functional discovery followed by separately addressed read campaigns.
 * A functional answer is only evidence of a responding OBD address, never
 * proof of the full vehicle topology or an authorization to write. */
data class J1979NetworkPlan(
    val protocol: Int,
    val vin: String,
    val selectedResponseIds: List<Int> = emptyList(),
    val budgetMs: Long = 180000
) {
    val extended get() = protocol in setOf(7,9)
    val valid get() = protocol in 6..9 &&
        vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) &&
        selectedResponseIds.size <= 32 && selectedResponseIds.distinct().size == selectedResponseIds.size &&
        selectedResponseIds.all { J1979LiveTarget(protocol,it,vin).valid } &&
        budgetMs in 1000..600000
}
data class J1979NetworkFrame(val responseId: Int,val dataHex: String)
data class J1979NetworkResponder(
    val requestId: Int,val responseId: Int,val status: String,val detail: String?,
    val supportedPids: List<Int>?,val nextPage: Boolean?,val negativeCode: Int?,
    val frames: List<J1979NetworkFrame>
)
data class J1979NetworkDiscovery(
    val protocol: Int,val observedProtocol: Int?,val rawProtocol: String?,
    val rawResponse: String?,val requestSent: Boolean,val completed: Boolean,
    val stopReason: String?,val responders: List<J1979NetworkResponder>,
    val frames: List<J1979NetworkFrame>,val ignoredFrames: Int
) {
    val eligibleResponseIds get() = if(completed)responders.filter { it.status=="PASSED" }.map { it.responseId }
        else emptyList()
}
data class J1979NetworkStep(
    val requestHex: String,val responseHex: String?,val rawResponse: String?,
    val status: String,val detail: String?,val durationMs: Long,
    val application: J1979CampaignSemantics.Step?,
    val extendedVehicleInfo: ObdJ1979ExtendedVehicleInfo.Result? = null,
    val multiSignalRecord: ObdJ1979MultiSignal.Record? = null
)
data class J1979NetworkInventory(
    val mode: Int,val status: String,val advertised: List<Int>,val pages: Int
)
data class J1979NetworkEcu(
    val requestId: Int,val responseId: Int,val observedVin: String?,
    val status: String,val stopReason: String?,val completed: Boolean,
    val steps: List<J1979NetworkStep>,val inventories: List<J1979NetworkInventory>
) {
    val scaledMonitorRecords get()=steps.flatMap {
        ObdCanMonitorRecords.project(it.requestHex,it.responseHex,it.status)
    }
    val statusRecords get()=steps.mapNotNull {
        ObdJ1979Status.tryRecord(it.requestHex,it.responseHex,it.status)
    }
    val operatingContext get()=ObdJ1979OperatingContext.project(steps.map {
        ObdJ1979OperatingContext.Observation(it.requestHex,it.responseHex,it.status)
    })
    val contextualMeasurements get() = ObdJ1979ScalingContext.project(steps.map {
        ObdJ1979ScalingContext.Observation(it.requestHex,it.responseHex,it.status)
    })
}
data class J1979NetworkReport(
    val plan: J1979NetworkPlan,val discovery: J1979NetworkDiscovery,
    val ecus: List<J1979NetworkEcu>,val completed: Boolean,val stopReason: String?,val durationMs: Long
)

internal object J1979NetworkCampaign {
    private const val MAX_DISCOVERY_FRAMES=512
    private const val MAX_RAW_LENGTH=256*1024
    internal data class DiscoveryReply(val raw: String,val protocolResponse: String)
    private fun hex(bytes: ByteArray)=ElmJ1979EvapTest.hex(bytes)
    private fun u(value: Byte)=value.toInt() and 255
    private fun fixedProtocol(raw: String): Int? = raw.replace('>','\n').lineSequence()
        .map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() && it!="ATDPN" }
        .toList().singleOrNull()?.takeIf { it.matches(Regex("[6-9]")) }?.toInt()

    private fun target(plan:J1979NetworkPlan,responseId:Int)=J1979LiveTarget(plan.protocol,responseId,plan.vin)

    /** Only ISO15765 single-frame PID00 answers are accepted in discovery.
     * Functional flow control is disabled by the caller. No source is chosen
     * implicitly and conflicting positive/negative answers stay visible. */
    internal fun decodeDiscovery(plan:J1979NetworkPlan,reply:DiscoveryReply):J1979NetworkDiscovery {
        require(plan.valid)
        val observed=fixedProtocol(reply.protocolResponse)
        val frames=mutableListOf<J1979NetworkFrame>()
        val byId=sortedMapOf<Int,MutableList<J1979NetworkResponder>>()
        var ignored=0
        var stop:String?=null
        if(reply.raw.length>MAX_RAW_LENGTH)stop="DISCOVERY_OUTPUT_LIMIT"
        else {
            val lines=reply.raw.replace('\r','\n').replace('>','\n').lineSequence()
                .map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }
                .take(MAX_DISCOVERY_FRAMES+1).toList()
            if(lines.size>MAX_DISCOVERY_FRAMES)stop="DISCOVERY_FRAME_LIMIT"
            else for(line in lines){
                if(line=="020100CCCCCCCC"||line=="020100"||line=="SEARCHING..."||line=="NO DATA")continue
                if(line.startsWith("FC:")||line.contains("ERROR")||line.contains("BUFFER FULL")||
                    line=="STOPPED"||line=="?"||line=="UNABLE TO CONNECT"){
                    stop="DISCOVERY_ADAPTER_FAILURE";break
                }
                val tokens=line.split(Regex("\\s+"))
                val headerCount=if(plan.extended&&tokens.size>=4&&tokens.take(4).all { it.matches(Regex("[0-9A-F]{2}")) })4 else 1
                val header=tokens.take(headerCount).joinToString("")
                val width=if(plan.extended)8 else 3
                if(header.length!=width||!header.matches(Regex("[0-9A-F]+"))){
                    stop="UNPARSEABLE_DISCOVERY_FRAME";break
                }
                val rx=header.toIntOrNull(16)
                if(rx==null||!target(plan,rx).valid){ignored++;continue}
                val data=tokens.drop(headerCount)
                if(data.isEmpty()||data.size>8||data.any { !it.matches(Regex("[0-9A-F]{2}")) }){
                    stop="MALFORMED_RESPONDER_FRAME";break
                }
                val bytes=data.map { it.toInt(16).toByte() }.toByteArray()
                val frame=J1979NetworkFrame(rx,hex(bytes))
                frames+=frame
                val tx=requireNotNull(target(plan,rx).requestId)
                val invalid=J1979NetworkResponder(tx,rx,"FAILED","INVALID_PID00_FRAME",null,null,null,listOf(frame))
                val response=when {
                    bytes.size>=7&&u(bytes[0])==6&&u(bytes[1])==0x41&&u(bytes[2])==0 -> {
                        val page=J1979CampaignSemantics.decodePositive("0100",hex(bytes.copyOfRange(1,7)))?.supportedPage()
                        if(page==null)invalid else invalid.copy(status="PASSED",detail=null,supportedPids=page.supportedIds,
                            nextPage=page.hasNextPage)
                    }
                    bytes.size>=4&&u(bytes[0])==3&&u(bytes[1])==0x7F&&u(bytes[2])==1&&u(bytes[3])!=0 ->
                        invalid.copy(status="NOT_TESTED",detail=if(u(bytes[3])==0x78)"PENDING_WITHOUT_FINAL_RESPONSE"
                            else "NRC_"+ElmJ1979EvapTest.hex(u(bytes[3]),2),
                            negativeCode=u(bytes[3]).takeUnless { it==0x78 })
                    else -> invalid
                }
                byId.getOrPut(rx){mutableListOf()}.add(response)
            }
        }
        val responders=byId.values.map { group ->
            val terminals=group.filter { it.detail!="PENDING_WITHOUT_FINAL_RESPONSE" }
            val first=terminals.firstOrNull()?:group.first()
            val evidence=group.flatMap { it.frames }
            if(terminals.any { it.copy(frames=emptyList())!=first.copy(frames=emptyList()) })
                first.copy(status="FAILED",detail="CONFLICTING_RESPONSES",supportedPids=null,nextPage=null,
                    negativeCode=null,frames=evidence)
            else first.copy(frames=evidence)
        }
        if(stop==null&&observed!=plan.protocol)stop="PROTOCOL_UNVERIFIED"
        if(stop==null&&responders.none { it.status=="PASSED" })stop="NO_VERIFIED_RESPONDER"
        return J1979NetworkDiscovery(plan.protocol,observed,reply.protocolResponse,
            reply.raw.take(MAX_RAW_LENGTH),true,stop==null,stop,responders,frames,ignored)
    }

    fun allowed(request: ByteArray): Boolean = when(request.size){
        1 -> u(request[0]) in setOf(3,7,10)
        2 -> u(request[0]) in setOf(1,6,9) || u(request[0])==8&&u(request[1])%32==0
        3 -> u(request[0])==2&&u(request[2])==0
        else -> false
    }

    suspend fun run(requested:J1979NetworkPlan,
        discover:suspend (Long)->DiscoveryReply,
        open:suspend (J1979LiveTarget,Long)->Unit,
        exchange:suspend (J1979LiveTarget,ByteArray,Long)->J1979LiveRecorder.Reply,
        requireCurrent:()->Unit,
        clock:()->Long={System.nanoTime()/1000000}):J1979NetworkReport {
        val plan=requested.copy(selectedResponseIds=requested.selectedResponseIds.toList())
        require(plan.valid)
        val start=clock()
        fun elapsed()=(clock()-start).coerceAtLeast(0)
        fun remaining()=plan.budgetMs-elapsed()
        suspend fun guard(){
            currentCoroutineContext().ensureActive()
            requireCurrent()
        }
        var stop:String?=null
        var discovery=J1979NetworkDiscovery(plan.protocol,null,null,null,false,false,
            "NOT_STARTED",emptyList(),emptyList(),0)
        val reports=mutableListOf<J1979NetworkEcu>()
        try {
            guard()
            val reply=discover(minOf(5000,remaining().coerceAtLeast(1)))
            guard()
            discovery=decodeDiscovery(plan,reply)
            if(remaining()<=0)discovery=discovery.copy(completed=false,stopReason="BUDGET_EXHAUSTED")
            stop=discovery.stopReason
            if(stop==null&&!plan.selectedResponseIds.all { it in discovery.eligibleResponseIds })
                stop="SELECTED_RESPONDER_UNVERIFIED"
        }catch(_:CancellationException){stop="CANCELLED"}
        catch(e:Exception){stop=e.javaClass.simpleName}
        if(discovery.stopReason=="NOT_STARTED")discovery=discovery.copy(stopReason=stop)
        for(rx in plan.selectedResponseIds){
            val target=target(plan,rx)
            val tx=requireNotNull(target.requestId)
            if(stop!=null){
                reports+=J1979NetworkEcu(tx,rx,null,"NOT_TESTED",stop,false,emptyList(),emptyList())
                continue
            }
            val steps=mutableListOf<J1979NetworkStep>()
            val inventories=mutableListOf<J1979NetworkInventory>()
            var observedVin:String?=null
            suspend fun read(request:ByteArray):J1979CampaignSemantics.Step?{
                if(stop!=null)return null
                guard()
                if(remaining()<=0){stop="BUDGET_EXHAUSTED";return null}
                check(allowed(request))
                val begun=clock()
                val command=hex(request)
                var reply:J1979LiveRecorder.Reply?=null
                var response:String?=null
                var evidence:J1979CampaignSemantics.Step?=null
                var extended:ObdJ1979ExtendedVehicleInfo.Result?=null
                var multi:ObdJ1979MultiSignal.Record?=null
                var status="NOT_TESTED"
                var detail:String?=null
                try {
                    reply=exchange(target,request,minOf(2500,remaining()))
                    response=reply.payload?.let(::hex)
                    guard()
                    if(remaining()<=0){stop="BUDGET_EXHAUSTED";detail="LATE_RESPONSE"}
                    else if(response==null){status="TIMEOUT";detail="NO_DATA"}
                    else {
                        evidence=J1979CampaignSemantics.decodePositive(command,response)
                            ?:J1979CampaignSemantics.decodeObservation(command,response,"RAW")
                            ?:J1979CampaignSemantics.decodeObservation(command,response,"NEGATIVE")
                        if(evidence==null){status="FAILED";stop="INVALID_APPLICATION_RESPONSE";detail=stop}
                        else {
                            status=evidence.status
                            detail=evidence.negativeResponseCode()?.let { "NRC_"+ElmJ1979EvapTest.hex(it,2) }
                            if(status in setOf("RAW","PASSED")&&command=="0141"&&
                                ObdJ1979OperatingContext.decodeCycle(requireNotNull(reply.payload).drop(2).map(::u),null)==null){
                                status="FAILED";detail="INVALID_DRIVE_CYCLE";evidence=null
                            }
                            if(status in setOf("RAW","PASSED") && request.size==2 && u(request[0])==1 &&
                                ObdJ1979ScalingContext.isConfiguration(u(request[1])) &&
                                !ObdJ1979ScalingContext.validConfiguration(u(request[1]),requireNotNull(reply.payload).drop(2).map(::u))){
                                status="FAILED";detail="INVALID_SCALING_CONFIGURATION";evidence=null
                            }
                            if(status in setOf("RAW","PASSED")&&request.size==2&&u(request[0])==1&&
                                ObdJ1979Status.width(u(request[1]))!=null&&
                                ObdJ1979Status.tryRecord(command,response,status)==null){
                                status="FAILED";detail="INVALID_CATEGORICAL_RECORD";evidence=null
                            }
                            if(status in setOf("RAW","PASSED") && request.size==2 &&
                                u(request[0])==1 && ObdJ1979MultiSignal.width(u(request[1]))!=null){
                                multi=ObdJ1979MultiSignal.tryRecord(command,response,status)
                                if(multi==null){
                                    status="FAILED";detail="INVALID_MULTI_SIGNAL";evidence=null
                                }else detail="MULTI_SIGNAL_DECODED"
                            }
                            if(status=="RAW" && request.size==2 && u(request[0])==9 &&
                                u(request[1]) in ObdJ1979ExtendedVehicleInfo.extendedTypes) {
                                extended=reply.payload?.let { bytes ->
                                    ObdJ1979ExtendedVehicleInfo.can(u(request[1]),bytes.map(::u))
                                }
                                if(extended==null){
                                    status="FAILED"
                                    detail="INVALID_EXTENDED_INFOTYPE"
                                    evidence=null
                                }else detail="EXTENDED_INFOTYPE_DECODED"
                            }
                        }
                    }
                }catch(_:CancellationException){status="NOT_TESTED";stop="CANCELLED";detail=stop;evidence=null;extended=null;multi=null}
                catch(e:Exception){status="FAILED";stop="TRANSPORT_OR_CONTEXT_ERROR";detail=e.javaClass.simpleName;evidence=null;extended=null}
                steps+=J1979NetworkStep(command,response,reply?.raw,status,detail,(clock()-begun).coerceAtLeast(0),evidence,extended,multi)
                return evidence?.takeIf { status=="PASSED" }
            }
            suspend fun inventory(mode:Int,cached:J1979CampaignSemantics.Step?=null,members:Boolean=true){
                val ids=sortedSetOf<Int>()
                var pages=0
                var complete=false
                for(base in 0..0xE0 step 32){
                    if(stop!=null)break
                    val query=if(mode==2)byteArrayOf(2,base.toByte(),0) else byteArrayOf(mode.toByte(),base.toByte())
                    val page=(if(base==0&&cached!=null)cached else read(query))?.supportedPage()?:break
                    pages++
                    ids.addAll(page.supportedIds)
                    if(members)for(pid in page.supportedIds){
                        if(stop!=null)break
                        if(mode in setOf(2,9)&&pid==2)continue
                        read(if(mode==2)byteArrayOf(2,pid.toByte(),0)else byteArrayOf(mode.toByte(),pid.toByte()))
                    }
                    if(stop!=null)break
                    if(!page.hasNextPage){complete=true;break}
                }
                inventories+=J1979NetworkInventory(mode,if(complete)"COMPLETE" else if(pages>0)"PARTIAL" else "UNAVAILABLE",
                    ids.toList(),pages)
            }
            try {
                guard()
                if(remaining()<=0)stop="BUDGET_EXHAUSTED"
                else {
                    open(target,remaining())
                    val initial=read(byteArrayOf(1,0))
                    if(initial?.supportedPage()==null&&stop==null)stop="INITIAL_PID00_UNVERIFIED"
                    if(stop==null){
                        observedVin=read(byteArrayOf(9,2))?.observedVin()
                        if(observedVin!=plan.vin&&stop==null)stop=if(observedVin==null)"VIN_UNVERIFIED"else "VIN_MISMATCH"
                    }
                    if(stop==null){
                        for(mode in listOf(3,7,10))read(byteArrayOf(mode.toByte()))
                        inventory(1,initial)
                        val freeze=read(byteArrayOf(2,2,0))?.freezeTrigger()
                        if(freeze?.present==true)inventory(2)
                        else inventories+=J1979NetworkInventory(2,if(freeze==null)"UNAVAILABLE"else "NOT_APPLICABLE",emptyList(),0)
                        inventory(6)
                        inventory(8,members=false)
                        inventory(9)
                        if(stop==null){
                            val finalVin=read(byteArrayOf(9,2))?.observedVin()
                            if(finalVin!=plan.vin&&stop==null)stop=if(finalVin==null)"VIN_UNVERIFIED"else "VIN_MISMATCH"
                        }
                    }
                }
            }catch(_:CancellationException){stop="CANCELLED"}
            catch(e:Exception){stop=e.javaClass.simpleName}
            reports+=J1979NetworkEcu(tx,rx,observedVin,
                if(stop==null)"PASSED"else if(stop=="CANCELLED"||steps.isEmpty())"NOT_TESTED"else "FAILED",
                stop,stop==null,steps.toList(),inventories.toList())
        }
        return J1979NetworkReport(plan,discovery,reports,stop==null,stop,elapsed())
    }
}
