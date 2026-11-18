package com.camarodiag.gen5.ui

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.camarodiag.gen5.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.camarodiag.gen5.domain.model.*
import com.camarodiag.gen5.control.VehicleControlCatalog
import com.camarodiag.gen5.control.ControlAvailability
import com.camarodiag.gen5.control.RemoteStartSafetyGate
import com.camarodiag.gen5.control.RemoteStartConditions
import com.camarodiag.gen5.control.VehicleGear
import com.camarodiag.gen5.control.VehicleSignal
import com.camarodiag.gen5.control.VehicleControlId
import com.camarodiag.gen5.data.obd.VxdiagProfiles
import com.camarodiag.gen5.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

enum class DiagTab(val labelEn: String, val labelFr: String, val labelResourceId: Int) {
    SETUP("Vehicle", "Véhicule", R.string.tab_vehicle),
    ADAPTER("Adapter", "Interface", R.string.tab_connect),
    MODULES("Modules", "Modules", R.string.tab_modules),
    LIVE("Live", "Direct", R.string.tab_live),
    CAN_SNIFFER("CAN Sniffer", "CAN Sniffer", R.string.tab_can_sniffer),
    DTCS("DTCs", "Défauts", R.string.tab_dtcs),
    MILEAGE("Mileage", "Odomètre", R.string.tab_mileage),
    CALIBRATION("ECU / Maps", "ECU / Cartographie", R.string.tab_calibration),
    OBD_SCAN("Full OBD Scan", "Scan OBD complet", R.string.tab_obd_scan),
    GUIDE("Guide", "Guide", R.string.tab_guide),
    CONTROL("Controls", "Pilotage", R.string.tab_controls),
    SETTINGS("Settings", "Réglages", R.string.tab_settings)
}


@Composable
internal fun localizedGenerationName(generation: CamaroGeneration): String =
    stringResource(R.string.vehicle_generation_label, if (generation == CamaroGeneration.GEN5) 5 else 6)

@Composable
private fun localizedEngineName(engine: CamaroEngine): String = when (engine) {
    CamaroEngine.LLT -> stringResource(R.string.engine_llt)
    CamaroEngine.LFX -> stringResource(R.string.engine_lfx)
    CamaroEngine.LS3 -> stringResource(R.string.engine_ls3)
    CamaroEngine.L99 -> stringResource(R.string.engine_l99)
    CamaroEngine.LSA -> stringResource(R.string.engine_lsa)
    CamaroEngine.LS7 -> stringResource(R.string.engine_ls7)
    CamaroEngine.LTG -> stringResource(R.string.engine_ltg)
    CamaroEngine.LGX -> stringResource(R.string.engine_lgx)
    CamaroEngine.LT1 -> stringResource(R.string.engine_lt1)
    CamaroEngine.LT4 -> stringResource(R.string.engine_lt4)
}

@Composable
private fun localizedTransmissionName(transmission: TransmissionType): String = when (transmission) {
    TransmissionType.MANUAL -> stringResource(R.string.transmission_manual)
    TransmissionType.AUTOMATIC -> stringResource(R.string.transmission_automatic)
}

@Composable
private fun localizedMarketName(market: MarketRegion): String = when (market) {
    MarketRegion.US_CA -> stringResource(R.string.market_us_ca)
    MarketRegion.EU_ECE -> stringResource(R.string.market_eu_ece)
}

@Composable
private fun localizedAdapterTierName(tier: AdapterTier): String = when (tier) {
    AdapterTier.PRO_STN -> stringResource(R.string.adapter_tier_pro_stn)
    AdapterTier.GENUINE_ELM -> stringResource(R.string.adapter_tier_genuine_elm)
    AdapterTier.CLONE_ELM -> stringResource(R.string.adapter_tier_clone_elm)
    AdapterTier.DISCONNECTED -> stringResource(R.string.adapter_tier_disconnected)
}

