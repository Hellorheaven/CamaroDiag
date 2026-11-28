package com.camarodiag.connectors

import java.util.Locale

/**
 * Separate engineering projection of an independently replayed CL/1 archive.
 * CL/1 remains an immutable raw recording. It does not contain dedicated
 * configuration or layout admissions, so no context-dependent scale is assumed.
 */
object J1979LiveScalarProjection {
    data class Sample(val cycle:Int,val pid:Int,val elapsedMs:Long,val sourceStatus:String,
        val responseHex:String?,val interpretation:String,val name:String?,val unit:String?,val number:Double?)
    data class Channel(val pid:Int,val name:String?,val unit:String?,val requested:Int,val attempted:Int,
        val count:Int,val missing:Int,val minimum:Double?,val maximum:Double?,val mean:Double?,
        val lastVerifiedValue:Double?,val lastVerifiedElapsedMs:Long?,val outcomes:Map<String,Int>)
    data class Projection(val vin:String,val tx:Int,val rx:Int,val identityRechecked:Boolean,
        val sourceCompleted:Boolean,val numericProjectionComplete:Boolean,val samples:List<Sample>,
        val channels:List<Channel>) {
        fun report():Map<String,Any?> = linkedMapOf(
            "schema" to "camarodiag-j1979-cl1-scalar-projection-v1",
            "vin" to vin,"tx" to tx,"rx" to rx,"identity_rechecked" to identityRechecked,
            "source_completed" to sourceCompleted,"numeric_projection_complete" to numericProjectionComplete,
            "physical_vehicle_verified" to false,"authenticated" to false,"permits_programming" to false,
            "samples" to samples.map { s -> linkedMapOf(
                "cycle" to s.cycle,"pid" to s.pid,"elapsed_ms" to s.elapsedMs,"source_status" to s.sourceStatus,
                "response_hex" to s.responseHex,"interpretation" to s.interpretation,
                "name" to s.name,"unit" to s.unit,"number" to s.number) },
            "channels" to channels.map { c -> linkedMapOf(
                "pid" to c.pid,"name" to c.name,"unit" to c.unit,"requested" to c.requested,
                "attempted" to c.attempted,"count" to c.count,"missing" to c.missing,"minimum" to c.minimum,
                "maximum" to c.maximum,"mean" to c.mean,"last_verified_value" to c.lastVerifiedValue,
                "last_verified_elapsed_ms" to c.lastVerifiedElapsedMs,"outcomes" to c.outcomes) })
    }
    private val contextual = setOf(0x0B,0x10,0x44,0x4F,0x50)+
        (6..9)+(0x24..0x2B)+(0x34..0x3B)+(0x55..0x58)
    private fun hex(pid:Int)=pid.toString(16).uppercase(Locale.ROOT).padStart(2,'0')
    private fun integer(value:Any?,min:Long,max:Long):Long {
        val n=when(value){is Int->value.toLong();is Long->value;else->error("Invalid replay integer")}
        require(n in min..max)
        return n
    }
    private class Accumulator(val pid:Int,val requested:Int) {
        var attempted=0
        var count=0
        var name:String?=null
        var unit:String?=null
        var min:Double?=null
        var max:Double?=null
        var mean:Double?=null
        var last:Double?=null
        var elapsed:Long?=null
        val outcomes=linkedMapOf<String,Int>()
        fun add(s:Sample) {
            attempted++
            outcomes[s.interpretation]=(outcomes[s.interpretation]?:0)+1
            val number=s.number?:return
            require(number.isFinite()&&s.name!=null&&s.unit!=null)
            require(name==null||name==s.name&&unit==s.unit)
            name=s.name;unit=s.unit
            count++
            min=min?.let { minOf(it,number) }?:number
            max=max?.let { maxOf(it,number) }?:number
            mean=mean?.let { it+(number-it)/count }?:number
            last=number;elapsed=s.elapsedMs
        }
        fun result():Channel {
            require(count<=attempted&&attempted<=requested)
            return Channel(pid,name,unit,requested,attempted,count,requested-count,min,max,mean,last,elapsed,outcomes.toMap())
        }
    }
    fun project(replay:J1979LiveNativeReplay.Result,expectedVin:String):Projection {
        require(replay.captureIntegrityVerified&&!replay.cancelled)
        val profile=requireNotNull(replay.profile)
        require(CuWire.validVin(expectedVin)&&profile.vin==expectedVin)
        val summary=requireNotNull(replay.summary)
        val result=requireNotNull(replay.result)
        require(result["declared_vin"]==expectedVin&&result["raw_only"]==true&&result["verified"]==true)
        val identity=summary["initial_identity_verified"]==true&&summary["final_identity_verified"]==true
        val rows=result["samples"] as? List<*>?:error("Missing CL/1 samples")
        require(rows.size<=J1979LiveNativeReplay.MAX_SAMPLES)
        val selected=profile.pids().toList()
        val stats=selected.associateWith { Accumulator(it,profile.cycles) }
        val samples=ArrayList<Sample>(rows.size)
        var previous=-1L
        for((index,item) in rows.withIndex()) {
            val row=item as? Map<*,*>?:error("Invalid CL/1 sample")
            val cycle=integer(row["cycle"],1,profile.cycles.toLong()).toInt()
            val pid=integer(row["pid"],1,255).toInt()
            require(cycle==index/selected.size+1&&pid==selected[index%selected.size])
            val elapsed=integer(row["elapsed_ms"],0,604000)
            require(elapsed>=previous);previous=elapsed
            val status=row["status"] as? String?:error("Missing CL/1 status")
            require(status in setOf("RAW","NEGATIVE","TIMEOUT"))
            val response=row["response_hex"] as? String
            require(response==null||response.length in 6..8190&&response.length%2==0&&
                response.all { it in "0123456789ABCDEF" })
            require((response==null)==(status=="TIMEOUT"))
            if(status=="RAW")require(response?.startsWith("41"+hex(pid))==true)
            if(status=="NEGATIVE")require(response?.length==6&&response.startsWith("7F01")&&
                response.takeLast(2) !in setOf("00","78"))
            val decoded=if(identity&&status=="RAW"&&pid !in contextual)
                J1979CampaignSemantics.decodePositive("01"+hex(pid),response)?.measurement() else null
            val meaning=when {
                status!="RAW"->status
                !identity->"IDENTITY_UNVERIFIED"
                pid in contextual->"CONTEXT_REQUIRED"
                decoded==null->"RAW_UNINTERPRETED"
                else->"DOCUMENTED_SCALAR"
            }
            val sample=Sample(cycle,pid,elapsed,status,response,meaning,decoded?.name,decoded?.unit,decoded?.number)
            stats.getValue(pid).add(sample);samples.add(sample)
        }
        val channels=selected.map { stats.getValue(it).result() }
        val complete=replay.completed()
        return Projection(expectedVin,profile.tx,profile.rx,identity,complete,
            complete&&identity&&channels.all { it.count==it.requested },samples.toList(),channels)
    }
    /** UTF-8, stable numeric formatting and explicit missing values. */
    fun csv(p:Projection,captureSha256:String):ByteArray {
        require(captureSha256.matches(Regex("[0-9a-f]{64}")))
        val out=StringBuilder("cycle,pid_hex,elapsed_ms,source_status,interpretation,name,number,unit,response_hex,vin,tx_can_id,rx_can_id,capture_sha256\r\n")
        fun quote(s:String?)=s?.let { "\""+it.replace("\"","\"\"")+"\"" }.orEmpty()
        for(s in p.samples) {
            out.append(s.cycle).append(',').append(hex(s.pid)).append(',').append(s.elapsedMs).append(',')
                .append(s.sourceStatus).append(',').append(s.interpretation).append(',').append(quote(s.name)).append(',')
                .append(s.number?.toString().orEmpty()).append(',').append(quote(s.unit)).append(',')
                .append(s.responseHex.orEmpty()).append(',').append(p.vin).append(',')
                .append(p.tx.toString(16).uppercase(Locale.ROOT)).append(',')
                .append(p.rx.toString(16).uppercase(Locale.ROOT)).append(',').append(captureSha256).append("\r\n")
            require(out.length<=40*1024*1024)
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }
}
