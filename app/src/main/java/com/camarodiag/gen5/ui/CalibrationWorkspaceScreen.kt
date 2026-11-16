package com.camarodiag.gen5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.camarodiag.gen5.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camarodiag.gen5.ui.theme.CamaroAmber
import com.camarodiag.gen5.ui.theme.OkGreen
import com.camarodiag.gen5.ui.theme.SurfaceDark
import com.camarodiag.gen5.ui.theme.TextMuted
import com.camarodiag.gen5.ui.theme.TextPrimary
import com.camarodiag.gen5.data.obd.CamaroCalibrationCatalog
import com.camarodiag.gen5.data.obd.CalibrationMapDefinition
import com.camarodiag.gen5.data.obd.ObdbCamaroSignal
import com.camarodiag.gen5.data.obd.CamaroSignalDefinition
import com.camarodiag.gen5.domain.model.VehicleProfile

data class CalibrationWorkspaceState(
    val isReading: Boolean = false,
    val ecuVinResponse: String = "",
    val calibrationIdResponse: String = "",
    val cvnResponse: String = "",
    val message: String = "",
    val isUdsReading: Boolean = false,
    val udsDidResponse: String = "",
    val udsDtcResponse: String = "",
    val udsMessage: String = "",
    val isJ2534BridgeConnecting: Boolean = false,
    val isJ2534BridgeConnected: Boolean = false,
    val j2534BridgeMessage: String = "",
    val nativeSignals: List<CamaroSignalDefinition> = emptyList(),
    val nativeSignalMessage: String = "",
    val obdbSignals: List<ObdbCamaroSignal> = emptyList(),
    val isObdbSignalReading: Boolean = false,
    val obdbSignalValue: String = "",
    val obdbSignalMessage: String = "",
    val importedFileName: String? = null,
    val importedFileSize: Long? = null,
    val importedFileSha256: String? = null,
    val importedFilePreview: String? = null,
    val importedFilePath: String? = null,
    val inspectionFormat: String? = null,
    val inspectionSummary: String? = null,
    val inspectionPayloadBytes: Long? = null,
    val inspectionRecordCount: Int? = null,
    val inspectionChecksumStatus: String? = null,
    val inspectionAddressRanges: List<String> = emptyList(),
    val inspectionWarnings: List<String> = emptyList(),
    val comparisonFileName: String? = null,
    val comparisonFilePath: String? = null,
    val comparisonFileSize: Long? = null,
    val comparisonSummary: String? = null,
    val comparisonChangedBytes: Long? = null,
    val comparisonAppendedBytes: Long? = null,
    val comparisonMissingBytes: Long? = null,
    val comparisonRanges: List<String> = emptyList(),
    val comparisonIdentical: Boolean? = null,
    val activeFilePath: String? = null,
    val mapDefinitions: List<CalibrationMapDefinition> = emptyList(),
    val selectedMapId: String? = null,
    val mapValues: List<List<String>> = emptyList(),
    val mapXAxisValues: List<String> = emptyList(),
    val mapYAxisValues: List<String> = emptyList(),
    val mapDefinitionsWarnings: List<String> = emptyList(),
    val mapDirty: Boolean = false,
    val mapError: String? = null
)


@Composable
private fun localizedCalibrationEngineName(engine: com.camarodiag.gen5.domain.model.CamaroEngine): String = when (engine) {
    com.camarodiag.gen5.domain.model.CamaroEngine.LLT -> stringResource(R.string.engine_llt)
    com.camarodiag.gen5.domain.model.CamaroEngine.LFX -> stringResource(R.string.engine_lfx)
    com.camarodiag.gen5.domain.model.CamaroEngine.LS3 -> stringResource(R.string.engine_ls3)
    com.camarodiag.gen5.domain.model.CamaroEngine.L99 -> stringResource(R.string.engine_l99)
    com.camarodiag.gen5.domain.model.CamaroEngine.LSA -> stringResource(R.string.engine_lsa)
    com.camarodiag.gen5.domain.model.CamaroEngine.LS7 -> stringResource(R.string.engine_ls7)
    com.camarodiag.gen5.domain.model.CamaroEngine.LTG -> stringResource(R.string.engine_ltg)
    com.camarodiag.gen5.domain.model.CamaroEngine.LGX -> stringResource(R.string.engine_lgx)
    com.camarodiag.gen5.domain.model.CamaroEngine.LT1 -> stringResource(R.string.engine_lt1)
    com.camarodiag.gen5.domain.model.CamaroEngine.LT4 -> stringResource(R.string.engine_lt4)
}

