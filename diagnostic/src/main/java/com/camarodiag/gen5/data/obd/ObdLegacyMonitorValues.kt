package com.camarodiag.gen5.data.obd

import java.util.Locale

/** Legacy service 05 uses the historical Annex C standard TID scaling.
 * Service 06 here is NOT ISO-TP Mode 06. An OEM definition is still needed
 * for its units, and a numerical comparison is never a monitor verdict. */
object ObdLegacyMonitorValues {
    data class Scale(val definition:String,val name:String,val unit:String,
        val value:Double,val minimum:Double?,val maximum:Double?,val numericRelation:String)
    data class Record(val mode:Int,val testId:Int,val sensorMask:Int?,val encodedDataHex:String,
        val valueEncoded:Int,val minimumEncoded:Int?=null,val maximumEncoded:Int?=null,
        val componentAndLimitEncoded:Int?=null,val componentId:Int?=null,
        val limitType:String?=null,val limitEncoded:Int?=null,val encodedRelation:String?=null,
        val scaled:Scale?=null)
    data class Observation(val requestHex:String,val status:String,val acceptedRecords:List<String>)
    data class Projected(val requestHex:String,val recordIndex:Int,val record:Record)
    private data class Definition(val name:String,val unit:String,val factor:Double)
    private val definitions=mapOf(
        1 to Definition("rich-lean-threshold","V",.005),
        2 to Definition("lean-rich-threshold","V",.005),
        3 to Definition("switch-low-voltage","V",.005),
        4 to Definition("switch-high-voltage","V",.005),
        5 to Definition("rich-lean-switch-time","s",.004),
        6 to Definition("lean-rich-switch-time","s",.004),
        7 to Definition("cycle-minimum-voltage","V",.005),
        8 to Definition("cycle-maximum-voltage","V",.005),
        9 to Definition("transition-interval","s",.04),
        10 to Definition("sensor-period","s",.04)
    )
    private fun bytes(hex:String):List<Int>? =
        if(hex.length>14 || hex.length%2!=0 || !hex.matches(Regex("[0-9a-fA-F]+"))) null
        else hex.chunked(2).map { it.toInt(16) }
    private fun hex(data:List<Int>)=data.joinToString("") { "%02X".format(Locale.ROOT,it) }

    fun decode(requestHex:String,responseHex:String):Record? {
        val request=bytes(requestHex) ?: return null
        val response=bytes(responseHex) ?: return null
        if(request.size !in 2..3 || request[0] !in 5..6)return null
        val mode=request[0]
        val tid=request[1]
        if(tid%32==0 || request.size!=(if(mode==5)3 else 2))return null
        if(mode==5) {
            val mask=request[2]
            if(mask !in setOf(1,2,4,8,16,32,64,128) ||
                response.size!=(if(tid in 1..4)4 else 6) ||
                response.take(3)!=listOf(0x45,tid,mask))return null
            val value=response[3]
            val low=response.getOrNull(4)
            val high=response.getOrNull(5)
            val scale=definitions[tid]?.let { d->
                val relation=when {
                    low==null||high==null->"NOT_APPLICABLE"
                    low>high->"INVERTED_LIMITS"
                    value<low->"BELOW"
                    value>high->"ABOVE"
                    else->"WITHIN"
                }
                Scale("ISO15031-5:2006-ANNEX-C",d.name,d.unit,value*d.factor,
                    low?.let { it*d.factor },high?.let { it*d.factor },relation)
            }
            return Record(mode,tid,mask,hex(response.drop(3)),value,low,high,scaled=scale)
        }
        if(response.size!=7 || response.take(2)!=listOf(0x46,tid))return null
        val component=response[2]
        val value=(response[3] shl 8) or response[4]
        val limit=(response[5] shl 8) or response[6]
        return Record(mode,tid,null,hex(response.drop(2)),value,
            componentAndLimitEncoded=component,componentId=component and 127,
            limitType=if(component and 128!=0)"MINIMUM" else "MAXIMUM",limitEncoded=limit,
            encodedRelation=if(value<limit)"BELOW" else if(value>limit)"ABOVE" else "EQUAL")
    }

    fun project(steps:List<Observation>):List<Projected> = steps.flatMap { step->
        if(step.status!="PASSED"||step.acceptedRecords.size>128)emptyList()
        else {
            val decoded=step.acceptedRecords.map { decode(step.requestHex,it) }
            if(decoded.any { it==null })emptyList()
            else decoded.filterNotNull().mapIndexed { index,record->
                Projected(step.requestHex.uppercase(Locale.ROOT),index,record)
            }
        }
    }

    fun format(record:Record):String {
        val raw=record.encodedDataHex
        val scaled=record.scaled
        return when {
            scaled!=null->buildString {
                append(raw).append(" · ").append(scaled.name).append(": ")
                append(scaled.value).append(" ").append(scaled.unit)
                if(scaled.minimum!=null&&scaled.maximum!=null)
                    append(" [").append(scaled.minimum).append("–").append(scaled.maximum).append("]")
            }
            record.mode==6->"$raw · CID=%02X %s RAW=%04X LIMIT_RAW=%04X".format(Locale.ROOT,
                record.componentId,record.limitType,record.valueEncoded,record.limitEncoded)
            else->raw
        }
    }
}
