package com.camarodiag.gen5.ui

import android.Manifest
import android.os.Build
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camarodiag.gen5.domain.model.*
import com.camarodiag.gen5.control.VehicleControlCatalog
import com.camarodiag.gen5.control.ControlAvailability
import com.camarodiag.gen5.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

enum class DiagTab(val labelEn: String, val labelFr: String) {
    SETUP("Vehicle", "Véhicule"),
    ADAPTER("Adapter", "Interface"),
    MODULES("Modules", "Modules"),
    LIVE("Live", "Direct"),
    DTCS("DTCs", "Défauts"),
    MILEAGE("Mileage", "Odomètre"),
    CALIBRATION("ECU / Maps", "ECU / Cartographie"),
    OBD_SCAN("Full OBD Scan", "Scan OBD complet"),
    GUIDE("Guide", "Guide"),
    CONTROL("Controls", "Pilotage"),
    SETTINGS("Settings", "Réglages")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamaroDiagApp(viewModel: MainViewModel) {
    val lang by viewModel.language.collectAsState()
    val units by viewModel.unitSystem.collectAsState()
    val vehicle by viewModel.vehicle.collectAsState()
    val adapter by viewModel.adapter.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val usbDevices by viewModel.usbDevices.collectAsState()
    val connError by viewModel.connectionMessage.collectAsState()
    val modules by viewModel.modules.collectAsState()
    val dtcs by viewModel.dtcs.collectAsState()
    val livePids by viewModel.livePids.collectAsState()
    val isStreaming by viewModel.isStreamingLive.collectAsState()
    val liveRecording by viewModel.liveRecordingState.collectAsState()
    val mileageReport by viewModel.mileageReport.collectAsState()
    val savedSessions by viewModel.savedSessions.collectAsState()
    val calibrationWorkspace by viewModel.calibrationWorkspace.collectAsState()
    val obdScanReport by viewModel.obdScanReport.collectAsState()
    val obdScanMessage by viewModel.obdScanMessage.collectAsState()
    val isObdScanning by viewModel.isObdScanning.collectAsState()

    var currentTab by remember { mutableStateOf(DiagTab.SETUP) }
    var moreExpanded by remember { mutableStateOf(false) }
    val isFr = lang == AppLanguage.FR
    fun t(en: String, fr: String) = if (isFr) fr else en

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

    val calibrationFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importCalibrationFile(it) }
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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bleLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            viewModel.refreshPairedDevices()
        }
    }

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
                            text = "${vehicle.generation.label} • ${vehicle.modelYear} • ${vehicle.engine.rpo} • ${vehicle.market.code}",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                },
                actions = {
                    if (adapter.deviceName.contains("Demo", ignoreCase = true) || adapter.deviceName.contains("Simulated", ignoreCase = true)) {
                        Text("DEMO", color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    } else {
                        Text("OBD", color = OkGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        viewModel.setLanguage(if (isFr) AppLanguage.EN else AppLanguage.FR)
                    }) {
                        Text(if (isFr) "FR 🇫🇷" else "EN 🇺🇸", color = CamaroBlue, fontWeight = FontWeight.Bold)
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
                    selected = currentTab == DiagTab.SETUP,
                    onClick = { currentTab = DiagTab.SETUP },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(if (isFr) "Véhicule" else "Vehicle") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BgDark,
                        selectedTextColor = CamaroAmber,
                        indicatorColor = CamaroAmber
                    )
                )

                NavigationBarItem(
                    selected = currentTab == DiagTab.ADAPTER,
                    onClick = { currentTab = DiagTab.ADAPTER },
                    icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
                    label = { Text(if (isFr) "Connexion" else "Connect") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BgDark,
                        selectedTextColor = CamaroAmber,
                        indicatorColor = CamaroAmber
                    )
                )

                NavigationBarItem(
                    selected = currentTab == DiagTab.LIVE,
                    onClick = { currentTab = DiagTab.LIVE },
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text(if (isFr) "Données" else "Live") },
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
                    label = { Text(if (isFr) "Diagnostic" else "DTCs") },
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
                        DiagTab.CALIBRATION,
                        DiagTab.OBD_SCAN,
                        DiagTab.GUIDE,
                        DiagTab.CONTROL,
                        DiagTab.SETTINGS
                    ),
                    onClick = { moreExpanded = true },
                    icon = { Icon(Icons.Default.MoreVert, contentDescription = null) },
                    label = { Text(if (isFr) "Plus" else "More") },
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
                        DiagTab.CALIBRATION,
                        DiagTab.OBD_SCAN,
                        DiagTab.GUIDE,
                        DiagTab.CONTROL,
                        DiagTab.SETTINGS
                    ).forEach { tab ->
                        DropdownMenuItem(
                            text = {
                                Text(if (isFr) tab.labelFr else tab.labelEn)
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
                .padding(padding)
                .padding(14.dp)
        ) {
            when (currentTab) {
                DiagTab.SETUP -> VehicleSetupScreen(
                    vehicle = vehicle,
                    isFr = isFr,
                    onChange = { y, e, tr, m -> viewModel.updateVehicle(y, e, tr, m) },
                    onVinChange = { viewModel.updateVin(it) }
                )
                DiagTab.ADAPTER -> AdapterScreen(
                    adapter = adapter,
                    pairedDevices = pairedDevices,
                    errorMsg = connError,
                    isFr = isFr,
                    onRefreshDevices = { viewModel.refreshPairedDevices() },
                    onConnectMac = { viewModel.connectRealBluetooth(it) },
                    onSelectSim = { viewModel.selectSimulatedAdapter(it) },
                    usbDevices = usbDevices,
                    onRefreshUsb = { viewModel.refreshPairedDevices() },
                    onConnectUsb = { viewModel.connectUsb(it) },
                    onConnectWifi = { host, port -> viewModel.connectWifi(host, port) }
                )
                DiagTab.MODULES -> ModulesScreen(modules, isFr)
                DiagTab.LIVE -> LiveDataScreen(
                    pids = livePids,
                    units = units,
                    isStreaming = isStreaming,
                    isFr = isFr,
                    recording = liveRecording,
                    onToggleStream = { viewModel.toggleLiveStream() },
                    onToggleUnits = {
                        viewModel.setUnitSystem(if (units == UnitSystem.METRIC) UnitSystem.IMPERIAL else UnitSystem.METRIC)
                    },
                    onToggleRecording = { viewModel.toggleLiveRecording() },
                    onExportRecording = {
                        liveRecordingExporter.launch("CamaroDiag_live_${System.currentTimeMillis()}.csv")
                    }
                )
                DiagTab.DTCS -> DtcScreen(dtcs, isFr, { viewModel.scanDtcs() }, { viewModel.clearDtcs() })
                DiagTab.MILEAGE -> MileageScreen(mileageReport, isFr, { viewModel.runMileageAudit() }, { viewModel.saveCurrentSession() })
                DiagTab.OBD_SCAN -> ObdScanScreen(
                    report = obdScanReport,
                    isScanning = isObdScanning,
                    message = obdScanMessage,
                    isConnected = adapter.tier != AdapterTier.DISCONNECTED &&
                        !adapter.deviceName.contains("Demo", ignoreCase = true) &&
                        !adapter.deviceName.contains("Simulated", ignoreCase = true),
                    isFr = isFr,
                    onRunScan = { viewModel.runFullObdScan() }
                )
                DiagTab.CALIBRATION -> CalibrationWorkspaceScreen(
                    state = calibrationWorkspace,
                    vehicleLabel = "${vehicle.modelYear} Camaro ${vehicle.engine.rpo} • ${vehicle.generation.label}",
                    ecmType = vehicle.engine.ecmType,
                    isConnected = !adapter.deviceName.contains("Demo", ignoreCase = true) &&
                        !adapter.deviceName.contains("Simulated", ignoreCase = true) &&
                        adapter.tier != AdapterTier.DISCONNECTED,
                    isFr = isFr,
                    vehicle = vehicle,
                    onReadIdentification = { viewModel.readCalibrationIdentification() },
                    onImportFile = { calibrationFilePicker.launch(arrayOf("*/*")) },
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
                DiagTab.GUIDE -> HardwareGuideScreen(isFr)
                DiagTab.CONTROL -> VehicleControlsScreen(vehicle, isFr)
                DiagTab.SETTINGS -> SettingsScreen(lang, units, savedSessions, isFr,
                    onLang = { viewModel.setLanguage(it) },
                    onUnits = { viewModel.setUnitSystem(it) },
                    onDeleteSession = { viewModel.deleteSavedSession(it) }
                )
            }
        }
    }
}

@Composable
fun VehicleSetupScreen(
    vehicle: VehicleProfile,
    isFr: Boolean,
    onChange: (Int, CamaroEngine, TransmissionType, MarketRegion) -> Unit,
    onVinChange: (String) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionTitle(if (isFr) "Configuration du véhicule" else "Vehicle configuration")
            Text("CHEVROLET CAMARO • ${vehicle.generation.label.uppercase()} • ${vehicle.modelYear}", color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isFr) "Génération" else "Generation", color = TextMuted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CamaroGeneration.entries.forEach { gen ->
                            FilterChip(selected = vehicle.generation == gen, onClick = {
                                val year = if (gen == CamaroGeneration.GEN5) 2015 else 2016
                                onChange(year, vehicle.engine, vehicle.transmission, vehicle.market)
                            }, label = { Text(if (isFr) gen.label.replace("Gen", "Génération") else gen.label) })
                        }
                    }
                    Text(if (isFr) "Année modèle" else "Model year", color = TextMuted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        (2010..2024).forEach { yr ->
                            FilterChip(
                                selected = vehicle.modelYear == yr,
                                onClick = { onChange(yr, vehicle.engine, vehicle.transmission, vehicle.market) },
                                label = { Text("$yr") }
                            )
                        }
                    }

                    Text(if (isFr) "Motorisation (Code RPO & Calculateur)" else "Engine (RPO Code & ECM)", color = TextMuted, fontSize = 13.sp)
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
                                Text("${eng.rpo} — ${if (isFr) eng.nameFr else eng.nameEn}", fontWeight = FontWeight.SemiBold)
                                Text("ECM: ${eng.ecmType}", fontSize = 12.sp, color = CamaroBlue)
                            }
                            if (vehicle.engine == eng) Icon(Icons.Default.CheckCircle, null, tint = CamaroAmber)
                        }
                    }

                    Text(if (isFr) "Transmission" else "Transmission", color = TextMuted, fontSize = 13.sp)
                    TransmissionType.entries.forEach { tr ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChange(vehicle.modelYear, vehicle.engine, tr, vehicle.market) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = vehicle.transmission == tr, onClick = { onChange(vehicle.modelYear, vehicle.engine, tr, vehicle.market) })
                            Text(if (isFr) tr.labelFr else tr.labelEn)
                        }
                    }

                    Text(if (isFr) "Marché / Homologation" else "Market Region", color = TextMuted, fontSize = 13.sp)
                    MarketRegion.entries.forEach { mk ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChange(vehicle.modelYear, vehicle.engine, vehicle.transmission, mk) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = vehicle.market == mk, onClick = { onChange(vehicle.modelYear, vehicle.engine, vehicle.transmission, mk) })
                            Text(if (isFr) mk.labelFr else mk.labelEn)
                        }
                    }
                    Text(if (isFr) "Numéro VIN" else "Vehicle VIN", color = TextMuted, fontSize = 13.sp)
                    OutlinedTextField(
                        value = vehicle.vin,
                        onValueChange = onVinChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("17 caractères") },
                        supportingText = { Text(if (isFr) "Saisir le VIN réel : aucun VIN fictif n'est généré." else "Enter the actual VIN; no sample VIN is generated.") }
                    )
                }
            }
        }
    }
}

