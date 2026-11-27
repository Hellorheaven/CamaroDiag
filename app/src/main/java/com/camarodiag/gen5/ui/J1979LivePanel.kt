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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.camarodiag.gen5.R
import com.camarodiag.gen5.data.obd.J1979LivePlan
import com.camarodiag.gen5.data.obd.J1979LiveTarget
import com.camarodiag.gen5.domain.model.VehicleProfile
import java.util.Locale

@Composable
internal fun J1979LivePanel(protocol: Int?,state: FunctionalTestState,vehicle: VehicleProfile,
    epoch: Long,run: (J1979LivePlan,Long,VehicleProfile)->Unit,exportCsv: ()->Unit) {
    var source by remember(protocol,epoch,vehicle) {
        mutableStateOf(if(protocol in setOf(7,9))"18DAF110" else "7E8")
    }
    var pids by remember { mutableStateOf("05,0C") }
    var cycles by remember { mutableStateOf("30") }
    var interval by remember { mutableStateOf("1000") }
    var budget by remember { mutableStateOf("180") }
    var confirmed by remember(protocol,epoch,vehicle) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val enabled=!state.running&&protocol!=null
    val ids=pids.split(",").map { it.trim().takeIf { raw->raw.matches(Regex("[0-9A-Fa-f]{2}")) }?.toIntOrNull(16) }
    val target=source.toIntOrNull(16)?.let { J1979LiveTarget(protocol?:0,it,vehicle.vin) }
    val plan=target?.takeIf { ids.all { id->id!=null } }?.let {
        J1979LivePlan(it,ids.filterNotNull(),cycles.toIntOrNull()?:0,interval.toLongOrNull()?:0,
            (budget.toLongOrNull()?:0)*1000)
    }
    OutlinedButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()){
        Text(stringResource(R.string.j1979_live_title))
    }
    if(expanded){
        @Composable fun Field(value:String,label:Int,limit:Int,change:(String)->Unit) {
            OutlinedTextField(value,{change(it.take(limit));confirmed=false},label={Text(stringResource(label))},
                enabled=enabled,singleLine=true,modifier=Modifier.fillMaxWidth())
        }
        Field(source,R.string.j1979_live_source,8){source=it.uppercase(Locale.ROOT)}
        target?.requestId?.let { Text("${target.idHex(it)} → ${target.idHex(target.responseId)}",fontFamily=FontFamily.Monospace) }
        Field(pids,R.string.j1979_live_pids,95){pids=it.uppercase(Locale.ROOT)}
        Field(cycles,R.string.j1979_live_cycles,4){cycles=it}
        Field(interval,R.string.j1979_live_interval,5){interval=it}
        Field(budget,R.string.j1979_live_budget,3){budget=it}
        Row(verticalAlignment=Alignment.CenterVertically){
            Checkbox(confirmed,{confirmed=it},enabled=enabled)
            Text(stringResource(R.string.j1979_live_confirm,vehicle.vin))
        }
        OutlinedButton(onClick={plan?.let { run(it,epoch,vehicle) };confirmed=false},
            enabled=enabled&&confirmed&&plan?.valid==true,modifier=Modifier.fillMaxWidth()){
            Text(stringResource(R.string.j1979_live_run))
        }
        if(protocol==null)Text(stringResource(R.string.j1979_live_unavailable),fontSize=11.sp)
    }
    if(state.livePlan!=null){
        state.liveRecent.takeLast(12).forEach {
            val value=it.value?.let { v->"${v.number} ${v.unit}" }?:it.status
            Text("${it.elapsedMs} ms · ${it.requestHex} · $value",fontFamily=FontFamily.Monospace,fontSize=11.sp)
        }
        state.liveReport?.let { r ->
            Text(stringResource(if(r.completed)R.string.j1979_live_completed else R.string.j1979_live_incomplete))
            Text(stringResource(R.string.j1979_live_cycle_count,r.completedCycles,r.plan.cycles))
            r.stopReason?.let { Text(it,fontSize=11.sp) }
            r.summaries.forEach {
                fun display(x:Double?)=x?.let { n->"%.3f".format(Locale.getDefault(),n) }?:"—"
                Text("${"%02X".format(Locale.ROOT,it.pid)} · ${it.name.orEmpty()} · ${it.statusCounts["PASSED"]?:0}/${it.attempted}")
                Text(stringResource(R.string.j1979_live_summary,display(it.minimum),display(it.maximum),display(it.mean),it.unit.orEmpty()),
                    fontSize=11.sp)
            }
            OutlinedButton(onClick=exportCsv,enabled=state.canExport,modifier=Modifier.fillMaxWidth()){
                Text(stringResource(R.string.j1979_live_export_csv))
            }
        }
        Text(stringResource(R.string.j1979_live_scope),fontSize=11.sp)
    }
}
