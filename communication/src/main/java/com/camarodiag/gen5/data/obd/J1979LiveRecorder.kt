package com.camarodiag.gen5.data.obd

import com.camarodiag.connectors.J1979CampaignSemantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import java.util.Locale

/** Selected physical classic CAN channel, not a functional broadcast.
 * The numeric PID catalog is derived from the shared application decoder. */
data class J1979LiveTarget(val protocol: Int, val responseId: Int, val vin: String) {
    val requestId: Int? get() = when {
        protocol in setOf(6,8) && responseId in 0x7E8..0x7EF -> responseId-8
        protocol in setOf(7,9) && responseId and 0x1FFFFF00 == 0x18DAF100 &&
            responseId in 0..0x1FFFFFFF && responseId and 255 < 0xF0 ->
                0x18DA00F1 or ((responseId and 255) shl 8)
        else -> null
    }
    val extended: Boolean get() = protocol in setOf(7,9)
    val valid: Boolean get() = requestId != null && vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))
    fun idHex(id: Int) = id.toString(16).uppercase(Locale.ROOT).padStart(if(extended)8 else 3,'0')
}

data class J1979LivePlan(val target: J1979LiveTarget, val pids: List<Int>,
    val cycles: Int = 30, val intervalMs: Long = 1000, val budgetMs: Long = 180000) {
    val valid: Boolean get() = target.valid && pids.size in 1..32 && pids.distinct().size == pids.size &&
        pids.all(J1979LiveRecorder::numericPid) && cycles in 1..1000 && cycles*pids.size <= 10000 &&
        intervalMs in 100..60000 && budgetMs in 1..600000
}
data class J1979LiveValue(val name: String,val number: Double,val unit: String)
data class J1979LiveObservation(val phase: String,val cycle: Int?,val pid: Int?,
    val requestHex: String,val responseHex: String?,val rawAdapterResponse: String?,
    val startedElapsedMs: Long,val elapsedMs: Long,val durationMs: Long,
    val status: String,val detail: String?,val value: J1979LiveValue?)
data class J1979LivePage(val base: Int,val supportedIds: List<Int>,val hasNextPage: Boolean)
data class J1979LiveSummary(val pid: Int,val name: String?,val unit: String?,val attempted: Int,
    val statusCounts: Map<String,Int>,val minimum: Double?,val maximum: Double?,val mean: Double?,
    val lastVerifiedValue: Double?,val lastVerifiedElapsedMs: Long?)
data class J1979LiveReport(val plan: J1979LivePlan,val observedVin: String?,val observedProtocol: Int?,
    val protocolResponse: String?,val advertisedPids: List<Int>,val inventoryPages: List<J1979LivePage>,
    val inventoryComplete: Boolean,val completedCycles: Int,val admission: List<J1979LiveObservation>,
    val observations: List<J1979LiveObservation>,val summaries: List<J1979LiveSummary>,
    val completed: Boolean,val stopReason: String?,val durationMs: Long) {
    val physicalVehicleVerified get() = false
    val wholeVehicleCoverageVerified get() = false
    val vinMatch: String get() = when {
        observedVin == null -> "UNVERIFIED"
        observedVin == plan.target.vin -> "MATCH"
        else -> "MISMATCH"
    }
}
internal object J1979LiveRecorder {
    data class Reply(val payload: ByteArray?,val raw: String?)
    private fun hex(bytes: ByteArray) = ElmJ1979EvapTest.hex(bytes)
    private fun hex(n: Int) = ElmJ1979EvapTest.hex(n,2)
    fun numericPid(pid: Int): Boolean {
        if(pid !in 1..255 || pid%32==0)return false
        val request="01"+hex(pid)
        return listOf(1,2,4).any { width ->
            J1979CampaignSemantics.decodePositive(request,"41"+hex(pid)+"00".repeat(width))?.measurement()!=null
        }
    }
    fun allowed(request: ByteArray) = request.contentEquals(byteArrayOf(9,2)) ||
        request.size==2 && request[0]==1.toByte() &&
        ((request[1].toInt() and 255)%32==0 || numericPid(request[1].toInt() and 255))
    private class Accumulator(private val pid: Int) {
        private val counts=linkedMapOf<String,Int>()
        private var name: String?=null
        private var unit: String?=null
        private var min: Double?=null
        private var max: Double?=null
        private var mean: Double?=null
        private var last: Double?=null
        private var time: Long?=null
        fun append(row: J1979LiveObservation) {
            counts[row.status]=(counts[row.status]?:0)+1
            if(row.status!="PASSED")return
            val value=requireNotNull(row.value)
            require(value.number.isFinite())
            name=value.name;unit=value.unit
            min=min?.let { minOf(it,value.number) }?:value.number
            max=max?.let { maxOf(it,value.number) }?:value.number
            mean=mean?.let { it+(value.number-it)/counts.getValue("PASSED") }?:value.number
            last=value.number;time=row.elapsedMs
        }
        fun snapshot()=J1979LiveSummary(pid,name,unit,counts.values.sum(),counts.toMap(),
            min,max,mean,last,time)
    }