@Composable
private fun localizedBusName(bus: CanBusType): String = when (bus) {
    CanBusType.HS_CAN -> stringResource(R.string.module_bus_hscan)
    CanBusType.SW_CAN -> stringResource(R.string.module_bus_swcan)
    CanBusType.UNKNOWN -> stringResource(R.string.module_bus_unknown)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamaroDiagApp(viewModel: MainViewModel) {
    val lang by viewModel.language.collectAsState()
    val systemConfiguration = LocalConfiguration.current
    val localizedConfiguration = remember(systemConfiguration, lang) {
        Configuration(systemConfiguration).apply {
            setLocales(LocaleList.forLanguageTags(if (lang == AppLanguage.ZH) "zh-CN" else lang.code))
        }
    }
    val units by viewModel.unitSystem.collectAsState()
    val vehicle by viewModel.vehicle.collectAsState()
    val savedVehicles by viewModel.savedVehicles.collectAsState()
    val selectedSavedVehicleId by viewModel.selectedSavedVehicleId.collectAsState()
    val adapter by viewModel.adapter.collectAsState()
    val lastConnectionProfile by viewModel.lastConnectionProfile.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val usbDevices by viewModel.usbDevices.collectAsState()
    val connError by viewModel.connectionMessage.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val modules by viewModel.modules.collectAsState()
    val dtcs by viewModel.dtcs.collectAsState()
    val livePids by viewModel.livePids.collectAsState()
    val isStreaming by viewModel.isStreamingLive.collectAsState()
    val liveRecording by viewModel.liveRecordingState.collectAsState()
    val liveTelemetryMessage by viewModel.liveTelemetryMessage.collectAsState()
    val mileageReport by viewModel.mileageReport.collectAsState()
    val savedSessions by viewModel.savedSessions.collectAsState()
    val calibrationWorkspace by viewModel.calibrationWorkspace.collectAsState()
    val liveCanMonitorState by viewModel.liveCanMonitorState.collectAsState()
    val obdScanReport by viewModel.obdScanReport.collectAsState()
    val obdScanMessage by viewModel.obdScanMessage.collectAsState()
    val isObdScanning by viewModel.isObdScanning.collectAsState()

    var currentTab by remember { mutableStateOf(DiagTab.SETUP) }
    var moreExpanded by remember { mutableStateOf(false) }
    UpdateManagerDialog()

    val btPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPairedDevices()
    }

    val bleLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.refreshPairedDevices()
    }

    val vinPhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            Toast.makeText(viewModel.getApplication(), R.string.vin_photo_cancelled, Toast.LENGTH_SHORT).show()
        } else {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val normalizedText = result.text.uppercase().replace(Regex("[^A-Z0-9]"), "")
                    val detectedVin = Regex("[A-HJ-NPR-Z0-9]{17}")
                        .find(normalizedText)?.value
                    if (detectedVin != null) {
                        viewModel.updateVin(detectedVin)
                        Toast.makeText(viewModel.getApplication(), R.string.vin_photo_success, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(viewModel.getApplication(), R.string.vin_photo_not_found, Toast.LENGTH_LONG).show()
                    }
                    recognizer.close()
                }
                .addOnFailureListener {
                    recognizer.close()
                    Toast.makeText(viewModel.getApplication(), R.string.vin_photo_error, Toast.LENGTH_LONG).show()
                }
        }
    }

    val calibrationFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importCalibrationFile(it) }
    }

    val canTracePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.analyzeCanTrace(it) }
    }

    val calibrationComparisonPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importComparisonCalibrationFile(it) }
    }

    val calibrationDefinitionsPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importCalibrationDefinitions(it) }
    }

    val calibrationDefinitionsTemplateSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/tab-separated-values")
    ) { uri ->
        uri?.let { viewModel.exportCalibrationDefinitionTemplate(it) }
    }

    val liveRecordingExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let { viewModel.exportLiveRecording(it) }
    }

    val calibrationFileExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { viewModel.exportCalibrationFile(it) }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btPermLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        } else {
            bleLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    CompositionLocalProvider(
        LocalConfiguration provides localizedConfiguration,
        LocalLayoutDirection provides if (lang == AppLanguage.AR || lang == AppLanguage.HE) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark),
                title = {
                    Column {
                        Text(
                            text = "CAMARODIAG",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 19.sp,
                            color = CamaroAmber,
                            letterSpacing = 1.6.sp
                        )
                        Text(
                            text = "${localizedGenerationName(vehicle.generation)} • ${vehicle.modelYear} • ${vehicle.engine.rpo} • ${vehicle.market.code}",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                },
                actions = {
                    when {
                        adapter.deviceName.contains("Demo", ignoreCase = true) ||
                            adapter.deviceName.contains("Simulated", ignoreCase = true) -> {
                            Text("DEMO", color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        adapter.tier != AdapterTier.DISCONNECTED -> {
                            Text("OBD", color = OkGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        else -> {
                            Text(stringResource(R.string.status_offline), color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            )
        },

        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                contentColor = TextPrimary
            ) {
                NavigationBarItem(
                    selected = currentTab == DiagTab.LIVE,
                    onClick = { currentTab = DiagTab.LIVE },
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_live)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BgDark,
                        selectedTextColor = CamaroAmber,
                        indicatorColor = CamaroAmber
                    )
                )

                NavigationBarItem(
                    selected = currentTab == DiagTab.DTCS,
                    onClick = { currentTab = DiagTab.DTCS },
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_dtcs)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BgDark,
                        selectedTextColor = CamaroAmber,
                        indicatorColor = CamaroAmber
                    )
                )

                NavigationBarItem(
                    selected = currentTab == DiagTab.SETTINGS,
                    onClick = { currentTab = DiagTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BgDark,
                        selectedTextColor = CamaroAmber,
                        indicatorColor = CamaroAmber
                    )
                )

                NavigationBarItem(
                    selected = currentTab in setOf(
                        DiagTab.MODULES,
                        DiagTab.MILEAGE,
                        DiagTab.CAN_SNIFFER,
                        DiagTab.CALIBRATION,
                        DiagTab.OBD_SCAN,
                        DiagTab.GUIDE,
                        DiagTab.CONTROL,
                        DiagTab.SETUP,
                        DiagTab.ADAPTER
                    ),
                    onClick = { moreExpanded = true },
                    icon = { Icon(Icons.Default.MoreVert, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_more)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BgDark,
                        selectedTextColor = CamaroAmber,
                        indicatorColor = CamaroAmber
                    )
                )

                DropdownMenu(
                    expanded = moreExpanded,
                    onDismissRequest = { moreExpanded = false }
                ) {
                    listOf(
                        DiagTab.MODULES,
                        DiagTab.MILEAGE,
                        DiagTab.CAN_SNIFFER,
                        DiagTab.CALIBRATION,
                        DiagTab.OBD_SCAN,
                        DiagTab.GUIDE,
                        DiagTab.CONTROL
                    ).forEach { tab ->
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(tab.labelResourceId))
                            },
                            onClick = {
                                currentTab = tab
                                moreExpanded = false
                            }
                        )
                    }
                }
            }
        }
   
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
                .padding(padding)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            calibrationWorkspace.vinComparison
                ?.takeIf { it.matches == false }
                ?.let { comparison ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2020)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, WarnRed)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(
                                    R.string.cal_vin_mismatch_detected,
                                    comparison.selectedVehicleVin ?: "—",
                                    comparison.ecuVin
                                ),
                                color = Color(0xFFFFB4AB),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                stringResource(R.string.cal_vin_mismatch_confirmation,
                                    comparison.selectedVehicleVin ?: "—",
                                    comparison.ecuVin
                                ),
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                            TextButton(onClick = { currentTab = DiagTab.CALIBRATION }) {
                                Text(stringResource(R.string.cal_vin_mismatch_title), color = CamaroAmber)
                            }
                        }
                    }
                }
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            when (currentTab) {
                DiagTab.SETUP -> VehicleSetupScreen(
                    vehicle = vehicle,
                    savedVehicles = savedVehicles,
                    selectedSavedVehicleId = selectedSavedVehicleId,
                    onChange = { y, e, tr, m -> viewModel.updateVehicle(y, e, tr, m) },
                    onVinChange = { viewModel.updateVin(it) },
                    onCaptureVinPhoto = { vinPhotoLauncher.launch(null) },
                    onSaveVehicle = { viewModel.saveCurrentVehicle(it) },
                    onLoadSavedVehicle = { viewModel.selectSavedVehicle(it) },
                    onDeleteSavedVehicle = { viewModel.deleteSavedVehicle(it) }
                )
                DiagTab.ADAPTER -> AdapterScreen(
                    adapter = adapter,
                    isBusy = isBusy || isObdScanning || calibrationWorkspace.isReading,
                    pairedDevices = pairedDevices,
                    errorMsg = connError,
                    onRefreshDevices = { viewModel.refreshPairedDevices() },
                    onConnectMac = { viewModel.connectRealBluetooth(it) },
                    onSelectSim = { viewModel.selectSimulatedAdapter(it) },
                    usbDevices = usbDevices,
                    onRefreshUsb = { viewModel.refreshPairedDevices() },
                    onConnectUsb = { viewModel.connectUsb(it) },
                    onConnectWifi = { host, port -> viewModel.connectWifi(host, port) }
                )
                DiagTab.MODULES -> ModulesScreen(modules)
                DiagTab.LIVE -> LiveDataScreen(
                    pids = livePids,
                    units = units,
                    isStreaming = isStreaming,
                    recording = liveRecording,
                    telemetryMessage = liveTelemetryMessage,
                    onToggleStream = { viewModel.toggleLiveStream() },
                    onToggleUnits = {
                        viewModel.setUnitSystem(if (units == UnitSystem.METRIC) UnitSystem.IMPERIAL else UnitSystem.METRIC)
                    },
                    onToggleRecording = { viewModel.toggleLiveRecording() },
                    onExportRecording = {
                        liveRecordingExporter.launch("CamaroDiag_live_${System.currentTimeMillis()}.csv")
                    }
                )
                DiagTab.CAN_SNIFFER -> CanSnifferScreen(
                    state = liveCanMonitorState,
                    isAvailable = adapter.tier != AdapterTier.DISCONNECTED &&
                        adapter.supportsCanHeaders &&
                        !adapter.deviceName.contains("Demo", ignoreCase = true) &&
                        !adapter.deviceName.contains("Simulated", ignoreCase = true),
                    onStart = { viewModel.startCanMonitor() },
                    onStop = { viewModel.stopCanMonitor() },
                    onSendRequest = { viewModel.sendSafeObdRequest(it) }
                )
                DiagTab.DTCS -> DtcScreen(dtcs, vehicle, { viewModel.scanDtcs() }, { viewModel.clearDtcs() })
                DiagTab.MILEAGE -> MileageScreen(mileageReport, { viewModel.runMileageAudit() }, { viewModel.saveCurrentSession() })
                DiagTab.OBD_SCAN -> ObdScanScreen(
                    report = obdScanReport,
                    isScanning = isObdScanning,
                    message = obdScanMessage,
                    isConnected = adapter.tier != AdapterTier.DISCONNECTED &&
                        !adapter.deviceName.contains("Demo", ignoreCase = true),
                    onRunScan = { viewModel.runFullObdScan() }
                )
                DiagTab.CALIBRATION -> CalibrationWorkspaceScreen(
                    state = calibrationWorkspace,
                    vehicleLabel = "${vehicle.modelYear} Camaro ${vehicle.engine.rpo} • ${localizedGenerationName(vehicle.generation)}",
                    ecmType = vehicle.engine.ecmType,
                    isConnected = !adapter.deviceName.contains("Demo", ignoreCase = true) &&
                        !adapter.deviceName.contains("Simulated", ignoreCase = true) &&
                        adapter.tier != AdapterTier.DISCONNECTED,
                    vehicle = vehicle,
                    onReadIdentification = { viewModel.readCalibrationIdentification() },
                    onCreateVehicleFromEcuVin = { viewModel.createVehicleFromEcuVin(it) },
                    onSyncVehicleVinFromEcu = { viewModel.syncVehicleVinFromEcu(it) },
                    onReadUdsDid = { requestId, responseId, did, protocol -> viewModel.readUdsDid(requestId, responseId, did, protocol) },
                    onReadUdsDtcs = { requestId, responseId, protocol -> viewModel.readUdsDtcs(requestId, responseId, protocol) },
                    onConnectJ2534Bridge = { host, port, token, requestId, responseId, baud ->
                        viewModel.connectJ2534Bridge(host, port, token, requestId, responseId, baud)
                    },
                    onDisconnectJ2534Bridge = { viewModel.disconnectJ2534Bridge() },
                    onReadObdbSignal = { signalId, profile -> viewModel.readObdbSignal(signalId, profile) },
                    onImportFile = { calibrationFilePicker.launch(arrayOf("*/*")) },
                    onImportTrace = { canTracePicker.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel", "*/*")) },
                    onImportComparisonFile = { calibrationComparisonPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                    onImportDefinitions = {
                        calibrationDefinitionsPicker.launch(arrayOf("text/*", "text/csv", "text/tab-separated-values", "*/*"))
                    },
                    onExportDefinitionTemplate = {
                        calibrationDefinitionsTemplateSaver.launch("camaro_calibration_tables_template.tsv")
                    },
                    onSelectMap = { viewModel.selectCalibrationMap(it) },
                    onCellChange = { row, column, value -> viewModel.updateCalibrationCell(row, column, value) },
                    onApplyMap = { viewModel.applyCalibrationTableEdits() },
                    onDiscardMap = { viewModel.discardCalibrationTableChanges() },
                    onExportFile = {
                        calibrationWorkspace.importedFileName?.let { fileName ->
                            calibrationFileExporter.launch(fileName)
                        }
                    },
                    onClearFile = { viewModel.clearCalibrationFile() }
                )
                DiagTab.GUIDE -> HardwareGuideScreen()
                DiagTab.CONTROL -> VehicleControlsScreen(vehicle)
                DiagTab.SETTINGS -> SettingsScreen(
                    lang = lang,
                    units = units,
                    vehicle = vehicle,
                    adapter = adapter,
                    lastConnectionProfile = lastConnectionProfile,
                    sessions = savedSessions,
                    onLang = { viewModel.setLanguage(it) },
                    onUnits = { viewModel.setUnitSystem(it) },
                    onDeleteSession = { viewModel.deleteSavedSession(it) },
                    onOpenVehicle = { currentTab = DiagTab.SETUP },
                    onOpenAdapter = { currentTab = DiagTab.ADAPTER }
                )
            }
            }
        }
    }
    }
}

