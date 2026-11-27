package com.camarodiag.gen5.data.obd

/**
 * ECU-scoped J1979 equipment configuration (PID 4F and 50).
 * ISO 15031-5:2006 B.60/B.61. No process-wide or vehicle-wide scale cache:
 * the caller supplies observations from one correlated ECU and one session.
 * Unverified configuration does not yield a plausible default reading.
 */
object ObdJ1979ScalingContext {
    data class Observation(val requestHex: String, val responseHex: String?, val status: String)
    data class Configuration(val pid: Int, val state: String, val dataHex: String?)
    data class Signal(val name: String, val unit: String, val rawEncoded: Int,
        val number: Double?, val scaleSource: String)
    data class Measurement(val requestHex: String, val configurationPid: Int, val signals: List<Signal>)
    data class Projection(val configuration: List<Configuration>, val records: List<Measurement>)

    fun dependency(pid: Int): Int? = when(pid) {
        0x10 -> 0x50
        0x0B,0x44,in 0x24..0x2B,in 0x34..0x3B -> 0x4F
        else -> null
    }
    fun isConfiguration(pid: Int) = pid == 0x4F || pid == 0x50
    fun validConfiguration(pid: Int, data: List<Int>) =
        isConfiguration(pid) && data.size == 4 && data.all { it in 0..255 } &&
            (pid != 0x50 || data.drop(1).all { it == 0 })

    private fun bytes(hex: String?): List<Int>? {
        if(hex==null || hex.length>8190 || hex.length%2!=0 ||
            hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' })return null
        return hex.chunked(2).map { it.toInt(16) }
    }
    private fun hex(data: List<Int>): String = data.joinToString("") {
        it.toString(16).uppercase(java.util.Locale.ROOT).padStart(2,'0')
    }

    class Context internal constructor(private val first: Configuration, private val second: Configuration) {
        val configurations: List<Configuration> get() = listOf(first,second)
        fun configuration(pid: Int) = if(pid==0x4F)first else second

        fun decode(request: List<Int>, response: List<Int>): Measurement? {
            if(request.size !in 2..3 || request[0] !in 1..2 || request.any { it !in 0..255 } ||
                response.any { it !in 0..255 })return null
            val mode=request[0]
            val pid=request[1]
            if(request.size!=(if(mode==2)3 else 2) || mode==2&&request[2]!=0)return null
            val dep=dependency(pid)?:return null
            if(response.size<=request.size || response.take(request.size)!=listOf(mode+0x40)+request.drop(1))return null
            val data=response.drop(request.size)
            val width=when(pid){0x0B->1;0x10,0x44->2;else->4}
            if(data.size!=width)return null
            val cfg=configuration(dep)
            val maxima=bytes(cfg.dataHex)?:List(4){0}
            fun field(name:String,unit:String,raw:Int,index:Int,default:Double,
                offset:Int=0,custom:(Int)->Double):Signal {
                val max=maxima[index]
                val scale=if(max==0)default else custom(max)
                return Signal(name,unit,raw,if(cfg.state=="UNVERIFIED")null else (raw-offset)*scale,
                    if(cfg.state=="UNVERIFIED")"UNVERIFIED" else if(max==0)"DEFAULT" else "ECU_CONFIGURATION")
            }
            val a=if(width==1)data[0] else (data[0] shl 8) or data[1]
            val signals=when(pid){
                0x0B->listOf(field("intake-manifold-absolute-pressure","kPa",a,3,1.0){it*10.0/255})
                0x10->listOf(field("mass-air-flow","g/s",a,0,.01){it*10.0/65535})
                0x44->listOf(field("commanded-equivalence-ratio","ratio",a,0,1.0/32768){it/65535.0})
                else->{
                    val voltage=pid<0x34
                    val sensor=pid-(if(voltage)0x24 else 0x34)+1
                    val prefix="wideband-o2-sensor-$sensor-"
                    val b=(data[2] shl 8) or data[3]
                    listOf(field(prefix+"equivalence-ratio","ratio",a,0,1.0/32768){it/65535.0},
                        if(voltage)field(prefix+"voltage","V",b,1,1.0/8192){it/65535.0}
                        else field(prefix+"current","mA",b,2,1.0/256,32768){it/32768.0})
                }
            }
            return Measurement(hex(request),dep,signals)
        }

        fun decode(requestHex: String,responseHex: String): Measurement? {
            return decode(bytes(requestHex)?:return null,bytes(responseHex)?:return null)
        }
    }

    private fun resolve(pid:Int,supported:Set<Int>,knownAbsent:Boolean,candidates:List<List<Int>?>):Configuration {
        if(pid !in supported)return Configuration(pid,
            if(candidates.isEmpty()&&knownAbsent)"DEFAULT_UNSUPPORTED" else "UNVERIFIED",null)
        val response=candidates.singleOrNull()
        if(response==null||response.size!=6||response.take(2)!=listOf(0x41,pid)||
            !validConfiguration(pid,response.drop(2)))return Configuration(pid,"UNVERIFIED",null)
        return Configuration(pid,"ECU_CONFIGURATION",hex(response.drop(2)))
    }

    /** When a caller has already discovered one ECU's complete support set. */
    fun fromSupport(supported:Set<Int>,complete:Boolean,configs:Map<Int,List<List<Int>?>>):Context =
        Context(resolve(0x4F,supported,complete,configs[0x4F].orEmpty()),
            resolve(0x50,supported,complete,configs[0x50].orEmpty()))

    fun build(observations:List<Observation>):Context {
        val pages=linkedMapOf<Int,MutableList<List<Int>?>>()
        val configs=linkedMapOf<Int,MutableList<List<Int>?>>()
        for(row in observations){
            val request=bytes(row.requestHex)?:continue
            if(request.size!=2||request[0]!=1)continue
            val response=if(row.status in setOf("RAW","PASSED"))bytes(row.responseHex) else null
            val pid=request[1]
            val target=when {
                pid%32==0->pages
                isConfiguration(pid)->configs
                else->continue
            }
            target.getOrPut(pid){mutableListOf()}.add(response)
        }
        val supported=mutableSetOf<Int>()
        val seen=mutableSetOf<Int>()
        var ended=false
        for(base in 0..0xE0 step 32){
            val response=pages[base]?.singleOrNull()?:break
            if(response.size!=6||response.take(2)!=listOf(0x41,base)||base==0xE0&&response[5] and 1!=0)break
            seen+=base
            for(bit in 0 until 31)
                if(response[2+bit/8] and (0x80 shr (bit%8))!=0)supported+=base+bit+1
            if(response[5] and 1==0){ended=true;break}
        }
        fun configuration(pid:Int)=resolve(pid,supported,ended||(pid-1)/32*32 in seen,configs[pid].orEmpty())
        return Context(configuration(0x4F),configuration(0x50))
    }

    fun project(observations:List<Observation>):Projection {
        val context=build(observations)
        val records=observations.mapNotNull { row ->
            if(row.status !in setOf("RAW","PASSED"))null
            else row.responseHex?.let { context.decode(row.requestHex,it) }
        }
        return Projection(context.configurations,records)
    }
}