    suspend fun run(requested: J1979LivePlan,exchange: suspend (ByteArray,Long)->Reply,
        verifyProtocol: suspend (Long)->String,requireCurrent: ()->Unit,
        onSample: (J1979LiveObservation)->Unit={},
        clock: ()->Long={System.nanoTime()/1000000},
        sleep: suspend (Long)->Unit={delay(it)}): J1979LiveReport {
        val plan=requested.copy(pids=requested.pids.toList())
        require(plan.valid)
        val start=clock()
        fun elapsed()=(clock()-start).coerceAtLeast(0)
        fun remaining()=plan.budgetMs-elapsed()
        val admission=mutableListOf<J1979LiveObservation>()
        val rows=mutableListOf<J1979LiveObservation>()
        val pages=mutableListOf<J1979LivePage>()
        val advertised=sortedSetOf<Int>()
        val stats=plan.pids.associateWith { Accumulator(it) }
        var vin: String?=null
        var observedProtocol: Int?=null
        var protocolRaw: String?=null
        var lastIdentity: Long?=null
        var stop: String?=null
        var completeInventory=false
        var cycles=0
        suspend fun active(): Boolean {
            if(stop!=null)return false
            currentCoroutineContext().ensureActive()
            requireCurrent()
            if(remaining()<=0){stop="BUDGET_EXHAUSTED";return false}
            return true
        }
        suspend fun query(request: ByteArray,phase: String,cycle: Int?=null,pid: Int?=null):
            Pair<J1979LiveObservation,J1979CampaignSemantics.Step?>? {
            if(!active())return null
            check(allowed(request))
            val begun=elapsed()
            val command=hex(request)
            var reply: Reply?=null
            var response: String?=null
            var evidence: J1979CampaignSemantics.Step?=null
            var value: J1979LiveValue?=null
            var status="NOT_TESTED"
            var detail: String?=null
            try {
                reply=exchange(request,minOf(2500,remaining()))
                response=reply.payload?.let(::hex)
                currentCoroutineContext().ensureActive()
                requireCurrent()
                if(remaining()<=0){stop="BUDGET_EXHAUSTED";detail="LATE_RESPONSE"}
                else if(response==null){status="TIMEOUT";detail="NO_DATA"}
                else {
                    val raw=response
                    evidence=J1979CampaignSemantics.decodePositive(command,raw)
                        ?:J1979CampaignSemantics.decodeObservation(command,raw,"NEGATIVE")
                    if(evidence==null){
                        status="FAILED";stop="INVALID_APPLICATION_RESPONSE";detail=stop
                    }else {
                        status=evidence.status
                        if(status=="NEGATIVE")detail="NRC_"+hex(requireNotNull(evidence.negativeResponseCode()))
                        if(phase=="SAMPLE"&&status=="PASSED"){
                            val m=evidence.measurement()
                            if(m==null||m.pid!=pid||m.mode!=1||!m.number.isFinite()){
                                status="FAILED";stop="INVALID_MEASUREMENT";detail=stop;evidence=null
                            }else value=J1979LiveValue(m.name,m.number,m.unit)
                        }
                    }
                }
            }catch(_:CancellationException){
                stop="CANCELLED";detail=stop;evidence=null;value=null;status="NOT_TESTED"
            }catch(e:Exception){
                stop="TRANSPORT_OR_CONTEXT_ERROR";detail=e.javaClass.simpleName
                evidence=null;value=null;status="FAILED"
            }
            val end=elapsed()
            val row=J1979LiveObservation(phase,cycle,pid,command,response,reply?.raw,begun,end,
                end-begun,status,detail,value)
            if(phase=="SAMPLE"){
                rows+=row
                stats.getValue(requireNotNull(pid)).append(row)
                onSample(row)
            }else admission+=row
            return row to evidence
        }
        suspend fun identity(phase: String): Boolean {
            val (_,evidence)=query(byteArrayOf(9,2),phase)?:return false
            if(stop!=null)return false
            val found=evidence?.observedVin()
            if(phase=="INITIAL_VIN")vin=found
            if(found!=plan.target.vin){
                stop=if(found==null)"VIN_UNVERIFIED" else "VIN_MISMATCH"
                return false
            }
            lastIdentity=elapsed()
            return true
        }
        suspend fun protocol(): Boolean {
            if(!active())return false
            protocolRaw=verifyProtocol(minOf(1500,remaining()))
            if(!active())return false
            val values=protocolRaw.orEmpty().replace('>','\n').lineSequence()
                .map { it.trim().uppercase(Locale.ROOT) }
                .filter { it.isNotEmpty() && it!="ATDPN" }.toList()
            observedProtocol=values.singleOrNull()?.takeIf { it.matches(Regex("[6-9]")) }?.toInt()
            if(observedProtocol!=plan.target.protocol){stop="PROTOCOL_UNVERIFIED";return false}
            return true
        }
        suspend fun inventory() {
            for(base in 0..0xE0 step 32){
                val (row,evidence)=query(byteArrayOf(1,base.toByte()),"INVENTORY")?:break
                if(stop!=null)break
                val page=evidence?.supportedPage()
                if(row.status!="PASSED"||page==null||page.mode!=1||page.base!=base)break
                val ids=page.supportedIds.filter { it%32!=0 }
                pages+=J1979LivePage(base,ids,page.hasNextPage)
                advertised.addAll(ids)
                if(!page.hasNextPage){completeInventory=true;break}
                if(plan.pids.all { it in advertised })break
            }
            if(stop==null&&!plan.pids.all { it in advertised })stop="SELECTED_PID_NOT_VERIFIED"
        }
        suspend fun pause(target: Long): Boolean {
            while(active()){
                val left=target-elapsed()
                if(left<=0)return true
                sleep(minOf(200,left,remaining()))
            }
            return false
        }
        try {
            // VIN establishes application response on the selected CAN
            // channel; ATDPN then confirms the fixed electrical profile.
            if(identity("INITIAL_VIN")&&protocol())inventory()
            if(stop==null){
                var next=elapsed()
                for(cycle in 1..plan.cycles){
                    if(!pause(next))break
                    if(elapsed()-(lastIdentity?:0)>=30000&&(!identity("PERIODIC_VIN")||!protocol()))break
                    val actual=elapsed()
                    var done=true
                    for(pid in plan.pids){
                        if(query(byteArrayOf(1,pid.toByte()),"SAMPLE",cycle,pid)==null||stop!=null){
                            done=false;break
                        }
                    }
                    if(!done)break
                    cycles++
                    next=maxOf(actual+plan.intervalMs,elapsed())
                }
            }
            if(stop==null&&cycles==plan.cycles&&identity("FINAL_VIN"))protocol()
            if(stop==null&&rows.none { it.status=="PASSED" })stop="NO_VERIFIED_MEASUREMENT"
        }catch(_:CancellationException){stop="CANCELLED"}
        catch(_:Exception){stop="TRANSPORT_OR_CONTEXT_ERROR"}
        return J1979LiveReport(plan,vin,observedProtocol,protocolRaw,advertised.toList(),pages.toList(),
            completeInventory,cycles,admission.toList(),rows.toList(),plan.pids.map { stats.getValue(it).snapshot() },
            stop==null&&cycles==plan.cycles,stop,elapsed())
    }
}