private fun calibrationProfileNotesResource(engine: com.camarodiag.gen5.domain.model.CamaroEngine): Int = when (engine) {
    com.camarodiag.gen5.domain.model.CamaroEngine.LLT -> R.string.cal_profile_notes_llt
    com.camarodiag.gen5.domain.model.CamaroEngine.LFX -> R.string.cal_profile_notes_lfx
    com.camarodiag.gen5.domain.model.CamaroEngine.LS3 -> R.string.cal_profile_notes_ls3
    com.camarodiag.gen5.domain.model.CamaroEngine.L99 -> R.string.cal_profile_notes_l99
    com.camarodiag.gen5.domain.model.CamaroEngine.LSA -> R.string.cal_profile_notes_lsa
    com.camarodiag.gen5.domain.model.CamaroEngine.LS7 -> R.string.cal_profile_notes_ls7
    com.camarodiag.gen5.domain.model.CamaroEngine.LTG -> R.string.cal_profile_notes_ltg
    com.camarodiag.gen5.domain.model.CamaroEngine.LGX -> R.string.cal_profile_notes_lgx
    com.camarodiag.gen5.domain.model.CamaroEngine.LT1 -> R.string.cal_profile_notes_lt1
    com.camarodiag.gen5.domain.model.CamaroEngine.LT4 -> R.string.cal_profile_notes_lt4
}

@Composable
private fun localizedAxisDetails(offset: Int?, count: Int?, unit: String?): String =
    if (offset == null || count == null) stringResource(R.string.cal_axis_undefined)
    else stringResource(R.string.cal_axis_values, offset.toString(16).uppercase(), count, unit.orEmpty())