@Composable
fun VehicleSetupScreen(
    vehicle: VehicleProfile,
    savedVehicles: List<SavedVehicleProfile>,
    selectedSavedVehicleId: String?,
    onChange: (Int, CamaroEngine, TransmissionType, MarketRegion) -> Unit,
    onVinChange: (String) -> Unit,
    onCaptureVinPhoto: () -> Unit,
    onSaveVehicle: (String) -> Unit,
    onLoadSavedVehicle: (String) -> Unit,
    onDeleteSavedVehicle: (String) -> Unit
) {
    var savedVehicleName by remember { mutableStateOf("") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionTitle(stringResource(R.string.vehicle_setup_header))
            Text("CHEVROLET CAMARO • ${localizedGenerationName(vehicle.generation).uppercase()} • ${vehicle.modelYear}", color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.vehicle_generation), color = TextMuted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CamaroGeneration.entries.forEach { gen ->
                            FilterChip(selected = vehicle.generation == gen, onClick = {
                                val year = if (gen == CamaroGeneration.GEN5) 2015 else 2016
                                onChange(year, vehicle.engine, vehicle.transmission, vehicle.market)
                            }, label = { Text(stringResource(R.string.vehicle_generation_label, if (gen == CamaroGeneration.GEN5) 5 else 6)) })
                        }
                    }
                    Text(stringResource(R.string.vehicle_model_year), color = TextMuted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        (2010..2024).forEach { yr ->
                            FilterChip(
                                selected = vehicle.modelYear == yr,
                                onClick = { onChange(yr, vehicle.engine, vehicle.transmission, vehicle.market) },
                                label = { Text("$yr") }
                            )
                        }
                    }

                    Text(stringResource(R.string.vehicle_engine), color = TextMuted, fontSize = 13.sp)
                    CamaroEngine.entries.filter { vehicle.modelYear in it.years }.forEach { eng ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChange(vehicle.modelYear, eng, vehicle.transmission, vehicle.market) }
                                .background(if (vehicle.engine == eng) SurfaceElevated else Color.Transparent, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("${eng.rpo} — ${localizedEngineName(eng)}", fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.vehicle_ecm_label, eng.ecmType), fontSize = 12.sp, color = CamaroBlue)
                            }
                            if (vehicle.engine == eng) Icon(Icons.Default.CheckCircle, null, tint = CamaroAmber)
                        }
                    }

                    Text(stringResource(R.string.vehicle_transmission), color = TextMuted, fontSize = 13.sp)
                    TransmissionType.entries.forEach { tr ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChange(vehicle.modelYear, vehicle.engine, tr, vehicle.market) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = vehicle.transmission == tr, onClick = { onChange(vehicle.modelYear, vehicle.engine, tr, vehicle.market) })
                            Text(localizedTransmissionName(tr))
                        }
                    }

                    Text(stringResource(R.string.vehicle_market_region), color = TextMuted, fontSize = 13.sp)
                    MarketRegion.entries.forEach { mk ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChange(vehicle.modelYear, vehicle.engine, vehicle.transmission, mk) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = vehicle.market == mk, onClick = { onChange(vehicle.modelYear, vehicle.engine, vehicle.transmission, mk) })
                            Text(localizedMarketName(mk))
                        }
                    }
                    Text(stringResource(R.string.vehicle_vin), color = TextMuted, fontSize = 13.sp)
                    OutlinedTextField(
                        value = vehicle.vin,
                        onValueChange = onVinChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.vehicle_vin_placeholder)) },
                        supportingText = { Text(stringResource(R.string.vehicle_vin_hint)) }
                    )
                    OutlinedButton(
                        onClick = onCaptureVinPhoto,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.vin_photo_capture))
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.saved_vehicle_profiles_title), color = CamaroAmber, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = savedVehicleName,
                        onValueChange = { savedVehicleName = it.take(60) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.saved_vehicle_name_label)) },
                        placeholder = { Text(stringResource(R.string.saved_vehicle_name_placeholder)) }
                    )
                    Button(
                        onClick = {
                            val defaultName = "${vehicle.modelYear} Camaro ${vehicle.engine.rpo}"
                            onSaveVehicle(savedVehicleName.ifBlank { defaultName })
                            savedVehicleName = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.saved_vehicle_save_current))
                    }
                    if (savedVehicles.isEmpty()) {
                        Text(stringResource(R.string.saved_vehicle_empty), color = TextMuted, fontSize = 12.sp)
                    } else {
                        savedVehicles.forEach { saved ->
                            HorizontalDivider(color = TextMuted.copy(alpha = .2f))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        saved.name,
                                        color = if (saved.id == selectedSavedVehicleId) CamaroAmber else TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (saved.id == selectedSavedVehicleId) {
                                        Text(stringResource(R.string.saved_vehicle_current), color = OkGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(
                                    "${saved.vehicle.modelYear} • ${saved.vehicle.engine.rpo} • ${saved.vehicle.transmission.code} • ${saved.vehicle.market.code}",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                                Text(
                                    stringResource(R.string.session_vin_label, saved.vehicle.vin.ifBlank { "—" }),
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                                if (saved.requiresReview) {
                                    Text(stringResource(R.string.saved_vehicle_requires_review), color = CamaroAmber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onLoadSavedVehicle(saved.id) }, modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.saved_vehicle_load))
                                    }
                                    TextButton(onClick = { onDeleteSavedVehicle(saved.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = null, tint = WarnRed)
                                        Text(stringResource(R.string.saved_vehicle_delete), color = WarnRed)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdapterScreen(
    adapter: AdapterCapabilities,
    isBusy: Boolean,
    pairedDevices: List<Pair<String, String>>,
    errorMsg: String?,
    onRefreshDevices: () -> Unit,
    onConnectMac: (String) -> Unit,
    onSelectSim: (String) -> Unit,
    usbDevices: List<String>,
    onRefreshUsb: () -> Unit,
    onConnectUsb: (String) -> Unit,
    onConnectWifi: (String, Int) -> Unit
) {
    var wifiHost by remember { mutableStateOf("192.168.0.10") }
    var wifiPort by remember { mutableStateOf("35000") }
    val vxdiagProfiles = remember { VxdiagProfiles.all() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionTitle(stringResource(R.string.adapter_section_title))
            if (isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.connection_busy), color = TextMuted, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(adapter.deviceName, fontWeight = FontWeight.Bold, color = CamaroAmber, fontSize = 16.sp)
                    Text(localizedAdapterTierName(adapter.tier), fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BadgeChip("CAN 11-bit / 500k", adapter.supportsCanHeaders)
                        BadgeChip(stringResource(R.string.swcan_wiring_verified), adapter.supportsSwCan)
                        BadgeChip(stringResource(R.string.long_response_mode), adapter.supportsLongFrames)
                    }
                    if (adapter.rawProbeLog.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        adapter.rawProbeLog.forEach { line ->
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.paired_bluetooth_devices), fontWeight = FontWeight.Bold)
                TextButton(onClick = onRefreshDevices, enabled = !isBusy) { Text(stringResource(R.string.common_refresh)) }
            }
            if (pairedDevices.isEmpty()) {
                Text(
                    stringResource(R.string.adapter_none_detected),
                    color = TextMuted, fontSize = 13.sp
                )
            } else {
                pairedDevices.forEach { (name, mac) ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = !isBusy) { onConnectMac(mac) },
                        colors = CardDefaults.cardColors(containerColor = SurfaceElevated)
                    ) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(name, fontWeight = FontWeight.SemiBold)
                                Text(mac, fontSize = 12.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                            }
                            Text(stringResource(R.string.common_connect), color = CamaroBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            errorMsg?.let { Text(it, color = WarnRed, fontSize = 12.sp) }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.usb_connection_title), fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.usb_devices_detected), modifier = Modifier.weight(1f))
                        TextButton(onClick = onRefreshUsb, enabled = !isBusy) { Text(stringResource(R.string.common_refresh)) }
                    }
                    if (usbDevices.isEmpty()) Text(stringResource(R.string.usb_none_detected), color = TextMuted)
                    usbDevices.forEach { label -> OutlinedButton(onClick = { onConnectUsb(label) }, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.adapter_connect_label, label)) } }
                    HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                    Text(stringResource(R.string.wifi_connection_title), fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = wifiHost, onValueChange = { wifiHost = it }, label = { Text(stringResource(R.string.adapter_ip_address)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = wifiPort, onValueChange = { wifiPort = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.adapter_tcp_port)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onConnectWifi(wifiHost, wifiPort.toIntOrNull() ?: 35000) }, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.adapter_connect_wifi)) }
                    Text(stringResource(R.string.adapter_vxdiag_android_limit), color = TextMuted, fontSize = 12.sp)
                }
            }
        }
        item {
            Text(stringResource(R.string.adapter_vxdiag_profiles), fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.adapter_vxdiag_profiles_hint),
                color = TextMuted,
                fontSize = 11.sp
            )
            vxdiagProfiles.forEach { profile ->
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(profile.label, color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                stringResource(R.string.adapter_discovery_only),
                                color = WarnRed,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(profile.protocols.joinToString(" • ") { it.name }, color = TextMuted, fontSize = 10.sp)
                        Text(
                            stringResource(R.string.adapter_hardware_profile_only),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.adapter_simulation_profiles), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSelectSim("MX_PLUS") }, enabled = !isBusy) { Text("OBDLink MX+") }
                OutlinedButton(onClick = { onSelectSim("ELM_15") }, enabled = !isBusy) { Text("ELM327 v1.5") }
                OutlinedButton(onClick = { onSelectSim("CLONE") }, enabled = !isBusy) { Text(stringResource(R.string.adapter_tier_clone_elm)) }
            }
        }
    }
}


