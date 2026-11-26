package com.camarodiag.gen5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.camarodiag.gen5.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import com.camarodiag.gen5.data.obd.EcuWriteConsentFlow
import com.camarodiag.gen5.data.obd.LegacyMutationTarget
import com.camarodiag.gen5.domain.model.VehicleProfile

@Composable
fun FunctionalTestsScreen(
    state: FunctionalTestState,
    onRunAll: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    onRunProtocol: (FunctionalProtocol, DoIpFunctionalTarget?) -> Unit,
    vehicle: VehicleProfile,
    connectionGeneration: Long,
    evapAvailable: Boolean,
    onRunEvap: (EcuWriteConsentFlow.Approval, Long, VehicleProfile) -> Unit,
    onRunClear: (EcuWriteConsentFlow.Approval, Long, VehicleProfile) -> Unit,
    legacyAvailableProtocol: Int?,
    onRunLegacy: (LegacyMutationTarget, EcuWriteConsentFlow.Approval, Long, VehicleProfile) -> Unit,
    nativeUsbEnabled: Boolean,
    onRunNativeUsb: (android.hardware.usb.UsbDevice, com.camarodiag.gen5.data.obd.CamaroDiagConnectorNativeCampaign.Target,
        android.net.Uri, Long, VehicleProfile) -> Unit
) {
    var selected by remember { mutableStateOf(FunctionalProtocol.J1979) }
    var expanded by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var tester by remember { mutableStateOf("") }
    var ecu by remember { mutableStateOf("") }
    var vin by remember { mutableStateOf("") }
    var tls by remember { mutableStateOf(true) }
    var physical by remember { mutableStateOf(false) }
    var evapConfirmedProfile by remember { mutableStateOf(false) }
    var evapPending by remember { mutableStateOf<Pair<Long, VehicleProfile>?>(null) }
    var evapStep by remember { mutableStateOf(EcuWriteConsentFlow.Step.WARNING) }
    var clearSelected by remember { mutableStateOf(false) }
    var engineSource by remember { mutableStateOf("") }
    var documentedSources by remember { mutableStateOf("") }
    var legacyProfileConfirmed by remember { mutableStateOf(false) }
    var legacyPending by remember { mutableStateOf<Triple<Long, VehicleProfile, LegacyMutationTarget>?>(null) }
    var legacyStep by remember { mutableStateOf(EcuWriteConsentFlow.Step.WARNING) }
    LaunchedEffect(vehicle, connectionGeneration, legacyAvailableProtocol, selected) {
        legacyPending = null
        legacyProfileConfirmed = false
        engineSource = ""
        documentedSources = ""
        legacyStep = EcuWriteConsentFlow.Step.WARNING
    }
    fun parseSource(value: String): Int? =
        value.trim().takeIf { it.matches(Regex("[0-9A-Fa-f]{2}")) }?.toIntOrNull(16)
    val legacyProtocol = selected.expectedLegacyElmProtocol
    val parsedEngine = parseSource(engineSource)
    val parsedSources = documentedSources.split(",").map(::parseSource)
    val legacyTarget = if (legacyProtocol != null && parsedEngine != null && parsedSources.all { it != null })
        LegacyMutationTarget(legacyProtocol,parsedEngine,parsedSources.filterNotNull(),true) else null
    val legacyReady = legacyAvailableProtocol == legacyProtocol && legacyTarget?.valid == true &&
        legacyProfileConfirmed && !state.running && vehicle.vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))
    legacyPending?.let { (epoch, profile, target) ->
        val warning = legacyStep == EcuWriteConsentFlow.Step.WARNING
        AlertDialog(
            onDismissRequest = { legacyPending = null; legacyProfileConfirmed = false },
            title = { Text(stringResource(if (target.clear) R.string.functional_clear_run else R.string.functional_evap_run)) },
            text = { Text(if (warning) stringResource(R.string.functional_legacy_mutation_scope) + "\n\n" +
                stringResource(if (target.clear) R.string.functional_clear_warning else R.string.functional_evap_warning)
                else stringResource(R.string.functional_legacy_mutation_confirm,profile.vin,
                    target.sources.joinToString(",") { "%02X".format(java.util.Locale.ROOT,it) })) },
            confirmButton = { TextButton(onClick = {
                if (epoch != connectionGeneration || profile != vehicle ||
                    legacyAvailableProtocol != target.protocol || selected.expectedLegacyElmProtocol != target.protocol ||
                    legacyTarget?.copy(clear=target.clear) != target || !legacyReady) {
                    legacyPending = null
                    legacyProfileConfirmed = false
                } else if (warning) {
                    legacyStep = EcuWriteConsentFlow.afterWarningOk(legacyStep)
                } else {
                    EcuWriteConsentFlow.approvalForContext(legacyStep,true,epoch,connectionGeneration,profile==vehicle)
                        ?.let { onRunLegacy(target,it,epoch,profile) }
                    legacyPending = null
                    legacyProfileConfirmed = false
                }
            }) { Text(stringResource(if (warning) R.string.functional_evap_ok else R.string.functional_evap_yes)) } },
            dismissButton = { TextButton(onClick = {
                legacyPending = null
                legacyProfileConfirmed = false
            }) { Text(stringResource(if (warning) R.string.functional_evap_cancel else R.string.functional_evap_no)) } })
    }
    LaunchedEffect(vehicle, connectionGeneration, evapAvailable) {
        evapPending = null
        evapConfirmedProfile = false
        evapStep = EcuWriteConsentFlow.Step.WARNING
        clearSelected = false
    }
    evapPending?.let { (epoch, profile) ->
        val warning = evapStep == EcuWriteConsentFlow.Step.WARNING
        AlertDialog(onDismissRequest = { evapPending = null; evapConfirmedProfile = false },
            title = { Text(stringResource(if (clearSelected) R.string.functional_clear_run else R.string.functional_evap_run)) },
            text = { Text(if (warning) stringResource(if (clearSelected) R.string.functional_clear_warning else R.string.functional_evap_warning)
                else stringResource(if (clearSelected) R.string.functional_clear_confirm else R.string.functional_evap_confirm, profile.vin)) },
            confirmButton = {
                TextButton(onClick = {
                    if (epoch != connectionGeneration || profile != vehicle || !evapAvailable) {
                        evapPending = null
                    } else if (warning) {
                        evapStep = EcuWriteConsentFlow.afterWarningOk(evapStep)
                    } else {
                        EcuWriteConsentFlow.approvalForContext(evapStep, true, epoch,
                            connectionGeneration, profile == vehicle)?.let {
                                if (clearSelected) onRunClear(it, epoch, profile) else onRunEvap(it, epoch, profile)
                            }
                        evapPending = null
                        evapConfirmedProfile = false
                    }
                }) { Text(stringResource(if (warning) R.string.functional_evap_ok else R.string.functional_evap_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { evapPending = null; evapConfirmedProfile = false }) {
                    Text(stringResource(if (warning) R.string.functional_evap_cancel else R.string.functional_evap_no))
                }
            })
    }
    val doIp = DoIpFunctionalTarget(host.trim(), tester, ecu, vin, tls, physical)
    val fr = LocalConfiguration.current.locales[0].language == "fr"
    val passed = state.checks.count { it.outcome == FunctionalTestOutcome.PASSED }
    val failed = state.checks.count { it.outcome == FunctionalTestOutcome.FAILED }
    val untested = state.checks.count { it.outcome == FunctionalTestOutcome.NOT_TESTED }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.functional_tests_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.functional_tests_readonly), style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { expanded = true }, enabled = !state.running, modifier = Modifier.fillMaxWidth()) {
                        Text(selected.title)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        FunctionalProtocol.entries.forEach { protocol ->
                            DropdownMenuItem(text = { Text(protocol.title + if (protocol.implemented) "" else
                                " — " + stringResource(R.string.functional_protocol_pending)) }, onClick = {
                                selected = protocol; expanded = false
                            })
                        }
                    }
                }
                if (selected == FunctionalProtocol.J1979) Text(stringResource(R.string.functional_j1979_candidates))
                selected.expectedLegacyElmProtocol?.let { number ->
                    val candidate = when (number) {
                        1 -> R.string.functional_legacy_pwm_candidates
                        2 -> R.string.functional_legacy_vpw_candidates
                        3 -> R.string.functional_legacy_iso9141_candidates
                        else -> R.string.functional_legacy_kwp_candidates
                    }
                    Text(stringResource(candidate), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.functional_legacy_route_required), style = MaterialTheme.typography.bodySmall)
                }
                if (selected == FunctionalProtocol.DOIP) {
                    Text(stringResource(R.string.functional_doip_candidates))
                    OutlinedTextField(host, { host = it.take(80); physical = false },
                        label = { Text(stringResource(R.string.functional_doip_host)) }, enabled = !state.running,
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(tester, { tester = it.uppercase().take(4); physical = false },
                        label = { Text(stringResource(R.string.functional_doip_tester)) }, enabled = !state.running,
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ecu, { ecu = it.uppercase().take(4); physical = false },
                        label = { Text(stringResource(R.string.functional_doip_ecu)) }, enabled = !state.running,
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(vin, { vin = it.uppercase().take(17); physical = false },
                        label = { Text(stringResource(R.string.functional_doip_vin)) }, enabled = !state.running,
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(tls, { tls = it; physical = false }, enabled = !state.running)
                        Text(stringResource(R.string.functional_doip_tls))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(physical, { physical = it }, enabled = !state.running)
                        Text(stringResource(R.string.functional_doip_confirm))
                    }
                }
                Button(onClick = {
                    onRunProtocol(selected, doIp.takeIf { selected == FunctionalProtocol.DOIP })
                    if (selected == FunctionalProtocol.DOIP) physical = false
                }, enabled = !state.running && selected.implemented &&
                    (selected != FunctionalProtocol.DOIP || doIp.valid),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.functional_protocol_run))
                }
                if (selected == FunctionalProtocol.J1979) {
                    NativeUsbFunctionalPanel(nativeUsbEnabled && !state.running, connectionGeneration, vehicle, onRunNativeUsb)
                    Text(stringResource(R.string.functional_evap_scope), style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(evapConfirmedProfile, { evapConfirmedProfile = it },
                            enabled = evapAvailable && !state.running)
                        Text(stringResource(R.string.functional_evap_profile, vehicle.vin))
                    }
                    OutlinedButton(onClick = {
                        clearSelected = false
                        evapStep = EcuWriteConsentFlow.Step.WARNING
                        evapPending = connectionGeneration to vehicle
                    }, enabled = evapAvailable && !state.running && evapConfirmedProfile &&
                        vehicle.vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.functional_evap_run))
                    }
                    OutlinedButton(onClick = {
                        clearSelected = true
                        evapStep = EcuWriteConsentFlow.Step.WARNING
                        evapPending = connectionGeneration to vehicle
                    }, enabled = evapAvailable && !state.running && evapConfirmedProfile &&
                        vehicle.vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.functional_clear_run))
                    }
                }
                if (legacyProtocol != null) {
                    Text(stringResource(R.string.functional_legacy_mutation_scope),style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(engineSource,{ engineSource=it.uppercase().take(2);legacyProfileConfirmed=false },
                        label={ Text(stringResource(R.string.functional_legacy_engine_source)) },
                        singleLine=true,enabled=!state.running,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(documentedSources,{ documentedSources=it.uppercase().take(127);legacyProfileConfirmed=false },
                        label={ Text(stringResource(R.string.functional_legacy_documented_sources)) },
                        singleLine=true,enabled=!state.running,modifier=Modifier.fillMaxWidth())
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(legacyProfileConfirmed,{legacyProfileConfirmed=it},
                            enabled=legacyAvailableProtocol==legacyProtocol && !state.running)
                        Text(stringResource(R.string.functional_legacy_mutation_profile,vehicle.vin))
                    }
                    OutlinedButton(onClick={
                        legacyTarget?.let {
                            legacyStep=EcuWriteConsentFlow.Step.WARNING
                            legacyPending=Triple(connectionGeneration,vehicle,it.copy(clear=true))
                        }
                    },enabled=legacyReady,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.functional_clear_run)) }
                    OutlinedButton(onClick={
                        legacyTarget?.let {
                            legacyStep=EcuWriteConsentFlow.Step.WARNING
                            legacyPending=Triple(connectionGeneration,vehicle,it.copy(clear=false))
                        }
                    },enabled=legacyReady,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.functional_evap_run)) }
                }
                state.legacyTarget?.let { Text(stringResource(if (it.clear) R.string.functional_clear_run else R.string.functional_evap_run)) }
                state.legacyResult?.reason?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                state.protocol?.let { Text(stringResource(R.string.functional_protocol_report, it.title)) }
                if (state.evapCampaign) Text(stringResource(R.string.functional_evap_run))
                state.evapResult?.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (state.clearCampaign) Text(stringResource(R.string.functional_clear_run))
                state.clearResult?.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onRunAll, enabled = !state.running) {
                        Text(stringResource(R.string.functional_tests_run))
                    }
                    if (state.running) {
                        OutlinedButton(onClick = onStop) {
                            Text(stringResource(R.string.functional_tests_stop))
                        }
                        CircularProgressIndicator(modifier = Modifier.height(22.dp))
                    } else {
                        OutlinedButton(onClick = onExport, enabled = state.canExport) {
                            Text(stringResource(R.string.functional_tests_export))
                        }
                    }
                }
                state.context?.let { evidence ->
                    Text(stringResource(R.string.vehicle_vin) + ": " +
                        evidence.selectedVin.ifBlank { stringResource(R.string.obd_not_available) },
                        style = MaterialTheme.typography.bodySmall)
                    Text(evidence.adapterName + " • " + evidence.transport,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (state.checks.isNotEmpty()) {
                    Text(stringResource(R.string.functional_tests_summary, passed, failed, untested))
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        items(state.checks, key = { it.key }) { check ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (fr) check.labelFr else check.labelEn, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (check.outcome) {
                            FunctionalTestOutcome.PASSED -> stringResource(R.string.functional_tests_passed)
                            FunctionalTestOutcome.FAILED -> stringResource(R.string.functional_tests_failed)
                            FunctionalTestOutcome.NOT_TESTED -> stringResource(R.string.functional_tests_skipped)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = when (check.outcome) {
                            FunctionalTestOutcome.PASSED -> MaterialTheme.colorScheme.primary
                            FunctionalTestOutcome.FAILED -> MaterialTheme.colorScheme.error
                            FunctionalTestOutcome.NOT_TESTED -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(if (fr) check.detailFr else check.detailEn, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val values = state.nativeUsbEvidence?.let { raw ->
            runCatching { NativeUsbFunctionalEvidence.diagnosticValues(org.json.JSONObject(raw)) }.getOrNull()
        }
        if (values != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(stringResource(R.string.functional_native_usb_values),style=MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.functional_native_usb_observed_vin,
                            values.observedVin ?: stringResource(R.string.obd_not_available)))
                        for ((type,entries) in values.identity) {
                            val label=when(type){
                                4->R.string.functional_native_usb_calibration
                                6->R.string.functional_native_usb_cvn
                                else->R.string.functional_native_usb_ecu_name
                            }
                            Text(stringResource(label)+": "+entries.joinToString(", "))
                        }
                        Text(stringResource(R.string.functional_native_usb_freeze_trigger)+": "+when {
                            values.freeze==null -> stringResource(R.string.obd_not_available)
                            !values.freeze.present -> stringResource(R.string.functional_native_usb_freeze_absent)
                            else -> values.freeze.dtc ?: stringResource(R.string.obd_not_available)
                        })
                        for ((mode,codes) in values.dtcs) {
                            val caption = when(mode) {
                                3 -> R.string.functional_native_usb_dtc_confirmed
                                7 -> R.string.functional_native_usb_dtc_pending
                                else -> R.string.functional_native_usb_dtc_permanent
                            }
                            Text(stringResource(caption)+": "+when {
                                codes == null -> stringResource(R.string.obd_not_available)
                                codes.isEmpty() -> stringResource(R.string.functional_native_usb_dtc_zero)
                                else -> codes.joinToString(", ")
                            })
                        }
                    }
                }
            }
            items(values.measurements,key={ "native-value:"+it.mode+":"+it.pid }) { value ->
                Card(modifier=Modifier.fillMaxWidth()) {
                    Column(modifier=Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.functional_native_usb_pid,
                            "%02X".format(java.util.Locale.ROOT,value.mode),
                            "%02X".format(java.util.Locale.ROOT,value.pid)))
                        Text(value.name,style=MaterialTheme.typography.bodySmall)
                        Text(java.text.NumberFormat.getNumberInstance().apply {
                            maximumFractionDigits=3
                        }.format(value.number)+" "+value.unit,style=MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if(values!=null&&values.pages.isNotEmpty()){
            item { Text(stringResource(R.string.functional_native_usb_inventory),style=MaterialTheme.typography.titleMedium) }
            items(values.pages,key={"native-page:"+it.mode+":"+it.base}) { page ->
                Card(modifier=Modifier.fillMaxWidth()) {
                    Column(modifier=Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.functional_native_usb_inventory_page,
                            "%02X".format(java.util.Locale.ROOT,page.mode),"%02X".format(java.util.Locale.ROOT,page.base)))
                        Text(if(page.ids.isEmpty()) stringResource(R.string.functional_native_usb_inventory_empty)
                            else page.ids.joinToString(", ") { "%02X".format(java.util.Locale.ROOT,it) })
                        if(page.next) Text(stringResource(R.string.functional_native_usb_inventory_next))
                    }
                }
            }
        }
        values?.readiness?.let { ready ->
            item {
                Card(modifier=Modifier.fillMaxWidth()) {
                    Column(modifier=Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(stringResource(R.string.functional_native_usb_readiness),style=MaterialTheme.typography.titleMedium)
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
                }
            }
        }
        if (values != null && values.monitors.isNotEmpty()) {
            item { Text(stringResource(R.string.functional_native_usb_monitors),style=MaterialTheme.typography.titleMedium) }
            items(values.monitors.size,key={ "native-monitor:"+it }) { index ->
                val monitor=values.monitors[index]
                Card(modifier=Modifier.fillMaxWidth()) {
                    Column(modifier=Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.functional_native_usb_monitor_id,
                            "%02X".format(java.util.Locale.ROOT,monitor.mid),
                            "%02X".format(java.util.Locale.ROOT,monitor.tid),
                            "%02X".format(java.util.Locale.ROOT,monitor.scaling)))
                        Text(stringResource(R.string.functional_native_usb_monitor_words,
                            "%04X".format(java.util.Locale.ROOT,monitor.value),
                            "%04X".format(java.util.Locale.ROOT,monitor.minimum),
                            "%04X".format(java.util.Locale.ROOT,monitor.maximum)))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