@Composable
fun AdapterScreen(
    adapter: AdapterCapabilities,
    pairedDevices: List<Pair<String, String>>,
    errorMsg: String?,
    isFr: Boolean,
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionTitle(if (isFr) "Interfaces OBD-II : Bluetooth, USB et Wi-Fi" else "Bluetooth OBD-II Adapter & CAN Probe")
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(adapter.deviceName, fontWeight = FontWeight.Bold, color = CamaroAmber, fontSize = 16.sp)
                    Text(if (isFr) adapter.tier.titleFr else adapter.tier.titleEn, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BadgeChip("HS-CAN 500k", true)
                        BadgeChip("SW-CAN GMLAN (Pin 1)", adapter.supportsSwCan)
                        BadgeChip("ISO-TP Long Frames", adapter.supportsLongFrames)
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
                Text(if (isFr) "Appareils Bluetooth Appairés" else "Paired Bluetooth Devices", fontWeight = FontWeight.Bold)
                TextButton(onClick = onRefreshDevices) { Text(if (isFr) "Actualiser" else "Refresh") }
            }
            if (pairedDevices.isEmpty()) {
                Text(
                    if (isFr) "Aucun adaptateur détecté. Les OBDLink MX+, MX, LX et autres Bluetooth classiques doivent être appairés ; le CX est recherché en BLE à proximité."
                    else "No adapter detected. Pair OBDLink MX+, MX, LX and other Classic Bluetooth adapters; CX is discovered nearby over BLE.",
                    color = TextMuted, fontSize = 13.sp
                )
            } else {
                pairedDevices.forEach { (name, mac) ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onConnectMac(mac) },
                        colors = CardDefaults.cardColors(containerColor = SurfaceElevated)
                    ) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(name, fontWeight = FontWeight.SemiBold)
                                Text(mac, fontSize = 12.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                            }
                            Text(if (isFr) "Connecter" else "Connect", color = CamaroBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            errorMsg?.let { Text(it, color = WarnRed, fontSize = 12.sp) }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (isFr) "Connexion USB (OBDLink EX / SX, ELM327 et interfaces série)" else "USB connection (OBDLink EX / SX, ELM327 and serial adapters)", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isFr) "Périphériques USB détectés" else "Detected USB devices", modifier = Modifier.weight(1f))
                        TextButton(onClick = onRefreshUsb) { Text(if (isFr) "Actualiser" else "Refresh") }
                    }
                    if (usbDevices.isEmpty()) Text(if (isFr) "Aucun adaptateur série USB détecté." else "No USB serial adapter detected.", color = TextMuted)
                    usbDevices.forEach { label -> OutlinedButton(onClick = { onConnectUsb(label) }, modifier = Modifier.fillMaxWidth()) { Text("Connecter : $label") } }
                    HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                    Text(if (isFr) "Connexion Wi-Fi ELM327" else "Wi-Fi ELM327 connection", fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = wifiHost, onValueChange = { wifiHost = it }, label = { Text(if (isFr) "Adresse IP de l'adaptateur" else "Adapter IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = wifiPort, onValueChange = { wifiPort = it.filter(Char::isDigit).take(5) }, label = { Text("Port TCP (souvent 35000 ou 23)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onConnectWifi(wifiHost, wifiPort.toIntOrNull() ?: 35000) }, modifier = Modifier.fillMaxWidth()) { Text(if (isFr) "Connecter en Wi-Fi" else "Connect over Wi-Fi") }
                    Text(if (isFr) "VXDIAG : architecture de transport prévue, mais les interfaces J2534 ne sont pas déclarées compatibles Android sans SDK Android officiel du modèle. Aucun accès J2534 n'est simulé." else "VXDIAG: transport architecture is prepared, but J2534 interfaces are not claimed Android-compatible without the model's official Android SDK. No J2534 access is simulated.", color = TextMuted, fontSize = 12.sp)
                }
            }
        }
        item {
            Text(if (isFr) "Profils de Simulation (Test sans véhicule)" else "Simulated Hardware Profiles (Bench Test)", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSelectSim("MX_PLUS") }) { Text("OBDLink MX+") }
                OutlinedButton(onClick = { onSelectSim("ELM_15") }) { Text("ELM327 v1.5") }
                OutlinedButton(onClick = { onSelectSim("CLONE") }) { Text("Clone v2.1") }
            }
        }
    }
}