@Composable
private fun localizedVehicleControlTitle(id: VehicleControlId): String = when (id) {
    VehicleControlId.WINDOWS -> stringResource(R.string.control_windows_title)
    VehicleControlId.SUNROOF -> stringResource(R.string.control_sunroof_title)
    VehicleControlId.DOOR_LOCKS -> stringResource(R.string.control_door_locks_title)
    VehicleControlId.LIGHTS -> stringResource(R.string.control_lights_title)
    VehicleControlId.HORN -> stringResource(R.string.control_horn_title)
    VehicleControlId.MIRRORS -> stringResource(R.string.control_mirrors_title)
    VehicleControlId.TRUNK -> stringResource(R.string.control_trunk_title)
    VehicleControlId.REMOTE_START -> stringResource(R.string.control_remote_start_title)
}

@Composable
private fun localizedVehicleControlExplanation(id: VehicleControlId): String = when (id) {
    VehicleControlId.WINDOWS -> stringResource(R.string.control_windows_explanation)
    VehicleControlId.SUNROOF -> stringResource(R.string.control_sunroof_explanation)
    VehicleControlId.DOOR_LOCKS -> stringResource(R.string.control_door_locks_explanation)
    VehicleControlId.LIGHTS -> stringResource(R.string.control_lights_explanation)
    VehicleControlId.HORN -> stringResource(R.string.control_horn_explanation)
    VehicleControlId.MIRRORS -> stringResource(R.string.control_mirrors_explanation)
    VehicleControlId.TRUNK -> stringResource(R.string.control_trunk_explanation)
    VehicleControlId.REMOTE_START -> stringResource(R.string.control_remote_start_explanation)
}

@Composable
private fun localizedModuleName(module: EcuModule): String = when (module.id) {
    "ECM" -> stringResource(R.string.module_ecm_name)
    "TCM" -> stringResource(R.string.module_tcm_name)
    "BCM" -> stringResource(R.string.module_bcm_name)
    "IPC" -> stringResource(R.string.module_ipc_name)
    "EBCM" -> stringResource(R.string.module_ebcm_name)
    "SDM" -> stringResource(R.string.module_sdm_name)
    else -> module.shortName
}

@Composable
private fun localizedModuleNotes(module: EcuModule): String {
    val notes = module.notesEn
    return when (module.id) {
        "ECM" -> when {
            notes.startsWith("Observed 7E8") -> stringResource(R.string.module_note_ecm_obd_detected)
            notes.startsWith("No standard OBD 7E8") -> stringResource(R.string.module_note_ecm_obd_missing)
            else -> stringResource(R.string.module_note_ecm)
        }
        "TCM" -> if (notes.contains("not assumed from Gen 5")) stringResource(R.string.module_note_tcm_gen6) else stringResource(R.string.module_note_tcm_gen5)
        "BCM" -> when {
            notes.contains("020I") -> stringResource(R.string.module_note_bcm_gen5)
            notes.contains("020M") -> stringResource(R.string.module_note_bcm_gen6_to2018)
            else -> stringResource(R.string.module_note_bcm_2019)
        }
        "IPC" -> if (notes.startsWith("Secondary odometer")) stringResource(R.string.module_note_ipc_gen6) else stringResource(R.string.module_note_ipc_gen5)
        "EBCM" -> if (notes.startsWith("Enhanced module")) stringResource(R.string.module_note_ebcm_gen6) else stringResource(R.string.module_note_ebcm_gen5)
        "SDM" -> stringResource(R.string.module_note_sdm)
        else -> stringResource(R.string.module_note_unavailable)
    }
}

private fun remoteStartBlockerResourceId(index: Int): Int = when (index) {
    0 -> R.string.remote_start_blocker_gear
    1 -> R.string.remote_start_blocker_hood
    2 -> R.string.remote_start_blocker_locks
    3 -> R.string.remote_start_blocker_hazards
    4 -> R.string.remote_start_blocker_theft
    5 -> R.string.remote_start_blocker_faults
    6 -> R.string.remote_start_blocker_stationary
    7 -> R.string.remote_start_blocker_equipment
    else -> R.string.control_blocked
}

