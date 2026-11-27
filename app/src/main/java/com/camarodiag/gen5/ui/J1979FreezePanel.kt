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
import com.camarodiag.gen5.data.obd.J1979FreezePlan
import com.camarodiag.gen5.data.obd.J1979LiveTarget
import com.camarodiag.gen5.domain.model.VehicleProfile
import java.util.Locale

internal fun parseFreezeIds(value: String, maximum: Int, emptyAllowed: Boolean): List<Int>? {
    if (value.isBlank()) return if (emptyAllowed) emptyList() else null
    val entries = value.split(",").map { it.trim() }
    if (entries.size > maximum || entries.any { !it.matches(Regex("[0-9A-Fa-f]{1,2}")) }) return null
    val ids = entries.map { it.toInt(16) }
    return ids.takeIf { it.distinct().size == it.size }
}

@Composable
internal fun J1979FreezePanel(protocol: Int?,state: FunctionalTestState,vehicle: VehicleProfile,
    epoch: Long,run: (J1979FreezePlan,Long,VehicleProfile) -> Unit) {
    var source by remember(protocol,epoch,vehicle) {
        mutableStateOf(if (protocol in setOf(7,9)) "18DAF110" else "7E8")
    }
    var frames by remember(epoch,vehicle) { mutableStateOf("00") }
    var pids by remember(epoch,vehicle) { mutableStateOf("") }
    var budget by remember(epoch,vehicle) { mutableStateOf("180") }
    var confirmed by remember(protocol,epoch,vehicle) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val enabled = !state.running && protocol in 6..9
    val selectedFrames = parseFreezeIds(frames,8,false)
    val selectedPids = parseFreezeIds(pids,64,true)
    val target = source.trim().takeIf { it.matches(Regex("[0-9A-Fa-f]{3,8}")) }
        ?.toIntOrNull(16)?.let { J1979LiveTarget(protocol ?: 0,it,vehicle.vin) }
    val plan = if (target != null && selectedFrames != null && selectedPids != null)
        J1979FreezePlan(target,selectedFrames,selectedPids,(budget.toLongOrNull() ?: 0) * 1000) else null
    OutlinedButton(onClick={ expanded = !expanded },modifier=Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.j1979_freeze_title))
    }
    if (expanded) {
        @Composable fun field(value:String,label:Int,limit:Int,change:(String)->Unit) {
            OutlinedTextField(value,{ change(it.take(limit)); confirmed=false },
                label={ Text(stringResource(label)) },enabled=enabled,singleLine=true,
                modifier=Modifier.fillMaxWidth())
        }
        field(source,R.string.j1979_live_source,8) { source=it.uppercase(Locale.ROOT) }
        target?.requestId?.let {
            Text("${target.idHex(it)} → ${target.idHex(target.responseId)}",fontFamily=FontFamily.Monospace)
        }
        field(frames,R.string.j1979_freeze_frames,23) { frames=it.uppercase(Locale.ROOT) }
        field(pids,R.string.j1979_freeze_pids,191) { pids=it.uppercase(Locale.ROOT) }
        field(budget,R.string.j1979_live_budget,3) { budget=it }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(confirmed,{confirmed=it},enabled=enabled)
            Text(stringResource(R.string.j1979_freeze_confirm,vehicle.vin))
        }
        OutlinedButton(onClick={ plan?.let { run(it,epoch,vehicle) }; confirmed=false },
            enabled=enabled && confirmed && plan?.valid==true,modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.j1979_freeze_run))
        }
        if (!enabled && !state.running) Text(stringResource(R.string.j1979_live_unavailable),fontSize=11.sp)
    }
    state.freezeReport?.let { report ->
        val result=report.result
        Text(stringResource(if (report.executionCompleted && result.allFramesStable)
            R.string.j1979_freeze_complete else R.string.j1979_freeze_incomplete))
        Text("VIN: ${result.initialVin ?: "—"}")
        Text(stringResource(R.string.j1979_freeze_identity,
            stringResource(if (result.identityRechecked) R.string.j1979_freeze_verified
                else R.string.j1979_freeze_unavailable)),fontSize=11.sp)
        report.transportProblem?.let { Text(it,fontSize=11.sp) }
        result.stopReason?.let { Text(it,fontSize=11.sp) }
        fun ids(values: List<Int>) = values.joinToString(", ") { "%02X".format(Locale.ROOT,it) }
        for (frame in result.frames) {
            val status = stringResource(when(frame.status) {
                "COMPLETE" -> R.string.j1979_freeze_status_complete
                "ABSENT" -> R.string.j1979_freeze_status_absent
                "PARTIAL" -> R.string.j1979_freeze_status_partial
                "CHANGED" -> R.string.j1979_freeze_status_changed
                else -> R.string.j1979_freeze_unavailable
            })
            Text(stringResource(R.string.j1979_freeze_summary,"%02X".format(Locale.ROOT,frame.frame),
                status,frame.triggerDtc ?: "—",frame.readPids.size,frame.advertisedPids.size))
            if (frame.unadvertisedSelectedPids.isNotEmpty())
                Text(stringResource(R.string.j1979_freeze_not_advertised,ids(frame.unadvertisedSelectedPids)),fontSize=11.sp)
            if (frame.notReadSelectedPids.isNotEmpty())
                Text(stringResource(R.string.j1979_freeze_not_read,ids(frame.notReadSelectedPids)),fontSize=11.sp)
            frame.detail?.let { Text(it,fontSize=11.sp) }
            // Keep composition bounded; the export retains every acquired row.
            frame.measurementSteps.take(24).forEach { index ->
                val row = result.steps.getOrNull(index) ?: return@forEach
                val value = row.evidence?.measurement
                Text(if (value != null) "${row.requestHex} · ${"%.3f".format(Locale.getDefault(),value.number)} ${value.unit}"
                    else "${row.requestHex} · ${row.responseHex?.take(128) ?: "—"} · ${row.status}",
                    fontFamily=FontFamily.Monospace,fontSize=11.sp)
            }
            if(frame.measurementSteps.size>24)Text(stringResource(R.string.j1979_freeze_more,
                frame.measurementSteps.size-24),fontSize=11.sp)
        }
        if(result.unvisitedFrames.isNotEmpty())Text(stringResource(R.string.j1979_freeze_unvisited,
            ids(result.unvisitedFrames)),fontSize=11.sp)
        Text(stringResource(R.string.j1979_freeze_scope),fontSize=11.sp)
    }
}
