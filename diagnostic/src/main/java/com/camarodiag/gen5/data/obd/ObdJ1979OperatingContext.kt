package com.camarodiag.gen5.data.obd

import java.util.Locale

/**
 * SAE J1979DA 2011 Figure B3 and tables B7-B10, B46, B66-B69.
 * Fuel trim layout is a vehicle property, not an ECU identifier.
 * A selected responder may positively establish upper-bank support,
 * but its silence cannot establish that other banks do not exist.
 */
object ObdJ1979OperatingContext {
    data class Observation(val requestHex:String,val responseHex:String?,val status:String)
    data class TrimSignal(val bank:Int,val name:String,val rawEncoded:Int,val number:Double?,
        val unit:String,val attribution:String)
    data class Trim(val requestHex:String,val frame:Int?,val layout1d:Int?,
        val signals:List<TrimSignal>,val upperBankVerified:Boolean?)
    data class Monitor(val name:String,val supported:Boolean?,val enabled:Boolean?,val complete:Boolean?,
        val enabledBit:Boolean,val incompleteBit:Boolean,val consistent:Boolean)
    data class Cycle(val requestHex:String,val rawHex:String,val baselineRawHex:String?,
        val supportVerified:Boolean,val compressionIgnition:Boolean,val monitors:List<Monitor>,val consistent:Boolean)
    data class Projection(val driveCycle:Cycle?,val fuelTrims:List<Trim>)

    private val continuous=listOf("misfire","fuel-system","components")
    private val spark=listOf("catalyst","heated-catalyst","evaporative-system","secondary-air",
        null,"oxygen-sensor","oxygen-sensor-heater","egr-vvt")
    private val diesel=listOf("nmhc-catalyst","nox-aftertreatment",null,"boost-pressure",
        null,"exhaust-gas-sensor","particulate-filter","egr-vvt")

    fun isTrim(pid:Int)=pid in 6..9||pid in 0x55..0x58
    fun validTrim(pid:Int,width:Int)=isTrim(pid)&&width in 1..2
    private fun bytes(value:String?):List<Int>? {
        if(value==null||value.length>8190||value.length%2!=0||
            !value.matches(Regex("[0-9a-fA-F]+")))return null
        return value.chunked(2).map { it.toInt(16) }
    }
    private fun hex(data:List<Int>)=data.joinToString("") { "%02X".format(Locale.ROOT,it) }

    private fun correlated(observations:List<Observation>):List<Pair<List<Int>,List<Int>>> =
        observations.mapNotNull { row ->
            if(row.status !in setOf("RAW","PASSED"))return@mapNotNull null
            val req=bytes(row.requestHex)?:return@mapNotNull null
            val res=bytes(row.responseHex)?:return@mapNotNull null
            if(res.size<=req.size)return@mapNotNull null
            when {
                req.size==2&&req[0]==1&&res.take(2)==listOf(0x41,req[1])->req to res.drop(2)
                req.size==3&&req[0]==2&&req[2]==0&&res.take(3)==listOf(0x42,req[1],0)->req to res.drop(3)
                else->null
            }
        }

    fun decodeTrim(request:List<Int>,data:List<Int>,layout:Int?):Trim? {
        if(request.size !in 2..3||request[0] !in 1..2||
            request.size!=(if(request[0]==2)3 else 2)||request[0]==2&&request[2]!=0||
            !validTrim(request[1],data.size)||data.any { it !in 0..255 }||
            layout!=null&&layout !in 0..255)return null
        val pid=request[1]
        val secondary=pid>=0x55
        val shortTerm=pid in setOf(6,8,0x55,0x57)
        val bank=if(pid in setOf(6,7,0x55,0x56))1 else 2
        val prefix=(if(shortTerm)"short" else "long")+"-term-"+
            (if(secondary)"secondary-o2-" else "")+"fuel-trim-bank-"
        val upper=layout!=null&&(layout and (if(bank==1)0x30 else 0xC0))!=0
        val signals=data.mapIndexed { index,raw ->
            val current=bank+index*2
            val verified=index==0||upper
            TrimSignal(current,prefix+current,raw,if(verified)(raw-128)*100.0/128 else null,
                "%",if(verified)"DEFINED" else "UNVERIFIED_UPPER_BANK")
        }
        return Trim(hex(request),request.getOrNull(2),layout,signals,if(data.size==2)upper else null)
    }