@Composable
fun VehicleControlsScreen(vehicle: VehicleProfile) {
    val capabilities = remember(vehicle) { VehicleControlCatalog().forVehicle(vehicle) }
    val remoteStartAssessment = remember(vehicle) {
        RemoteStartSafetyGate.assess(
            RemoteStartConditions(
                gear = VehicleGear.UNKNOWN,
                hoodClosed = VehicleSignal.UNKNOWN,
                doorsLocked = VehicleSignal.UNKNOWN,
                hazardsOff = VehicleSignal.UNKNOWN,
                theftSystemReady = VehicleSignal.UNKNOWN,
                criticalFaultsAbsent = VehicleSignal.UNKNOWN,
                vehicleStationary = VehicleSignal.UNKNOWN,
                oemRemoteStartEquipped = VehicleSignal.UNKNOWN
            )
        )
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionTitle(stringResource(R.string.control_vehicle_title))
            Text(stringResource(R.string.control_vehicle_label, vehicle.modelYear, localizedGenerationName(vehicle.generation), vehicle.engine.rpo), color = CamaroAmber)
            Text(stringResource(R.string.control_gm_body_notice), color = TextMuted, fontSize = 12.sp)
        }
        items(capabilities) { capability ->
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(localizedVehicleControlTitle(capability.id), fontWeight = FontWeight.Bold)
                        Text(capability.vehicleContext, color = CamaroBlue, fontSize = 10.sp)
                        Text(localizedVehicleControlExplanation(capability.id), color = TextMuted, fontSize = 12.sp)
                        if (capability.id == VehicleControlId.REMOTE_START) {
                            Text(
                                stringResource(
                                    R.string.control_interlock_assessment,
                                    stringResource(if (remoteStartAssessment.allowedToRequestOemProcedure) R.string.control_conditions_confirmed else R.string.control_blocked)
                                ),
                                color = WarnRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                            remoteStartAssessment.blockersFr.forEachIndexed { index, _ ->
                                Text("• " + stringResource(remoteStartBlockerResourceId(index)), color = WarnRed, fontSize = 10.sp)
                            }
                        }
                    }
                    val availabilityLabel = when (capability.availability) {
                        ControlAvailability.NOT_VERIFIED -> stringResource(R.string.status_not_verified)
                        ControlAvailability.AVAILABLE -> stringResource(R.string.status_available)
                        ControlAvailability.NOT_EQUIPPED -> stringResource(R.string.status_not_equipped)
                    }
                    val availabilityColor = when (capability.availability) {
                        ControlAvailability.NOT_VERIFIED -> WarnRed
                        ControlAvailability.AVAILABLE -> OkGreen
                        ControlAvailability.NOT_EQUIPPED -> TextMuted
                    }
                    Text(availabilityLabel, color = availabilityColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Text(stringResource(R.string.control_no_actuation_notice), color = TextMuted, fontSize = 12.sp) }
    }
}

@Composable
fun ModulesScreen(modules: List<EcuModule>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionTitle(stringResource(R.string.modules_vehicle_title))
        }
        items(modules) { mod ->
            val statusColor = when (mod.status) {
                ModuleStatus.ACCESSIBLE -> OkGreen
                ModuleStatus.REQUIRES_SWCAN -> CamaroAmber
                ModuleStatus.UNSUPPORTED_BY_ADAPTER -> WarnRed
                ModuleStatus.NOT_EQUIPPED -> TextMuted
                ModuleStatus.DISCOVERY_REQUIRED -> CamaroAmber
            }
            val statusLabel = when (mod.status) {
                ModuleStatus.ACCESSIBLE -> stringResource(R.string.module_accessible)
                ModuleStatus.REQUIRES_SWCAN -> stringResource(R.string.module_requires_swcan)
                ModuleStatus.UNSUPPORTED_BY_ADAPTER -> stringResource(R.string.module_blocked_adapter)
                ModuleStatus.NOT_EQUIPPED -> stringResource(R.string.module_not_equipped)
                ModuleStatus.DISCOVERY_REQUIRED -> stringResource(R.string.module_discovery_required)
            }
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${mod.shortName} • ${localizedBusName(mod.bus)}", fontWeight = FontWeight.Bold, color = CamaroBlue)
                        Text(statusLabel, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Text(localizedModuleName(mod), fontWeight = FontWeight.SemiBold)
                    if (mod.txHeader.isNotBlank() && mod.rxHeader.isNotBlank()) {
                        Text(stringResource(R.string.module_can_headers, mod.txHeader, mod.rxHeader, mod.bus.pinout), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
                    }
                    Text(localizedModuleNotes(mod), fontSize = 12.sp, color = TextMuted)
                }
            }
        }
    }
}


@Composable
private fun localizedLivePidLabel(pid: LivePidValue): String {
    val demo = pid.labelEn.contains("(DEMO)", ignoreCase = true)
    return when (pid.id) {
        "RPM" -> stringResource(if (demo) R.string.live_pid_rpm_demo else R.string.live_pid_rpm)
        "ECT" -> stringResource(if (demo) R.string.live_pid_ect_demo else R.string.live_pid_ect)
        "OIL" -> stringResource(R.string.live_pid_oil_demo)
        "STFT" -> stringResource(if (demo) R.string.live_pid_stft_demo else R.string.live_pid_stft)
        "BAT" -> stringResource(if (demo) R.string.live_pid_bat_demo else R.string.live_pid_bat)
        "SPD" -> stringResource(R.string.live_pid_speed)
        "TPS" -> stringResource(R.string.live_pid_throttle)
        "LOAD" -> stringResource(R.string.live_pid_load)
        "MAP" -> stringResource(R.string.live_pid_map)
        "IAT" -> stringResource(R.string.live_pid_iat)
        "MAF" -> stringResource(R.string.live_pid_maf)
        else -> stringResource(R.string.live_pid_generic, pid.id)
    }
}

@Composable
fun LiveDataScreen(
    pids: List<LivePidValue>,
    units: UnitSystem,
    isStreaming: Boolean,
    recording: LiveRecordingState,
    telemetryMessage: String?,
    onToggleStream: () -> Unit,
    onToggleUnits: () -> Unit,
    onToggleRecording: () -> Unit,
    onExportRecording: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.live_engine_telemetry))
                    Row {
                        TextButton(onClick = onToggleUnits) {
                            Text(if (units == UnitSystem.METRIC) stringResource(R.string.units_metric) else stringResource(R.string.units_imperial), color = CamaroBlue)
                        }
                        TextButton(onClick = onToggleStream) {
                            Text(if (isStreaming) stringResource(R.string.live_pause) else stringResource(R.string.live_resume), color = CamaroAmber)
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onToggleRecording,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = if (recording.isRecording) WarnRed else CamaroAmber)
                            ) {
                                Text(
                                    if (recording.isRecording) {
                                        stringResource(R.string.live_stop_recording)
                                    } else {
                                        stringResource(R.string.live_record)
                                    },
                                    color = Color.Black
                                )
                            }
                            OutlinedButton(
                                onClick = onExportRecording,
                                enabled = recording.sampleCount > 0 && !recording.isRecording,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.live_export_csv))
                            }
                        }
                        Text(
                            stringResource(
                                R.string.live_recording_status,
                                recording.sampleCount,
                                stringResource(if (recording.isRecording) R.string.live_recording_state_active else R.string.live_recording_state_stopped)
                            ),
                            color = if (recording.isRecording) CamaroAmber else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        recording.message.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = TextMuted, fontSize = 11.sp)
                        }
                        telemetryMessage?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = WarnRed, fontSize = 11.sp)
                        }
                        Text(
                            stringResource(R.string.live_recording_notice),
                            color = TextMuted, fontSize = 10.sp
                        )
                    }
                }
            }
        }
        items(pids) { pid ->
            val displayVal = if (units == UnitSystem.METRIC) pid.metricValue else pid.imperialValue
            val displayUnit = if (units == UnitSystem.METRIC) pid.metricUnit else pid.imperialUnit
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Text(localizedLivePidLabel(pid), color = TextMuted, fontSize = 13.sp)
                        Text(
                            text = "${String.format(Locale.getDefault(), "%.1f", displayVal)} $displayUnit",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (pid.history.size > 2) {
                        Spacer(Modifier.height(8.dp))
                        Sparkline(pid.history, pid.minMetric, pid.maxMetric)
                    }
                }
            }
        }
    }
}