@Composable
fun CalibrationWorkspaceScreen(
    state: CalibrationWorkspaceState,
    vehicleLabel: String,
    ecmType: String,
    isConnected: Boolean,
    isFr: Boolean,
    onReadIdentification: () -> Unit,
    onReadUdsDid: (String, String, String, String) -> Unit,
    onReadUdsDtcs: (String, String, String) -> Unit,
    onConnectJ2534Bridge: (String, String, String, String, String, String) -> Unit,
    onDisconnectJ2534Bridge: () -> Unit,
    onReadObdbSignal: (String, String) -> Unit,
    vehicle: VehicleProfile,
    onImportFile: () -> Unit,
    onImportComparisonFile: () -> Unit,
    onImportDefinitions: () -> Unit,
    onExportDefinitionTemplate: () -> Unit,
    onSelectMap: (String) -> Unit,
    onCellChange: (Int, Int, String) -> Unit,
    onApplyMap: () -> Unit,
    onDiscardMap: () -> Unit,
    onExportFile: () -> Unit,
    onClearFile: () -> Unit
) {
    var udsRequestCanId by remember { mutableStateOf("") }
    var udsResponseCanId by remember { mutableStateOf("") }
    var udsDid by remember { mutableStateOf("F190") }
    var udsCanProtocol by remember { mutableStateOf("33") }
    var j2534Host by remember { mutableStateOf("192.168.1.100") }
    var j2534Port by remember { mutableStateOf("35001") }
    var j2534Token by remember { mutableStateOf("") }
    var obdbSignalSearch by remember { mutableStateOf("") }
    var obdbSignalMenuExpanded by remember { mutableStateOf(false) }
    var selectedObdbSignalId by remember { mutableStateOf("") }
    val selectedObdbSignal = state.obdbSignals.firstOrNull { it.id == selectedObdbSignalId }
        ?: state.obdbSignals.firstOrNull()
    val profileUses29BitIds = udsCanProtocol in setOf("34", "36", "64")
    val canIdsMatchProfile = udsRequestCanId.length == udsResponseCanId.length &&
        (udsRequestCanId.length == 8) == profileUses29BitIds &&
        udsRequestCanId.length in setOf(3, 8)
    val filteredObdbSignals = state.obdbSignals.filter {
        obdbSignalSearch.isBlank() ||
            it.name.contains(obdbSignalSearch, true) ||
            it.id.contains(obdbSignalSearch, true) ||
            it.path.contains(obdbSignalSearch, true)
    }
    val currentProfile = remember(vehicle) { CamaroCalibrationCatalog.forVehicle(vehicle) }
    val allProfiles = remember { CamaroCalibrationCatalog.profiles }
    val yearProfiles = remember(vehicle.modelYear) { CamaroCalibrationCatalog.forYear(vehicle.modelYear) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.cal_workspace_title),
                    color = CamaroAmber, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Text(vehicleLabel, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.cal_ecm_family, ecmType),
                    color = TextMuted, fontSize = 12.sp)
                Text(
                    currentProfile?.let {
                        stringResource(R.string.cal_engine_profile, it.engine.rpo, it.generation.label, it.modelYears.first, it.modelYears.last)
                    } ?: stringResource(R.string.cal_engine_profile_missing),
                    color = CamaroAmber, fontSize = 12.sp
                )
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.cal_engine_coverage),
                        color = CamaroAmber, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.cal_engine_profiles_hint),
                        color = TextMuted, fontSize = 12.sp
                    )
                    Text(
                        stringResource(R.string.cal_catalogued_engines, vehicle.modelYear, yearProfiles.joinToString(", ") { it.engine.rpo }.ifBlank { stringResource(R.string.common_none) }),
                        color = CamaroAmber,
                        fontSize = 11.sp
                    )
                    allProfiles.forEach { profile ->
                        HorizontalDivider(color = TextMuted.copy(alpha = .18f))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${profile.engine.rpo} • ${profile.generation.label} • ${profile.modelYears.first}–${profile.modelYears.last}",
                                color = if (profile.engine == vehicle.engine) CamaroAmber else TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(localizedCalibrationEngineName(profile.engine), color = TextPrimary, fontSize = 12.sp)
                            Text("ECM : ${profile.nominalControllerFamily}", color = TextMuted, fontSize = 11.sp)
                            Text(
                                stringResource(
                                    R.string.cal_profile_capabilities,
                                    stringResource(if (profile.supportsStandardCalIdRead && profile.supportsStandardCvnRead) R.string.cal_status_profiled else R.string.cal_status_not_verified),
                                    stringResource(if (profile.supportsBinaryRead) R.string.cal_status_validated else R.string.cal_status_unavailable),
                                    stringResource(if (profile.supportsTableEditing) R.string.cal_status_available else R.string.cal_status_not_supplied),
                                    stringResource(if (profile.supportsEcuWrite) R.string.cal_status_validated else R.string.cal_status_disabled)
                                ),
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                            Text(stringResource(calibrationProfileNotesResource(profile.engine)), color = TextMuted, fontSize = 10.sp)
                            Text(
                                stringResource(R.string.cal_capability_summary),
                                color = TextMuted, fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Icon(Icons.Default.Memory, contentDescription = null, tint = CamaroAmber)
                        Text(stringResource(R.string.cal_ecu_identification),
                            color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        stringResource(R.string.cal_obd_mode09_explanation),
                        color = TextMuted, fontSize = 12.sp
                    )
                    Button(
                        onClick = onReadIdentification,
                        enabled = isConnected && !state.isReading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isReading) CircularProgressIndicator()
                        else Text(stringResource(R.string.cal_read_ecu_ids))
                    }
                    if (!isConnected) Text(
                        stringResource(R.string.cal_connect_obd_required),
                        color = CamaroAmber, fontSize = 12.sp
                    )
                    HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                    Text(stringResource(R.string.cal_uds_can_title),
                        color = CamaroAmber, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.cal_uds_transport_hint),
                        color = TextMuted, fontSize = 11.sp
                    )
                    OutlinedTextField(
                        value = udsRequestCanId,
                        onValueChange = { udsRequestCanId = it.uppercase().filter { ch -> ch.isDigit() || ch in 'A'..'F' }.take(8) },
                        label = { Text(stringResource(R.string.cal_can_request_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                    OutlinedTextField(
                        value = udsResponseCanId,
                        onValueChange = { udsResponseCanId = it.uppercase().filter { ch -> ch.isDigit() || ch in 'A'..'F' }.take(8) },
                        label = { Text(stringResource(R.string.cal_can_response_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                    OutlinedTextField(
                        value = udsCanProtocol,
                        onValueChange = { udsCanProtocol = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.cal_can_profile)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = udsDid,
                        onValueChange = { udsDid = it.uppercase().filter { ch -> ch.isDigit() || ch in 'A'..'F' }.take(4) },
                        label = { Text(stringResource(R.string.cal_uds_did)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = .72f))) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(stringResource(R.string.cal_j2534_bridge_title),
                                color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                stringResource(R.string.cal_j2534_launch_instructions),
                                color = TextMuted, fontSize = 10.sp
                            )
                            Text(
                                stringResource(R.string.cal_j2534_security_notice),
                                color = CamaroAmber, fontSize = 10.sp
                            )
                            OutlinedTextField(
                                value = j2534Host,
                                onValueChange = { j2534Host = it.trim().take(64) },
                                label = { Text(stringResource(R.string.cal_pc_ip_address)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                            )
                            OutlinedTextField(
                                value = j2534Port,
                                onValueChange = { j2534Port = it.filter(Char::isDigit).take(5) },
                                label = { Text("Port TCP") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = j2534Token,
                                onValueChange = { j2534Token = it.trim().take(128) },
                                label = { Text(stringResource(R.string.cal_pc_pairing_token)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                            )
                            if (!state.isJ2534BridgeConnected) {
                                Button(
                                    onClick = {
                                        onConnectJ2534Bridge(
                                            j2534Host, j2534Port, j2534Token,
                                            udsRequestCanId, udsResponseCanId, udsCanProtocol
                                        )
                                    },
                                    enabled = !state.isJ2534BridgeConnecting &&
                                        j2534Host.isNotBlank() && j2534Port.isNotBlank() &&
                                        j2534Token.isNotBlank() && canIdsMatchProfile &&
                                        udsCanProtocol in setOf("33", "34", "35", "36", "63", "64"),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (state.isJ2534BridgeConnecting) CircularProgressIndicator()
                                    else Text(stringResource(R.string.cal_connect_j2534))
                                }
                            } else {
                                OutlinedButton(onClick = onDisconnectJ2534Bridge, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.cal_disconnect_pc_bridge))
                                }
                            }
                            if (state.j2534BridgeMessage.isNotBlank()) {
                                Text(state.j2534BridgeMessage,
                                    color = if (state.isJ2534BridgeConnected) OkGreen else CamaroAmber,
                                    fontSize = 11.sp)
                            }
                            Text(
                                stringResource(R.string.cal_bridge_capabilities),
                                color = TextMuted, fontSize = 10.sp
                            )
                        }
                    }
                    Button(
                        onClick = { onReadUdsDid(udsRequestCanId, udsResponseCanId, udsDid, udsCanProtocol) },
                        enabled = (isConnected || state.isJ2534BridgeConnected) && !state.isUdsReading &&
                            canIdsMatchProfile && udsDid.length == 4 &&
                            udsCanProtocol in setOf("33", "34", "35", "36", "63", "64"),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isUdsReading) CircularProgressIndicator()
                        else Text(stringResource(R.string.cal_read_uds_did))
                    }
                    if (state.udsMessage.isNotBlank()) Text(state.udsMessage, color = CamaroAmber, fontSize = 11.sp)
                    if (state.udsDidResponse.isNotBlank()) {
                        Text("UDS $udsDid", color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(state.udsDidResponse, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { onReadUdsDtcs(udsRequestCanId, udsResponseCanId, udsCanProtocol) },
                        enabled = (isConnected || state.isJ2534BridgeConnected) && !state.isUdsReading &&
                            canIdsMatchProfile &&
                            udsCanProtocol in setOf("33", "34", "35", "36", "63", "64"),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.cal_read_uds_dtcs))
                    }
                    if (state.udsDtcResponse.isNotBlank()) {
                        Text(stringResource(R.string.cal_uds_trouble_codes),
                            color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(state.udsDtcResponse, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                    if (state.ecuVinResponse.isNotBlank() || state.calibrationIdResponse.isNotBlank() || state.cvnResponse.isNotBlank()) {
                        HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                        Text("VIN • 09 02", color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(state.ecuVinResponse.ifBlank { "—" }, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("CAL ID • 09 04", color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(state.calibrationIdResponse.ifBlank { "—" }, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("CVN • 09 06", color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(state.cvnResponse.ifBlank { "—" }, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.cal_native_catalog_title),
                        color = CamaroAmber, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.cal_native_catalog_explanation),
                        color = TextMuted, fontSize = 11.sp
                    )
                    Text(state.nativeSignalMessage.ifBlank {
                        stringResource(R.string.cal_native_catalog_loading)
                    }, color = TextMuted, fontSize = 10.sp)
                    if (state.nativeSignals.isEmpty()) {
                        Text(
                            stringResource(R.string.cal_native_catalog_empty),
                            color = CamaroAmber, fontSize = 11.sp
                        )
                    } else {
                        state.nativeSignals.forEach { signal ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(signal.name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${signal.id} • ${signal.category} • DID 0x${signal.identifierHex} • ${signal.verification}",
                                    color = TextMuted, fontSize = 10.sp
                                )
                                Text(
                                    stringResource(R.string.cal_native_catalog_read_disabled),
                                    color = CamaroAmber, fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(stringResource(R.string.cal_obdb_catalog_title),
                        color = CamaroAmber, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.cal_obdb_catalog_explanation),
                        color = TextMuted, fontSize = 11.sp
                    )
                    Text(state.obdbSignalMessage.ifBlank {
                        stringResource(R.string.cal_obdb_catalog_loading)
                    }, color = TextMuted, fontSize = 10.sp)
                    OutlinedTextField(
                        value = obdbSignalSearch,
                        onValueChange = { obdbSignalSearch = it.take(60) },
                        label = { Text(stringResource(R.string.cal_search_signal)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box {
                        TextButton(
                            onClick = { obdbSignalMenuExpanded = true },
                            enabled = state.obdbSignals.isNotEmpty()
                        ) {
                            Text(selectedObdbSignal?.let { "${it.name} • ${it.id}" }
                                ?: stringResource(R.string.cal_no_signal))
                        }
                        DropdownMenu(
                            expanded = obdbSignalMenuExpanded,
                            onDismissRequest = { obdbSignalMenuExpanded = false }
                        ) {
                            filteredObdbSignals.forEach { signal ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(signal.name, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "${signal.id} • ${signal.path} • ${signal.requestHeaderHex} → ${signal.responseHeaderHex ?: "?"}",
                                                color = TextMuted,
                                                fontSize = 10.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedObdbSignalId = signal.id
                                        obdbSignalMenuExpanded = false
                                        udsRequestCanId = signal.requestCanId?.toString(16)?.uppercase() ?: signal.requestHeaderHex
                                        udsResponseCanId = signal.responseCanId?.toString(16)?.uppercase() ?: signal.responseHeaderHex.orEmpty()
                                        udsDid = signal.identifierHex
                                        udsCanProtocol = "33"
                                    }
                                )
                            }
                        }
                    }
                    selectedObdbSignal?.let { signal ->
                        Text(
                            stringResource(
                                R.string.cal_signal_metadata,
                                signal.identifierHex,
                                signal.requestHeaderHex,
                                signal.responseHeaderHex ?: stringResource(R.string.cal_status_unresolved),
                                signal.frequencySeconds
                            ),
                            color = TextMuted, fontSize = 10.sp
                        )
                        if (signal.debugSignal) {
                            Text(stringResource(R.string.cal_signal_debug),
                                color = CamaroAmber, fontSize = 10.sp)
                        }
                        if (signal.flowControlRequired) {
                            Text(stringResource(R.string.cal_signal_flow_control),
                                color = TextMuted, fontSize = 10.sp)
                        }
                        signal.description?.let { Text(it, color = TextMuted, fontSize = 10.sp) }
                        if (!signal.hasSupportedPhysicalCanAddressing) {
                            Text(
                                stringResource(R.string.cal_signal_can_unresolved),
                                color = CamaroAmber, fontSize = 11.sp
                            )
                        }
                        Button(
                            onClick = { onReadObdbSignal(signal.id, udsCanProtocol) },
                            enabled = (isConnected || state.isJ2534BridgeConnected) &&
                                signal.hasSupportedPhysicalCanAddressing &&
                                !profileUses29BitIds &&
                                signal.service == 0x22 &&
                                !state.isObdbSignalReading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (state.isObdbSignalReading) CircularProgressIndicator()
                            else Text(stringResource(R.string.cal_read_signal))
                        }
                    }
                    if (state.obdbSignalValue.isNotBlank()) {
                        Text(state.obdbSignalValue, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Icon(Icons.Default.FolderOpen, contentDescription = null, tint = CamaroAmber)
                        Text(stringResource(R.string.calibration_files),
                            color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        stringResource(R.string.cal_file_inspection_hint),
                        color = TextMuted, fontSize = 12.sp
                    )
                    OutlinedButton(onClick = onImportFile, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Text(stringResource(R.string.cal_import_file))
                    }
                    Text(
                        stringResource(R.string.cal_import_supported_formats),
                        color = TextMuted, fontSize = 11.sp
                    )
                    state.importedFileName?.let { name ->
                        HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                        Text(name, color = CamaroAmber, fontWeight = FontWeight.Bold)
                        Text("${state.importedFileSize ?: 0L} bytes", color = TextPrimary, fontSize = 12.sp)
                        Text("SHA-256", color = TextMuted, fontSize = 11.sp)
                        Text(state.importedFileSha256.orEmpty(), color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        Text(stringResource(R.string.cal_hex_preview), color = TextMuted, fontSize = 11.sp)
                        Text(state.importedFilePreview.orEmpty(), color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        state.inspectionFormat?.let { format ->
                            HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                            Text(stringResource(R.string.cal_file_inspection, format), color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(state.inspectionSummary.orEmpty(), color = TextPrimary, fontSize = 12.sp)
                            state.inspectionPayloadBytes?.let { Text(stringResource(R.string.cal_payload_size, it), color = TextMuted, fontSize = 11.sp) }
                            state.inspectionRecordCount?.takeIf { it > 0 }?.let { Text(stringResource(R.string.cal_record_count, it), color = TextMuted, fontSize = 11.sp) }
                            Text(stringResource(R.string.cal_checksum_validation, state.inspectionChecksumStatus), color = TextMuted, fontSize = 11.sp)
                            state.inspectionAddressRanges.forEach { range -> Text(range, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                            state.inspectionWarnings.forEach { warning -> Text("• $warning", color = CamaroAmber, fontSize = 10.sp) }
                        }
                        OutlinedButton(onClick = onExportFile, modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.Icon(Icons.Default.CloudUpload, contentDescription = null)
                            Text(stringResource(R.string.cal_export_file))
                        }
                        if (state.inspectionFormat == "BIN / raw binary") {
                            OutlinedButton(onClick = onImportComparisonFile, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.cal_import_second_bin))
                            }
                        } else {
                            Text(
                                stringResource(R.string.cal_binary_compare_limit),
                                color = TextMuted, fontSize = 10.sp
                            )
                        }
                        state.comparisonFileName?.let { comparisonName ->
                            HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                            Text(stringResource(R.string.cal_comparison_name, comparisonName), color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(state.comparisonSummary.orEmpty(), color = TextPrimary, fontSize = 12.sp)
                            Text(
                                stringResource(R.string.cal_comparison_byte_counts),
                                color = TextMuted, fontSize = 11.sp
                            )
                            state.comparisonRanges.forEach { range ->
                                Text(range, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }
                            if (state.comparisonIdentical == true) Text(stringResource(R.string.cal_images_identical), color = OkGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        TextButton(onClick = onClearFile) { Text(stringResource(R.string.cal_remove_files)) }
                    }
                }
            }
        }
        item {
            var mapMenuExpanded by remember { mutableStateOf(false) }
            val selectedMap = state.mapDefinitions.firstOrNull { it.id == state.selectedMapId }
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(stringResource(R.string.cal_table_editor),
                        color = CamaroAmber, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.cal_table_import_hint),
                        color = TextMuted, fontSize = 11.sp
                    )
                    OutlinedButton(
                        onClick = onImportDefinitions,
                        enabled = state.importedFilePath != null && state.inspectionFormat == "BIN / raw binary",
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.cal_import_table_definitions)) }
                    TextButton(onClick = onExportDefinitionTemplate, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_export_table_template))
                    }
                    if (state.mapDefinitions.isNotEmpty()) {
                        OutlinedButton(onClick = { mapMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selectedMap?.let { "${it.name} • ${it.rows} × ${it.columns}" }
                                ?: stringResource(R.string.cal_select_table))
                        }
                        DropdownMenu(expanded = mapMenuExpanded, onDismissRequest = { mapMenuExpanded = false }) {
                            state.mapDefinitions.forEach { definition ->
                                DropdownMenuItem(
                                    text = { Text("${definition.name} • ${definition.rows} × ${definition.columns}") },
                                    onClick = {
                                        mapMenuExpanded = false
                                        onSelectMap(definition.id)
                                    }
                                )
                            }
                        }
                        selectedMap?.let { definition ->
                            Text(
                                "${definition.id} • offset 0x${definition.fileOffset.toString(16).uppercase()} • ${definition.valueType} • ${definition.endian} • ×${definition.scale} + ${definition.bias} ${definition.unit}",
                                color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                            )
                            Text(
                                stringResource(R.string.cal_axis_summary, stringResource(R.string.cal_axis_x), localizedAxisDetails(definition.xAxis?.fileOffset, definition.xAxis?.count, definition.xAxis?.unit)),
                                color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                            )
                            Text(
                                stringResource(R.string.cal_axis_summary, stringResource(R.string.cal_axis_y), localizedAxisDetails(definition.yAxis?.fileOffset, definition.yAxis?.count, definition.yAxis?.unit)),
                                color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                        state.mapDefinitionsWarnings.forEach { warning ->
                            Text(warning, color = CamaroAmber, fontSize = 10.sp)
                        }
                        state.mapError?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFFF6B6B), fontSize = 11.sp) }
                        if (state.mapValues.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.cal_table_values), color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (state.mapDirty) (stringResource(R.string.cal_unsaved_edits))
                                    else (stringResource(R.string.cal_loaded)),
                                    color = if (state.mapDirty) CamaroAmber else OkGreen, fontSize = 10.sp
                                )
                            }
                            if (state.mapXAxisValues.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.cal_x_axis, selectedMap?.xAxis?.unit.orEmpty()),
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("X", modifier = Modifier.width(48.dp), color = CamaroAmber, fontSize = 10.sp)
                                    state.mapXAxisValues.forEach { axisValue ->
                                        Text(
                                            axisValue,
                                            modifier = Modifier.width(88.dp),
                                            color = CamaroAmber,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().height(320.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed(state.mapValues) { rowIndex, rowValues ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            state.mapYAxisValues.getOrNull(rowIndex) ?: "%02d".format(rowIndex),
                                            modifier = Modifier.width(48.dp),
                                            color = CamaroAmber,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        rowValues.forEachIndexed { columnIndex, value ->
                                            OutlinedTextField(
                                                value = value,
                                                onValueChange = { onCellChange(rowIndex, columnIndex, it) },
                                                modifier = Modifier.width(88.dp).height(58.dp),
                                                singleLine = true,
                                                textStyle = TextStyle(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 10.sp,
                                                    color = TextPrimary
                                                ),
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                            )
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = onDiscardMap,
                                    enabled = state.mapDirty,
                                    modifier = Modifier.weight(1f)
                                ) { Text(stringResource(R.string.cal_discard_changes)) }
                                Button(
                                    onClick = onApplyMap,
                                    enabled = state.mapDirty,
                                    modifier = Modifier.weight(1f)
                                ) { Text(stringResource(R.string.cal_apply_to_file)) }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.cal_table_editing_notice),
                        color = TextMuted, fontSize = 10.sp
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Icon(Icons.Default.Security, contentDescription = null, tint = CamaroAmber)
                        Text(stringResource(R.string.cal_ecu_memory_read_write),
                            color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        stringResource(R.string.cal_offline_editor_scope),
                        color = TextMuted, fontSize = 12.sp
                    )
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_ecu_write_unavailable))
                    }
                    Text(
                        stringResource(R.string.cal_no_calibration_write),
                        color = CamaroAmber, fontSize = 12.sp
                    )
                }
            }
        }
        if (state.message.isNotBlank()) {
            item { Text(state.message, color = CamaroAmber, fontSize = 12.sp) }
        }
    }
}