@Composable
fun VehicleControlsScreen(vehicle: VehicleProfile, isFr: Boolean) {
    val capabilities = remember(vehicle) { VehicleControlCatalog().forVehicle(vehicle) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionTitle(if (isFr) "Pilotage des équipements" else "Vehicle controls")
            Text(if (isFr) "Camaro ${vehicle.modelYear} • ${vehicle.generation.label} • ${vehicle.engine.rpo}" else "Camaro ${vehicle.modelYear} • ${vehicle.generation.label} • ${vehicle.engine.rpo}", color = CamaroAmber)
            Text(if (isFr) "Les commandes carrosserie GM sont spécifiques au VIN, au BCM et à la calibration. Elles restent désactivées tant que les DID, sessions de sécurité et séquences de commande n'ont pas été vérifiés sur ce véhicule." else "GM body commands are VIN-, BCM- and calibration-specific. They remain disabled until DIDs, security sessions and command sequences are verified on this vehicle.", color = TextMuted, fontSize = 12.sp)
        }
        items(capabilities) { capability ->
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (isFr) capability.titleFr else capability.titleEn, fontWeight = FontWeight.Bold)
                        Text(if (isFr) capability.explanationFr else capability.explanationEn, color = TextMuted, fontSize = 12.sp)
                    }
                    Text(if (isFr) "NON VALIDÉ" else "NOT VERIFIED", color = WarnRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Text(if (isFr) "Aucune trame d'ouverture de vitre, de déverrouillage ou d'actionneur n'est envoyée par défaut. Cela évite les commandes erronées ou dangereuses." else "No window, unlock or actuator frame is sent by default. This prevents incorrect or unsafe commands.", color = TextMuted, fontSize = 12.sp) }
    }
}