@Composable
fun CanSnifferScreen(
    state: LiveCanMonitorState,
    isAvailable: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSendRequest: (String) -> Unit
) {
    var requestInput by remember { mutableStateOf("01 0C") }
    val report = state.report
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.can_sniffer_title))
                    Text(stringResource(R.string.can_sniffer_description), color = TextMuted, fontSize = 12.sp)
                    Text(stringResource(R.string.can_sniffer_safety), color = CamaroAmber, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onStart,
                            enabled = isAvailable && !state.isCapturing,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.can_sniffer_start))
                        }
                        OutlinedButton(
                            onClick = onStop,
                            enabled = state.isCapturing,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.can_sniffer_stop))
                        }
                    }
                    if (!isAvailable) {
                        Text(stringResource(R.string.can_sniffer_unavailable), color = WarnRed, fontSize = 11.sp)
                    }
                    state.message?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = TextMuted, fontSize = 11.sp)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.can_sniffer_request_label), color = CamaroAmber, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = requestInput,
                        onValueChange = { requestInput = it.take(16) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.can_sniffer_request_label)) },
                        placeholder = { Text("01 0C") }
                    )
                    Button(
                        onClick = { onSendRequest(requestInput) },
                        enabled = isAvailable && !state.isCapturing && !state.isSendingRequest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.can_sniffer_send))
                    }
                    state.requestResponse?.let { response ->
                        Text(stringResource(R.string.can_sniffer_response_heading), color = CamaroAmber, fontWeight = FontWeight.Bold)
                        Text(response.ifBlank { "—" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
        if (report == null || report.frames.isEmpty()) {
            item {
                Text(stringResource(R.string.can_sniffer_empty), color = TextMuted, fontSize = 12.sp)
            }
        } else {
            item {
                Text(stringResource(R.string.can_sniffer_frame_count, report.frames.size), color = CamaroAmber, fontWeight = FontWeight.Bold)
            }
            items(report.frames.takeLast(100).asReversed()) { frame ->
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "#${frame.lineNumber} • 0x${String.format(Locale.ROOT, "%03X", frame.canId)} • DLC ${frame.data.size}",
                            color = CamaroAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            frame.data.joinToString(" ") { String.format(Locale.ROOT, "%02X", it) },
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            if (report.pidObservations.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.can_sniffer_pid_heading), color = CamaroAmber, fontWeight = FontWeight.Bold)
                }
                items(report.pidObservations.takeLast(30).asReversed()) { observation ->
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(observation.name, fontSize = 12.sp, color = TextMuted)
                                Text("PID 0x${String.format(Locale.ROOT, "%02X", observation.pid)} • CAN 0x${String.format(Locale.ROOT, "%03X", observation.canId)}", fontSize = 10.sp, color = TextMuted)
                            }
                            Text(
                                "${String.format(Locale.ROOT, "%.1f", observation.value)} ${observation.unit}",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
            if (report.messages.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.cal_trace_uds_services), color = CamaroAmber, fontWeight = FontWeight.Bold)
                }
                items(report.messages.takeLast(30).asReversed()) { message ->
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            val didLabel = message.did?.let {
                                " • DID 0x${String.format(Locale.ROOT, "%04X", it)}"
                            }.orEmpty()
                            Text(
                                "${message.serviceName}$didLabel • CAN 0x${String.format(Locale.ROOT, "%03X", message.canId)}",
                                color = CamaroAmber,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                message.payload.joinToString(" ") { String.format(Locale.ROOT, "%02X", it) },
                                color = TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Sparkline(values: List<Double>, minVal: Double, maxVal: Double) {
    Canvas(modifier = Modifier.fillMaxWidth().height(34.dp)) {
        val span = (maxVal - minVal).coerceAtLeast(1.0)
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val norm = ((v - minVal) / span).coerceIn(0.0, 1.0).toFloat()
            val y = size.height * (1f - norm)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = CamaroAmber, style = Stroke(width = 3f))
    }
}

@Composable
private fun localizedDtcStatus(dtc: DiagnosticDtc): String {
    val isFrench = LocalConfiguration.current.locales[0].language.equals("fr", ignoreCase = true)
    val rawStatus = dtc.statusEn.trim()
    // UDS ReadDTCInformation carries eight independent status bits. Preserve their
    // complete decoded summary instead of collapsing it to a single pending/confirmed badge.
    if (rawStatus.isNotBlank() &&
        !rawStatus.equals("Pending", ignoreCase = true) &&
        !rawStatus.equals("Confirmed", ignoreCase = true) &&
        !rawStatus.equals("Permanent", ignoreCase = true)
    ) {
        return (if (isFrench) dtc.statusFr else dtc.statusEn).ifBlank { rawStatus }
    }
    return when {
        dtc.isPending -> stringResource(R.string.obd_pending)
        dtc.statusEn.equals("Permanent", ignoreCase = true) -> stringResource(R.string.obd_permanent)
        else -> stringResource(R.string.obd_confirmed)
    }
}

@Composable
private fun localizedDtcDescription(dtc: DiagnosticDtc): String {
    val code = dtc.code.substringBefore('-').uppercase()
    val translated = when (code) {
        "P0101" -> stringResource(R.string.dtc_desc_p0101)
        "P0102" -> stringResource(R.string.dtc_desc_p0102)
        "P0103" -> stringResource(R.string.dtc_desc_p0103)
        "P0113" -> stringResource(R.string.dtc_desc_p0113)
        "P0128" -> stringResource(R.string.dtc_desc_p0128)
        "P0171" -> stringResource(R.string.dtc_desc_p0171)
        "P0172" -> stringResource(R.string.dtc_desc_p0172)
        "P0174" -> stringResource(R.string.dtc_desc_p0174)
        "P0175" -> stringResource(R.string.dtc_desc_p0175)
        "P0300" -> stringResource(R.string.dtc_desc_p0300)
        "P0301" -> stringResource(R.string.dtc_desc_p0301)
        "P0302" -> stringResource(R.string.dtc_desc_p0302)
        "P0303" -> stringResource(R.string.dtc_desc_p0303)
        "P0304" -> stringResource(R.string.dtc_desc_p0304)
        "P0305" -> stringResource(R.string.dtc_desc_p0305)
        "P0306" -> stringResource(R.string.dtc_desc_p0306)
        "P0307" -> stringResource(R.string.dtc_desc_p0307)
        "P0308" -> stringResource(R.string.dtc_desc_p0308)
        "P0420" -> stringResource(R.string.dtc_desc_p0420)
        "P0430" -> stringResource(R.string.dtc_desc_p0430)
        "P0442" -> stringResource(R.string.dtc_desc_p0442)
        "P0455" -> stringResource(R.string.dtc_desc_p0455)
        "P0496" -> stringResource(R.string.dtc_desc_p0496)
        "P0507" -> stringResource(R.string.dtc_desc_p0507)
        "P06DD" -> stringResource(R.string.dtc_desc_p06dd)
        "P1101" -> stringResource(R.string.dtc_desc_p1101)
        "P0010" -> stringResource(R.string.dtc_desc_p0010)
        "P0011" -> stringResource(R.string.dtc_desc_p0011)
        "P0014" -> stringResource(R.string.dtc_desc_p0014)
        "P0016" -> stringResource(R.string.dtc_desc_p0016)
        "P0017" -> stringResource(R.string.dtc_desc_p0017)
        "P0018" -> stringResource(R.string.dtc_desc_p0018)
        "P0019" -> stringResource(R.string.dtc_desc_p0019)
        "P0087" -> stringResource(R.string.dtc_desc_p0087)
        "P0089" -> stringResource(R.string.dtc_desc_p0089)
        "P0191" -> stringResource(R.string.dtc_desc_p0191)
        "P0299" -> stringResource(R.string.dtc_desc_p0299)
        else -> ""
    }
    return translated.ifBlank {
        val isFrench = LocalConfiguration.current.locales[0].language.equals("fr", ignoreCase = true)
        (if (isFrench) dtc.descFr else dtc.descEn).ifBlank {
            stringResource(R.string.dtc_description_unavailable)
        }
    }
}

@Composable
fun DtcScreen(dtcs: List<DiagnosticDtc>, vehicle: VehicleProfile, onScan: () -> Unit, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    val isFrench = LocalConfiguration.current.locales[0].language.equals("fr", ignoreCase = true)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.dtc_title))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onScan) { Text(stringResource(R.string.dtc_scan)) }
                    Button(onClick = { confirmClear = true }, colors = ButtonDefaults.buttonColors(containerColor = WarnRed)) {
                        Text(stringResource(R.string.dtc_clear), color = Color.White)
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.dtc_clear_warning),
                color = TextMuted, fontSize = 11.sp
            )
        }
        item {
            val coverage = CamaroDtcCatalog.coverageNotice(vehicle)
            Text(
                if (isFrench) coverage.first else coverage.second,
                color = CamaroAmber,
                fontSize = 11.sp
            )
            if (dtcs.any { DtcCatalogRegistry.catalogueFor(it.module, vehicle).hasVehicleSpecificDefinitions }) {
                Text(
                    stringResource(R.string.dtc_gm_calibration_not_validated),
                    color = CamaroAmber,
                    fontSize = 11.sp
                )
            }
        }
        if (dtcs.isEmpty()) {
            item {
                Text(stringResource(R.string.dtc_none_returned), color = OkGreen)
            }
        } else {
            items(dtcs) { dtc ->
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${dtc.code} (${dtc.module})", fontWeight = FontWeight.Bold, color = if (dtc.isPending) CamaroAmber else WarnRed, fontSize = 16.sp)
                            Text(localizedDtcStatus(dtc), fontSize = 12.sp, color = TextMuted)
                        }
                        Text(localizedDtcDescription(dtc))
                        val moduleCatalogue = DtcCatalogRegistry.catalogueFor(dtc.module, vehicle)
                        val applicabilityNotice = if (moduleCatalogue.hasVehicleSpecificDefinitions) {
                            CamaroDtcCatalog.applicabilityNotice(dtc.code.substringBefore('-'), vehicle)
                        } else {
                            null
                        }
                        if (applicabilityNotice != null) {
                            Text(
                                if (isFrench) applicabilityNotice.first else applicabilityNotice.second,
                                color = WarnRed,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                        if (!moduleCatalogue.hasVehicleSpecificDefinitions) {
                            Text(
                                if (isFrench) moduleCatalogue.noteFr else moduleCatalogue.noteEn,
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        val vehicleExplanation = if (moduleCatalogue.hasVehicleSpecificDefinitions) {
                            CamaroDtcCatalog.find(dtc.code.substringBefore('-'), vehicle)
                        } else {
                            null
                        }
                        if (vehicleExplanation != null) {
                            HorizontalDivider(color = TextMuted.copy(alpha = 0.25f))
                            Text(stringResource(R.string.dtc_vehicle_interpretation), fontWeight = FontWeight.Bold, color = CamaroAmber)
                            Text(if (isFrench) vehicleExplanation.meaningFr else vehicleExplanation.meaningEn)
                            Text(
                                stringResource(R.string.dtc_severity) + ": " +
                                    if (isFrench) vehicleExplanation.severityFr else vehicleExplanation.severityEn,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(stringResource(R.string.dtc_possible_causes), fontWeight = FontWeight.Bold)
                            (if (isFrench) vehicleExplanation.causesFr else vehicleExplanation.causesEn).forEach { cause ->
                                Text("• $cause")
                            }
                            Text(stringResource(R.string.dtc_recommended_checks), fontWeight = FontWeight.Bold)
                            (if (isFrench) vehicleExplanation.checksFr else vehicleExplanation.checksEn).forEachIndexed { index, check ->
                                Text("${index + 1}. $check")
                            }
                            val applicability = if (isFrench) vehicleExplanation.applicabilityFr else vehicleExplanation.applicabilityEn
                            if (!applicability.isNullOrBlank()) {
                                Text(applicability, color = WarnRed)
                            }
                        }
                        val gmMonitor = if (moduleCatalogue.hasVehicleSpecificDefinitions) {
                            GmEngineMonitorCatalog.find(dtc.code.substringBefore('-'), vehicle)
                        } else {
                            null
                        }
                        val gmSourceLabel = GmEngineMonitorCatalog.sourceLabel(vehicle) ?: "GM"
                        if (gmMonitor != null) {
                            HorizontalDivider(color = TextMuted.copy(alpha = 0.25f))
                            Text(
                                stringResource(R.string.dtc_gm_monitor_title, gmSourceLabel),
                                fontWeight = FontWeight.Bold,
                                color = CamaroAmber
                            )
                            Text(if (isFrench) gmMonitor.componentFr else gmMonitor.componentEn, fontWeight = FontWeight.SemiBold)
                            Text(if (isFrench) gmMonitor.strategyFr else gmMonitor.strategyEn)
                            Text(stringResource(R.string.dtc_gm_monitor_criteria, if (isFrench) gmMonitor.malfunctionCriteriaFr else gmMonitor.malfunctionCriteriaEn))
                            Text(stringResource(R.string.dtc_gm_monitor_enable, if (isFrench) gmMonitor.enableConditionsFr else gmMonitor.enableConditionsEn))
                            Text(stringResource(R.string.dtc_gm_monitor_failure, if (isFrench) gmMonitor.failureCriteriaFr else gmMonitor.failureCriteriaEn))
                            Text(stringResource(R.string.dtc_gm_monitor_mil, gmMonitor.milClassification))
                            Text(
                                stringResource(R.string.dtc_gm_monitor_source, gmSourceLabel, gmMonitor.sourcePage),
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        } else if (vehicleExplanation == null && moduleCatalogue.hasVehicleSpecificDefinitions) {
                            Text(
                                stringResource(R.string.dtc_vehicle_specific_not_documented),
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.dtc_confirm_clear)) },
            text = { Text(stringResource(R.string.dtc_clear_description)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarnRed)
                ) { Text(stringResource(R.string.dtc_clear_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}


@Composable
private fun localizedMileageSummary(report: MileageAuditReport): String {
    val valid = report.readings.filter { reading ->
        reading.kmValue?.let { it.isFinite() && it >= 0.0 } == true
    }
    val independent = valid.filter {
        !it.profileEvidenceReference.isNullOrBlank() &&
            it.confidence == ConfidenceLevel.HIGH &&
            it.moduleId.uppercase() in setOf("BCM", "IPC", "ECM_TOTAL_ODO")
    }.distinctBy { it.moduleId.uppercase() }
    val values = independent.mapNotNull { it.kmValue }
    val delta = if (values.size >= 2) values.maxOrNull()!! - values.minOrNull()!! else 0.0
    return when {
        report.hasDiscrepancy -> stringResource(R.string.mileage_summary_discrepancy, delta)
        values.size >= 2 -> stringResource(R.string.mileage_summary_agreement, delta)
        valid.isEmpty() -> stringResource(R.string.mileage_summary_no_data)
        else -> stringResource(R.string.mileage_summary_insufficient, valid.size, independent.size)
    }
}

@Composable
private fun localizedMileageSourceName(item: MileageSourceReading): String = when (item.moduleId) {
    "J2534" -> stringResource(R.string.mileage_source_j2534)
    "DEMO" -> stringResource(R.string.mileage_source_demo)
    "ECM_A6" -> stringResource(R.string.mileage_source_ecm_a6)
    "ECM_D3" -> stringResource(R.string.mileage_source_ecm_d3)
    "BCM" -> stringResource(R.string.module_bcm_name)
    "IPC" -> stringResource(R.string.module_ipc_name)
    "ECM_TOTAL_ODO" -> stringResource(R.string.mileage_source_ecm_total)
    else -> stringResource(R.string.mileage_source_generic, item.moduleId)
}

@Composable
private fun localizedMileageDetails(item: MileageSourceReading): String = when (item.moduleId) {
    "J2534" -> stringResource(R.string.mileage_details_j2534)
    "DEMO" -> stringResource(R.string.mileage_details_demo)
    "ECM_A6" -> stringResource(
        R.string.mileage_details_pid,
        "A6",
        if (item.didInfo.contains("not advertised by ECU")) stringResource(R.string.mileage_pid_not_advertised) else item.didInfo.substringAfter("•", item.didInfo).trim()
    )
    "ECM_D3" -> stringResource(
        R.string.mileage_details_pid,
        "D3",
        if (item.didInfo.contains("not advertised by ECU")) stringResource(R.string.mileage_pid_not_advertised) else item.didInfo.substringAfter("•", item.didInfo).trim()
    )
    "BCM", "IPC" -> stringResource(R.string.mileage_details_module_not_read)
    else -> stringResource(R.string.mileage_details_unavailable)
}

@Composable
private fun localizedMileageConfidence(confidence: ConfidenceLevel): String = when (confidence) {
    ConfidenceLevel.HIGH -> stringResource(R.string.confidence_high)
    ConfidenceLevel.MODERATE -> stringResource(R.string.confidence_moderate)
    ConfidenceLevel.UNAVAILABLE -> stringResource(R.string.confidence_unavailable)
}

@Composable
private fun localizedMileageCaveat(item: MileageSourceReading): String = when (item.moduleId) {
    "J2534" -> stringResource(R.string.mileage_caveat_j2534)
    "DEMO" -> stringResource(R.string.mileage_caveat_demo)
    "ECM_A6" -> stringResource(if (item.kmValue != null) R.string.mileage_caveat_ecm_a6_valid else R.string.mileage_caveat_ecm_a6_missing)
    "ECM_D3" -> stringResource(if (item.kmValue != null) R.string.mileage_caveat_ecm_d3_valid else R.string.mileage_caveat_ecm_d3_missing)
    "BCM" -> stringResource(R.string.mileage_caveat_bcm)
    "IPC" -> stringResource(R.string.mileage_caveat_ipc)
    else -> stringResource(R.string.mileage_caveat_unavailable)
}

@Composable
fun MileageScreen(
    report: MileageAuditReport?,
    onRefreshAudit: () -> Unit,
    onSaveReport: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.mileage_consistency))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onRefreshAudit) { Text(stringResource(R.string.mileage_reread)) }
                    Button(onClick = onSaveReport) { Text(stringResource(R.string.common_save)) }
                }
            }
        }
        report?.let { rep ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = if (rep.hasDiscrepancy) Color(0xFF3B1518) else SurfaceElevated)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.mileage_engineering_summary, rep.vin),
                            fontWeight = FontWeight.Bold,
                            color = if (rep.hasDiscrepancy) WarnRed else OkGreen
                        )
                        Text(localizedMileageSummary(rep), fontSize = 13.sp)
                    }
                }
            }
            items(rep.readings) { item ->
                val confColor = when (item.confidence) {
                    ConfidenceLevel.HIGH -> OkGreen
                    ConfidenceLevel.MODERATE -> CamaroAmber
                    ConfidenceLevel.UNAVAILABLE -> TextMuted
                }
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(localizedMileageSourceName(item), fontWeight = FontWeight.Bold)
                            Text(
                                text = item.kmValue?.let { "${String.format(Locale.getDefault(), "%,.1f", it)} km" } ?: stringResource(R.string.obd_not_available),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (item.kmValue != null) CamaroBlue else TextMuted
                            )
                        }
                        Text(localizedMileageDetails(item), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
                        Text(localizedMileageConfidence(item.confidence), color = confColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(localizedMileageCaveat(item), fontSize = 12.sp, color = TextMuted)
                    }
                }
            }
        }
    }
}