    fun decodeCycle(data:List<Int>,baseline:List<Int>?):Cycle? {
        if(data.size!=4||data.any { it !in 0..255 }||data[0]!=0||data[1] and 0x80!=0)return null
        val b=data[1]
        val c=data[2]
        val d=data[3]
        val compression=b and 8!=0
        val reserved=if(compression)0x14 else 0x10
        if((c or d) and reserved!=0)return null
        val valid=baseline!=null&&baseline.size==4&&baseline.all { it in 0..255 }&&
            (baseline[1] and 8!=0)==compression&&baseline[1] and 0x80==0&&
            (baseline[2] or baseline[3]) and reserved==0
        val monitors=mutableListOf<Monitor>()
        fun add(names:List<String?>,support:Int?,enabled:Int,incomplete:Int){
            names.forEachIndexed { bit,name ->
                if(name==null)return@forEachIndexed
                val mask=1 shl bit
                val supported=support?.let { it and mask!=0 }
                val en=enabled and mask!=0
                val inc=incomplete and mask!=0
                monitors.add(Monitor(name,supported,if(supported==true)en else null,
                    if(supported==true)!inc else null,en,inc,supported!=false||!(en||inc)))
            }
        }
        add(continuous,if(valid)baseline!![1] else null,b,b shr 4)
        add(if(compression)diesel else spark,if(valid)baseline!![2] else null,c,d)
        return Cycle("0141",hex(data),baseline?.let(::hex),valid,compression,monitors.toList(),
            monitors.all { it.consistent })
    }

    data class Layout(val state:String,val rawEncoded:Int?)

    fun resolveLayout(observations:List<Observation>):Layout {
        val rows=correlated(observations)
        val pages=rows.filter { it.first==listOf(1,0) }
        val values=rows.filter { it.first==listOf(1,0x1D) }
        val page=pages.singleOrNull()?.second
        if(page==null||page.size!=4)return Layout("UNVERIFIED",null)
        if(page[3] and 8==0)return Layout(
            if(values.isEmpty())"NOT_ADVERTISED_BY_SELECTED_ECU" else "UNVERIFIED",null)
        val value=values.singleOrNull()?.second?.singleOrNull()?:return Layout("UNVERIFIED",null)
        return Layout("VERIFIED",value)
    }

    fun liveSignals(requestHex:String,responseHex:String?,layout:Layout):List<ObdJ1979MultiSignal.Signal>? {
        val req=bytes(requestHex)?:return null
        val res=bytes(responseHex)?:return null
        if(req.size!=2||req[0]!=1||res.size<=2||res.take(2)!=listOf(0x41,req[1]))return null
        return decodeTrim(req,res.drop(2),layout.rawEncoded)?.signals?.mapNotNull { s ->
            s.number?.let { ObdJ1979MultiSignal.Signal(req[1],s.name,s.unit,it,s.rawEncoded.toLong()) }
        }
    }

    fun project(observations:List<Observation>):Projection {
        val rows=correlated(observations)
        val layout=rows.filter { it.first==listOf(1,0x1D) }
            .singleOrNull()?.second?.singleOrNull()
        val baseline=rows.filter { it.first==listOf(1,1) }.singleOrNull()?.second
        val cycle=rows.filter { it.first==listOf(1,0x41) }.singleOrNull()?.second?.let { decodeCycle(it,baseline) }
        val trims=rows.mapNotNull { (req,data)->decodeTrim(req,data,layout) }
        return Projection(cycle,trims)
    }
}
