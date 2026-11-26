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
    onRunClear: (EcuWriteConsentFlow.Approval, Long, VehicleProfile) -> Unit
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
        item { Spacer(Modifier.height(12.dp)) }
    }
}