@Composable
fun HardwareGuideScreen() {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionTitle(stringResource(R.string.guide_title))
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.guide_recommended_mxplus),
                        fontWeight = FontWeight.Bold, color = OkGreen
                    )
                    Text(
                        stringResource(R.string.guide_mxplus_description),
                        fontSize = 13.sp
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.guide_hscan_alternative),
                        fontWeight = FontWeight.Bold, color = CamaroAmber
                    )
                    Text(
                        stringResource(R.string.guide_vlinker_description),
                        fontSize = 13.sp
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.guide_clone_limitations),
                        fontWeight = FontWeight.Bold, color = WarnRed
                    )
                    Text(
                        stringResource(R.string.guide_clone_description),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun SettingsScreen(
    lang: AppLanguage,
    units: UnitSystem,
    vehicle: VehicleProfile,
    adapter: AdapterCapabilities,
    lastConnectionProfile: SavedObdConnectionProfile?,
    sessions: List<com.camarodiag.gen5.data.db.SavedSessionEntity>,
    onLang: (AppLanguage) -> Unit,
    onUnits: (UnitSystem) -> Unit,
    onDeleteSession: (com.camarodiag.gen5.data.db.SavedSessionEntity) -> Unit,
    onOpenVehicle: () -> Unit,
    onOpenAdapter: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    var languageExpanded by remember { mutableStateOf(false) }
    val currentVehicle = "${localizedGenerationName(vehicle.generation)} • ${vehicle.modelYear} Camaro ${vehicle.engine.rpo} • ${vehicle.transmission.code} • ${vehicle.market.code} • ${vehicle.vin.ifBlank { "VIN —" }}"
    val currentAdapter = if (adapter.tier == AdapterTier.DISCONNECTED) {
        stringResource(R.string.settings_adapter_not_connected)
    } else {
        "${adapter.deviceName} • ${localizedAdapterTierName(adapter.tier)}"
    }
    val savedConnectionText = lastConnectionProfile?.let { profile ->
        val transportLabel = when (profile.transport) {
            "bluetooth" -> stringResource(R.string.settings_connection_bluetooth)
            "usb" -> stringResource(R.string.settings_connection_usb)
            "wifi" -> stringResource(R.string.settings_connection_wifi)
            else -> profile.transport
        }
        stringResource(R.string.settings_connection_details, transportLabel, profile.adapterName, profile.endpoint)
    } ?: stringResource(R.string.settings_no_last_connection)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionTitle(stringResource(R.string.settings_title))
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.vehicle_setup_header), fontWeight = FontWeight.Bold, color = CamaroAmber)
                    OutlinedButton(onClick = onOpenVehicle, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.DirectionsCar, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tab_vehicle))
                    }
                    HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                    Text(stringResource(R.string.adapter_section_title), fontWeight = FontWeight.Bold, color = CamaroAmber)
                    OutlinedButton(onClick = onOpenAdapter, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tab_connect))
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.system_language_title), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.system_language_current, lang.label), color = CamaroAmber)
                    Box {
                        OutlinedButton(onClick = { languageExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(lang.label)
                        }
                        DropdownMenu(
                            expanded = languageExpanded,
                            onDismissRequest = { languageExpanded = false }
                        ) {
                            AppLanguage.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        onLang(option)
                                        languageExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Text(stringResource(R.string.system_language_hint), color = TextMuted, fontSize = 12.sp)
                    Text(stringResource(R.string.settings_current_vehicle), fontWeight = FontWeight.Bold)
                    Text(currentVehicle, color = TextMuted, fontSize = 12.sp)
                    Text(stringResource(R.string.settings_current_adapter), fontWeight = FontWeight.Bold)
                    Text(currentAdapter, color = if (adapter.tier == AdapterTier.DISCONNECTED) TextMuted else CamaroAmber, fontSize = 12.sp)
                    Text(stringResource(R.string.settings_last_connection), fontWeight = FontWeight.Bold)
                    Text(savedConnectionText, color = TextMuted, fontSize = 12.sp)
                    Text(stringResource(R.string.settings_unit_system), fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = units == UnitSystem.METRIC, onClick = { onUnits(UnitSystem.METRIC) }, label = { Text(stringResource(R.string.units_metric_details)) })
                        FilterChip(selected = units == UnitSystem.IMPERIAL, onClick = { onUnits(UnitSystem.IMPERIAL) }, label = { Text(stringResource(R.string.units_imperial_details)) })
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.settings_saved_sessions), fontWeight = FontWeight.Bold)
        }
        if (sessions.isEmpty()) {
            item {
                Text(stringResource(R.string.settings_no_sessions), color = TextMuted, fontSize = 13.sp)
            }
        } else {
            items(sessions) { s ->
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(s.vehicleLabel, fontWeight = FontWeight.Bold, color = CamaroAmber)
                            IconButton(onClick = { onDeleteSession(s) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Delete, null, tint = WarnRed)
                            }
                        }
                        Text("${sdf.format(Date(s.timestamp))} • ${stringResource(R.string.session_vin_label, s.vin)}", fontSize = 11.sp, color = TextMuted)
                        Text(stringResource(R.string.session_dtcs_label, s.dtcSummary), fontSize = 12.sp, color = CamaroBlue)
                        Text(s.mileageSummary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
}

@Composable
fun BadgeChip(label: String, active: Boolean) {
    Surface(
        color = if (active) OkGreen.copy(alpha = 0.2f) else WarnRed.copy(alpha = 0.2f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 11.sp,
            color = if (active) OkGreen else WarnRed,
            fontWeight = FontWeight.Bold
        )
    }
}