@Composable
fun ModulesScreen(modules: List<EcuModule>, isFr: Boolean) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionTitle(if (isFr) "Calculateurs du véhicule" else "Vehicle control modules")
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
                ModuleStatus.ACCESSIBLE -> if (isFr) "Accessible" else "Accessible"
                ModuleStatus.REQUIRES_SWCAN -> if (isFr) "Requiert SW-CAN (Pin 1)" else "Requires SW-CAN (Pin 1)"
                ModuleStatus.UNSUPPORTED_BY_ADAPTER -> if (isFr) "Bloqué par adaptateur" else "Blocked by adapter"
                ModuleStatus.NOT_EQUIPPED -> if (isFr) "Non équipé" else "Not equipped"
                ModuleStatus.DISCOVERY_REQUIRED -> if (isFr) "À identifier sur le véhicule" else "Vehicle discovery required"
            }
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${mod.shortName} • ${mod.bus.label}", fontWeight = FontWeight.Bold, color = CamaroBlue)
                        Text(statusLabel, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Text(if (isFr) mod.nameFr else mod.nameEn, fontWeight = FontWeight.SemiBold)
                    if (mod.txHeader.isNotBlank() && mod.rxHeader.isNotBlank()) {
                        Text("TX: 0x${mod.txHeader} | RX: 0x${mod.rxHeader} (${mod.bus.pinout})", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
                    }
                    Text(if (isFr) mod.notesFr else mod.notesEn, fontSize = 12.sp, color = TextMuted)
                }
            }
        }
    }
}

