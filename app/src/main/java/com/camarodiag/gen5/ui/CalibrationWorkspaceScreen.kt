package com.camarodiag.gen5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import android.app.Activity
import android.content.ContextWrapper
import android.security.KeyChain
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.camarodiag.gen5.data.obd.DoIpClientTls
import com.camarodiag.gen5.data.obd.DoIpClientCertificateSelection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.camarodiag.gen5.R
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.camarodiag.gen5.ui.theme.WarnRed
import com.camarodiag.gen5.data.obd.CamaroCalibrationCatalog
import com.camarodiag.gen5.data.obd.DoIpDirectRead
import com.camarodiag.gen5.data.obd.EcuWriteConsentFlow
import com.camarodiag.gen5.data.obd.DoIpMemoryBackup
import com.camarodiag.gen5.data.obd.J2534BridgePairingCodeFormat
import com.camarodiag.gen5.data.obd.Lfx2012ControllerReferences
import com.camarodiag.gen5.data.obd.Lfx2012FlexFuelPreflight
import com.camarodiag.gen5.data.obd.Lfx2012FuelReadOnly
import com.camarodiag.gen5.data.obd.Lfx2012FuelSessionHistory
import com.camarodiag.gen5.data.obd.Lfx2012ProgrammingAudit
import com.camarodiag.gen5.data.obd.EcuBenchExpertPlan
import com.camarodiag.gen5.data.obd.CamaroDiagConnectorWiringGuide
import com.camarodiag.gen5.data.obd.EcuBackupManifest
import com.camarodiag.gen5.data.obd.GmlanIdentityEvidence
import com.camarodiag.gen5.data.obd.EcuBackupCoverage
import com.camarodiag.gen5.data.obd.CalibrationAxisDefinition
import com.camarodiag.gen5.data.obd.CalibrationMapDefinition
import com.camarodiag.gen5.data.obd.ObdbCamaroSignal
import com.camarodiag.gen5.data.obd.CamaroSignalDefinition
import com.camarodiag.gen5.data.obd.CanTraceAnalysisReport
import com.camarodiag.gen5.domain.model.VehicleProfile
import com.camarodiag.gen5.domain.model.VehicleVinComparison

sealed interface ArchiveVerification {
    data object Reading : ArchiveVerification
    data object Failed : ArchiveVerification
    data class Verified(val manifest: EcuBackupManifest.Manifest) : ArchiveVerification
}

data class CalibrationWorkspaceState(
    val isReading: Boolean = false,
    val isGmlanReading: Boolean = false,
    val isFuelSnapshotReading: Boolean = false,
    val fuelReadOnlySnapshot: Lfx2012FuelReadOnly.Snapshot? = null,
    val fuelHistoryCount: Int = 0,
    val fuelHistorySummary: Lfx2012FuelSessionHistory.Report? = null,
    val fuelSnapshotError: String = "",
    val gmlanIdentity: GmlanIdentityEvidence? = null,
    val gmlanIdentityFailed: Boolean = false,
    val ecuVinResponse: String = "",
    /** Origin of the captured identity; never infer origin from the CURRENT adapter state. */
    val ecuIdentityFromSimulation: Boolean = false,
    val vinComparison: VehicleVinComparison? = null,
    val vinModelYear: Int? = null,
    val ecuNameResponse: String = "",
    val respondingEcuAddresses: List<String> = emptyList(),
    val calibrationIdResponse: String = "",
    val cvnResponse: String = "",
    val message: String = "",
    val isUdsReading: Boolean = false,
    val udsDidResponse: String = "",
    val udsDtcResponse: String = "",
    val udsMessage: String = "",
    val doIpReading: Boolean = false,
    val doIpGatewayReading: Boolean = false,
    val doIpDiscoveredVin: String = "",
    val doIpGatewayHost: String = "",
    val doIpGatewayIdentity: DoIpDirectRead.Gateway? = null,
    val doIpGatewayResult: String = "",
    val doIpResult: String = "",
    val doIpTrace: List<String> = emptyList(),
    val doIpBackupScope: DoIpMemoryBackup.Scope? = null,
    val doIpBackupBytes: Int = 0,
    val doIpLiveSamples: List<DoIpMonitorRow> = emptyList(),
    val doIpMonitorReport: DoIpMonitorReport? = null,
    val isJ2534BridgeConnecting: Boolean = false,
    val isJ2534BridgeConnected: Boolean = false,
    val isJ2534MemoryReadAvailable: Boolean = false,
    val j2534BridgeMessage: String = "",
    val isJ2534MemoryCapturing: Boolean = false,
    val j2534MemoryCaptureBytesRead: Long = 0L,
    val j2534MemoryCaptureTotalBytes: Long = 0L,
    val j2534MemoryCaptureMessage: String = "",
    val nativeSignals: List<CamaroSignalDefinition> = emptyList(),
    val nativeSignalMessage: String = "",
    val obdbSignals: List<ObdbCamaroSignal> = emptyList(),
    val isObdbSignalReading: Boolean = false,
    val obdbSignalValue: String = "",
    val obdbSignalMessage: String = "",
    val isTraceAnalyzing: Boolean = false,
    val traceMessage: String = "",
    val canTraceReport: CanTraceAnalysisReport? = null,
    val archiveVerification: ArchiveVerification? = null,
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
    val partialWriteSummary: String? = null,
    val partialWriteChangedBytes: Long? = null,
    val partialWriteSectorAddresses: List<String> = emptyList(),
    val activeFilePath: String? = null,
    val mapDefinitions: List<CalibrationMapDefinition> = emptyList(),
    val selectedMapId: String? = null,
    val mapValues: List<List<String>> = emptyList(),
    val mapXAxisValues: List<String> = emptyList(),
    val mapYAxisValues: List<String> = emptyList(),
    val mapDefinitionsWarnings: List<String> = emptyList(),
    val mapDirty: Boolean = false,
    val mapError: String? = null
) {
    val hasMode09Identification: Boolean
        get() = ecuVinResponse.isNotBlank() || ecuNameResponse.isNotBlank() ||
            calibrationIdResponse.isNotBlank() || cvnResponse.isNotBlank()

    val canExportIdentification: Boolean
        get() = !isReading && !isGmlanReading && (hasMode09Identification || gmlanIdentity != null)
}


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
private fun localizedAxisSummary(
    axisLabelResource: Int,
    axis: CalibrationAxisDefinition?
): String {
    val axisLabel = stringResource(axisLabelResource)
    val details = if (axis == null) {
        stringResource(R.string.cal_axis_undefined)
    } else {
        stringResource(
            R.string.cal_axis_values,
            axis.fileOffset.toString(16).uppercase(),
            axis.count,
            axis.unit.orEmpty()
        )
    }
    return stringResource(R.string.cal_axis_summary, axisLabel, details)
}

@Composable
private fun localizedInspectionFormat(format: String): String = stringResource(
    when (format) {
        "RAW_BINARY" -> R.string.cal_format_raw_binary
        "INTEL_HEX" -> R.string.cal_format_intel_hex
        "MOTOROLA_S_RECORD" -> R.string.cal_format_motorola_srecord
        "ZIP_ARCHIVE" -> R.string.cal_format_zip_archive
        "EXTERNAL_DEFINITION" -> R.string.cal_format_external_definition
        "EXTERNAL_TUNE_PROJECT" -> R.string.cal_format_external_tune_project
        else -> R.string.cal_format_unknown
    }
)

data class DoIpConsoleCommand(val host: String, val tester: String, val ecu: String,
    val tls: Boolean, val payload: String, val vehicle: VehicleProfile, val generation: Long,
    val certificate: DoIpClientCertificateSelection? = null,
    val options: DoIpDirectRead.SessionOptions = DoIpDirectRead.SessionOptions())

