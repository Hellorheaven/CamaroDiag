package com.camarodiag.gen5.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.camarodiag.gen5.R

/** Common presentation for the standard scan and independent native recipe. */
@Composable
internal fun NativeUsbReadinessContent(ready:NativeUsbFunctionalEvidence.Readiness) {
    Text(stringResource(if(ready.milOn) R.string.functional_native_usb_mil_on else R.string.functional_native_usb_mil_off))
    Text(stringResource(R.string.functional_native_usb_dtc_count,ready.dtcCount))
    Text(stringResource(if(ready.compression) R.string.functional_native_usb_compression else R.string.functional_native_usb_spark))
    if(ready.monitors.isEmpty()) Text(stringResource(R.string.functional_native_usb_no_monitors))
    for((name,complete) in ready.monitors) {
        val label=when(name) {
            "misfire" -> R.string.functional_native_usb_monitor_misfire
            "fuel-system" -> R.string.functional_native_usb_monitor_fuel_system
            "components" -> R.string.functional_native_usb_monitor_components
            "catalyst" -> R.string.functional_native_usb_monitor_catalyst
            "heated-catalyst" -> R.string.functional_native_usb_monitor_heated_catalyst
            "evaporative-system" -> R.string.functional_native_usb_monitor_evaporative_system
            "secondary-air" -> R.string.functional_native_usb_monitor_secondary_air
            "oxygen-sensor" -> R.string.functional_native_usb_monitor_oxygen_sensor
            "oxygen-sensor-heater" -> R.string.functional_native_usb_monitor_oxygen_sensor_heater
            "egr-vvt" -> R.string.functional_native_usb_monitor_egr_vvt
            "nmhc-catalyst" -> R.string.functional_native_usb_monitor_nmhc_catalyst
            "nox-aftertreatment" -> R.string.functional_native_usb_monitor_nox_aftertreatment
            "boost-pressure" -> R.string.functional_native_usb_monitor_boost_pressure
            "exhaust-gas-sensor" -> R.string.functional_native_usb_monitor_exhaust_gas_sensor
            "particulate-filter" -> R.string.functional_native_usb_monitor_particulate_filter
            else -> continue
        }
        Text(stringResource(label)+": "+stringResource(if(complete) R.string.functional_native_usb_ready else R.string.functional_native_usb_incomplete))
    }
}

@Composable
internal fun NativeUsbMonitorContent(monitor:NativeUsbFunctionalEvidence.Monitor) {
    Text(stringResource(R.string.functional_native_usb_monitor_id,
        "%02X".format(java.util.Locale.ROOT,monitor.mid),
        "%02X".format(java.util.Locale.ROOT,monitor.tid),
        "%02X".format(java.util.Locale.ROOT,monitor.scaling)))
    Text(stringResource(R.string.functional_native_usb_monitor_words,
        "%04X".format(java.util.Locale.ROOT,monitor.value),
        "%04X".format(java.util.Locale.ROOT,monitor.minimum),
        "%04X".format(java.util.Locale.ROOT,monitor.maximum)))
}