@Composable
fun LiveDataScreen(
    pids: List<LivePidValue>,
    units: UnitSystem,
    isStreaming: Boolean,
    isFr: Boolean,
    recording: LiveRecordingState,
    onToggleStream: () -> Unit,
    onToggleUnits: () -> Unit,
    onToggleRecording: () -> Unit,
    onExportRecording: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(if (isFr) "Données Moteur Temps Réel" else "Live Engine Telemetry")
                    Row {
                        TextButton(onClick = onToggleUnits) {
                            Text(if (units == UnitSystem.METRIC) "SI / Metric" else "Imperial", color = CamaroBlue)
                        }
                        TextButton(onClick = onToggleStream) {
                            Text(if (isStreaming) "Pause" else if (isFr) "Reprendre" else "Resume", color = CamaroAmber)
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
                                        if (isFr) "Arrêter l'enregistrement" else "Stop recording"
                                    } else {
                                        if (isFr) "Enregistrer les données" else "Record live data"
                                    },
                                    color = Color.Black
                                )
                            }
                            OutlinedButton(
                                onClick = onExportRecording,
                                enabled = recording.sampleCount > 0 && !recording.isRecording,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isFr) "Exporter CSV" else "Export CSV")
                            }
                        }
                        Text(
                            if (isFr) "${recording.sampleCount} échantillon(s) • ${if (recording.isRecording) "ENREGISTREMENT" else "ARRÊTÉ"}"
                            else "${recording.sampleCount} sample(s) • ${if (recording.isRecording) "RECORDING" else "STOPPED"}",
                            color = if (recording.isRecording) CamaroAmber else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        recording.message.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = TextMuted, fontSize = 11.sp)
                        }
                        Text(
                            if (isFr) "Le journal enregistre les valeurs OBD affichées (métrique) avec leur horodatage. Il ne modifie pas les tables du calculateur."
                            else "The logger records displayed OBD values (metric) with timestamps. It does not alter ECU calibration tables.",
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
                        Text(if (isFr) pid.labelFr else pid.labelEn, color = TextMuted, fontSize = 13.sp)
                        Text(
                            text = "${String.format("%.1f", displayVal)} $displayUnit",
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
fun DtcScreen(dtcs: List<DiagnosticDtc>, isFr: Boolean, onScan: () -> Unit, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(if (isFr) "Codes Défaut (DTC)" else "Diagnostic Trouble Codes")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onScan) { Text(if (isFr) "Scanner" else "Scan") }
                    Button(onClick = { confirmClear = true }, colors = ButtonDefaults.buttonColors(containerColor = WarnRed)) {
                        Text(if (isFr) "Effacer" else "Clear", color = Color.White)
                    }
                }
            }
        }
        item {
            Text(
                if (isFr) "L'effacement Mode 04 supprime les défauts d'émissions et réinitialise certains moniteurs de readiness. Enregistrer le rapport avant de continuer."
                else "Mode 04 clearing removes emissions-related trouble codes and resets some readiness monitors. Save the report before continuing.",
                color = TextMuted, fontSize = 11.sp
            )
        }
        if (dtcs.isEmpty()) {
            item {
                Text(if (isFr) "Aucun code défaut retourné par le dernier scan." else "No trouble codes returned by the last scan.", color = OkGreen)
            }
        } else {
            items(dtcs) { dtc ->
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${dtc.code} (${dtc.module})", fontWeight = FontWeight.Bold, color = if (dtc.isPending) CamaroAmber else WarnRed, fontSize = 16.sp)
                            Text(if (isFr) dtc.statusFr else dtc.statusEn, fontSize = 12.sp, color = TextMuted)
                        }
                        Text(if (isFr) dtc.descFr else dtc.descEn)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(if (isFr) "Confirmer l'effacement des défauts ?" else "Confirm DTC clearing?") },
            text = { Text(if (isFr) "Cette opération envoie le service OBD Mode 04 à l'ECU. Les données de readiness peuvent être réinitialisées. Elle ne répare pas la cause du défaut." else "This sends OBD Mode 04 to the ECU. Readiness data may be reset. It does not repair the cause of a fault.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarnRed)
                ) { Text(if (isFr) "Effacer les défauts" else "Clear codes") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(if (isFr) "Annuler" else "Cancel") }
            }
        )
    }
}

