package com.camarodiag.gen5.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.camarodiag.gen5.R
import com.camarodiag.gen5.data.obd.LegacyReadReport
import java.util.Locale

@Composable
internal fun LegacyReadReportContent(report: LegacyReadReport) {
    fun hex(n: Int) = "%02X".format(Locale.ROOT,n)
    Text(stringResource(R.string.legacy_read_result,report.target.sourceHex))
    Text(stringResource(if(report.completed)R.string.legacy_read_completed else R.string.legacy_read_incomplete))
    Text(stringResource(when(report.vinMatch) {
        "MATCH" -> R.string.legacy_read_vin_match
        "MISMATCH" -> R.string.legacy_read_vin_mismatch
        "UNVERIFIED" -> R.string.legacy_read_vin_unverified
        else -> R.string.legacy_read_vin_not_requested
    }))
    report.observedVin?.let { Text(it,fontFamily=FontFamily.Monospace) }
    report.stopReason?.let { Text(it,fontSize=11.sp) }
    report.inventory.forEach {
        val state=stringResource(when(it.state) {
            "COMPLETE" -> R.string.legacy_read_inventory_complete
            "PARTIAL" -> R.string.legacy_read_inventory_partial
            "NOT_APPLICABLE" -> R.string.legacy_read_inventory_na
            else -> R.string.legacy_read_inventory_unavailable
        })
        Text(stringResource(R.string.legacy_read_inventory,hex(it.mode)+(it.sensorMask?.let { mask -> "/"+hex(mask) }?:""),
            it.advertisedIds.size,state),fontSize=12.sp)
    }
    report.dtcModes.forEach {
        Text(stringResource(R.string.legacy_read_dtc,hex(it.mode),when {
            it.codes==null -> stringResource(R.string.status_not_verified)
            it.codes.isEmpty() -> "0"
            else -> it.codes.joinToString(", ")
        }))
    }
    report.readiness?.let {
        Text(stringResource(R.string.legacy_read_readiness,
            stringResource(if(it.milOn) R.string.legacy_read_mil_on else R.string.legacy_read_mil_off),it.confirmedDtcCount))
        it.supportedMonitors.forEach { (name,ready) ->
            Text(name+": "+stringResource(if(ready)R.string.legacy_read_monitor_ready else R.string.legacy_read_monitor_incomplete),fontSize=11.sp)
        }
    }
    report.measurements.forEach {
        Text("${hex(it.mode)}/${hex(it.pid)} · ${it.name}: ${it.number} ${it.unit}",fontSize=11.sp)
    }
    report.identification.forEach {
        val extended=it.extended
        if(extended==null) Text("09/${hex(it.infoType)}: ${it.values.joinToString(", ")}",fontSize=11.sp)
        else J1979ExtendedVehicleInfoContent(extended)
    }
    report.freezeTrigger?.let {
        Text(stringResource(if(it.present) R.string.legacy_read_freeze_present else R.string.legacy_read_freeze_absent))
        it.dtc?.let { code -> Text(code) }
    }
    report.monitors.forEach {
        Text("${hex(it.mode)}/${hex(it.testId)}${it.sensorMask?.let { mask -> "/"+hex(mask) }?:""}: ${it.encodedDataHex}",
            fontFamily=FontFamily.Monospace,fontSize=11.sp)
    }
    Text(stringResource(R.string.legacy_read_scope),fontSize=11.sp)
}
