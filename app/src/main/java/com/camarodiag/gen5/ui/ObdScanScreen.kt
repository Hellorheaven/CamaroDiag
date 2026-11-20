package com.camarodiag.gen5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
    onRunScan: () -> Unit
) {
    val unavailable = stringResource(R.string.obd_not_available)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.obd_scan_title),
                    color = CamaroAmber, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Text(
                    stringResource(R.string.obd_scan_description),
                    color = TextMuted, fontSize = 12.sp
                )
                Button(
                    onClick = onRunScan,
                    enabled = isConnected && !isScanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isScanning) CircularProgressIndicator()
                    else Text(stringResource(R.string.obd_run_full_scan))
                }
                if (!isConnected) Text(
                    stringResource(R.string.obd_connect_adapter_notice),
                    color = CamaroAmber, fontSize = 12.sp
                )
                message?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = CamaroAmber, fontSize = 12.sp)
                }
            }
        }

        if (report != null) {
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
                    InfoLine(stringResource(R.string.obd_supported_pids), report.supportedPids.size.toString())
                    InfoLine(
                        stringResource(R.string.obd_responding_can_ids),
                        report.respondingEcuAddresses.joinToString(", ").ifBlank { stringResource(R.string.obd_none_identified) }
                    )
                    InfoLine(stringResource(R.string.obd_adapter_voltage), report.adapterInfo["VOLTAGE"] ?: "—")
                    InfoLine(stringResource(R.string.obd_protocol), report.adapterInfo["PROTOCOL"] ?: "—")
                }
            }

            item {
                Text(
                    stringResource(R.string.obd_can_id_limit_explanation),
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            item {
                InfoCard(title = stringResource(R.string.obd_readiness)) {
                    Text(report.readiness ?: stringResource(R.string.obd_data_unavailable),
                        color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }

            item {
                SectionTitle(stringResource(R.string.obd_returned_live_data))
            }
            if (report.liveData.isEmpty()) {
                item { Text(stringResource(R.string.obd_no_live_data), color = TextMuted) }
            } else {
                items(report.liveData, key = { it.pid }) { value ->
                    ObdValueCard(value)
                }
            }

            item {
                SectionTitle(stringResource(R.string.obd_dtc_title))
            }
            val allCodes = report.confirmedDtcs + report.pendingDtcs + report.permanentDtcs
            if (allCodes.isEmpty()) {
                item { Text(stringResource(R.string.obd_no_dtcs), color = OkGreen) }
            } else {
                items(allCodes.distinctBy { it.code to it.status }, key = { "${it.code}-${it.status}" }) { dtc ->
                    DtcResultCard(dtc)
                }
            }

            item {
                InfoCard(title = stringResource(R.string.obd_freeze_frame)) {
                    InfoLine(
                        stringResource(R.string.obd_trigger_dtc),
                        report.freezeFrameDtc?.code ?: unavailable
                    )
                    if (report.freezeFrame.isEmpty()) {
                        Text(stringResource(R.string.obd_no_freeze_frame), color = TextMuted, fontSize = 12.sp)
                    } else {
                        report.freezeFrame.forEach { (pid, bytes) ->
                            InfoLine("PID %02X".format(pid), bytes.joinToString(" ") { "%02X".format(it) })
                        }
                    }
                }
            }

            item {
                InfoCard(title = stringResource(R.string.obd_mode06_results)) {
                    Text(report.monitorTestRaw ?: stringResource(R.string.obd_not_available),
                        color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
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
private fun localizedObdPidName(value: ObdValue): String = when (value.pid) {
    0x03 -> stringResource(R.string.live_pid_fuel_system_status)
    0x04 -> stringResource(R.string.live_pid_load)
    0x05 -> stringResource(R.string.live_pid_ect)
    0x06 -> stringResource(R.string.live_pid_stft)
    0x07 -> stringResource(R.string.live_pid_ltft)
    0x0C -> stringResource(R.string.live_pid_rpm)
    0x0D -> stringResource(R.string.live_pid_speed)
    0x0F -> stringResource(R.string.live_pid_iat)
    0x10 -> stringResource(R.string.live_pid_maf)
    0x11 -> stringResource(R.string.live_pid_throttle)
    0x42 -> stringResource(R.string.live_pid_bat)
    else -> stringResource(R.string.obd_pid_generic, value.pid)
}

@Composable
private fun ObdValueCard(value: ObdValue) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.obd_pid_generic, value.pid), color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(localizedObdPidName(value), color = TextMuted, fontSize = 12.sp)
            }
            Text("${value.value} ${value.unit}", color = TextPrimary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DtcResultCard(dtc: ObdDtc) {
    var showHelp by remember(dtc.code) { mutableStateOf(false) }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(dtc.code) },
            text = { Text(stringResource(R.string.obd_dtc_help_unavailable)) },
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
    Card(modifier = Modifier.clickable { showHelp = true }, colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(dtc.code, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(stringResource(R.string.obd_powertrain_ecm), color = TextMuted, fontSize = 10.sp)
            }
            Text(status, color = statusColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        }
    }
}