@Composable
fun MileageScreen(
    report: MileageAuditReport?,
    isFr: Boolean,
    onRefreshAudit: () -> Unit,
    onSaveReport: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(if (isFr) "Contrôle de cohérence kilométrique" else "Mileage consistency check")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onRefreshAudit) { Text(if (isFr) "Relire" else "Re-read") }
                    Button(onClick = onSaveReport) { Text(if (isFr) "Sauver" else "Save") }
                }
            }
        }
        report?.let { rep ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = if (rep.hasDiscrepancy) Color(0xFF3B1518) else SurfaceElevated)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (isFr) "Synthèse Technique (VIN: ${rep.vin})" else "Engineering Summary (VIN: ${rep.vin})",
                            fontWeight = FontWeight.Bold,
                            color = if (rep.hasDiscrepancy) WarnRed else OkGreen
                        )
                        Text(if (isFr) rep.summaryFr else rep.summaryEn, fontSize = 13.sp)
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
                            Text(if (isFr) item.moduleNameFr else item.moduleNameEn, fontWeight = FontWeight.Bold)
                            Text(
                                text = item.kmValue?.let { "${String.format("%,.1f", it)} km" } ?: "N/A",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (item.kmValue != null) CamaroBlue else TextMuted
                            )
                        }
                        Text(item.didInfo, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
                        Text(if (isFr) item.confidence.labelFr else item.confidence.labelEn, color = confColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (isFr) item.caveatFr else item.caveatEn, fontSize = 12.sp, color = TextMuted)
                    }
                }
            }
        }
    }
}

