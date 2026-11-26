package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.*
import org.json.JSONObject
import java.util.Locale

/** Projection of a locally produced, authenticated CU/1 capture, never a
 * reconnectable adapter or proof of physical provenance. Exactly one ECU. */
internal object NativeUsbObdScanProjection {
    fun project(report:JSONObject, expectedVin:String, tx:Int, rx:Int, extended:Boolean):ObdScanReport? {
        if(!expectedVin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) ||
            report.optString("source")!="LIVE_USB_CU1" ||
            report.optString("protocol")!="SAE-J1979" ||
            report.optString("vin_match")!="MATCH" ||
            report.optString("declared_vin")!=expectedVin ||
            report.optString("request_can_id")!=tx.toString(16).uppercase(Locale.ROOT) ||
            report.optString("response_can_id")!=rx.toString(16).uppercase(Locale.ROOT) ||
            report.opt("extended")!=extended) return null
        val values=NativeUsbFunctionalEvidence.diagnosticValues(report) ?: return null
        if(values.observedVin!=expectedVin) return null
        val summary=report.optJSONObject("summary") ?: return null
        if(!summary.optBoolean("transport_observed")) return null
        val rows=report.optJSONArray("steps") ?: return null
        val steps=(0 until rows.length()).map { rows.optJSONObject(it) ?: return null }
        if(steps.size!=summary.optInt("steps",-1)) return null
        val first=steps.firstOrNull() ?: return null
        if(first.optString("request_hex")!="0100" || first.optString("status")!="PASSED" ||
            !first.optString("response_hex").matches(Regex("4100[0-9A-F]{8}"))) return null
        val vinRaw="490201"+expectedVin.toByteArray(Charsets.US_ASCII).joinToString("") {
            "%02X".format(Locale.ROOT,it.toInt() and 255)
        }
        if(steps.none { it.optString("request_hex")=="0902" && it.optString("status")=="PASSED" &&
                it.optString("response_hex")==vinRaw }) return null
        val responder=rx.toString(16).uppercase(Locale.ROOT)
        fun positive(row:JSONObject)=row.optString("status")=="PASSED"
        fun raw(row:JSONObject)=row.optString("response_hex").takeUnless { row.isNull("response_hex") }.orEmpty()
        fun bytes(row:JSONObject):List<Int> {
            val data=raw(row)
            return if(data.length in 2..8190 && data.length%2==0 && data.matches(Regex("[0-9A-F]+")))
                data.chunked(2).map { it.toInt(16) } else emptyList()
        }
        fun modeRows(mode:Int)=steps.filter { it.optString("request_hex").startsWith("%02X".format(Locale.ROOT,mode)) }
        fun pid(row:JSONObject)=row.optString("request_hex").drop(2).take(2).toIntOrNull(16)
        fun codes(mode:Int,status:String):List<ObdDtc>? {
            val row=modeRows(mode).singleOrNull() ?: return null
            if(!positive(row)) return null
            val data=bytes(row)
            if(data.size<2||data[0]!=mode+64||data.size!=2+data[1]*2) return null
            val decoded=data.drop(2).chunked(2).map { pair ->
                val a=pair[0];val b=pair[1]
                if(a==0&&b==0) return null
                "${"PCBU"[a shr 6]}${(a shr 4) and 3}%X%02X".format(Locale.ROOT,a and 15,b)
            }
            if(decoded!=values.dtcs[mode]) return null
            return decoded.map { ObdDtc(it,status) }
        }
        val confirmed=codes(3,"confirmed");val pending=codes(7,"pending");val permanent=codes(10,"permanent")
        val verified=buildSet { if(confirmed!=null)add(3);if(pending!=null)add(7);if(permanent!=null)add(10) }
        fun inventory(mode:Int)=values.pages.filter { it.mode==mode }.flatMap { it.ids }.toSet()
        val live=values.measurements.filter { it.mode==1 }.mapNotNull { value ->
            val row=modeRows(1).singleOrNull { positive(it)&&pid(it)==value.pid } ?: return@mapNotNull null
            val data=bytes(row)
            if(data.size<3||data[0]!=65||data[1]!=value.pid) return@mapNotNull null
            ObdValue(value.pid,value.name,value.number.toString(),value.unit,data.drop(2),responder)
        }
        val reads=modeRows(1).mapNotNull { row ->
            val id=pid(row)?.takeIf { it%32!=0 } ?: return@mapNotNull null
            val data=bytes(row)
            val eligible=positive(row)&&data.size>2&&data[0]==65&&data[1]==id
            val observation=if(data.size>2&&data[0]==65&&data[1]==id)
                listOf(ObdMode01Observation(responder,data.drop(2),eligible,live.count { it.pid==id })) else emptyList()
            ObdMode01Read(id,raw(row).ifEmpty { row.optString("status") },observation)
        }
        val freezeReads=modeRows(2).mapNotNull { row ->
            val id=pid(row) ?: return@mapNotNull null
            val data=bytes(row)
            val valid=positive(row)&&data.size>3&&data.take(3)==listOf(66,id,0)
            ObdFreezeFrameRead(id,raw(row).ifEmpty { row.optString("status") },data.drop(3).takeIf { valid })
        }
        val freeze=if(values.freeze?.present==true) freezeReads.filter { it.pid!=2&&it.pid%32!=0 }
            .mapNotNull { it.verifiedBytes?.let { data->it.pid to data } }.toMap() else emptyMap()
        val identityReads=modeRows(9).mapNotNull { row ->
            val id=pid(row) ?: return@mapNotNull null
            val data=bytes(row)
            ObdVehicleInfoRead(id,raw(row).ifEmpty { row.optString("status") },
                if(positive(row)&&data.size>2&&data.take(2)==listOf(73,id)) listOf(data) else emptyList())
        }
        val controls=modeRows(8).mapNotNull { row ->
            val id=pid(row) ?: return@mapNotNull null
            val data=bytes(row)
            ObdMode08Page(id,row.optString("request_hex"),raw(row).ifEmpty { row.optString("status") },
                if(values.pages.any { it.mode==8&&it.base==id }&&positive(row)) listOf(data) else emptyList())
        }
        val support=inventory(1)
        return ObdScanReport(
            supportedPids=support,liveData=live,confirmedDtcs=confirmed.orEmpty(),
            pendingDtcs=pending.orEmpty(),permanentDtcs=permanent.orEmpty(),vin=expectedVin,
            calibrationIds=values.identity[4].orEmpty(),calibrationVerificationNumbers=values.identity[6].orEmpty(),
            ecuNames=values.identity[10].orEmpty(),
            readiness=modeRows(1).firstOrNull { pid(it)==1&&positive(it)&&values.readiness!=null }?.let(::raw),
            freezeFrame=freeze,monitorTestRaw=modeRows(6).filter { pid(it)?.let { id->id%32!=0 }==true }
                .joinToString("\n") { it.optString("request_hex")+" "+it.optString("status")+" "+raw(it) }.ifBlank { null },
            adapterInfo=mapOf("IDENTIFICATION" to "CamaroDiagConnectors CU/1","PROTOCOL" to "SAE J1979 / CAN",
                "NATIVE_USB_SCOPE" to "SINGLE_ECU"),
            respondingEcuAddresses=listOf(responder),rawLog=steps.map {
                it.optString("request_hex")+" ["+it.optString("status")+"] "+raw(it)
            },freezeFrameDtc=values.freeze?.dtc?.let { ObdDtc(it,"freeze") },
            physicalCanResponder=responder,unavailableDtcModes=setOf(3,7,10)-verified,
            supportedPidsByResponder=mapOf(responder to support),mode01Reads=reads,
            freezeFrameSupportedPids=inventory(2),freezeFrameReads=freezeReads,
            freezeFrameSupportComplete=summary.optBoolean("freeze_inventory_complete").takeIf { values.freeze?.present==true },
            freezeFrameResponder=responder.takeIf { values.freeze!=null },
            vehicleInfoSupportedTypes=inventory(9),vehicleInfoSupportComplete=summary.optBoolean("identity_inventory_complete"),
            vehicleInfoReads=identityReads,mode08=ObdMode08Inventory(inventory(8),
                summary.optBoolean("control_inventory_complete"),controls,responder).takeIf { controls.isNotEmpty() },
            pidInventoryComplete=summary.optBoolean("pid_inventory_complete"),verifiedDtcModes=verified,
            nativeUsbEvidence=report.toString())
    }
}