// Keep static UI copy in Android resources so this screen follows the active system locale.
@Composable
fun CalibrationWorkspaceScreen(
    state: CalibrationWorkspaceState,
    consoleBusy: Boolean,
    vehicleLabel: String,
    ecmType: String,
    isConnected: Boolean,
    canReadEnhancedUds: Boolean,
    availableUdsReadProfiles: (String, String) -> Set<Int>,
    onReadIdentification: () -> Unit,
    onReadGmlanIdentification: () -> Unit,
    onReadLfx2012FuelSnapshot: () -> Unit,
    onExportLfx2012FuelHistory: () -> Unit,
    onExportIdentification: () -> Unit,
    onCreateVehicleFromEcuVin: (String) -> Unit,
    onSyncVehicleVinFromEcu: (String) -> Unit,
    onReadUdsDid: (String, String, String, String) -> Unit,
    onReadUdsDtcs: (String, String, String) -> Unit,
    onReadDirectDoIp: (String, String, String, String, Boolean, DoIpDirectRead.Operation, DoIpClientCertificateSelection?, DoIpDirectRead.SessionOptions) -> Unit,
    doIpAccountUid: String?,
    doIpContextGeneration: Long,
    onConfirmDoIpCommand: (DoIpConsoleCommand, EcuWriteConsentFlow.Approval) -> Unit,
    onInspectDoIpGateway: (String, DoIpDirectRead.GatewayOperation, String?) -> Unit,
    onCancelDirectDoIp: () -> Unit,
    onMonitorDirectDoIp: (String, String, String, String, Boolean, Int, Int, DoIpDirectRead.Operation, DoIpClientCertificateSelection?, DoIpDirectRead.SessionOptions) -> Unit,
    onExportDoIpMonitoring: (DoIpMonitorReport) -> Unit,
    onImportDoIpBackupProfile: () -> Unit,
    onVerifyDoIpBackup: (DoIpMemoryBackup.Scope) -> Unit,
    onCaptureDoIpBackup: (String, String, String, Boolean, DoIpMemoryBackup.Scope, DoIpClientCertificateSelection?, DoIpDirectRead.SessionOptions) -> Unit,
    onConnectJ2534Bridge: (String, String, String, String, String, String) -> Unit,
    onDisconnectJ2534Bridge: () -> Unit,
    onCaptureJ2534Memory: (String, String, Int, EcuBackupCoverage.Region) -> Unit,
    onReadObdbSignal: (String, String) -> Unit,
    vehicle: VehicleProfile,
    onImportFile: () -> Unit,
    onVerifyEcuBackup: () -> Unit,
    onImportTrace: () -> Unit,
    onImportComparisonFile: () -> Unit,
    onPreviewPartialWrite: (Long, Int, Int) -> Unit,
    onExportExternalDumpPair: (EcuBenchExpertPlan.AccessMethod, EcuBenchExpertPlan.MemoryRegion, Long) -> Unit,
    onImportDefinitions: () -> Unit,
    onExportDefinitionTemplate: () -> Unit,
    onSelectMap: (String) -> Unit,
    onCellChange: (Int, Int, String) -> Unit,
    onApplyMap: () -> Unit,
    onDiscardMap: () -> Unit,
    onExportFile: () -> Unit,
    onClearFile: () -> Unit
) {
    var doIpHost by remember(vehicle) { mutableStateOf("") }
    var doIpDiscoveryEid by remember(vehicle) { mutableStateOf("") }
    var doIpDiscoveryInterface by remember(vehicle) { mutableStateOf("") }
    var doIpDiscoveryAdvanced by remember(vehicle) { mutableStateOf(false) }
    var doIpNetworkMenu by remember(vehicle) { mutableStateOf(false) }
    var doIpNetworkInterfaces by remember(vehicle) { mutableStateOf(emptyList<DoIpDirectRead.DiscoveryInterface>()) }
    var doIpNetworkLoading by remember(vehicle) { mutableStateOf(false) }
    var doIpNetworkError by remember(vehicle) { mutableStateOf(false) }
    val doIpNetworkScope = rememberCoroutineScope()
    LaunchedEffect(state.doIpGatewayHost, state.doIpDiscoveredVin) {
        if (doIpHost.isBlank() && state.doIpDiscoveredVin.isNotBlank() &&
            state.doIpDiscoveredVin == vehicle.vin.uppercase() && state.doIpGatewayHost.isNotBlank()) {
            doIpHost = state.doIpGatewayHost
        }
    }
    var doIpTester by remember(vehicle) { mutableStateOf("") }
    var doIpEcu by remember(vehicle) { mutableStateOf("") }
    var doIpInventoryAddresses by remember(vehicle) { mutableStateOf("") }
    LaunchedEffect(state.doIpBackupScope, vehicle.vin) {
        val scope = state.doIpBackupScope
        if (scope != null && scope.vin == vehicle.vin.uppercase()) {
            // Import explicitly selects the logical addresses in this ECU profile.
            doIpTester = "%04X".format(scope.tester)
            doIpEcu = "%04X".format(scope.ecu)
        }
    }
    var doIpDid by remember(vehicle) { mutableStateOf("F190") }
    var doIpDtcMask by remember(vehicle) { mutableStateOf("FF") }
    var doIpMultiLayout by remember(vehicle) { mutableStateOf("") }
    var doIpWwhSeverity by remember(vehicle) { mutableStateOf("FF") }
    var doIpSeverityMask by remember(vehicle) { mutableStateOf("FF") }
    var doIpWwhReadiness by remember(vehicle) { mutableStateOf("") }
    var doIpDetailDtc by remember(vehicle) { mutableStateOf("") }
    var doIpDetailRecord by remember(vehicle) { mutableStateOf("01") }
    var doIpCommand by remember(vehicle) { mutableStateOf("") }
    var pendingDoIpCommand by remember(vehicle, doIpContextGeneration) { mutableStateOf<DoIpConsoleCommand?>(null) }
    var doIpConsentStep by remember(vehicle, doIpContextGeneration) { mutableStateOf(EcuWriteConsentFlow.Step.WARNING) }
    var doIpTls by remember(vehicle) { mutableStateOf(true) }
    var doIpOptionsExpanded by remember(vehicle) { mutableStateOf(false) }
    var doIpTimeout by remember(vehicle) { mutableStateOf("5000") }
    var doIpP2 by remember(vehicle) { mutableStateOf("5000") }
    var doIpP2Star by remember(vehicle) { mutableStateOf("5000") }
    var doIpRouting by remember(vehicle) { mutableStateOf("00") }
    var doIpOem by remember(vehicle) { mutableStateOf("") }
    val doIpOptions = remember(doIpTimeout, doIpP2, doIpP2Star, doIpRouting, doIpOem) {
        runCatching { DoIpDirectRead.SessionOptions.parse(doIpTimeout, doIpP2, doIpP2Star, doIpRouting, doIpOem) }.getOrNull()
    }
    val certContext = LocalContext.current
    val certActivity = remember(certContext) {
        generateSequence(certContext) { (it as? ContextWrapper)?.baseContext?.takeUnless { base -> base === it } }
            .filterIsInstance<Activity>().firstOrNull()
    }
    val certCoroutine = rememberCoroutineScope()
    // Bind to the selected account/vehicle/host. A changed target cannot reuse a stale alias.
    var doIpCertificate by remember(doIpAccountUid, vehicle, doIpHost, doIpTls) {
        mutableStateOf<DoIpClientCertificateSelection?>(null)
    }
    var doIpCertificateBusy by remember(doIpAccountUid, vehicle, doIpHost, doIpTls) { mutableStateOf(false) }
    var doIpCertificateError by remember(doIpAccountUid, vehicle, doIpHost, doIpTls) { mutableStateOf(false) }
    var doIpSampleCount by remember(vehicle) { mutableStateOf("10") }
    var doIpInterval by remember(vehicle) { mutableStateOf("1000") }
    var pendingDoIpBackup by remember(vehicle) { mutableStateOf<DoIpMemoryBackup.Scope?>(null) }
    var udsRequestCanId by remember(vehicle) { mutableStateOf("") }
    var udsResponseCanId by remember(vehicle) { mutableStateOf("") }
    var udsDid by remember { mutableStateOf("F190") }
    var udsCanProtocol by remember { mutableStateOf("33") }
    var udsProfileMenuExpanded by remember { mutableStateOf(false) }
    var j2534Host by remember { mutableStateOf("192.168.1.100") }
    var j2534Port by remember { mutableStateOf("35001") }
    var j2534Token by remember { mutableStateOf("") }
    var memoryReadAddressHex by remember { mutableStateOf("") }
    var memoryReadLengthText by remember { mutableStateOf("") }
    var memoryReadSessionExtended by remember { mutableStateOf(false) }
    var memoryReadRegion by remember { mutableStateOf(EcuBackupCoverage.Region.OTHER) }
    var memoryReadRegionMenuExpanded by remember { mutableStateOf(false) }
    var expertExpanded by remember { mutableStateOf(false) }
    var expertControllerFamily by remember { mutableStateOf("") }
    var expertChipMarking by remember { mutableStateOf("") }
    var expertProgrammerName by remember { mutableStateOf("") }
    var patchStartHex by remember { mutableStateOf("") }
    var patchEraseBytes by remember { mutableStateOf("") }
    var patchProgramBytes by remember { mutableStateOf("") }
    var expertAccessMethod by remember { mutableStateOf(EcuBenchExpertPlan.AccessMethod.BOOT_BAM) }
    var expertMemoryRegion by remember { mutableStateOf(EcuBenchExpertPlan.MemoryRegion.PFLASH) }
    var expertAccessMenuExpanded by remember { mutableStateOf(false) }
    var expertRegionMenuExpanded by remember { mutableStateOf(false) }
    var obdbSignalSearch by remember { mutableStateOf("") }
    var obdbSignalMenuExpanded by remember { mutableStateOf(false) }
    var showVinMismatchDialog by remember { mutableStateOf(false) }
    var showVinSyncConfirmation by remember { mutableStateOf(false) }
    LaunchedEffect(state.vinComparison) {
        if (state.vinComparison?.matches == false) showVinMismatchDialog = true
    }
    var selectedObdbSignalId by remember { mutableStateOf("") }
    val selectedObdbSignal = state.obdbSignals.firstOrNull { it.id == selectedObdbSignalId }
        ?: state.obdbSignals.firstOrNull()
    val compatibleUdsProfiles = availableUdsReadProfiles(udsRequestCanId, udsResponseCanId)
    val profileAvailable = udsCanProtocol.toIntOrNull() in compatibleUdsProfiles
    val signalProfiles = selectedObdbSignal?.let { signal ->
        availableUdsReadProfiles(signal.requestCanId?.toString(16).orEmpty(),
            signal.responseCanId?.toString(16).orEmpty()).intersect(setOf(33, 35))
    }.orEmpty()
    val signalProfile = udsCanProtocol.toIntOrNull()?.takeIf { it in signalProfiles }
        ?: signalProfiles.singleOrNull()
    LaunchedEffect(compatibleUdsProfiles, canReadEnhancedUds) {
        if (canReadEnhancedUds && !profileAvailable && compatibleUdsProfiles.size == 1)
            udsCanProtocol = compatibleUdsProfiles.single().toString()
    }
    val canIdsMatchProfile = J2534CanProfileValidation.isValid(
        udsRequestCanId, udsResponseCanId, udsCanProtocol
    )
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
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.cal_doip_title), color = CamaroAmber, fontWeight = FontWeight.Bold)
                    val idle = !consoleBusy && !state.doIpReading && !state.isUdsReading && !state.isObdbSignalReading &&
                        !state.isJ2534MemoryCapturing
                    OutlinedTextField(doIpHost, { doIpHost = it.trim().take(64) },
                        enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.cal_doip_host)) })
                    val vinReady = vehicle.vin.uppercase().matches(Regex("[A-HJ-NPR-Z0-9]{17}"))
                    if (doIpHost.isNotBlank()) {
                        OutlinedTextField(doIpDiscoveryEid, { doIpDiscoveryEid = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(12) },
                            enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.cal_doip_discovery_eid)) })
                    }
                    OutlinedButton(onClick = { onInspectDoIpGateway(doIpHost, DoIpDirectRead.GatewayOperation.DISCOVER,
                        doIpDiscoveryEid.takeIf { doIpHost.isNotBlank() && it.isNotEmpty() }) },
                        enabled = idle && (doIpHost.isNotBlank() || vinReady) &&
                            (doIpHost.isBlank() || doIpDiscoveryEid.length in setOf(0, 12)), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (doIpHost.isBlank()) R.string.cal_doip_discover_by_vin
                            else R.string.cal_doip_discover))
                    }
                    if (doIpHost.isBlank()) {
                        TextButton(onClick = { doIpDiscoveryAdvanced = !doIpDiscoveryAdvanced }, enabled = idle) {
                            Text(stringResource(R.string.cal_doip_discovery_advanced))
                        }
                        if (doIpDiscoveryAdvanced) {
                            OutlinedButton(onClick = { onInspectDoIpGateway("", DoIpDirectRead.GatewayOperation.LISTEN_IPV4, null) },
                                enabled = idle && vinReady, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.cal_doip_listen_ipv4))
                            }
                            OutlinedTextField(doIpDiscoveryInterface, { doIpDiscoveryInterface = it.filter(Char::isDigit).take(10) },
                                enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                label = { Text(stringResource(R.string.cal_doip_ipv6_interface)) })
                            Box {
                                OutlinedButton(onClick = {
                                    doIpNetworkLoading = true
                                    doIpNetworkError = false
                                    doIpNetworkInterfaces = emptyList()
                                    doIpNetworkScope.launch {
                                        try {
                                            doIpNetworkInterfaces = DoIpDirectRead.availableDiscoveryInterfaces()
                                            doIpNetworkMenu = true
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { doIpNetworkError = true }
                                        finally { doIpNetworkLoading = false }
                                    }
                                }, enabled = idle && !doIpNetworkLoading, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.cal_doip_select_interface))
                                }
                                DropdownMenu(expanded = doIpNetworkMenu, onDismissRequest = { doIpNetworkMenu = false }) {
                                    if (doIpNetworkInterfaces.isEmpty()) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.cal_doip_no_interfaces)) },
                                            onClick = { doIpNetworkMenu = false })
                                    }
                                    doIpNetworkInterfaces.forEach { network ->
                                        DropdownMenuItem(text = { Text("${network.name} (#${network.index})") },
                                            onClick = {
                                                doIpDiscoveryInterface = network.index.toString()
                                                doIpNetworkMenu = false
                                            })
                                    }
                                }
                            }
                            if (doIpNetworkError)
                                Text(stringResource(R.string.cal_doip_interface_error), color = WarnRed, fontSize = 12.sp)
                            val interfaceReady = (doIpDiscoveryInterface.toIntOrNull() ?: 0) > 0
                            OutlinedButton(onClick = { onInspectDoIpGateway(doIpDiscoveryInterface, DoIpDirectRead.GatewayOperation.DISCOVER_IPV6, null) },
                                enabled = idle && vinReady && interfaceReady, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.cal_doip_discover_ipv6))
                            }
                            OutlinedButton(onClick = { onInspectDoIpGateway(doIpDiscoveryInterface, DoIpDirectRead.GatewayOperation.LISTEN_IPV6, null) },
                                enabled = idle && vinReady && interfaceReady, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.cal_doip_listen_ipv6))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onInspectDoIpGateway(doIpHost, DoIpDirectRead.GatewayOperation.STATUS, null) },
                            enabled = idle && doIpHost.isNotBlank(), modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_doip_gateway_status_button))
                        }
                        OutlinedButton(onClick = { onInspectDoIpGateway(doIpHost, DoIpDirectRead.GatewayOperation.POWER, null) },
                            enabled = idle && doIpHost.isNotBlank(), modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_doip_power))
                        }
                    }
                    if ((state.doIpGatewayHost == doIpHost || doIpHost.isBlank()) && state.doIpGatewayResult.isNotBlank())
                        Text(state.doIpGatewayResult, color = TextPrimary, fontSize = 12.sp)
                    OutlinedTextField(doIpTester, { doIpTester = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(4) },
                        enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.cal_doip_tester)) })
                    OutlinedTextField(doIpEcu, { doIpEcu = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(4) },
                        enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.cal_doip_ecu)) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = doIpTls, onCheckedChange = { doIpTls = it }, enabled = idle)
                        Text(stringResource(R.string.cal_doip_tls))
                    }
                    if (doIpTls) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                try { certActivity?.startActivity(KeyChain.createInstallIntent()) }
                                catch (_: Exception) { doIpCertificateError = true }
                            }, enabled = idle && !doIpCertificateBusy && certActivity != null,
                                modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.cal_doip_cert_install))
                            }
                            OutlinedButton(onClick = {
                                val activity = certActivity ?: return@OutlinedButton
                                val host = doIpHost
                                val vin = vehicle.vin.uppercase()
                                doIpCertificateBusy = true
                                doIpCertificateError = false
                                // Clear the old choice: a cancelled/failed new selection must not reuse it.
                                doIpCertificate = null
                                try {
                                    KeyChain.choosePrivateKeyAlias(activity, { alias ->
                                        certCoroutine.launch {
                                            try {
                                                if (alias != null && alias != "android:alias-selection-denied")
                                                    doIpCertificate = DoIpClientTls.select(certContext, alias, host, vin)
                                                else doIpCertificateError = true
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) { doIpCertificateError = true }
                                            finally { doIpCertificateBusy = false }
                                        }
                                    }, null, null, host, 3496, null)
                                } catch (_: Exception) {
                                    doIpCertificateBusy = false
                                    doIpCertificateError = true
                                }
                            }, enabled = idle && !doIpCertificateBusy && certActivity != null && vinReady && doIpHost.isNotBlank(),
                                modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.cal_doip_cert_select))
                            }
                        }
                        doIpCertificate?.let { selected ->
                            Text(stringResource(R.string.cal_doip_cert_selected, selected.alias, selected.fingerprint),
                                fontSize = 12.sp)
                            TextButton(onClick = { doIpCertificate = null }, enabled = idle && !doIpCertificateBusy) {
                                Text(stringResource(R.string.cal_doip_cert_clear))
                            }
                        } ?: Text(stringResource(R.string.cal_doip_cert_none), fontSize = 12.sp)
                        if (doIpCertificateError) {
                            Text(stringResource(R.string.cal_doip_tls_error), color = WarnRed, fontSize = 12.sp)
                            TextButton(onClick = { doIpCertificate = null; doIpCertificateError = false }, enabled = idle) {
                                Text(stringResource(R.string.cal_doip_cert_clear))
                            }
                        }
                    }
                    TextButton(onClick = { doIpOptionsExpanded = !doIpOptionsExpanded }, enabled = idle) {
                        Text(stringResource(R.string.cal_doip_advanced_session))
                    }
                    if (doIpOptionsExpanded) {
                        OutlinedTextField(doIpTimeout, { doIpTimeout = it.filter(Char::isDigit).take(5) },
                            enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text(stringResource(R.string.cal_doip_total_ms)) })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(doIpP2, { doIpP2 = it.filter(Char::isDigit).take(5) },
                                enabled = idle, singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                label = { Text(stringResource(R.string.cal_doip_p2_ms)) })
                            OutlinedTextField(doIpP2Star, { doIpP2Star = it.filter(Char::isDigit).take(5) },
                                enabled = idle, singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                label = { Text(stringResource(R.string.cal_doip_p2_star_ms)) })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(doIpRouting, { doIpRouting = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(2) },
                                enabled = idle, singleLine = true, modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.cal_doip_routing_type)) })
                            OutlinedTextField(doIpOem, { doIpOem = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(8) },
                                enabled = idle, singleLine = true, modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.cal_doip_routing_oem)) })
                        }
                    }
                    if (doIpOptions == null)
                        Text(stringResource(R.string.cal_doip_invalid_session), color = WarnRed, fontSize = 12.sp)
                    OutlinedTextField(doIpDid, { doIpDid = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(4) },
                        enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.cal_uds_did)) })
                    val targetReady = idle && doIpOptions != null && !doIpCertificateBusy && !doIpCertificateError && (doIpHost.isNotBlank() || vinReady) && doIpTester.length == 4 &&
                        doIpEcu.length == 4 && doIpTester != doIpEcu
                    OutlinedTextField(doIpInventoryAddresses, { doIpInventoryAddresses =
                        it.uppercase().filter { c -> c in "0123456789ABCDEF," }.take(79) },
                        enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.cal_doip_inventory_addresses)) })
                    val inventoryAddresses = runCatching {
                        DoIpDirectRead.parseInventoryAddresses(doIpInventoryAddresses)
                    }.getOrNull()
                    OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester,
                        "%04X".format(requireNotNull(inventoryAddresses).first()), doIpInventoryAddresses, doIpTls,
                        DoIpDirectRead.Operation.INVENTORY, doIpCertificate, requireNotNull(doIpOptions)) },
                        enabled = idle && vinReady && doIpOptions != null && !doIpCertificateBusy &&
                            !doIpCertificateError && doIpTester.length == 4 &&
                            inventoryAddresses != null && doIpTester.toIntOrNull(16) !in inventoryAddresses,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_doip_inventory))
                    }
                    OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester,
                        "%04X".format(requireNotNull(inventoryAddresses).first()), doIpInventoryAddresses, doIpTls,
                        DoIpDirectRead.Operation.OBD_INVENTORY, doIpCertificate, requireNotNull(doIpOptions)) },
                        enabled = idle && vinReady && doIpOptions != null && !doIpCertificateBusy &&
                            !doIpCertificateError && doIpTester.length == 4 &&
                            inventoryAddresses != null && doIpTester.toIntOrNull(16) !in inventoryAddresses,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_doip_obd_inventory))
                    }
                    Button(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpDid, doIpTls, DoIpDirectRead.Operation.VIN, doIpCertificate, requireNotNull(doIpOptions)) },
                        enabled = targetReady, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_doip_read_vin))
                    }
                    OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpDid, doIpTls, DoIpDirectRead.Operation.DID, doIpCertificate, requireNotNull(doIpOptions)) },
                        enabled = targetReady && vinReady && doIpDid.length == 4, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_read_uds_did))
                    }
                    OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpDid,
                        doIpTls, DoIpDirectRead.Operation.SCALING_DID, doIpCertificate, requireNotNull(doIpOptions)) },
                        enabled = targetReady && vinReady && doIpDid.length == 4, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_doip_scaling_read))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, "F810", doIpTls,
                            DoIpDirectRead.Operation.OBD_IDENTIFY, doIpCertificate, requireNotNull(doIpOptions)) },
                            enabled = targetReady && vinReady, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_doip_obd_identify))
                        }
                        OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpDid, doIpTls,
                            DoIpDirectRead.Operation.OBD_DID, doIpCertificate, requireNotNull(doIpOptions)) },
                            enabled = targetReady && vinReady && doIpDid.length == 4 &&
                                doIpDid.toIntOrNull(16)?.let(DoIpDirectRead::isObdDid) == true, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_doip_obd_read))
                        }
                    }
                    OutlinedTextField(doIpMultiLayout, { doIpMultiLayout = it.uppercase().filter { c -> c in "0123456789ABCDEF:, " }.take(383) },
                        enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.cal_doip_multi_layout)) })
                    val multiLayout = remember(doIpMultiLayout) {
                        runCatching { com.camarodiag.gen5.data.obd.DoIpMultiDid.parse(doIpMultiLayout) }.getOrNull()
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpMultiLayout,
                            doIpTls, DoIpDirectRead.Operation.MULTI_DID, doIpCertificate, requireNotNull(doIpOptions)) },
                            enabled = targetReady && vinReady && multiLayout != null, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_doip_multi_read))
                        }
                        OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpMultiLayout,
                            doIpTls, DoIpDirectRead.Operation.OBD_MULTI_DID, doIpCertificate, requireNotNull(doIpOptions)) },
                            enabled = targetReady && vinReady && multiLayout?.all { DoIpDirectRead.isObdDid(it.did) } == true,
                            modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cal_doip_multi_obd_read)) }
                    }
                    OutlinedTextField(doIpDtcMask, { doIpDtcMask = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(2) },
                        enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.cal_doip_dtc_mask)) })
                    OutlinedTextField(doIpSeverityMask, { doIpSeverityMask = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(2) },
                        enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.cal_doip_severity_mask)) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((operation, label) in listOf(DoIpDirectRead.Operation.SEVERITY_COUNT to R.string.cal_doip_severity_count,
                            DoIpDirectRead.Operation.SEVERITY_LIST to R.string.cal_doip_severity_list)) {
                            OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu,
                                doIpSeverityMask + doIpDtcMask, doIpTls, operation, doIpCertificate, requireNotNull(doIpOptions)) },
                                enabled = targetReady && vinReady && doIpSeverityMask.length == 2 && doIpDtcMask.length == 2,
                                modifier = Modifier.weight(1f)) { Text(stringResource(label)) }
                        }
                    }
                    OutlinedTextField(doIpWwhSeverity, { doIpWwhSeverity = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(2) },
                        enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.cal_doip_wwh_severity)) })
                    OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpWwhSeverity + doIpDtcMask,
                        doIpTls, DoIpDirectRead.Operation.OBD_WWH_MASK, doIpCertificate, requireNotNull(doIpOptions)) },
                        enabled = targetReady && vinReady && doIpWwhSeverity.length == 2 && doIpDtcMask.length == 2,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cal_doip_wwh_mask)) }
                    OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, "", doIpTls,
                        DoIpDirectRead.Operation.OBD_WWH_PERMANENT, doIpCertificate, requireNotNull(doIpOptions)) },
                        enabled = targetReady && vinReady, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_doip_wwh_permanent))
                    }
                    OutlinedTextField(doIpWwhReadiness, { doIpWwhReadiness = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(2) },
                        enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.cal_doip_wwh_readiness_group)) })
                    OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpWwhReadiness, doIpTls,
                        DoIpDirectRead.Operation.OBD_WWH_READINESS, doIpCertificate, requireNotNull(doIpOptions)) },
                        enabled = targetReady && vinReady && doIpWwhReadiness.length == 2, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_doip_wwh_readiness))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpDtcMask, doIpTls, DoIpDirectRead.Operation.DTC, doIpCertificate, requireNotNull(doIpOptions)) },
                            enabled = targetReady && vinReady && doIpDtcMask.length == 2, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_read_uds_dtcs))
                        }
                        OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpDtcMask, doIpTls, DoIpDirectRead.Operation.DTC_COUNT, doIpCertificate, requireNotNull(doIpOptions)) },
                            enabled = targetReady && vinReady && doIpDtcMask.length == 2, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_doip_count_button))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpDtcMask, doIpTls,
                            DoIpDirectRead.Operation.OBD_DTC, doIpCertificate, requireNotNull(doIpOptions)) },
                            enabled = targetReady && vinReady && doIpDtcMask.length == 2, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_doip_obd_dtcs))
                        }
                        OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpDtcMask, doIpTls,
                            DoIpDirectRead.Operation.OBD_DTC_COUNT, doIpCertificate, requireNotNull(doIpOptions)) },
                            enabled = targetReady && vinReady && doIpDtcMask.length == 2, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_doip_obd_count))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(doIpSampleCount, { doIpSampleCount = it.filter(Char::isDigit).take(2) },
                            enabled = idle, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text(stringResource(R.string.cal_doip_samples)) })
                        OutlinedTextField(doIpInterval, { doIpInterval = it.filter(Char::isDigit).take(4) },
                            enabled = idle, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text(stringResource(R.string.cal_doip_interval)) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(doIpDetailDtc, { doIpDetailDtc = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(6) },
                            enabled = idle, singleLine = true, modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.cal_doip_detail_dtc)) })
                        OutlinedTextField(doIpDetailRecord, { doIpDetailRecord = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(2) },
                            enabled = idle, singleLine = true, modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.cal_doip_detail_record)) })
                    }
                    OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpDetailDtc,
                        doIpTls, DoIpDirectRead.Operation.SEVERITY_INFO, doIpCertificate, requireNotNull(doIpOptions)) },
                        enabled = targetReady && vinReady && doIpDetailDtc.length == 6,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cal_doip_severity_info)) }
                    val detailReady = targetReady && vinReady && doIpDetailDtc.length == 6 &&
                        doIpDetailRecord.length == 2 && doIpDetailRecord != "00"
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu,
                            doIpDetailDtc + doIpDetailRecord, doIpTls, DoIpDirectRead.Operation.DTC_SNAPSHOT, doIpCertificate, requireNotNull(doIpOptions)) },
                            enabled = detailReady, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_doip_snapshot))
                        }
                        OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu,
                            doIpDetailDtc + doIpDetailRecord, doIpTls, DoIpDirectRead.Operation.DTC_EXTENDED, doIpCertificate, requireNotNull(doIpOptions)) },
                            enabled = detailReady, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_doip_extended))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu,
                            doIpDetailDtc + doIpDetailRecord, doIpTls, DoIpDirectRead.Operation.OBD_DTC_SNAPSHOT, doIpCertificate, requireNotNull(doIpOptions)) },
                            enabled = detailReady, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_doip_obd_snapshot))
                        }
                        OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu,
                            doIpDetailDtc + doIpDetailRecord, doIpTls, DoIpDirectRead.Operation.OBD_DTC_EXTENDED, doIpCertificate, requireNotNull(doIpOptions)) },
                            enabled = detailReady, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_doip_obd_extended))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, "", doIpTls,
                            DoIpDirectRead.Operation.SUPPORTED_DTCS, doIpCertificate, requireNotNull(doIpOptions)) },
                            enabled = targetReady && vinReady, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_doip_supported_dtcs))
                        }
                        OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, "", doIpTls,
                            DoIpDirectRead.Operation.SNAPSHOT_INDEX, doIpCertificate, requireNotNull(doIpOptions)) },
                            enabled = targetReady && vinReady, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cal_doip_snapshot_index))
                        }
                    }
                    for (pair in listOf(
                        listOf(DoIpDirectRead.Operation.FIRST_FAILED_DTC to R.string.cal_doip_first_failed,
                            DoIpDirectRead.Operation.FIRST_CONFIRMED_DTC to R.string.cal_doip_first_confirmed),
                        listOf(DoIpDirectRead.Operation.RECENT_FAILED_DTC to R.string.cal_doip_recent_failed,
                            DoIpDirectRead.Operation.RECENT_CONFIRMED_DTC to R.string.cal_doip_recent_confirmed))) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((operation, label) in pair) {
                                OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, "", doIpTls,
                                    operation, doIpCertificate, requireNotNull(doIpOptions)) },
                                    enabled = targetReady && vinReady, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(label))
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { onReadDirectDoIp(doIpHost, doIpTester, doIpEcu, "", doIpTls,
                        DoIpDirectRead.Operation.FAULT_COUNTERS, doIpCertificate, requireNotNull(doIpOptions)) },
                        enabled = targetReady && vinReady, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_doip_fault_counters))
                    }
                    OutlinedTextField(doIpCommand, { doIpCommand = it.take(12288) },
                        enabled = idle, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.cal_doip_command_payload)) })
                    OutlinedButton(onClick = {
                        doIpConsentStep = EcuWriteConsentFlow.Step.WARNING
                        pendingDoIpCommand = DoIpConsoleCommand(doIpHost, doIpTester, doIpEcu,
                            doIpTls, doIpCommand, vehicle, doIpContextGeneration, doIpCertificate, requireNotNull(doIpOptions))
                    }, enabled = targetReady && vinReady && runCatching { DoIpDirectRead.parseCommands(doIpCommand) }.isSuccess,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_doip_command_send))
                    }
                    pendingDoIpCommand?.let { pending ->
                        val sameContext = pending.vehicle == vehicle && pending.generation == doIpContextGeneration &&
                            pending.host == doIpHost && pending.tester == doIpTester && pending.ecu == doIpEcu &&
                            pending.tls == doIpTls && pending.certificate == doIpCertificate &&
                            pending.options == doIpOptions && pending.payload == doIpCommand && targetReady
                        AlertDialog(onDismissRequest = { pendingDoIpCommand = null },
                            title = { Text(stringResource(if (doIpConsentStep == EcuWriteConsentFlow.Step.WARNING)
                                R.string.ecu_write_warning_title else R.string.ecu_write_confirm_title)) },
                            text = {
                                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.cal_doip_command_target, pending.vehicle.vin,
                                        pending.host.ifBlank { stringResource(R.string.cal_doip_discover_by_vin) },
                                        pending.tester, pending.ecu, if (pending.tls) "TLS" else "TCP"))
                                    pending.certificate?.let { selected ->
                                        Text(stringResource(R.string.cal_doip_cert_selected, selected.alias, selected.fingerprint))
                                    }
                                    Text(stringResource(R.string.cal_doip_session_summary,
                                        pending.options.timeoutMs, pending.options.p2Ms, pending.options.p2StarMs,
                                        "%02X".format(pending.options.routingType), pending.options.routingOemHex.ifEmpty { "—" }))
                                    Text(pending.payload, fontFamily = FontFamily.Monospace)
                                    Text(stringResource(if (doIpConsentStep == EcuWriteConsentFlow.Step.WARNING)
                                        R.string.ecu_write_warning_message else R.string.ecu_write_confirm_message))
                                }
                            },
                            confirmButton = {
                                TextButton(enabled = sameContext, onClick = {
                                    if (doIpConsentStep == EcuWriteConsentFlow.Step.WARNING)
                                        doIpConsentStep = EcuWriteConsentFlow.afterWarningOk(doIpConsentStep)
                                    else {
                                        EcuWriteConsentFlow.approval(doIpConsentStep, true, sameContext)?.let {
                                            onConfirmDoIpCommand(pending, it)
                                        }
                                        pendingDoIpCommand = null
                                    }
                                }) { Text(stringResource(if (doIpConsentStep == EcuWriteConsentFlow.Step.WARNING)
                                    R.string.ecu_write_ok else R.string.ecu_write_yes)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingDoIpCommand = null }) {
                                    Text(stringResource(if (doIpConsentStep == EcuWriteConsentFlow.Step.WARNING)
                                        R.string.ecu_write_cancel else R.string.ecu_write_no))
                                }
                            })
                    }
                    val samples = doIpSampleCount.toIntOrNull()
                    val interval = doIpInterval.toIntOrNull()
                    OutlinedButton(onClick = {
                        if (samples != null && interval != null)
                            onMonitorDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpDid, doIpTls, samples, interval, DoIpDirectRead.Operation.MONITOR, doIpCertificate, requireNotNull(doIpOptions))
                    }, enabled = targetReady && vinReady && doIpDid.length == 4 &&
                        samples != null && samples in 1..30 && interval != null && interval in 250..5000,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_doip_monitor))
                    }
                    OutlinedButton(onClick = {
                        if (samples != null && interval != null)
                            onMonitorDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpDid, doIpTls, samples, interval, DoIpDirectRead.Operation.OBD_MONITOR, doIpCertificate, requireNotNull(doIpOptions))
                    }, enabled = targetReady && vinReady && doIpDid.length == 4 &&
                        doIpDid.toIntOrNull(16)?.let(DoIpDirectRead::isObdDid) == true &&
                        samples != null && samples in 1..30 && interval != null && interval in 250..5000,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_doip_obd_monitor))
                    }
                    val multiMonitorReady = multiLayout?.let {
                        runCatching { com.camarodiag.gen5.data.obd.DoIpMultiDid.validateMonitor(it) }.isSuccess
                    } == true
                    OutlinedButton(onClick = {
                        if (samples != null && interval != null)
                            onMonitorDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpMultiLayout, doIpTls,
                                samples, interval, DoIpDirectRead.Operation.MULTI_MONITOR, doIpCertificate, requireNotNull(doIpOptions))
                    }, enabled = targetReady && vinReady && multiMonitorReady &&
                        samples != null && samples in 1..30 && interval != null && interval in 250..5000,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_doip_multi_monitor))
                    }
                    OutlinedButton(onClick = {
                        if (samples != null && interval != null)
                            onMonitorDirectDoIp(doIpHost, doIpTester, doIpEcu, doIpMultiLayout, doIpTls,
                                samples, interval, DoIpDirectRead.Operation.OBD_MULTI_MONITOR, doIpCertificate, requireNotNull(doIpOptions))
                    }, enabled = targetReady && vinReady && multiMonitorReady &&
                        multiLayout?.all { DoIpDirectRead.isObdDid(it.did) } == true &&
                        samples != null && samples in 1..30 && interval != null && interval in 250..5000,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_doip_obd_multi_monitor))
                    }
                    HorizontalDivider()
                    OutlinedButton(onClick = onImportDoIpBackupProfile, enabled = idle && vinReady,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cal_doip_backup_profile))
                    }
                    state.doIpBackupScope?.let { scope ->
                        Text(stringResource(R.string.cal_doip_backup_scope,
                            scope.vin, "%04X".format(scope.ecu), "%08X".format(scope.start), scope.length),
                            color = TextPrimary, fontSize = 12.sp)
                        OutlinedButton(onClick = { onVerifyDoIpBackup(scope) },
                            enabled = idle && scope.vin == vehicle.vin.uppercase(),
                            modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.cal_doip_verify_backup))
                        }
                        val matches = scope.vin == vehicle.vin.uppercase() &&
                            doIpTester.toIntOrNull(16) == scope.tester && doIpEcu.toIntOrNull(16) == scope.ecu
                        OutlinedButton(onClick = { pendingDoIpBackup = scope },
                            enabled = targetReady && matches,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.cal_doip_backup))
                        }
                    }
                    pendingDoIpBackup?.let { scope ->
                        if (scope == state.doIpBackupScope && targetReady) {
                            AlertDialog(onDismissRequest = { pendingDoIpBackup = null },
                                title = { Text(stringResource(R.string.cal_doip_backup)) },
                                text = { Text(stringResource(R.string.cal_doip_backup_confirm,
                                    scope.vin, "%04X".format(scope.ecu), "%08X".format(scope.start), scope.length)) },
                                confirmButton = { TextButton(onClick = {
                                    pendingDoIpBackup = null
                                    onCaptureDoIpBackup(doIpHost, doIpTester, doIpEcu, doIpTls, scope, doIpCertificate, requireNotNull(doIpOptions))
                                }) { Text(stringResource(R.string.cal_doip_backup)) } },
                                dismissButton = { TextButton(onClick = { pendingDoIpBackup = null }) {
                                    Text(stringResource(R.string.cal_doip_cancel))
                                } })
                        }
                    }
                    if (state.doIpReading) {
                        if (state.doIpBackupBytes > 0 && state.doIpBackupScope != null) {
                            val total = state.doIpBackupScope.length * 2
                            LinearProgressIndicator(progress = { (state.doIpBackupBytes.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth())
                            Text("${state.doIpBackupBytes} / $total")
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = onCancelDirectDoIp, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.cal_doip_cancel))
                        }
                    }
                    if (state.doIpResult.isNotBlank())
                        Text(state.doIpResult, color = TextPrimary, fontFamily = FontFamily.Monospace)
                    if (state.doIpLiveSamples.isNotEmpty()) {
                        Text(stringResource(R.string.cal_doip_monitor_samples_received, state.doIpLiveSamples.size),
                            color = CamaroAmber, fontWeight = FontWeight.Bold)
                        state.doIpLiveSamples.forEach { sample ->
                            val data = if (sample.values.isEmpty()) sample.payloadHex else sample.values.joinToString("  ") {
                                "%04X=%s".format(it.did, it.payloadHex)
                            }
                            Text("#${sample.index}  ${sample.elapsedMs} ms  $data",
                                color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        if (idle && state.doIpMonitorReport == null)
                            Text(stringResource(R.string.cal_doip_monitor_partial), color = CamaroAmber, fontSize = 11.sp)
                    }
                    state.doIpMonitorReport?.let { report ->
                        OutlinedButton(onClick = { onExportDoIpMonitoring(report) },
                            enabled = idle, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.cal_doip_monitor_export))
                        }
                    }
                    if (state.doIpTrace.isNotEmpty() && state.doIpBackupBytes > 0)
                        Text(stringResource(R.string.cal_doip_recent_trace), color = TextMuted, fontSize = 10.sp)
                    state.doIpTrace.forEach { line ->
                        Text(line, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
        }
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
        if (vehicle.modelYear == 2012 && vehicle.engine.rpo == "LFX") {
            item {
                val unavailable = stringResource(R.string.obd_not_available)
                val recognizedEcuName = state.ecuNameResponse.trim()
                    .takeIf { it != unavailable && Lfx2012ControllerReferences.hasCandidateE39FamilyLabel(it) }
                val reportedCalibrationId = state.calibrationIdResponse.trim()
                    .takeIf { it.isNotBlank() && it != unavailable }
                val completeReadOnlyFingerprint =
                    Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(
                        Lfx2012ControllerReferences.ObservedIdentity(
                            ecuName = recognizedEcuName,
                            calibrationIds = listOf(state.calibrationIdResponse),
                            calibrationVerificationNumbers = listOf(state.cvnResponse),
                            simulated = state.ecuIdentityFromSimulation
                        )
                    )
                val responderAttribution =
                    Lfx2012ControllerReferences.classifyResponderAttribution(state.respondingEcuAddresses)
                val ecuVin = state.ecuVinResponse.trim().uppercase()
                    .takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) }
                val evidence = Lfx2012FlexFuelPreflight.Evidence(
                    controllerId = recognizedEcuName,
                    firmwareId = reportedCalibrationId,
                    verifiedFirmware = false,
                    originalBackupVerified = false,
                    recoveryVerified = false
                )
                val missing = Lfx2012FlexFuelPreflight.missingPreconditions(
                    vehicle = Lfx2012FlexFuelPreflight.Vehicle(
                        modelYear = vehicle.modelYear,
                        engineCode = vehicle.engine.rpo,
                        generation = 5,
                        selectedVin = vehicle.vin,
                        ecuVin = ecuVin
                    ),
                    evidence = evidence
                )
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.flexfuel_lfx2012_title),
                            color = CamaroAmber, fontWeight = FontWeight.Bold)

                        // Independent SAE fuel survey, never an ECU flash/
                        // FlexFuel activation command. Values are only from
                        // the physical ELM/STN or J2534 channel selected.
                        OutlinedButton(
                            onClick = onReadLfx2012FuelSnapshot,
                            enabled = (isConnected || state.isJ2534BridgeConnected) &&
                                !state.isFuelSnapshotReading
                        ) {
                            Text(stringResource(R.string.flexfuel_lfx2012_read_fuel))
                        }
                        val ethanolHistory = state.fuelHistorySummary?.readings
                            ?.firstOrNull { it.pid == Lfx2012FuelReadOnly.ETHANOL }
                        if (ethanolHistory != null &&
                            ethanolHistory.reportedCount > 0 &&
                            ethanolHistory.minimum != null &&
                            ethanolHistory.maximum != null
                        ) {
                            Text(
                                stringResource(
                                    R.string.flexfuel_lfx2012_history_range,
                                    ethanolHistory.reportedCount,
                                    requireNotNull(ethanolHistory.minimum),
                                    requireNotNull(ethanolHistory.maximum)
                                ),
                                color = TextMuted, fontSize = 11.sp
                            )
                        }
                        if (ethanolHistory != null && ethanolHistory.reportedCount > 0 &&
                            ethanolHistory.currentSnapshotPercent == null
                        ) {
                            Text(
                                stringResource(R.string.flexfuel_lfx2012_ethanol_no_current_measurement),
                                color = WarnRed, fontSize = 11.sp
                            )
                        }
                        state.fuelHistorySummary?.readings
                            ?.filter { it.pid != Lfx2012FuelReadOnly.ETHANOL &&
                                it.reportedCount > 0 &&
                                it.minimum != null && it.maximum != null }
                            ?.forEach { reading ->
                                val label = when (reading.pid) {
                                    Lfx2012FuelReadOnly.STFT_BANK_1 ->
                                        R.string.flexfuel_lfx2012_pid_stft_b1
                                    Lfx2012FuelReadOnly.LTFT_BANK_1 ->
                                        R.string.flexfuel_lfx2012_pid_ltft_b1
                                    Lfx2012FuelReadOnly.STFT_BANK_2 ->
                                        R.string.flexfuel_lfx2012_pid_stft_b2
                                    else -> R.string.flexfuel_lfx2012_pid_ltft_b2
                                }
                                Text(
                                    stringResource(
                                        R.string.flexfuel_lfx2012_history_trim_range,
                                        stringResource(label), reading.reportedCount,
                                        requireNotNull(reading.minimum),
                                        requireNotNull(reading.maximum)
                                    ),
                                    color = TextMuted, fontSize = 11.sp
                                )
                            }
                        if (state.fuelHistoryCount > 0) {
                            OutlinedButton(
                                onClick = onExportLfx2012FuelHistory,
                                enabled = !state.isFuelSnapshotReading
                            ) {
                                Text(stringResource(
                                    R.string.flexfuel_lfx2012_export_history,
                                    state.fuelHistoryCount
                                ))
                            }
                        }
                        if (state.isFuelSnapshotReading) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.flexfuel_lfx2012_reading_fuel),
                                color = TextMuted, fontSize = 11.sp)
                        }
                        val fuelSnapshot = state.fuelReadOnlySnapshot
                        if (fuelSnapshot != null) {
                            val source = if (fuelSnapshot.source == Lfx2012FuelReadOnly.Source.J2534)
                                stringResource(R.string.flexfuel_lfx2012_source_j2534)
                            else stringResource(R.string.flexfuel_lfx2012_source_elm)
                            Text(
                                stringResource(
                                    R.string.flexfuel_lfx2012_observed_source, source,
                                    java.text.DateFormat.getDateTimeInstance()
                                        .format(java.util.Date(fuelSnapshot.observedAtUtcMillis))
                                ),
                                color = TextMuted, fontSize = 11.sp
                            )
                            fuelSnapshot.readings.forEach { reading ->
                                val label = when (reading.pid) {
                                    Lfx2012FuelReadOnly.ETHANOL ->
                                        R.string.flexfuel_lfx2012_pid_ethanol
                                    Lfx2012FuelReadOnly.STFT_BANK_1 ->
                                        R.string.flexfuel_lfx2012_pid_stft_b1
                                    Lfx2012FuelReadOnly.LTFT_BANK_1 ->
                                        R.string.flexfuel_lfx2012_pid_ltft_b1
                                    Lfx2012FuelReadOnly.STFT_BANK_2 ->
                                        R.string.flexfuel_lfx2012_pid_stft_b2
                                    else -> R.string.flexfuel_lfx2012_pid_ltft_b2
                                }
                                val value = reading.percent
                                val reported = when {
                                    reading.availability == Lfx2012FuelReadOnly.Availability.REPORTED &&
                                        value != null ->
                                        stringResource(R.string.flexfuel_lfx2012_reported_percent, value)
                                    reading.availability == Lfx2012FuelReadOnly.Availability.UNSUPPORTED ->
                                        stringResource(R.string.flexfuel_lfx2012_pid_unsupported)
                                    else -> stringResource(R.string.flexfuel_lfx2012_pid_no_response)
                                }
                                Text(
                                    stringResource(label) + ": " + reported,
                                    color = TextPrimary, fontSize = 12.sp
                                )
                            }
                            val physicalVinMatched = !state.ecuIdentityFromSimulation &&
                                state.vinComparison?.matches == true &&
                                state.ecuVinResponse.trim().uppercase() == vehicle.vin.trim().uppercase() &&
                                vehicle.vin.isNotBlank()
                            if (!physicalVinMatched) {
                                Text(stringResource(R.string.flexfuel_lfx2012_vin_unverified),
                                    color = WarnRed, fontSize = 11.sp)
                            }
                            Text(stringResource(R.string.flexfuel_lfx2012_observation_warning),
                                color = CamaroAmber, fontSize = 11.sp)
                            Text(stringResource(R.string.flexfuel_lfx2012_continuous_telemetry),
                                color = TextMuted, fontSize = 11.sp)
                        }
                        if (state.fuelSnapshotError.isNotBlank()) {
                            Text(
                                stringResource(R.string.flexfuel_lfx2012_fuel_failed) +
                                    " " + state.fuelSnapshotError,
                                color = WarnRed, fontSize = 11.sp
                            )
                        }
                        Lfx2012ControllerReferences.servicePartGroups.forEach { group ->
                            Text(stringResource(R.string.flexfuel_lfx2012_parts,
                                Lfx2012ControllerReferences.controllerFamily, group.servicePart,
                                group.replaces.joinToString(", ")),
                                color = TextPrimary, fontSize = 12.sp)
                            Text(group.exampleLabel, color = TextMuted, fontSize = 11.sp)
                            group.catalogBootFile?.let { bootFile ->
                                Text(stringResource(R.string.flexfuel_lfx2012_boot, bootFile),
                                    color = TextMuted, fontSize = 11.sp)
                            }
                        }
                        var labelQuery by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = labelQuery,
                            onValueChange = { labelQuery = it },
                            label = { Text("Serv# / GM P/N / BCC") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (labelQuery.isNotBlank()) {
                            val matching = Lfx2012ControllerReferences.findLabels(labelQuery)
                            if (matching.isEmpty()) Text(unavailable, color = TextMuted, fontSize = 11.sp)
                            matching.forEach { label ->
                                Text("Serv# ${label.serviceNumber} · GM P/N ${label.partNumber} · BCC ${label.bcc}",
                                    color = TextPrimary, fontSize = 12.sp)
                            }
                        }
                        Text(stringResource(R.string.flexfuel_lfx2012_parts_warning),
                            color = TextMuted, fontSize = 11.sp)
                        Text(stringResource(R.string.flexfuel_lfx2012_calid_label,
                            reportedCalibrationId ?: unavailable),
                            color = TextMuted, fontSize = 11.sp)
                        if (state.ecuIdentityFromSimulation) {
                            Text(
                                stringResource(R.string.flexfuel_lfx2012_simulation_warning),
                                color = WarnRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (!completeReadOnlyFingerprint) {
                            Text(stringResource(R.string.flexfuel_lfx2012_fingerprint_incomplete),
                                color = WarnRed, fontSize = 11.sp)
                        }
                        if (state.respondingEcuAddresses.isNotEmpty() || completeReadOnlyFingerprint) {
                            val responderNotice = when (responderAttribution) {
                                Lfx2012ControllerReferences.ResponderAttribution.NO_VALID_CAN_ADDRESS ->
                                    R.string.flexfuel_lfx2012_responder_unknown
                                Lfx2012ControllerReferences.ResponderAttribution.SINGLE_ADDRESS_OBSERVED ->
                                    R.string.flexfuel_lfx2012_responder_single
                                Lfx2012ControllerReferences.ResponderAttribution.MULTIPLE_ADDRESSES_OBSERVED ->
                                    R.string.flexfuel_lfx2012_responder_multiple
                            }
                            Text(
                                stringResource(responderNotice),
                                color = if (responderAttribution ==
                                    Lfx2012ControllerReferences.ResponderAttribution.SINGLE_ADDRESS_OBSERVED)
                                    TextMuted else WarnRed,
                                fontSize = 11.sp
                            )
                        }
                        Text(stringResource(R.string.flexfuel_lfx2012_checks),
                            color = CamaroAmber, fontWeight = FontWeight.SemiBold)
                        missing.forEach { missingRequirement ->
                            val labelId = when (missingRequirement) {
                                Lfx2012FlexFuelPreflight.Status.VEHICLE_NOT_SELECTED ->
                                    R.string.flexfuel_preflight_vehicle_missing
                                Lfx2012FlexFuelPreflight.Status.WRONG_VEHICLE ->
                                    R.string.flexfuel_preflight_wrong_vehicle
                                Lfx2012FlexFuelPreflight.Status.VIN_MISMATCH ->
                                    R.string.flexfuel_preflight_vin
                                Lfx2012FlexFuelPreflight.Status.ECU_IDENTITY_MISSING ->
                                    R.string.flexfuel_preflight_ecu
                                Lfx2012FlexFuelPreflight.Status.ECU_IDENTITY_UNVERIFIED ->
                                    R.string.flexfuel_preflight_ecu_unverified
                                Lfx2012FlexFuelPreflight.Status.FIRMWARE_NOT_VERIFIED ->
                                    R.string.flexfuel_preflight_firmware
                                Lfx2012FlexFuelPreflight.Status.ORIGINAL_BACKUP_MISSING ->
                                    R.string.flexfuel_preflight_backup
                                Lfx2012FlexFuelPreflight.Status.RECOVERY_NOT_VERIFIED ->
                                    R.string.flexfuel_preflight_recovery
                                Lfx2012FlexFuelPreflight.Status.WRITE_NOT_VALIDATED ->
                                    R.string.flexfuel_preflight_write
                            }
                            Text("• " + stringResource(labelId),
                                color = WarnRed, fontSize = 11.sp)
                        }
                        val audit = Lfx2012ProgrammingAudit.review(
                            before = Lfx2012ProgrammingAudit.Snapshot(
                                vin = ecuVin,
                                ecuName = recognizedEcuName,
                                calibrationIds = state.calibrationIdResponse.lines()
                                    .filter(String::isNotBlank),
                                verificationNumbers = state.cvnResponse.lines()
                                    .filter(String::isNotBlank),
                                dtcs = null,
                                voltage = null,
                                communicationConfirmed = state.respondingEcuAddresses.isNotEmpty(),
                                functionalCheckPassed = null,
                                oemRelearnReviewed = false,
                                capturedAtUtcMillis = 0L
                            ),
                            after = null
                        )
                        Text(stringResource(R.string.flexfuel_lfx2012_audit_title),
                            color = CamaroAmber, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(
                                R.string.flexfuel_lfx2012_audit_summary,
                                audit.before.count {
                                    it.status == Lfx2012ProgrammingAudit.CheckStatus.OBSERVED
                                },
                                audit.before.size
                            ),
                            color = TextMuted, fontSize = 11.sp
                        )
                        Text(stringResource(R.string.flexfuel_lfx2012_audit_post_not_performed),
                            color = WarnRed, fontSize = 11.sp)
                        // Text(
                            // stringResource(R.string.flexfuel_lfx2012_readonly_testing_notice),
                            // color = TextMuted,
                            // fontSize = 11.sp
                        // )
                        OutlinedButton(
                            onClick = onReadIdentification,
                            enabled = (isConnected || state.isJ2534BridgeConnected) && !state.isReading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.cal_read_ecu_ids))
                        }
                        OutlinedButton(
                            onClick = onExportIdentification,
                            enabled = state.canExportIdentification,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.flexfuel_lfx2012_export_fingerprint))
                        }
                    }
                }
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
                                "${profile.engine.rpo} • ${localizedGenerationName(profile.generation)} • ${profile.modelYears.first}–${profile.modelYears.last}",
                                color = if (profile.engine == vehicle.engine) CamaroAmber else TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(localizedCalibrationEngineName(profile.engine), color = TextPrimary, fontSize = 12.sp)
                            Text(stringResource(R.string.vehicle_ecm_label, profile.nominalControllerFamily), color = TextMuted, fontSize = 11.sp)
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
                    // Text(
                        // stringResource(R.string.cal_obd_mode09_explanation),
                        // color = TextMuted, fontSize = 12.sp
                    // )
                    Button(
                        onClick = onReadIdentification,
                        enabled = (isConnected || state.isJ2534BridgeConnected) && !state.isReading,
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
                    // Keep the old free-form profile field for reference:
                    // OutlinedTextField(value = udsCanProtocol, onValueChange = { udsCanProtocol = it.filter(Char::isDigit).take(2) })
                    val allCanProfiles = setOf(33, 34, 35, 36, 63, 64)
                    val displayedProfiles = if (canReadEnhancedUds) compatibleUdsProfiles else allCanProfiles
                    val profileLabels = mapOf(33 to "CAN 11-bit / 500 kbit/s",
                        34 to "CAN 29-bit / 500 kbit/s", 35 to "CAN 11-bit / 250 kbit/s",
                        36 to "CAN 29-bit / 250 kbit/s", 63 to "SW-CAN 11-bit / 33.3 kbit/s",
                        64 to "SW-CAN 29-bit / 33.3 kbit/s")
                    Box {
                        OutlinedButton(onClick = { udsProfileMenuExpanded = true },
                            enabled = !state.isUdsReading && !state.isObdbSignalReading && displayedProfiles.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (canReadEnhancedUds && !profileAvailable)
                                stringResource(R.string.cal_choose_compatible_profile)
                            else profileLabels[udsCanProtocol.toIntOrNull()] ?: stringResource(R.string.cal_can_profile))
                        }
                        DropdownMenu(expanded = udsProfileMenuExpanded,
                            onDismissRequest = { udsProfileMenuExpanded = false }) {
                            displayedProfiles.sorted().forEach { profile ->
                                DropdownMenuItem(text = { Text(profileLabels.getValue(profile)) },
                                    onClick = { udsCanProtocol = profile.toString(); udsProfileMenuExpanded = false })
                            }
                        }
                    }
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
                                label = { Text(stringResource(R.string.cal_tcp_port)) },
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
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                visualTransformation = PasswordVisualTransformation()
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
                                        J2534BridgePairingCodeFormat.isValid(j2534Token) && canIdsMatchProfile &&
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
                            HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                            if (state.isJ2534BridgeConnected) {
                                OutlinedButton(
                                    onClick = onReadGmlanIdentification,
                                    enabled = !state.isGmlanReading && !state.isJ2534MemoryCapturing &&
                                        !state.isReading && !state.isUdsReading,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (state.isGmlanReading) CircularProgressIndicator()
                                    else Text(stringResource(R.string.cal_gmlan_read_identity))
                                }
                                Text(stringResource(R.string.cal_gmlan_identity_notice),
                                    color = TextMuted, fontSize = 10.sp)
                                if (state.gmlanIdentityFailed) {
                                    Text(stringResource(R.string.cal_gmlan_identity_failed),
                                        color = WarnRed, fontSize = 11.sp)
                                }
                                state.gmlanIdentity?.let { identity ->
                                    Text(stringResource(R.string.cal_gmlan_osid, identity.operatingSystemId),
                                        color = CamaroAmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.cal_gmlan_vin_can, identity.vin, identity.responseCanId),
                                        color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    val selectedVin = vehicle.vin.trim().uppercase()
                                    if (selectedVin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) {
                                        if (selectedVin == identity.vin) {
                                            Text(stringResource(R.string.cal_vin_matches_profile),
                                                color = OkGreen, fontSize = 11.sp)
                                        } else {
                                            Text(stringResource(R.string.cal_vin_mismatch_detected, selectedVin, identity.vin),
                                                color = WarnRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else Text(stringResource(R.string.cal_vin_profile_not_set),
                                        color = CamaroAmber, fontSize = 11.sp)
                                    OutlinedButton(
                                        onClick = onExportIdentification,
                                        enabled = state.canExportIdentification,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.flexfuel_lfx2012_export_fingerprint))
                                    }
                                }
                                HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                            }
                            Text(stringResource(R.string.cal_live_memory_title),
                                color = CamaroAmber, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.cal_live_memory_notice),
                                color = TextMuted, fontSize = 10.sp)
                            OutlinedTextField(
                                value = memoryReadAddressHex,
                                onValueChange = {
                                    memoryReadAddressHex = it.uppercase()
                                        .filter { ch -> ch.isDigit() || ch in 'A'..'F' }.take(8)
                                },
                                label = { Text(stringResource(R.string.cal_expert_patch_address)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                            )
                            OutlinedTextField(
                                value = memoryReadLengthText,
                                onValueChange = {
                                    memoryReadLengthText = it.filter(Char::isDigit).take(7)
                                },
                                label = { Text(stringResource(R.string.cal_live_memory_length)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Text(stringResource(R.string.cal_expert_region_title),
                                color = TextPrimary, fontSize = 12.sp)
                            Box {
                                OutlinedButton(onClick = { memoryReadRegionMenuExpanded = true }) {
                                    Text(when (memoryReadRegion) {
                                        EcuBackupCoverage.Region.CALIBRATION ->
                                            stringResource(R.string.cal_live_memory_region_calibration)
                                        EcuBackupCoverage.Region.PROGRAM_FLASH ->
                                            stringResource(R.string.cal_expert_region_pflash)
                                        EcuBackupCoverage.Region.DATA_FLASH ->
                                            stringResource(R.string.cal_expert_region_dflash)
                                        EcuBackupCoverage.Region.EXTERNAL_EEPROM ->
                                            stringResource(R.string.cal_expert_region_eeprom)
                                        EcuBackupCoverage.Region.OTHER ->
                                            stringResource(R.string.cal_live_memory_region_other)
                                    })
                                }
                                DropdownMenu(
                                    expanded = memoryReadRegionMenuExpanded,
                                    onDismissRequest = { memoryReadRegionMenuExpanded = false }
                                ) {
                                    listOf(
                                        EcuBackupCoverage.Region.OTHER,
                                        EcuBackupCoverage.Region.CALIBRATION,
                                        EcuBackupCoverage.Region.PROGRAM_FLASH,
                                        EcuBackupCoverage.Region.DATA_FLASH,
                                        EcuBackupCoverage.Region.EXTERNAL_EEPROM
                                    ).forEach { region ->
                                        DropdownMenuItem(
                                            text = { Text(when (region) {
                                                EcuBackupCoverage.Region.CALIBRATION ->
                                                    stringResource(R.string.cal_live_memory_region_calibration)
                                                EcuBackupCoverage.Region.PROGRAM_FLASH ->
                                                    stringResource(R.string.cal_expert_region_pflash)
                                                EcuBackupCoverage.Region.DATA_FLASH ->
                                                    stringResource(R.string.cal_expert_region_dflash)
                                                EcuBackupCoverage.Region.EXTERNAL_EEPROM ->
                                                    stringResource(R.string.cal_expert_region_eeprom)
                                                EcuBackupCoverage.Region.OTHER ->
                                                    stringResource(R.string.cal_live_memory_region_other)
                                            }) },
                                            onClick = {
                                                memoryReadRegion = region
                                                memoryReadRegionMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Text(
                                stringResource(R.string.cal_live_memory_region_profile_match),
                                color = TextMuted, fontSize = 11.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = memoryReadSessionExtended,
                                    onCheckedChange = { memoryReadSessionExtended = it }
                                )
                                Text(stringResource(R.string.cal_live_memory_extended),
                                    color = TextPrimary, fontSize = 12.sp)
                            }
                            val memoryAddress = memoryReadAddressHex.toLongOrNull(16)
                            val memoryBytes = memoryReadLengthText.toIntOrNull()
                            OutlinedButton(
                                onClick = {
                                    onCaptureJ2534Memory(
                                        memoryReadAddressHex, memoryReadLengthText,
                                        if (memoryReadSessionExtended) 3 else 1,
                                        memoryReadRegion
                                    )
                                },
                                enabled = state.isJ2534BridgeConnected &&
                                    state.isJ2534MemoryReadAvailable &&
                                    !state.isJ2534MemoryCapturing &&
                                    vehicle.vin.uppercase().matches(Regex("[A-HJ-NPR-Z0-9]{17}")) &&
                                    memoryAddress != null && memoryBytes != null &&
                                    memoryBytes in 1..(4 * 1024 * 1024) &&
                                    memoryAddress + memoryBytes <= 0x1_0000_0000L,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (state.isJ2534MemoryCapturing) CircularProgressIndicator()
                                else Text(stringResource(R.string.cal_live_memory_save))
                            }
                            if (state.isJ2534MemoryCapturing &&
                                state.j2534MemoryCaptureTotalBytes > 0L
                            ) {
                                LinearProgressIndicator(
                                    progress = {
                                        (state.j2534MemoryCaptureBytesRead.toFloat() /
                                            state.j2534MemoryCaptureTotalBytes.toFloat())
                                            .coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "${state.j2534MemoryCaptureBytesRead} / " +
                                        "${state.j2534MemoryCaptureTotalBytes}",
                                    color = TextMuted, fontSize = 11.sp
                                )
                            }
                            if (state.j2534MemoryCaptureMessage.isNotBlank()) {
                                Text(state.j2534MemoryCaptureMessage,
                                    color = if (state.isJ2534MemoryCapturing) CamaroAmber else TextPrimary,
                                    fontSize = 11.sp)
                            }
                        }
                    }
                    Button(
                        onClick = { onReadUdsDid(udsRequestCanId, udsResponseCanId, udsDid, udsCanProtocol) },
                        enabled = canReadEnhancedUds && profileAvailable && !state.isUdsReading &&
                            !state.doIpReading && !state.isObdbSignalReading && canIdsMatchProfile && udsDid.length == 4 &&
                            udsCanProtocol in setOf("33", "34", "35", "36", "63", "64"),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isUdsReading) CircularProgressIndicator()
                        else Text(stringResource(R.string.cal_read_uds_did))
                    }
                    if (state.udsMessage.isNotBlank()) Text(state.udsMessage, color = CamaroAmber, fontSize = 11.sp)
                    if (state.udsDidResponse.isNotBlank()) {
                        Text(stringResource(R.string.cal_uds_did_title, udsDid), color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(state.udsDidResponse, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { onReadUdsDtcs(udsRequestCanId, udsResponseCanId, udsCanProtocol) },
                        enabled = canReadEnhancedUds && profileAvailable && !state.isUdsReading &&
                            !state.doIpReading && !state.isObdbSignalReading && canIdsMatchProfile &&
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
                    if (state.ecuVinResponse.isNotBlank() || state.calibrationIdResponse.isNotBlank() || state.cvnResponse.isNotBlank() || state.ecuNameResponse.isNotBlank() || state.respondingEcuAddresses.isNotEmpty() || state.vinModelYear != null) {
                        HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                        Text(stringResource(R.string.cal_mode09_vin_label), color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(state.ecuVinResponse.ifBlank { "—" }, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        state.vinComparison?.let { comparison ->
                            when (comparison.matches) {
                                true -> Text(
                                    stringResource(R.string.cal_vin_matches_profile),
                                    color = OkGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                false -> Text(
                                    stringResource(
                                        R.string.cal_vin_mismatch_detected,
                                        comparison.selectedVehicleVin.orEmpty(),
                                        comparison.ecuVin
                                    ),
                                    color = WarnRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                null -> Text(
                                    stringResource(R.string.cal_vin_profile_not_set),
                                    color = CamaroAmber,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        state.vinModelYear?.let { year ->
                            val generation = if (year <= 2015) "Gen 5" else "Gen 6"
                            Text(stringResource(R.string.cal_vin_model_year, year, generation),
                                color = TextPrimary, fontSize = 12.sp)
                            if (year != vehicle.modelYear) {
                                Text(stringResource(R.string.cal_vin_model_year_mismatch, year, vehicle.modelYear),
                                    color = WarnRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Text(stringResource(R.string.cal_ecu_name_label), color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(state.ecuNameResponse.ifBlank { "—" }, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text(stringResource(R.string.cal_ecu_response_addresses_label), color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(state.respondingEcuAddresses.joinToString(", ").ifBlank { "—" }, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text(stringResource(R.string.cal_ecu_identity_limits), color = TextMuted, fontSize = 10.sp)
                        Text(stringResource(R.string.cal_mode09_calid_label), color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(state.calibrationIdResponse.ifBlank { "—" }, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text(stringResource(R.string.cal_mode09_cvn_label), color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                    // Text(
                        // stringResource(R.string.cal_native_catalog_explanation),
                        // color = TextMuted, fontSize = 11.sp
                    // )
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
                                        if (signalProfiles.size == 1) udsCanProtocol = signalProfiles.single().toString()
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
                            onClick = { signalProfile?.let { onReadObdbSignal(signal.id, it.toString()) } },
                            enabled = canReadEnhancedUds &&
                                signal.hasSupportedPhysicalCanAddressing &&
                                signalProfile != null &&
                                signal.service == 0x22 &&
                                !state.doIpReading && !state.isUdsReading && !state.isObdbSignalReading,
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
                    Text(
                        stringResource(R.string.cal_trace_title),
                        color = TextPrimary, fontWeight = FontWeight.Bold
                    )
                    // Text(
                        // stringResource(R.string.cal_trace_description),
                        // color = TextMuted, fontSize = 12.sp
                    // )
                    OutlinedButton(
                        onClick = onImportTrace,
                        enabled = !state.isTraceAnalyzing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isTraceAnalyzing) CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
                        else androidx.compose.material3.Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Text(stringResource(R.string.cal_trace_import))
                    }
                    if (state.traceMessage.isNotBlank()) {
                        Text(state.traceMessage, color = CamaroAmber, fontSize = 12.sp)
                    }
                    state.canTraceReport?.let { report ->
                        HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                        Text(report.sourceName, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.cal_trace_can_ids, report.observedCanIds.joinToString { "0x" + it.toString(16).uppercase() }),
                            color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp
                        )
                        Text(
                            stringResource(R.string.cal_trace_uds_services, report.udsServices.joinToString { "0x" + it.toString(16).uppercase().padStart(2, '0') }),
                            color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp
                        )
                        Text(
                            stringResource(R.string.cal_trace_candidate_dids, report.readDidCandidates.joinToString { "0x" + it.toString(16).uppercase().padStart(4, '0') }.ifBlank { stringResource(R.string.common_none) }),
                            color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                        )
                        if (report.pidObservations.isNotEmpty()) {
                            HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                            Text("SAE J1979 • Mode 01 PID signals", color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            report.pidObservations.take(30).forEach { observation ->
                                Text(
                                    "CAN ${observation.canId.toString(16).uppercase()} • PID ${observation.pid.toString(16).uppercase().padStart(2, '0')} • ${observation.name}: ${"%.1f".format(java.util.Locale.ROOT, observation.value)} ${observation.unit}",
                                    color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 10.sp
                                )
                            }
                            if (report.pidObservations.size > 30) {
                                Text("… and ${report.pidObservations.size - 30} more PID observation(s).", color = TextMuted, fontSize = 10.sp)
                            }
                        }
                        report.didObservations.take(20).forEach { observation ->
                            val direction = when (observation.direction) {
                                com.camarodiag.gen5.data.obd.CanTraceDirection.TX -> "TX"
                                com.camarodiag.gen5.data.obd.CanTraceDirection.RX -> "RX"
                                com.camarodiag.gen5.data.obd.CanTraceDirection.UNKNOWN -> "?"
                            }
                            val result = if (observation.positiveResponse) "POS" else "REQ/OBS"
                            Text(
                                "$direction • CAN ${observation.canId.toString(16).uppercase()} • SVC ${observation.responseService.toString(16).uppercase()} • DID ${observation.did.toString(16).uppercase().padStart(4, '0')} • ${result}",
                                color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp
                            )
                        }
                        if (report.didObservations.size > 20) {
                            Text(
                                stringResource(R.string.cal_trace_more_observations, report.didObservations.size - 20),
                                color = TextMuted, fontSize = 10.sp
                            )
                        }
                        report.messages.filter { it.negativeResponseCode != null }.take(20).forEach { message ->
                            Text(
                                stringResource(R.string.cal_trace_nrc_label, message.canId.toString(16).uppercase(), message.service?.toString(16)?.uppercase().orEmpty(), message.negativeResponseCode?.toString(16)?.uppercase()?.padStart(2, '0').orEmpty()),
                                color = CamaroAmber, fontFamily = FontFamily.Monospace, fontSize = 10.sp
                            )
                        }
                        report.errors.take(10).forEach { error ->
                            Text(error, color = CamaroAmber, fontSize = 10.sp)
                        }
                        Text(
                            stringResource(R.string.cal_trace_observations_only),
                            color = CamaroAmber, fontSize = 11.sp
                        )
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
                    // Text(
                        // stringResource(R.string.cal_file_inspection_hint),
                        // color = TextMuted, fontSize = 12.sp
                    // )
                    OutlinedButton(onClick = onImportFile, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Text(stringResource(R.string.cal_import_file))
                    }
                    Text(
                        stringResource(R.string.cal_import_supported_formats),
                        color = TextMuted, fontSize = 11.sp
                    )
                    OutlinedButton(
                        onClick = onVerifyEcuBackup,
                        enabled = state.archiveVerification != ArchiveVerification.Reading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.cal_archive_verify)) }
                    when (val check = state.archiveVerification) {
                        ArchiveVerification.Reading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        ArchiveVerification.Failed -> Text(stringResource(R.string.cal_archive_failed), color = WarnRed)
                        is ArchiveVerification.Verified -> {
                            val manifest = check.manifest
                            Text(stringResource(R.string.cal_archive_verified), color = OkGreen)
                            if (manifest.coverage.status == EcuBackupCoverage.Status.PARTIAL) {
                                Text(stringResource(R.string.cal_archive_partial), color = CamaroAmber)
                            }
                            manifest.vehicleVin?.let { Text("${stringResource(R.string.obd_vin)} $it", color = TextPrimary) }
                            val route = manifest.channel
                            if (route != null) {
                                Text(
                                    stringResource(
                                        R.string.cal_archive_channel,
                                        route.requestCanId.toString(16).uppercase(),
                                        route.responseCanId.toString(16).uppercase(),
                                        route.protocolNumber,
                                        route.diagnosticSession
                                    ),
                                    color = TextMuted, fontSize = 11.sp
                                )
                            } else {
                                Text(stringResource(R.string.cal_archive_legacy_route),
                                    color = CamaroAmber, fontSize = 11.sp)
                            }
                            val firmware = manifest.firmwareEvidence
                            if (firmware != null) {
                                Text(
                                    stringResource(
                                        R.string.cal_archive_osid,
                                        firmware.operatingSystemId,
                                        firmware.osDid.toString(16).uppercase()
                                    ),
                                    color = TextPrimary, fontSize = 11.sp
                                )
                            } else {
                                Text(stringResource(R.string.cal_archive_osid_missing),
                                    color = TextMuted, fontSize = 11.sp)
                            }
                            Text(stringResource(R.string.cal_archive_unattested),
                                color = CamaroAmber, fontSize = 11.sp)
                            Text(stringResource(R.string.cal_payload_size, manifest.coverage.capturedBytes), color = TextPrimary)
                            for (entry in manifest.entries) {
                                val end = entry.startAddress + entry.bytesCaptured - 1
                                Text(stringResource(R.string.cal_address_range,
                                    entry.startAddress.toString(16).uppercase(), end.toString(16).uppercase(),
                                    entry.bytesCaptured), color = TextMuted, fontSize = 11.sp)
                                Text(entry.sha256, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }
                        }
                        null -> Unit
                    }
                    state.importedFileName?.let { name ->
                        HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                        Text(name, color = CamaroAmber, fontWeight = FontWeight.Bold)
                        Text(pluralStringResource(
                            R.plurals.cal_file_size_bytes,
                            (state.importedFileSize ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                            state.importedFileSize ?: 0L
                        ), color = TextPrimary, fontSize = 12.sp)
                        Text(stringResource(R.string.cal_file_hash_label), color = TextMuted, fontSize = 11.sp)
                        Text(state.importedFileSha256.orEmpty(), color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        Text(stringResource(R.string.cal_hex_preview), color = TextMuted, fontSize = 11.sp)
                        Text(state.importedFilePreview.orEmpty(), color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        state.inspectionFormat?.let { format ->
                            HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                            Text(stringResource(R.string.cal_file_inspection, localizedInspectionFormat(format)), color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(state.inspectionSummary.orEmpty(), color = TextPrimary, fontSize = 12.sp)
                            state.inspectionPayloadBytes?.let { Text(stringResource(R.string.cal_payload_size, it), color = TextMuted, fontSize = 11.sp) }
                            state.inspectionRecordCount?.takeIf { it > 0 }?.let { Text(stringResource(R.string.cal_record_count, it), color = TextMuted, fontSize = 11.sp) }
                            Text(stringResource(R.string.cal_checksum_validation, state.inspectionChecksumStatus.orEmpty()), color = TextMuted, fontSize = 11.sp)
                            state.inspectionAddressRanges.forEach { range -> Text(range, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                            state.inspectionWarnings.forEach { warning -> Text("• $warning", color = CamaroAmber, fontSize = 10.sp) }
                        }
                        OutlinedButton(onClick = onExportFile, modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.Icon(Icons.Default.CloudUpload, contentDescription = null)
                            Text(stringResource(R.string.cal_export_file))
                        }
                        if (state.inspectionFormat == "RAW_BINARY") {
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
                                stringResource(R.string.cal_comparison_byte_counts, state.comparisonChangedBytes ?: 0,
                                    state.comparisonAppendedBytes ?: 0, state.comparisonMissingBytes ?: 0),
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
                        enabled = state.importedFilePath != null && state.inspectionFormat == "RAW_BINARY",
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
                                localizedAxisSummary(R.string.cal_axis_x, definition.xAxis),
                                color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                            )
                            Text(
                                localizedAxisSummary(R.string.cal_axis_y, definition.yAxis),
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
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    item {
                                        Text("X", modifier = Modifier.width(48.dp),
                                            color = CamaroAmber, fontSize = 10.sp)
                                    }
                                    itemsIndexed(state.mapXAxisValues) { _, axisValue ->
                                        Text(axisValue, modifier = Modifier.width(88.dp),
                                            color = CamaroAmber,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp)
                                    }
                                }
                            }
                            LazyColumn(
                                // Keep a bounded inner viewport (required for nested lazy
                                // layouts), but adapt it to small and large maps rather
                                // than forcing a cramped 320.dp editor in every case.
                                modifier = Modifier.fillMaxWidth().height(
                                    (state.mapValues.size.coerceIn(3, 7) * 62).dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed(state.mapValues, key = { rowIndex, _ -> rowIndex }) { rowIndex, rowValues ->
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        item {
                                            Text(
                                                state.mapYAxisValues.getOrNull(rowIndex)
                                                    ?: "%02d".format(rowIndex),
                                                modifier = Modifier.width(48.dp),
                                                color = CamaroAmber,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        itemsIndexed(rowValues, key = { columnIndex, _ -> columnIndex }) { columnIndex, value ->
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
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Icon(Icons.Default.Memory, contentDescription = null, tint = CamaroAmber)
                        Text(stringResource(R.string.cal_expert_title), color = CamaroAmber, fontWeight = FontWeight.Bold)
                    }
                    Text(stringResource(R.string.cal_expert_intro), color = TextMuted, fontSize = 12.sp)
                    OutlinedButton(onClick = { expertExpanded = !expertExpanded }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (expertExpanded) R.string.cal_expert_collapse else R.string.cal_expert_expand))
                    }
                    if (expertExpanded) {
                        ConnectorCheckpointPanel()
                        OutlinedTextField(
                            value = expertControllerFamily,
                            onValueChange = { expertControllerFamily = it.uppercase().take(24) },
                            label = { Text(stringResource(R.string.cal_expert_family)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = expertChipMarking,
                            onValueChange = { expertChipMarking = it.take(64) },
                            label = { Text(stringResource(R.string.cal_expert_chip)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = expertProgrammerName,
                            onValueChange = { expertProgrammerName = it.take(64) },
                            label = { Text(stringResource(R.string.cal_expert_programmer)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(stringResource(R.string.cal_expert_access_title), color = TextPrimary)
                        // Protocol names are industry-standard identifiers; existing
                        // explanatory labels remain localized in all 30 locales.
                        val accessLabels = mapOf(
                            EcuBenchExpertPlan.AccessMethod.OBD to stringResource(R.string.cal_expert_access_obd),
                            EcuBenchExpertPlan.AccessMethod.CAN_DIRECT to "CAN",
                            EcuBenchExpertPlan.AccessMethod.BENCH to stringResource(R.string.cal_expert_access_bench),
                            EcuBenchExpertPlan.AccessMethod.BOOT_BAM to stringResource(R.string.cal_expert_access_boot),
                            EcuBenchExpertPlan.AccessMethod.BDM to "BDM",
                            EcuBenchExpertPlan.AccessMethod.SWD to "SWD",
                            EcuBenchExpertPlan.AccessMethod.NEXUS_JTAG to stringResource(R.string.cal_expert_access_nexus),
                            EcuBenchExpertPlan.AccessMethod.EXTERNAL_EEPROM_ISP to "EEPROM ISP",
                            EcuBenchExpertPlan.AccessMethod.EXTERNAL_CHIP_OFF to stringResource(R.string.cal_expert_access_chipoff)
                        )
                        Box {
                            OutlinedButton(onClick = { expertAccessMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(accessLabels.getValue(expertAccessMethod))
                            }
                            DropdownMenu(
                                expanded = expertAccessMenuExpanded,
                                onDismissRequest = { expertAccessMenuExpanded = false }
                            ) {
                                accessLabels.forEach { (method, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            expertAccessMethod = method
                                            if (!method.permitsRegion(expertMemoryRegion)) {
                                                expertMemoryRegion = EcuBenchExpertPlan.MemoryRegion.values()
                                                    .first(method::permitsRegion)
                                            }
                                            expertAccessMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Text(stringResource(R.string.cal_expert_region_title), color = TextPrimary)
                        Box {
                            OutlinedButton(onClick = { expertRegionMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(when (expertMemoryRegion) {
                                    EcuBenchExpertPlan.MemoryRegion.PFLASH -> R.string.cal_expert_region_pflash
                                    EcuBenchExpertPlan.MemoryRegion.DFLASH -> R.string.cal_expert_region_dflash
                                    EcuBenchExpertPlan.MemoryRegion.EXTERNAL_EEPROM -> R.string.cal_expert_region_eeprom
                                }))
                            }
                            DropdownMenu(
                                expanded = expertRegionMenuExpanded,
                                onDismissRequest = { expertRegionMenuExpanded = false }
                            ) {
                                listOf(
                                    EcuBenchExpertPlan.MemoryRegion.PFLASH to R.string.cal_expert_region_pflash,
                                    EcuBenchExpertPlan.MemoryRegion.DFLASH to R.string.cal_expert_region_dflash,
                                    EcuBenchExpertPlan.MemoryRegion.EXTERNAL_EEPROM to R.string.cal_expert_region_eeprom
                                ).filter { (region, _) -> expertAccessMethod.permitsRegion(region) }
                                    .forEach { (region, label) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label)) },
                                        onClick = { expertMemoryRegion = region; expertRegionMenuExpanded = false }
                                    )
                                }
                            }
                        }
                        val wiringUriHandler = LocalUriHandler.current
                        var wiringLinkFailed by remember { mutableStateOf(false) }
                        val wiringReference = CamaroDiagConnectorWiringGuide.findReference(
                            vehicle.modelYear, vehicle.engine.rpo, expertControllerFamily
                        )
                        OutlinedButton(
                            onClick = {
                                wiringLinkFailed = runCatching {
                                    wiringUriHandler.openUri(CamaroDiagConnectorWiringGuide.genericUrl)
                                }.isFailure
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("CamaroDiagConnectors · " + stringResource(R.string.tab_guide))
                        }
                        OutlinedButton(
                            onClick = {
                                wiringLinkFailed = runCatching {
                                    wiringUriHandler.openUri(wiringReference?.url ?: CamaroDiagConnectorWiringGuide.catalogueUrl)
                                }.isFailure
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(wiringReference?.label ?: "ECU · CamaroDiagConnectors")
                        }
                        if (wiringLinkFailed) {
                            Text(stringResource(R.string.obd_data_unavailable), color = WarnRed, fontSize = 11.sp)
                        }
                        Text(stringResource(R.string.status_not_verified), color = WarnRed, fontSize = 11.sp)
                        Text(stringResource(R.string.cal_expert_block_driver), color = WarnRed, fontSize = 11.sp)
                        val expertTarget = EcuBenchExpertPlan.Target(
                            controllerFamily = expertControllerFamily.trim(),
                            chipMarking = expertChipMarking.trim(),
                            programmerName = expertProgrammerName.trim(),
                            accessMethod = expertAccessMethod,
                            region = expertMemoryRegion
                        )
                        val candidateImage = state.importedFileName?.let {
                            EcuBenchExpertPlan.CandidateImage(
                                fileName = it,
                                format = state.inspectionFormat,
                                sizeBytes = state.importedFileSize,
                                sha256 = state.importedFileSha256
                            )
                        }
                        Text(
                            candidateImage?.let { stringResource(R.string.cal_expert_image_selected, it.fileName.orEmpty()) }
                                ?: stringResource(R.string.cal_expert_image_none),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                        OutlinedTextField(
                            value = patchStartHex,
                            onValueChange = { patchStartHex = it.take(16) },
                            label = { Text(stringResource(R.string.cal_expert_patch_address)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (state.comparisonIdentical == true && state.importedFilePath != null &&
                            state.activeFilePath == state.importedFilePath && state.comparisonFilePath != null && expertAccessMethod.permitsRegion(expertMemoryRegion)) {
                            Text(stringResource(R.string.cal_comparison_identical), color = TextPrimary, fontSize = 11.sp)
                            Text(stringResource(R.string.cal_warning_binary_integrity), color = TextMuted, fontSize = 11.sp)
                            Text(stringResource(R.string.cal_archive_partial), color = TextMuted, fontSize = 11.sp)
                            OutlinedButton(
                                onClick = {
                                    val address = patchStartHex.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16)
                                    if (address != null && address >= 0)
                                        onExportExternalDumpPair(expertAccessMethod, expertMemoryRegion, address)
                                },
                                enabled = patchStartHex.trim().removePrefix("0x").removePrefix("0X")
                                    .toLongOrNull(16)?.let { it >= 0 } == true,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.cal_export_file) + " (ZIP)")
                            }
                        }
                        if (expertMemoryRegion != EcuBenchExpertPlan.MemoryRegion.EXTERNAL_EEPROM) {
                            Text(stringResource(R.string.cal_expert_patch_title), color = CamaroAmber, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.cal_expert_patch_warning), color = TextMuted, fontSize = 11.sp)
                            OutlinedTextField(
                                value = patchEraseBytes,
                                onValueChange = { patchEraseBytes = it.take(10) },
                                label = { Text(stringResource(R.string.cal_expert_patch_erase_bytes)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = patchProgramBytes,
                                onValueChange = { patchProgramBytes = it.take(10) },
                                label = { Text(stringResource(R.string.cal_expert_patch_program_bytes)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedButton(
                                onClick = {
                                    onPreviewPartialWrite(
                                        patchStartHex.trim().removePrefix("0x").removePrefix("0X")
                                            .toLongOrNull(16) ?: -1L,
                                        patchEraseBytes.toIntOrNull() ?: 0,
                                        patchProgramBytes.toIntOrNull() ?: 0
                                    )
                                },
                                enabled = state.importedFilePath != null &&
                                    state.comparisonFilePath != null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.cal_expert_patch_preview_button))
                            }
                            state.partialWriteSummary?.let { summary ->
                                Text(summary, color = CamaroAmber, fontSize = 11.sp)
                                state.partialWriteSectorAddresses.forEach { address ->
                                    Text(address, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                            }
                        }
                        Text(stringResource(R.string.cal_expert_identity_notice), color = CamaroAmber, fontSize = 11.sp)
                        Text(stringResource(R.string.cal_expert_blockers), color = CamaroAmber, fontWeight = FontWeight.Bold)
                        EcuBenchExpertPlan.blockers(expertTarget, candidateImage).forEach { blocker ->
                            val label = when (blocker) {
                                EcuBenchExpertPlan.Blocker.CONTROLLER_NOT_IDENTIFIED -> R.string.cal_expert_block_controller
                                EcuBenchExpertPlan.Blocker.CHIP_NOT_IDENTIFIED -> R.string.cal_expert_block_chip
                                EcuBenchExpertPlan.Blocker.ACCESS_REGION_INCOMPATIBLE -> R.string.cal_expert_block_access_region
                                EcuBenchExpertPlan.Blocker.PROGRAMMER_NOT_IDENTIFIED -> R.string.cal_expert_block_programmer
                                EcuBenchExpertPlan.Blocker.IMAGE_MISSING -> R.string.cal_expert_block_image_missing
                                EcuBenchExpertPlan.Blocker.IMAGE_NOT_RAW_BINARY -> R.string.cal_expert_block_image_format
                                EcuBenchExpertPlan.Blocker.IMAGE_INTEGRITY_MISSING -> R.string.cal_expert_block_image_integrity
                                EcuBenchExpertPlan.Blocker.ORIGINAL_READBACK_NOT_VERIFIED -> R.string.cal_expert_block_readback
                                EcuBenchExpertPlan.Blocker.RECOVERY_PATH_NOT_VERIFIED -> R.string.cal_expert_block_recovery
                                EcuBenchExpertPlan.Blocker.MEMORY_MAP_NOT_VERIFIED -> R.string.cal_expert_block_memory_map
                                EcuBenchExpertPlan.Blocker.HARDWARE_PROGRAMMING_DRIVER_UNAVAILABLE -> R.string.cal_expert_block_driver
                            }
                            Text("• " + stringResource(label), color = WarnRed, fontSize = 11.sp)
                        }
                    }
                    Text(stringResource(R.string.cal_expert_no_write), color = CamaroAmber, fontSize = 11.sp)
                }
            }
        }
        if (state.message.isNotBlank()) {
            item { Text(state.message, color = CamaroAmber, fontSize = 12.sp) }
        }
    }
    if (showVinMismatchDialog) {
        val comparison = state.vinComparison
        if (comparison?.matches == false) {
            AlertDialog(
                onDismissRequest = { showVinMismatchDialog = false },
                title = { Text(stringResource(R.string.cal_vin_mismatch_title), color = WarnRed) },
                text = {
                    Text(
                        stringResource(
                            R.string.cal_vin_mismatch_confirmation,
                            comparison.selectedVehicleVin.orEmpty(),
                            comparison.ecuVin
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onCreateVehicleFromEcuVin(comparison.ecuVin)
                        showVinMismatchDialog = false
                    }) {
                        Text(stringResource(R.string.cal_vin_create_vehicle))
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            showVinMismatchDialog = false
                            showVinSyncConfirmation = true
                        }) {
                            Text(stringResource(R.string.cal_vin_sync_existing))
                        }
                        TextButton(onClick = { showVinMismatchDialog = false }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                }
            )
        }
    }
    if (showVinSyncConfirmation) {
        val comparison = state.vinComparison
        if (comparison != null) {
            AlertDialog(
                onDismissRequest = { showVinSyncConfirmation = false },
                title = { Text(stringResource(R.string.cal_vin_sync_warning_title), color = WarnRed) },
                text = {
                    Text(
                        stringResource(
                            R.string.cal_vin_sync_warning_message,
                            comparison.selectedVehicleVin.orEmpty(),
                            comparison.ecuVin
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onSyncVehicleVinFromEcu(comparison.ecuVin)
                        showVinSyncConfirmation = false
                    }) {
                        Text(stringResource(R.string.cal_vin_sync_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showVinSyncConfirmation = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }
}