@Composable
fun HardwareGuideScreen(isFr: Boolean) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionTitle(if (isFr) "Guide d'Achat & Compatibilité OBD-II GM" else "OBD-II Hardware & GM GMLAN Buying Guide")
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (isFr) "1. Recommandé : OBDLink MX+ (Puce STN2255)" else "1. Recommended: OBDLink MX+ (STN2255 Chip)",
                        fontWeight = FontWeight.Bold, color = OkGreen
                    )
                    Text(
                        if (isFr) "Interface STN haut de gamme à privilégier pour les diagnostics GM avancés. La prise en charge physique SW-CAN dépend du modèle exact de l'interface et ne garantit pas à elle seule l'accès au BCM, à l'IPC ou au SDM : les protocoles et identifiants doivent être validés pour le VIN."
                        else "A high-end STN interface is recommended for advanced GM diagnostics. Physical SW-CAN support depends on the exact adapter model and does not by itself guarantee BCM, IPC or SDM access; protocols and identifiers must be validated for the VIN.",
                        fontSize = 13.sp
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (isFr) "2. Alternative HS-CAN : vLinker MC+ / OBDLink LX" else "2. HS-CAN Alternative: vLinker MC+ / OBDLink LX",
                        fontWeight = FontWeight.Bold, color = CamaroAmber
                    )
                    Text(
                        if (isFr) "Excellente fiabilité sur le bus moteur/boîte/ABS (HS-CAN 500 kbps). Le vLinker MC+ gère également le SW-CAN GMLAN."
                        else "High-throughput reliability on powertrain/ABS HS-CAN (500 kbps). The vLinker MC+ also supports GMLAN SW-CAN.",
                        fontSize = 13.sp
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (isFr) "3. Limites des Clones ELM327 v2.1 Génériques" else "3. Limitations of Generic ELM327 v2.1 Clones",
                        fontWeight = FontWeight.Bold, color = WarnRed
                    )
                    Text(
                        if (isFr) "Les boîtiers bleus ou mini ELM327 à bas coût n'ont aucun circuit physique sur la broche 1 (SW-CAN), possèdent des mémoires tampon tronquées et rejettent souvent les commandes d'adressage CAN étendu. L'accès aux modules dépend toujours de l'équipement du véhicule, de la puce de l'interface et du protocole supporté."
                        else "Low-cost blue/mini ELM327 clones lack physical transceivers on Pin 1 (SW-CAN), suffer from truncated RX buffers, and often fail extended CAN header addressing. Module access always depends on vehicle equipment, adapter hardware, and supported protocols.",
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    lang: AppLanguage,
    units: UnitSystem,
    sessions: List<com.camarodiag.gen5.data.db.SavedSessionEntity>,
    isFr: Boolean,
    onLang: (AppLanguage) -> Unit,
    onUnits: (UnitSystem) -> Unit,
    onDeleteSession: (com.camarodiag.gen5.data.db.SavedSessionEntity) -> Unit
) {
    val sdf = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionTitle(if (isFr) "Paramètres & Rapports Enregistrés" else "Settings & Saved Reports")
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isFr) "Langue de l'application" else "Application Language", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppLanguage.entries.forEach { l ->
                            FilterChip(selected = lang == l, onClick = { onLang(l) }, label = { Text(l.label) })
                        }
                    }
                    Text(if (isFr) "Système d'unités" else "Measurement Units", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = units == UnitSystem.METRIC, onClick = { onUnits(UnitSystem.METRIC) }, label = { Text("Metric (km, °C, kPa)") })
                        FilterChip(selected = units == UnitSystem.IMPERIAL, onClick = { onUnits(UnitSystem.IMPERIAL) }, label = { Text("Imperial (mi, °F, PSI)") })
                    }
                }
            }
        }
        item {
            Text(if (isFr) "Sessions Sauvegardées (Base Locale Room)" else "Saved Diagnostic Sessions (Local Room DB)", fontWeight = FontWeight.Bold)
        }
        if (sessions.isEmpty()) {
            item {
                Text(if (isFr) "Aucune session enregistrée. Utilisez le bouton 'Sauver' dans l'onglet Odomètre." else "No saved sessions yet. Use 'Save' in the Mileage tab.", color = TextMuted, fontSize = 13.sp)
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
                        Text("${sdf.format(Date(s.timestamp))} • VIN: ${s.vin}", fontSize = 11.sp, color = TextMuted)
                        Text("DTCs: ${s.dtcSummary}", fontSize = 12.sp, color = CamaroBlue)
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
