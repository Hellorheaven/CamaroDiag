package com.camarodiag.gen5.ui

import androidx.core.content.edit
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.camarodiag.gen5.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camarodiag.gen5.data.obd.ObdDtc
import com.camarodiag.gen5.data.obd.ObdScanReport
import com.camarodiag.gen5.data.obd.ObdValue
import com.camarodiag.gen5.domain.model.CamaroDtcCatalog
import com.camarodiag.gen5.domain.model.DtcCatalogRegistry
import com.camarodiag.gen5.domain.model.GmEngineMonitorCatalog
import com.camarodiag.gen5.domain.model.VehicleProfile
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.camarodiag.gen5.ui.theme.CamaroAmber
import com.camarodiag.gen5.ui.theme.OkGreen
import com.camarodiag.gen5.ui.theme.SurfaceDark
import com.camarodiag.gen5.ui.theme.TextMuted
import com.camarodiag.gen5.ui.theme.TextPrimary
import com.camarodiag.gen5.ui.theme.WarnRed

@Composable
fun ObdScanScreen(
    report: ObdScanReport?,
    isScanning: Boolean,
    message: String?,
    isConnected: Boolean,
    vehicle: VehicleProfile,
    accountUid: String?,
    savedVehicleId: String?,
    onRunScan: () -> Unit,
    onExport: ((ObdScanReport) -> Unit)? = null,
    nativeUsbPanel: (@Composable () -> Unit)? = null,
    onStopNativeScan: (() -> Unit)? = null
) {
    val unavailable = stringResource(R.string.obd_not_available)
    val nativeSnapshot=report?.nativeUsbEvidence!=null
    val nativeValues=remember(report?.nativeUsbEvidence) {
        report?.nativeUsbEvidence?.let { runCatching { NativeUsbFunctionalEvidence.scanValues(org.json.JSONObject(it)) }.getOrNull() }
    }
    val sensorSelectionAvailable=isConnected||nativeSnapshot

    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("obd_sensor_selection", android.content.Context.MODE_PRIVATE)
    }
    val simulatedReport = report?.adapterInfo?.get("IDENTIFICATION").orEmpty()
        .contains("SIMULATED", ignoreCase = true)
    val vehicleKey = report?.vin?.trim()?.uppercase(java.util.Locale.ROOT)
        ?.takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) && !simulatedReport }
    // Only the physically observed VIN identifies a persistent selection.
    // The selected garage vehicle may differ from the connected vehicle.
    val vehicleIdentity = vehicleKey?.takeUnless { nativeSnapshot }?.let { "vin_$it" }
    val preferenceKey = SensorSelectionScope.key(accountUid, vehicleIdentity)
    val storedPids = remember(preferenceKey, preferences) {
        preferenceKey?.let { preferences.getString(it, null)?.split(",")?.mapNotNull(String::toIntOrNull) }
    }
    val catalogPids = report?.liveData.orEmpty().map { it.pid }.distinct().sorted()
    val sensorNames = catalogPids.associateWith { localizedObdPidName(it) }
    val measuredPids = report?.liveData.orEmpty().map { it.pid }.toSet()
    // When no physical VIN is known, keep the selection in this report only.
    val transientReport = report.takeIf { preferenceKey == null }
    var choosingSensors by remember(preferenceKey, transientReport) { mutableStateOf(false) }
    var sensorQuery by remember(preferenceKey, transientReport) { mutableStateOf("") }
    var chosenPids by remember(preferenceKey, transientReport) { mutableStateOf(storedPids.orEmpty()) }
    var selectionApplied by remember(preferenceKey, transientReport) { mutableStateOf(storedPids != null) }
    LaunchedEffect(sensorSelectionAvailable, isScanning, measuredPids) {
        if (!sensorSelectionAvailable || isScanning || measuredPids.isEmpty()) choosingSensors = false
    }
    if (choosingSensors && report != null && sensorSelectionAvailable && !isScanning && measuredPids.isNotEmpty()) {
        var pendingPids by remember(preferenceKey, choosingSensors, measuredPids) {
            mutableStateOf((if (selectionApplied) chosenPids else report.liveData.map { it.pid })
                .filter { it in measuredPids }.distinct())
        }
        AlertDialog(
            onDismissRequest = { choosingSensors = false },
            title = { Text(stringResource(R.string.obd_choose_sensors)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = sensorQuery,
                        onValueChange = { sensorQuery = it },
                        label = { Text(stringResource(R.string.obd_filter_sensors)) },
                        singleLine = true
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        items(catalogPids.filter { pid ->
                            pid.toString(16).contains(sensorQuery, ignoreCase = true) ||
                                sensorNames[pid].orEmpty().contains(sensorQuery, ignoreCase = true)
                        }, key = { it }) { pid ->
                            Row(
                                modifier = Modifier.fillMaxWidth().toggleable(
                                    value = pid in pendingPids,
                                    enabled = pid in measuredPids,
                                    role = Role.Checkbox,
                                    onValueChange = { checked ->
                                        pendingPids = if (checked) (pendingPids + pid).distinct()
                                            else pendingPids - pid
                                    }
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // One toggle target for the entire row, including
                                // the checkbox; no competing nested click handlers.
                                Checkbox(
                                    checked = pid in pendingPids,
                                    enabled = pid in measuredPids,
                                    onCheckedChange = null
                                )
                                Column {
                                    Text(sensorNames[pid] ?: stringResource(R.string.obd_pid_generic, pid))
                                    if (pid !in measuredPids) {
                                        Text(stringResource(R.string.obd_sensor_not_measured), color = TextMuted, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    chosenPids = pendingPids.filter { it in measuredPids }.distinct()
                    selectionApplied = true
                    preferenceKey?.let { key ->
                        preferences.edit { putString(key, chosenPids.joinToString(",")) }
                    }
                    choosingSensors = false
                }) { Text(stringResource(R.string.obd_sensor_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { choosingSensors = false }) {
                    Text(stringResource(R.string.obd_sensor_cancel))
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.obd_scan_title),
                    color = CamaroAmber, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                // Text(
                    // stringResource(R.string.obd_scan_description),
                    // color = TextMuted, fontSize = 12.sp
                // )
                Button(
                    onClick = onRunScan,
                    enabled = isConnected && !isScanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isScanning) CircularProgressIndicator()
                    else Text(stringResource(R.string.obd_run_full_scan))
                }
                if (report != null && onExport != null) {
                    Button(onClick = { onExport(report) }, enabled = !isScanning,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.obd_export_full_scan))
                    }
                }
                if (isScanning && onStopNativeScan!=null) {
                    Button(onClick=onStopNativeScan,modifier=Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.functional_native_usb_scan_stop))
                    }
                }
                if (!isConnected && nativeUsbPanel==null && !nativeSnapshot) Text(
                    stringResource(R.string.obd_connect_adapter_notice),
                    color = CamaroAmber, fontSize = 12.sp
                )
                message?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = CamaroAmber, fontSize = 12.sp)
                }
            }
        }

        nativeUsbPanel?.let { panel -> item { panel() } }
        if (report != null) {
            if(nativeSnapshot) item {
                Text(stringResource(R.string.functional_native_usb_scan_scope),color=CamaroAmber,fontWeight=FontWeight.Bold)
            }
            val isSimulated = report.adapterInfo["IDENTIFICATION"].orEmpty()
                .contains("SIMULATED", ignoreCase = true)
            if (isSimulated) {
                item {
                    Text(
                        stringResource(R.string.obd_offline_simulation_notice),
                        color = CamaroAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
            item {
                InfoCard(title = stringResource(R.string.obd_vehicle_ecu_identification)) {
                    InfoLine(stringResource(R.string.obd_vin), report.vin ?: unavailable)
                    InfoLine(stringResource(R.string.obd_cal_id), report.calibrationIds.joinToString(" | ").ifBlank { unavailable })
                    InfoLine(stringResource(R.string.obd_cvn), report.calibrationVerificationNumbers.joinToString(" | ").ifBlank { unavailable })
                    InfoLine(stringResource(R.string.obd_ecu_name), report.ecuNames.joinToString(" | ").ifBlank { unavailable })
                    if (report.vehicleInfoSupportComplete != null) {
                        InfoLine(stringResource(R.string.obd_mode09_types),
                            report.vehicleInfoSupportedTypes.sorted().joinToString(" ") { "%02X".format(it) }.ifBlank { unavailable })
                        if (report.vehicleInfoSupportComplete == false)
                            Text(stringResource(R.string.obd_mode09_partial), color = CamaroAmber, fontSize = 11.sp)
                        report.vehicleInfoReads.filter { it.infoType % 32 != 0 }.forEach { read ->
                            Text(stringResource(R.string.obd_mode09_raw, read.infoType), fontSize = 11.sp)
                            Text(read.rawResponse.take(1024), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                    InfoLine(stringResource(R.string.obd_supported_pids),
                        if (report.pidInventoryComplete == null || report.firstUnresolvedPid == 1)
                            unavailable else report.supportedPids.size.toString())
                    report.supportedPidsByResponder.toSortedMap().forEach { (address, pids) ->
                        InfoLine(stringResource(R.string.obd_pid_count_by_ecu, address), pids.size.toString())
                    }
                    if (report.pidAttributionIncomplete)
                        Text(stringResource(R.string.obd_pid_attribution_incomplete), color = CamaroAmber, fontSize = 11.sp)
                    report.firstUnresolvedPid?.let { pid ->
                        Text(stringResource(R.string.obd_pid_discovery_partial, pid), color = CamaroAmber, fontSize = 11.sp)
                    }
                    InfoLine(
                        stringResource(R.string.obd_responding_can_ids),
                        report.respondingEcuAddresses.joinToString(", ").ifBlank { stringResource(R.string.obd_none_identified) }
                    )
                    InfoLine(stringResource(R.string.obd_adapter_voltage), report.adapterInfo["VOLTAGE"] ?: "—")
                    InfoLine(stringResource(R.string.obd_protocol), report.adapterInfo["PROTOCOL"] ?: "—")
                }
            }

            item {
                val physical = report.physicalCanResponder
                Text(
                    if (physical != null) {
                        stringResource(R.string.obd_physical_can_scope, physical)
                    } else stringResource(R.string.obd_can_id_limit_explanation),
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            item {
                InfoCard(title = stringResource(R.string.obd_readiness)) {
                    val readiness=nativeValues?.readiness
                    if(readiness!=null) NativeUsbReadinessContent(readiness)
                    else Text(report.readiness ?: stringResource(R.string.obd_data_unavailable),
                        color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }

            item {
                SectionTitle(stringResource(R.string.obd_returned_live_data))
            }
            item {
                Button(onClick = { choosingSensors = true }, enabled = measuredPids.isNotEmpty() && sensorSelectionAvailable && !isScanning, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.obd_choose_sensors))
                }
            }
            if (report.liveData.isEmpty()) {
                item { Text(stringResource(R.string.obd_no_live_data), color = TextMuted) }
            } else {
                items(report.liveData.filter { !selectionApplied || it.pid in chosenPids }, key = {
                    "${it.responder.orEmpty()}:${it.pid}:${it.name}:${it.unit}"
                }) { value ->
                    ObdValueCard(value)
                }
            }

            val unresolvedReads = report.mode01Reads.filter { read ->
                read.observations.isEmpty() || read.observations.any { it.decodedValueCount == 0 }
            }
            if (unresolvedReads.isNotEmpty()) {
                item {
                    SectionTitle(stringResource(R.string.obd_raw_pid_title))
                    Text(stringResource(R.string.obd_raw_pid_notice), color = TextMuted, fontSize = 12.sp)
                }
                items(unresolvedReads, key = { "mode01-raw-${it.pid}" }) { read ->
                    InfoCard(title = stringResource(R.string.obd_pid_generic, read.pid)) {
                        if (read.observations.isEmpty()) {
                            Text(stringResource(R.string.obd_raw_pid_no_value), color = CamaroAmber)
                            Text(read.rawResponse.take(1024), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        } else read.observations.filter { it.decodedValueCount == 0 }.forEach { observation ->
                            observation.responder?.let { Text(stringResource(R.string.obd_value_source, it), color = TextMuted) }
                            Text(stringResource(if (observation.eligibleForDecode) R.string.obd_raw_pid_undecoded
                                else R.string.obd_raw_pid_unattributed), color = CamaroAmber)
                            Text(observation.bytes.joinToString(" ") { "%02X".format(it) },
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }

            item {
                SectionTitle(stringResource(R.string.obd_dtc_title))
            }
            val allCodes = report.confirmedDtcs + report.pendingDtcs + report.permanentDtcs
            val unknownDtcModes = report.unverifiedDtcModes
            if (unknownDtcModes.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.obd_data_unavailable) + ": OBD " +
                        unknownDtcModes.sorted().joinToString(" / ") { mode -> "%02X".format(java.util.Locale.ROOT, mode) },
                        color = CamaroAmber, fontSize = 11.sp)
                }
            }
            if (allCodes.isEmpty()) {
                if (unknownDtcModes.isEmpty()) {
                    item { Text(stringResource(R.string.obd_no_dtcs), color = OkGreen) }
                }
            } else {
                val matchesVehicle = !nativeSnapshot && ObdDtcGuidancePolicy.hasMatchedEcm(
                    selectedVin = vehicle.vin,
                    reportedVin = report.vin,
                    responderAddresses = report.respondingEcuAddresses,
                    simulation = isSimulated
                )
                items(allCodes.distinctBy { it.code to it.status }, key = { "${it.code}-${it.status}" }) { dtc ->
                    DtcResultCard(dtc, vehicle, matchesVehicle)
                }
            }

            item {
                InfoCard(title = stringResource(R.string.obd_freeze_frame)) {
                    InfoLine(
                        stringResource(R.string.obd_trigger_dtc),
                        report.freezeFrameDtc?.code ?: if(nativeSnapshot&&nativeValues?.freeze?.present==false)
                            stringResource(R.string.functional_native_usb_freeze_absent) else unavailable
                    )
                    report.freezeFrameResponder?.let { InfoLine(stringResource(R.string.obd_value_source, it), it) }
                    if (report.freezeFrameSupportComplete == false)
                        Text(stringResource(R.string.obd_freeze_partial), color = CamaroAmber, fontSize = 11.sp)
                    if (report.freezeFrame.isEmpty()) {
                        Text(stringResource(if(nativeSnapshot) when(nativeValues?.freeze?.present) {
                            null->R.string.functional_native_usb_freeze_missing
                            true->R.string.functional_native_usb_freeze_values_missing
                            false->R.string.functional_native_usb_freeze_absent
                        } else R.string.obd_no_freeze_frame), color = TextMuted, fontSize = 12.sp)
                    } else {
                        report.freezeFrame.forEach { (pid, bytes) ->
                            InfoLine("PID %02X".format(pid), bytes.joinToString(" ") { "%02X".format(it) })
                        }
                    }
                    report.freezeFrameReads.filter { it.verifiedBytes == null }.forEach { read ->
                        Text(stringResource(R.string.obd_freeze_unresolved, read.pid), color = CamaroAmber, fontSize = 11.sp)
                        Text(read.rawResponse.take(1024), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }

            report.mode05?.let { mode ->
                item {
                    InfoCard(title = stringResource(R.string.obd_mode05_results)) {
                        if (!mode.complete)
                            Text(stringResource(R.string.obd_mode05_partial), color = CamaroAmber, fontSize = 11.sp)
                        Text(stringResource(R.string.obd_mode05_raw_notice), color = TextMuted, fontSize = 11.sp)
                        mode.reads.forEach { read ->
                            InfoLine(read.command, read.positiveRecords.takeIf { it.isNotEmpty() }
                                ?.joinToString("\n") { record -> record.joinToString(" ") { "%02X".format(it) } }
                                ?: stringResource(R.string.obd_not_available))
                        }
                    }
                }
            }

            item {
                InfoCard(title = stringResource(R.string.obd_mode06_results)) {
                    if(nativeValues?.monitors?.isNotEmpty()==true) {
                        Text(stringResource(R.string.functional_native_usb_monitor_encoded_scope),color=TextMuted,fontSize=11.sp)
                        nativeValues.monitors.forEach { monitor -> NativeUsbMonitorContent(monitor) }
                    }
                    Text(report.monitorTestRaw ?: stringResource(R.string.obd_not_available),
                        color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }

            report.mode08?.let { mode ->
                item {
                    InfoCard(title = stringResource(R.string.obd_mode08_inventory)) {
                        if (!mode.complete)
                            Text(stringResource(R.string.obd_mode08_partial), color = CamaroAmber, fontSize = 11.sp)
                        Text(mode.supportedTests.sorted().joinToString(", ") { "TID %02X".format(it) }
                            .ifBlank { stringResource(R.string.obd_not_available) }, color = TextPrimary)
                        Text(stringResource(R.string.obd_mode08_no_execution), color = TextMuted, fontSize = 11.sp)
                    }
                }
            }

            item {
                InfoCard(title = stringResource(R.string.obd_raw_scan_log)) {
                    report.rawLog.forEach { line ->
                        Text(line, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    }
                }
            }

            item {
                Text(
                    if (isSimulated) {
                        stringResource(R.string.obd_simulation_scope)
                    } else {
                        stringResource(R.string.obd_real_scan_scope)
                    },
                    color = TextMuted, fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = CamaroAmber, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = TextMuted.copy(alpha = .25f))
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(0.32f))
        Text(value, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.68f))
    }
}

@Composable
private fun localizedObdPidName(value: ObdValue): String = localizedObdPidName(value.pid)

@Composable
private fun localizedObdPidName(pid: Int): String = when (pid) {
    0x03 -> stringResource(R.string.live_pid_fuel_system_status)
    0x04 -> stringResource(R.string.live_pid_load)
    0x05 -> stringResource(R.string.live_pid_ect)
    0x06 -> stringResource(R.string.live_pid_stft)
    0x07 -> stringResource(R.string.live_pid_ltft)
    0x0B -> stringResource(R.string.live_pid_map)
    0x0C -> stringResource(R.string.live_pid_rpm)
    0x0D -> stringResource(R.string.live_pid_speed)
    0x0F -> stringResource(R.string.live_pid_iat)
    0x10 -> stringResource(R.string.live_pid_maf)
    0x11 -> stringResource(R.string.live_pid_throttle)
    0x1C -> stringResource(R.string.obd_pid_standard)
    0x1F -> stringResource(R.string.obd_pid_runtime)
    0x21 -> stringResource(R.string.obd_pid_mil_distance)
    0x30 -> stringResource(R.string.obd_pid_warmups)
    0x31 -> stringResource(R.string.obd_pid_clear_distance)
    0x42 -> stringResource(R.string.live_pid_bat)
    0x43 -> stringResource(R.string.live_pid_abs_load)
    0x44 -> stringResource(R.string.live_pid_eq_ratio)
    0x46 -> stringResource(R.string.live_pid_ambient)
    0x49 -> stringResource(R.string.live_pid_app_d)
    0x4A -> stringResource(R.string.live_pid_app_e)
    0xA6 -> stringResource(R.string.obd_pid_odometer)
    0x5B -> stringResource(R.string.live_pid_hybrid_bat_life)
    0x52 -> stringResource(R.string.live_pid_ethanol)
    else -> stringResource(R.string.obd_pid_generic, pid)
}

@Composable
private fun ObdValueCard(value: ObdValue) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.obd_pid_generic, value.pid), color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(localizedObdPidName(value), color = TextMuted, fontSize = 12.sp)
                value.responder?.let { Text(stringResource(R.string.obd_value_source, it),
                    color = TextMuted, fontSize = 11.sp) }
                if (value.temperatureAtEncodingLimit) {
                    Text(stringResource(R.string.obd_temperature_encoding_limit,
                        value.raw.joinToString(" ") { "%02X".format(it) }),
                        color = CamaroAmber, fontSize = 11.sp)
                }
            }
            Text("${value.value} ${value.unit}",
                color = if (value.temperatureAtEncodingLimit) CamaroAmber else TextPrimary,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DtcResultCard(
    dtc: ObdDtc,
    vehicle: VehicleProfile,
    vehicleEcuIdentityConfirmed: Boolean
) {
    val isFrench = LocalConfiguration.current.locales[0].language.equals("fr", ignoreCase = true)
    val normalizedCode = dtc.code.substringBefore('-')
    val generic = DtcCatalogRegistry.genericDescription(normalizedCode)
    val specific = if (vehicleEcuIdentityConfirmed) {
        CamaroDtcCatalog.find(normalizedCode, vehicle)
    } else null
    val monitor = if (vehicleEcuIdentityConfirmed) {
        GmEngineMonitorCatalog.find(normalizedCode, vehicle)
    } else null
    val gmSource = GmEngineMonitorCatalog.sourceLabel(vehicle) ?: "GM"
    var showHelp by remember(dtc.code, dtc.status) { mutableStateOf(false) }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(dtc.code) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (generic != null) Text(if (isFrench) generic.second else generic.first)
                    if (specific == null && monitor == null && generic == null) {
                        Text(stringResource(R.string.obd_dtc_help_unavailable))
                    }
                    if (!vehicleEcuIdentityConfirmed) {
                        Text(stringResource(R.string.dtc_vehicle_identity_required),
                            color = WarnRed, fontSize = 12.sp)
                    }
                    if (specific != null) {
                        Text(if (isFrench) specific.meaningFr else specific.meaningEn)
                        Text(stringResource(R.string.dtc_severity), fontWeight = FontWeight.Bold)
                        Text(if (isFrench) specific.severityFr else specific.severityEn)
                        Text(stringResource(R.string.dtc_possible_causes), fontWeight = FontWeight.Bold)
                        (if (isFrench) specific.causesFr else specific.causesEn).forEach {
                            Text("• $it")
                        }
                        Text(stringResource(R.string.dtc_recommended_checks), fontWeight = FontWeight.Bold)
                        (if (isFrench) specific.checksFr else specific.checksEn).forEachIndexed { index, check ->
                            Text("${index + 1}. $check")
                        }
                    }
                    if (monitor != null) {
                        HorizontalDivider(color = TextMuted.copy(alpha = 0.25f))
                        Text(stringResource(R.string.dtc_gm_monitor_title, gmSource),
                            fontWeight = FontWeight.Bold, color = CamaroAmber)
                        Text(if (isFrench) monitor.componentFr else monitor.componentEn)
                        Text(if (isFrench) monitor.strategyFr else monitor.strategyEn)
                        Text(stringResource(R.string.dtc_gm_monitor_criteria,
                            if (isFrench) monitor.malfunctionCriteriaFr else monitor.malfunctionCriteriaEn))
                        Text(stringResource(R.string.dtc_gm_monitor_enable,
                            if (isFrench) monitor.enableConditionsFr else monitor.enableConditionsEn))
                        Text(stringResource(R.string.dtc_gm_monitor_failure,
                            if (isFrench) monitor.failureCriteriaFr else monitor.failureCriteriaEn))
                        Text(stringResource(R.string.dtc_gm_monitor_source, gmSource, monitor.sourcePage),
                            color = TextMuted, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    Text(stringResource(R.string.obd_dtc_help_close))
                }
            }
        )
    }
    val status = when (dtc.status) {
        "pending" -> stringResource(R.string.obd_pending)
        "permanent" -> stringResource(R.string.obd_permanent)
        else -> stringResource(R.string.obd_confirmed)
    }
    val statusColor = if (dtc.status == "confirmed") WarnRed else CamaroAmber
    Card(modifier = Modifier.clickable { showHelp = true },
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Row(Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(dtc.code, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                // A generic OBD DTC has no physical module attribution here.
                // Text(stringResource(R.string.obd_powertrain_ecm),
                //     color = TextMuted, fontSize = 10.sp)
            }
            Text(status, color = statusColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        }
    }
}
