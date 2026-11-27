package com.camarodiag.gen5.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.camarodiag.gen5.R
import com.camarodiag.gen5.data.obd.ObdJ1979ExtendedVehicleInfo
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun J1979ExtendedVehicleInfoContent(record: ObdJ1979ExtendedVehicleInfo.Result) {
    val heading = stringResource(when(record.infoType) {
        8 -> R.string.j1979_info_ipt_spark
        11 -> R.string.j1979_info_ipt_diesel
        10 -> R.string.j1979_info_ecu_name
        13 -> R.string.j1979_info_serial
        15 -> R.string.j1979_info_approval
        else -> R.string.j1979_info_other
    })
    Text(heading,fontSize=12.sp)
    record.values.forEach { Text(it,fontSize=11.sp) }
    val general=record.generalDenominator
    val ignition=record.ignitionCycles
    if(general!=null&&ignition!=null){
        Text(stringResource(R.string.j1979_info_counter_totals,general,ignition),fontSize=11.sp)
        val format=NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits=4 }
        record.monitors.forEach { monitor ->
            val label=stringResource(when(monitor.monitor){
                "catalyst-bank-1" -> R.string.j1979_info_cat1
                "catalyst-bank-2" -> R.string.j1979_info_cat2
                "oxygen-sensor-bank-1" -> R.string.j1979_info_o21
                "oxygen-sensor-bank-2" -> R.string.j1979_info_o22
                "secondary-oxygen-bank-1" -> R.string.j1979_info_so21
                "secondary-oxygen-bank-2" -> R.string.j1979_info_so22
                "egr-vvt" -> R.string.j1979_info_egr
                "secondary-air" -> R.string.j1979_info_air
                "evap" -> R.string.j1979_info_evap
                "nmhc-catalyst" -> R.string.j1979_info_nmhc
                "nox-scr" -> R.string.j1979_info_scr
                "nox-adsorber" -> R.string.j1979_info_adsorber
                "particulate-filter" -> R.string.j1979_info_pm
                "exhaust-gas-sensor" -> R.string.j1979_info_egs
                "boost-pressure" -> R.string.j1979_info_boost
                "fuel-system" -> R.string.j1979_info_fuel
                else -> R.string.j1979_info_other
            })
            val ratio=monitor.ratio?.let { format.format(it) } ?: stringResource(R.string.j1979_info_ratio_unavailable)
            Text(stringResource(R.string.j1979_info_counter_pair,label,monitor.numerator,monitor.denominator,ratio),fontSize=11.sp)
        }
    }
}
