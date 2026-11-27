package com.camarodiag.gen5.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.camarodiag.gen5.R
import com.camarodiag.gen5.data.obd.J1979NetworkPlan
import com.camarodiag.gen5.domain.model.VehicleProfile
import java.util.Locale

@Composable
internal fun J1979NetworkPanel(protocol:Int?,state:FunctionalTestState,vehicle:VehicleProfile,
    epoch:Long,run:(J1979NetworkPlan,Long,VehicleProfile)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    var chosen by remember(protocol,vehicle,epoch) { mutableStateOf(setOf<Int>()) }
    var budget by remember { mutableStateOf("180") }
    var confirmed by remember(protocol,vehicle,epoch) { mutableStateOf(false) }
    val previous=state.networkReport?.takeIf { it.plan.protocol==protocol&&it.plan.vin==vehicle.vin }
    val eligible=previous?.discovery?.eligibleResponseIds.orEmpty()
    val discovery=J1979NetworkPlan(protocol?:0,vehicle.vin,budgetMs=(budget.toLongOrNull()?:0)*1000)
    val scan=discovery.copy(selectedResponseIds=chosen.sorted())
    val enabled=!state.running&&discovery.valid
    fun id(value:Int)="%X".format(Locale.ROOT,value)
    OutlinedButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()){
        Text(stringResource(R.string.j1979_network_title))
    }
    if(expanded){
        Text(stringResource(R.string.j1979_network_scope),fontSize=11.sp)
        OutlinedTextField(budget,{budget=it.take(3);confirmed=false},singleLine=true,
            label={Text(stringResource(R.string.j1979_live_budget))},enabled=!state.running,modifier=Modifier.fillMaxWidth())
        Row(verticalAlignment=Alignment.CenterVertically){
            Checkbox(confirmed,{confirmed=it},enabled=enabled)
            Text(stringResource(R.string.j1979_network_confirm,vehicle.vin))
        }
        OutlinedButton(onClick={chosen=emptySet();run(discovery,epoch,vehicle);confirmed=false},
            enabled=enabled&&confirmed,modifier=Modifier.fillMaxWidth()){
            Text(stringResource(R.string.j1979_network_discover))
        }
        previous?.discovery?.responders?.forEach { responder->
            Row(verticalAlignment=Alignment.CenterVertically){
                Checkbox(responder.responseId in chosen,{checked->
                    chosen=if(checked)(chosen+responder.responseId).take(32).toSet()else chosen-responder.responseId
                    confirmed=false
                },enabled=enabled&&responder.responseId in eligible)
                Text("${id(responder.requestId)} → ${id(responder.responseId)} · ${responder.status}"+
                    (responder.detail?.let { " · $it" }?:""))
            }
            responder.supportedPids?.let { Text(it.joinToString(" ") { p->"%02X".format(Locale.ROOT,p) },fontSize=11.sp) }
        }
        OutlinedButton(onClick={run(scan,epoch,vehicle);confirmed=false},
            enabled=enabled&&confirmed&&scan.valid&&chosen.isNotEmpty()&&chosen.all { it in eligible },
            modifier=Modifier.fillMaxWidth()){
            Text(stringResource(R.string.j1979_network_scan))
        }
        previous?.let { r->
            Text(stringResource(if(r.completed)R.string.j1979_network_completed else R.string.j1979_network_incomplete))
            r.stopReason?.let { Text(it,fontSize=11.sp) }
            r.ecus.forEach { ecu->
                Text("${id(ecu.responseId)} · ${ecu.status} · ${ecu.observedVin.orEmpty()}")
                Text(stringResource(R.string.j1979_network_steps,ecu.steps.size),fontSize=11.sp)
                val contextual=ecu.contextualMeasurements.records.associateBy { it.requestHex }
                ecu.steps.forEach { step->
                    val scaled=contextual[step.requestHex]
                    scaled?.signals?.forEach { signal ->
                        Text("${signal.name}: ${signal.number ?: "—"} ${signal.unit}",fontSize=11.sp)
                    }
                    step.extendedVehicleInfo?.let { J1979ExtendedVehicleInfoContent(it) }
                    if(scaled==null) step.multiSignalRecord?.signals?.forEach { signal ->
                        Text("${signal.name}: ${signal.number} ${signal.unit}",fontSize=11.sp)
                    }
                    val value=if(scaled==null)step.application?.measurement()else null
                    val codes=step.application?.dtcCodes()
                    Text("${step.requestHex} · ${step.status}"+
                        (value?.let { " · ${it.number} ${it.unit}" }?:"")+
                        (codes?.let { " · ${it.joinToString(",")}" }?:""),fontSize=11.sp)
                }
            }
        }
    }
}
