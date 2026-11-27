package com.camarodiag.gen5.ui

import com.camarodiag.gen5.auth.LicenseLevel
import com.camarodiag.gen5.auth.LicenseRolloutConfig

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.core.content.edit
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.camarodiag.gen5.data.obd.CanConsoleCapabilityPolicy
import com.camarodiag.gen5.data.obd.CanConsoleDisplayFilter
import com.camarodiag.gen5.data.obd.CanConsoleExportPolicy
import com.camarodiag.gen5.data.obd.ObservedCanId
import com.camarodiag.gen5.data.obd.ParsedCanConsoleRecord
import com.camarodiag.gen5.data.obd.CanTraceFilter
import com.camarodiag.gen5.data.obd.RawCanFrameParser
import com.camarodiag.gen5.data.obd.SafeRawCanTransmitPolicy
import com.camarodiag.gen5.data.obd.EcuWriteConsentFlow
import com.camarodiag.gen5.data.obd.CanTraceDirection
import com.camarodiag.gen5.data.obd.filterCanTrace
import com.camarodiag.gen5.data.obd.UdsNegativeResponse
import com.camarodiag.gen5.data.obd.J2534LiveMemoryBackup
import com.camarodiag.gen5.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
private fun canTracePidDisplayName(pid: Int, fallback: String): String = when (pid) {
    0x1F -> stringResource(R.string.can_trace_pid_1f)
    0x21 -> stringResource(R.string.can_trace_pid_21)
    0x2F -> stringResource(R.string.can_trace_pid_2f)
    0x31 -> stringResource(R.string.can_trace_pid_31)
    0x33 -> stringResource(R.string.can_trace_pid_33)
    0x3C -> stringResource(R.string.can_trace_pid_3c)
    0x3D -> stringResource(R.string.can_trace_pid_3d)
    0x3E -> stringResource(R.string.can_trace_pid_3e)
    0x3F -> stringResource(R.string.can_trace_pid_3f)
    0x47 -> stringResource(R.string.can_trace_pid_47)
    0x48 -> stringResource(R.string.can_trace_pid_48)
    0x49 -> stringResource(R.string.can_trace_pid_49)
    0x4A -> stringResource(R.string.can_trace_pid_4a)
    0x4B -> stringResource(R.string.can_trace_pid_4b)
    0x4D -> stringResource(R.string.can_trace_pid_4d)
    0x4E -> stringResource(R.string.can_trace_pid_4e)
    0x5A -> stringResource(R.string.can_trace_pid_5a)
    0x5C -> stringResource(R.string.can_trace_pid_5c)
    0x5D -> stringResource(R.string.can_trace_pid_5d)
    0x5E -> stringResource(R.string.can_trace_pid_5e)
    0x61 -> stringResource(R.string.can_trace_pid_61)
    0x62 -> stringResource(R.string.can_trace_pid_62)
    0x63 -> stringResource(R.string.can_trace_pid_63)
    else -> fallback
}

enum class DiagTab(val labelEn: String, val labelFr: String, val labelResourceId: Int) {
    SETUP("Vehicle", "Véhicule", R.string.tab_vehicle),
    ADAPTER("Adapter", "Interface", R.string.tab_connect),
    MODULES("Modules", "Modules", R.string.tab_modules),
    LIVE("Live", "Direct", R.string.tab_live),
    CAN_SNIFFER("CAN / OBD Console", "Console CAN / OBD", R.string.tab_can_sniffer),
    DTCS("DTCs", "Défauts", R.string.tab_dtcs),
    MILEAGE("Mileage", "Odomètre", R.string.tab_mileage),
    CALIBRATION("ECU / Maps", "ECU / Cartographie", R.string.tab_calibration),
    OBD_SCAN("Full OBD Scan", "Scan OBD complet", R.string.tab_obd_scan),
    FUNCTIONAL_TESTS("Functional Tests", "Tests fonctionnels", R.string.tab_functional_tests),
    DEBUG_DOWNLOADS("Debug Builds", "Versions Debug", R.string.tab_debug_builds),
    LICENSE("License", "Licence", R.string.tab_license),
    GUIDE("Guide", "Guide", R.string.tab_guide),
    CONTROL("Controls", "Pilotage", R.string.tab_controls),
    ABOUT("About", "À propos", R.string.about_title),
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
fun CamaroDiagApp(
    viewModel: MainViewModel, systemLocale: Locale, onOpenAccount: () -> Unit,
    licenseLevel: LicenseLevel,
    onRefreshLicense: () -> Unit
) {
    val ownerTestsAllowed = licenseLevel == LicenseLevel.OWNER
    val premiumAllowed = !LicenseRolloutConfig.ENABLED || licenseLevel != LicenseLevel.FREE
    val premiumTabs = remember {
        setOf(
            DiagTab.MODULES, DiagTab.CAN_SNIFFER, DiagTab.MILEAGE,
            DiagTab.CALIBRATION, DiagTab.OBD_SCAN, DiagTab.CONTROL
        )
    }
    val lang by viewModel.language.collectAsState()
    val followSystemLanguage by viewModel.followSystemLanguage.collectAsState()
    val units by viewModel.unitSystem.collectAsState()
    val vehicle by viewModel.vehicle.collectAsState()
    val savedVehicles by viewModel.savedVehicles.collectAsState()
    val accountUid by viewModel.activeAccountUid.collectAsState()
    val legacyVehicleImportAvailable by viewModel.legacyVehicleImportAvailable.collectAsState()
    val selectedSavedVehicleId by viewModel.selectedSavedVehicleId.collectAsState()
    val adapter by viewModel.adapter.collectAsState()
    val connectionGeneration by viewModel.connectionGeneration.collectAsState()
    val lastConnectionProfile by viewModel.lastConnectionProfile.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val usbDevices by viewModel.usbDevices.collectAsState()
    val connError by viewModel.connectionMessage.collectAsState()
    val connectionLossMessage by viewModel.connectionLossMessage.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val modules by viewModel.modules.collectAsState()
    val dtcs by viewModel.dtcs.collectAsState()
    val livePids by viewModel.livePids.collectAsState()
    val detectedLiveSensors by viewModel.detectedLiveSensors.collectAsState()
    val dtcScanNotice by viewModel.dtcScanNotice.collectAsState()
    val isStreaming by viewModel.isStreamingLive.collectAsState()
    val liveRecording by viewModel.liveRecordingState.collectAsState()
    val liveTelemetryMessage by viewModel.liveTelemetryMessage.collectAsState()
    val mileageReport by viewModel.mileageReport.collectAsState()
    val observedStandardVin by viewModel.observedStandardVin.collectAsState()
    val observedObdResponders by viewModel.observedObdResponders.collectAsState()
    val savedSessions by viewModel.savedSessions.collectAsState()
    val calibrationWorkspace by viewModel.calibrationWorkspace.collectAsState()
    val liveCanMonitorState by viewModel.liveCanMonitorState.collectAsState()
    val obdScanReport by viewModel.obdScanReport.collectAsState()
    val obdScanMessage by viewModel.obdScanMessage.collectAsState()
    val isObdScanning by viewModel.isObdScanning.collectAsState()
    val functionalTests by viewModel.functionalTestState.collectAsState()

    var currentTab by remember { mutableStateOf(DiagTab.SETUP) }
    var premiumPreviewTab by remember { mutableStateOf<DiagTab?>(null) }
    var moreExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(licenseLevel) {
        if (!LicenseRolloutConfig.ENABLED && currentTab == DiagTab.LICENSE)
            currentTab = DiagTab.SETUP
        if (!ownerTestsAllowed &&
            currentTab in setOf(DiagTab.FUNCTIONAL_TESTS, DiagTab.DEBUG_DOWNLOADS)
        ) currentTab = if (LicenseRolloutConfig.ENABLED) DiagTab.LICENSE else DiagTab.SETUP
        if (!premiumAllowed && currentTab in premiumTabs) currentTab = DiagTab.LICENSE
    }
    val permissionContext = LocalContext.current
    val exportScope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<(() -> Unit)?>(null) }
    val exportFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val action = pendingExport
        pendingExport = null
        if (uri != null) {
            if (CamaroDiagExportStorage.authorize(permissionContext, uri)) {
                action?.invoke()
            } else {
                Toast.makeText(permissionContext, R.string.export_folder_instruction, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun withExportFolder(action: () -> Unit) {
        if (pendingExport != null) return
        val owner = viewModel.activeAccountUid.value ?: return
        val generation = viewModel.exportAccountGeneration()
        val selectedProfile = viewModel.vehicle.value
        val guardedAction: () -> Unit = {
            if (viewModel.canResumeExport(generation, owner) &&
                viewModel.vehicle.value == selectedProfile) action()
        }
        pendingExport = guardedAction
        exportScope.launch {
            try {
                if (CamaroDiagExportStorage.availableTree(permissionContext) != null) {
                    pendingExport = null
                    guardedAction()
                } else if (viewModel.canResumeExport(generation, owner) &&
                    viewModel.vehicle.value == selectedProfile) {
                    Toast.makeText(permissionContext, R.string.export_folder_instruction, Toast.LENGTH_LONG).show()
                    exportFolderPicker.launch(CamaroDiagExportStorage.initialPickerUri())
                } else {
                    pendingExport = null
                }
            } catch (cancelled: CancellationException) {
                pendingExport = null
                throw cancelled
            } catch (_: Exception) {
                pendingExport = null
                Toast.makeText(permissionContext, R.string.export_folder_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    var pendingBluetoothMac by remember { mutableStateOf<String?>(null) }
    val btPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            val selected = pendingBluetoothMac
            if (selected == null) viewModel.refreshPairedDevices()
            else viewModel.connectRealBluetooth(selected)
        }
        pendingBluetoothMac = null
    }

    val bleLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val selected = pendingBluetoothMac
            if (selected == null) viewModel.refreshPairedDevices()
            else viewModel.connectRealBluetooth(selected)
        }
        pendingBluetoothMac = null
    }

    // Bluetooth permissions are requested ONLY after the user explicitly
    // refreshes paired devices or selects a Bluetooth adapter, never at app launch.
    fun withBluetoothPermission(mac: String? = null) {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (required.all {
            ContextCompat.checkSelfPermission(permissionContext, it) == PackageManager.PERMISSION_GRANTED
        }) {
            if (mac == null) viewModel.refreshPairedDevices()
            else viewModel.connectRealBluetooth(mac)
        } else {
            pendingBluetoothMac = mac
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                btPermLauncher.launch(required)
            } else {
                bleLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
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

    var pendingDoIpProfile by remember { mutableStateOf<Triple<String, Long, VehicleProfile>?>(null) }
    val doIpProfilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val context = pendingDoIpProfile
        pendingDoIpProfile = null
        if (uri != null && context != null && viewModel.canResumeExport(context.second, context.first) &&
            viewModel.vehicle.value == context.third) viewModel.importDoIpBackupProfile(uri)
    }

    var pendingDoIpArchive by remember {
        mutableStateOf<Pair<Triple<String, Long, VehicleProfile>, com.camarodiag.gen5.data.obd.DoIpMemoryBackup.Scope>?>(null)
    }
    val doIpArchivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pendingDoIpArchive
        pendingDoIpArchive = null
        if (uri != null && pending != null && viewModel.canResumeExport(pending.first.second, pending.first.first) &&
            viewModel.vehicle.value == pending.first.third)
            viewModel.verifyDirectDoIpBackup(uri, pending.second)
    }

    var pendingPhysicalRecovery by remember {
        mutableStateOf<Pair<Pair<String, Long>, PhysicalRecoveryIdentity>?>(null)
    }
    val physicalRecoveryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pending = pendingPhysicalRecovery
        pendingPhysicalRecovery = null
        if (uri != null && pending != null && viewModel.canResumeExport(pending.first.second, pending.first.first)) {
            viewModel.inspectPhysicalRecoveryFolder(uri, pending.second)
        }
    }

    val ecuBackupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::verifyEcuBackup)
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

    val premiumPreview = premiumPreviewTab
    if (premiumPreview != null) {
        val frenchPreview = LocalConfiguration.current.locales[0].language == "fr"
        val summary = when (premiumPreview) {
            DiagTab.MODULES -> if (frenchPreview) "Explorer les calculateurs et consulter leurs informations détaillées." else "Explore ECU modules and inspect detailed module information."
            DiagTab.CAN_SNIFFER -> if (frenchPreview) "Analyser les trames CAN, filtrer les échanges et utiliser les outils de diagnostic bus autorisés." else "Inspect and filter CAN traffic with supported bus diagnostics."
            DiagTab.MILEAGE -> if (frenchPreview) "Comparer les informations kilométriques disponibles et générer un rapport d'analyse." else "Compare available mileage information and generate an analysis report."
            DiagTab.CALIBRATION -> if (frenchPreview) "Consulter et gérer les fonctions ECU, sauvegardes et cartographies selon la compatibilité du calculateur." else "Access supported ECU, backup and calibration workflows."
            DiagTab.OBD_SCAN -> if (frenchPreview) "Effectuer un scan complet des modules et centraliser les résultats." else "Run a full module scan and review combined results."
            DiagTab.CONTROL -> if (frenchPreview) "Accéder aux commandes véhicule compatibles, avec les protections de sécurité nécessaires." else "Use supported vehicle controls with required safeguards."
            else -> if (frenchPreview) "Fonctionnalité avancée de diagnostic CamaroDiag." else "Advanced CamaroDiag diagnostic feature."
        }
        AlertDialog(
            onDismissRequest = { premiumPreviewTab = null },
            title = { Text(stringResource(premiumPreview.labelResourceId)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(summary)
                    Text(
                        if (frenchPreview) "Cette fonction nécessite une licence CamaroDiag. Les données temps réel et les codes défauts restent gratuits."
                        else "This feature requires a CamaroDiag license. Live data and trouble codes remain free."
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { premiumPreviewTab = null }) {
                    Text(if (frenchPreview) "Compris" else "Got it")
                }
            }
        )
    }

     Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 19.sp,
                            color = CamaroAmber,
                            letterSpacing = 1.6.sp
                        )
                        Text(
                            text = observedStandardVin?.takeUnless(VehicleVinMatcher::isCamaroCandidate)?.let {
                                stringResource(R.string.obd_connected_other_vehicle, it)
                            } ?: "${localizedGenerationName(vehicle.generation)} • ${vehicle.modelYear} • ${vehicle.engine.rpo} • ${vehicle.market.code}",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenAccount) {
                        Icon(Icons.Default.AccountCircle,
                            contentDescription = stringResource(R.string.app_account_action))
                    }
                    when {
                        adapter.deviceName.contains("Demo", ignoreCase = true) ||
                            adapter.deviceName.contains("Simulated", ignoreCase = true) -> {
                            Text("DEMO", color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        calibrationWorkspace.isJ2534BridgeConnected -> {
                            Text("PC J2534", color = OkGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp)
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
                        DiagTab.FUNCTIONAL_TESTS,
                        DiagTab.DEBUG_DOWNLOADS,
                        DiagTab.LICENSE,
                        DiagTab.GUIDE,
                        DiagTab.CONTROL,
                        DiagTab.ABOUT,
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
                    (listOf(DiagTab.MODULES) +
                        (if (LicenseRolloutConfig.ENABLED) listOf(DiagTab.LICENSE) else emptyList()) +
                        (if (ownerTestsAllowed) listOf(DiagTab.FUNCTIONAL_TESTS, DiagTab.DEBUG_DOWNLOADS) else emptyList()) +
                        listOf(
                        DiagTab.MILEAGE,
                        DiagTab.CAN_SNIFFER,
                        DiagTab.CALIBRATION,
                        DiagTab.OBD_SCAN,
                        DiagTab.GUIDE,
                        DiagTab.CONTROL,
                        DiagTab.ABOUT
                    )).forEach { tab ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    (if (!premiumAllowed && tab in premiumTabs) "🔒 " else "") +
                                        stringResource(tab.labelResourceId),
                                    color = if (!premiumAllowed && tab in premiumTabs) TextMuted else TextPrimary
                                )
                            },
                            onClick = {
                                if (!premiumAllowed && tab in premiumTabs) {
                                    premiumPreviewTab = tab
                                } else {
                                    currentTab = tab
                                }
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
            connectionLossMessage?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4A2020)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, WarnRed)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = WarnRed)
                        Text(message, modifier = Modifier.weight(1f), color = TextPrimary)
                        IconButton(onClick = { viewModel.dismissConnectionLoss() }) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = TextPrimary)
                        }
                    }
                }
            }
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
            when (if (!premiumAllowed && currentTab in premiumTabs)
                DiagTab.LICENSE else currentTab) {
                DiagTab.SETUP -> VehicleSetupScreen(
                    vehicle = vehicle,
                    savedVehicles = savedVehicles,
                    legacyVehicleImportAvailable = legacyVehicleImportAvailable,
                    selectedSavedVehicleId = selectedSavedVehicleId,
                    onChange = { y, e, tr, m -> viewModel.updateVehicle(y, e, tr, m) },
                    onVinChange = { viewModel.updateVin(it) },
                    onCaptureVinPhoto = { vinPhotoLauncher.launch(null) },
                    onSaveVehicle = { viewModel.saveCurrentVehicle(it) },
                    onImportLegacyVehicles = { viewModel.importLegacyVehicles() },
                    onLoadSavedVehicle = { viewModel.selectSavedVehicle(it) },
                    onDeleteSavedVehicle = { viewModel.deleteSavedVehicle(it) }
                )
                DiagTab.ADAPTER -> AdapterScreen(
                    adapter = adapter,
                    isJ2534BridgeConnected = calibrationWorkspace.isJ2534BridgeConnected,
                    isBusy = isBusy || isObdScanning || calibrationWorkspace.isReading,
                    pairedDevices = pairedDevices,
                    errorMsg = connError,
                    onRefreshDevices = { withBluetoothPermission() },
                    onConnectMac = { mac -> withBluetoothPermission(mac) },
                    onSelectSim = { viewModel.selectSimulatedAdapter(it) },
                    usbDevices = usbDevices,
                    onRefreshUsb = { viewModel.refreshUsbDevices() },
                    onConnectUsb = { viewModel.connectUsb(it) },
                    onDisconnectAdapter = { viewModel.disconnectSelectedAdapter() },
                    onDiscoverConnector = { viewModel.discoverCamaroDiagConnector(it) },
                    onDiscoverBinaryConnector = { viewModel.discoverCamaroDiagConnector(it, binary = true) },
                    // Physical CDJ/USB checkpoint capture is not implemented (C5): never enable it as a working export.
                    onConnectWifi = { host, port -> viewModel.connectWifi(host, port) }
                )
                DiagTab.MODULES -> ModulesScreen(
                    modules, obdScanReport, isObdScanning, { viewModel.runFullObdScan() },
                    FunctionalTestPlanner.currentResponders(functionalTests, connectionGeneration, vehicle.vin),
                    obdScanMessage, observedObdResponders
                )
                DiagTab.LICENSE -> {
                    if (LicenseRolloutConfig.ENABLED) {
                        LicenseScreen(licenseLevel, onRefreshLicense)
                    } else {
                        HardwareGuideScreen()
                    }
                }
                DiagTab.DEBUG_DOWNLOADS -> {
                    if (ownerTestsAllowed) DebugDownloadsScreen()
                }
                DiagTab.FUNCTIONAL_TESTS -> {
                    if (ownerTestsAllowed) FunctionalTestsScreen(
                        state = functionalTests,
                        vehicle = vehicle,
                        connectionGeneration = connectionGeneration,
                        evapAvailable = adapter.tier != AdapterTier.DISCONNECTED &&
                            !calibrationWorkspace.isJ2534BridgeConnected && viewModel.canRunFunctionalEvapTest(),
                        onRunEvap = { approval, epoch, profile ->
                            viewModel.runFunctionalEvapTest(approval, epoch, profile)
                        },
                        onRunClear = { approval, epoch, profile ->
                            viewModel.runFunctionalClearTest(approval, epoch, profile)
                        },
                        legacyAvailableProtocol = viewModel.functionalLegacyMutationProtocol(),
                        onRunLegacy = { target, approval, epoch, profile ->
                            viewModel.runFunctionalLegacyMutation(target, approval, epoch, profile)
                        },
                        onRunLegacyRead = { target, epoch, profile ->
                            viewModel.runFunctionalLegacyRead(target,epoch,profile)
                        },
                        liveProtocol = viewModel.functionalLiveProtocol(),
                        onRunLive = { plan,epoch,profile -> viewModel.runFunctionalLive(plan,epoch,profile) },
                        onExportLiveCsv = { withExportFolder { viewModel.exportFunctionalLiveCsv() } },
                        onProjectCatalogue = { catalogue,reference,report,pin,epoch,profile ->
                            withExportFolder { viewModel.projectFunctionalCatalogue(catalogue,reference,report,pin,epoch,profile) }
                        },
                        onRunFreeze = { plan,epoch,profile -> viewModel.runFunctionalFreeze(plan,epoch,profile) },
                        onRunNetwork = { plan,epoch,profile -> viewModel.runFunctionalNetwork(plan,epoch,profile) },
                        nativeUsbEnabled = adapter.tier == AdapterTier.DISCONNECTED && !calibrationWorkspace.isJ2534BridgeConnected,
                        onRunNativeUsb = viewModel::runNativeUsbFunctionalTest,
                        onRunNativeFreeze = viewModel::runNativeFreezeFunctionalTest,
                        onRunAll = { viewModel.runFunctionalTests() },
                        onRunProtocol = { protocol, target -> viewModel.runFunctionalTests(protocol, target) },
                        onStop = { viewModel.cancelFunctionalTests() },
                        onExport = {
                            withExportFolder {
                                viewModel.exportFunctionalTests("CamaroDiag_functional_tests_" + System.currentTimeMillis() + ".json")
                            }
                        }
                    )
                }
                DiagTab.LIVE -> LiveDataScreen(
                    pids = livePids,
                    units = units,
                    isStreaming = isStreaming,
                    recording = liveRecording,
                    telemetryMessage = liveTelemetryMessage,
                    accountUid = accountUid,
                    vehicleIdentity = observedStandardVin?.let { observed ->
                        if (VehicleVinMatcher.normalize(vehicle.vin) == observed && selectedSavedVehicleId != null)
                            "garage_$selectedSavedVehicleId" else "vin_$observed"
                    },
                    detectedSensors = detectedLiveSensors,
                    connectionGeneration = connectionGeneration,
                    onToggleStream = { viewModel.toggleLiveStream() },
                    onToggleUnits = {
                        viewModel.setUnitSystem(if (units == UnitSystem.METRIC) UnitSystem.IMPERIAL else UnitSystem.METRIC)
                    },
                    onToggleRecording = {
                        if (premiumAllowed) viewModel.toggleLiveRecording()
                        else currentTab = DiagTab.LICENSE
                    },
                    onExportRecording = {
                        if (!premiumAllowed) currentTab = DiagTab.LICENSE
                        else withExportFolder { viewModel.exportLiveRecording("CamaroDiag_live_${System.currentTimeMillis()}.csv") }
                    }
                )
                DiagTab.CAN_SNIFFER -> CanSnifferScreen(
                    state = liveCanMonitorState,
                    isAvailable = viewModel.canMonitorAvailable(),
                    availableRawCanProfiles = viewModel.availableRawCanProfiles(),
                    canReadObdAvailable = viewModel.canConsoleReadObd(),
                    canTransmitAvailable = adapter.supportsCanTransmit,
                    canExportAvailable = viewModel.canExportCanCapture(),
                    knownModules = modules,
                    onStart = { can29Bit -> viewModel.startCanMonitor(can29Bit = can29Bit) },
                    onBitrateChange = viewModel::setCanConsoleBitrate,
                    onStop = { if (liveCanMonitorState.isDoIpActive) viewModel.cancelDirectDoIp() else viewModel.stopCanMonitor() },
                    onSendRequest = { viewModel.sendCanConsoleInput(it) },
                    onConfirmRawFrame = { frame, epoch, approval ->
                        viewModel.confirmRawCanConsoleFrame(frame, epoch, approval)
                    },
                    onClearHistory = { viewModel.clearCanConsoleHistory() },
                    onExport = { withExportFolder { viewModel.exportCanCapture("CamaroDiag_CAN_${System.currentTimeMillis()}.txt") } }
                )
                DiagTab.DTCS -> DtcScreen(dtcs, vehicle, calibrationWorkspace.vinComparison, connectionGeneration, dtcScanNotice, { viewModel.scanDtcs() }, { approval, epoch, confirmedVehicle -> viewModel.clearDtcs(approval, epoch, confirmedVehicle) })
                DiagTab.MILEAGE -> MileageScreen(mileageReport, { viewModel.runMileageAudit() }, { withExportFolder { viewModel.saveCurrentSession() } })
                DiagTab.ABOUT -> LazyColumn {
                    item { AboutUpdateCard() }
                }
                DiagTab.OBD_SCAN -> ObdScanScreen(
                    report = obdScanReport,
                    isScanning = isObdScanning,
                    message = obdScanMessage,
                    isConnected = adapter.tier != AdapterTier.DISCONNECTED &&
                        !adapter.deviceName.contains("Demo", ignoreCase = true),
                    vehicle = vehicle,
                    accountUid = accountUid,
                    savedVehicleId = selectedSavedVehicleId,
                    onRunScan = { viewModel.runFullObdScan() },
                    onExport = { report -> withExportFolder { viewModel.exportFullObdScan(report) } },
                    nativeUsbPanel = if(ownerTestsAllowed && adapter.tier==AdapterTier.DISCONNECTED &&
                        !calibrationWorkspace.isJ2534BridgeConnected) {
                        { NativeUsbFunctionalPanel(!isBusy&&!isObdScanning&&!functionalTests.running,connectionGeneration,
                            vehicle,viewModel::runNativeUsbObdScan,asScan=true) }
                    } else null,
                    onStopNativeScan = if(functionalTests.running && isObdScanning &&
                        functionalTests.context?.transport=="NATIVE_USB_CU1") viewModel::cancelFunctionalTests else null
                )
                DiagTab.CALIBRATION -> CalibrationWorkspaceScreen(
                    state = calibrationWorkspace,
                    consoleBusy = liveCanMonitorState.isCapturing || liveCanMonitorState.isSendingRequest,
                    canReadEnhancedUds = viewModel.canReadEnhancedUds(),
                    availableUdsReadProfiles = viewModel::availableUdsReadProfiles,
                    vehicleLabel = "${vehicle.modelYear} Camaro ${vehicle.engine.rpo} • ${localizedGenerationName(vehicle.generation)}",
                    ecmType = vehicle.engine.ecmType,
                    isConnected = !adapter.deviceName.contains("Demo", ignoreCase = true) &&
                        !adapter.deviceName.contains("Simulated", ignoreCase = true) &&
                        adapter.tier != AdapterTier.DISCONNECTED,
                    vehicle = vehicle,
                    onReadIdentification = { viewModel.readCalibrationIdentification() },
                    onReadGmlanIdentification = { viewModel.readGmlanIdentification() },
                    onReadLfx2012FuelSnapshot = { viewModel.readLfx2012FuelSnapshot() },
                    onExportLfx2012FuelHistory = {
                        withExportFolder {
                            viewModel.exportLfx2012FuelHistory("CamaroDiag_LFX_fuel_${System.currentTimeMillis()}.csv")
                        }
                    },
                    onExportIdentification = {
                        withExportFolder {
                            viewModel.exportEcuIdentification("CamaroDiag_ECU_identity_${System.currentTimeMillis()}.json")
                        }
                    },
                    onCreateVehicleFromEcuVin = { viewModel.createVehicleFromEcuVin(it) },
                    onSyncVehicleVinFromEcu = { viewModel.syncVehicleVinFromEcu(it) },
                    onReadUdsDid = { requestId, responseId, did, protocol -> viewModel.readUdsDid(requestId, responseId, did, protocol) },
                    onReadUdsDtcs = { requestId, responseId, protocol -> viewModel.readUdsDtcs(requestId, responseId, protocol) },
                    onReadDirectDoIp = { host, tester, ecu, did, tls, operation, certificate, options ->
                        viewModel.readDirectDoIp(host, tester, ecu, did, tls, operation, certificate = certificate, options = options)
                    },
                    doIpAccountUid = accountUid,
                    doIpContextGeneration = liveCanMonitorState.consoleContextGeneration,
                    onConfirmDoIpCommand = viewModel::confirmDirectDoIpCommand,
                    onMonitorDirectDoIp = { host, tester, ecu, did, tls, samples, interval, operation, certificate, options ->
                        viewModel.readDirectDoIp(host, tester, ecu, did, tls,
                            operation, samples, interval, certificate = certificate, options = options)
                    },
                    onCancelDirectDoIp = viewModel::cancelDirectDoIp,
                    onInspectDoIpGateway = viewModel::inspectDirectDoIpGateway,
                    onExportDoIpMonitoring = { report -> withExportFolder { viewModel.exportDoIpMonitoring(report) } },
                    onImportDoIpBackupProfile = {
                        viewModel.activeAccountUid.value?.let { owner ->
                            pendingDoIpProfile = Triple(owner, viewModel.exportAccountGeneration(), viewModel.vehicle.value)
                            doIpProfilePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        }
                    },
                    onVerifyDoIpBackup = { scope ->
                        viewModel.activeAccountUid.value?.let { owner ->
                            pendingDoIpArchive = Triple(owner, viewModel.exportAccountGeneration(), viewModel.vehicle.value) to scope
                            doIpArchivePicker.launch(arrayOf("application/zip", "application/octet-stream"))
                        }
                    },
                    onCaptureDoIpBackup = { host, tester, ecu, tls, scope, certificate, options ->
                        withExportFolder { viewModel.captureDirectDoIpBackup(host, tester, ecu, tls, scope, certificate, options) }
                    },
                    onConnectJ2534Bridge = { host, port, token, requestId, responseId, baud ->
                        viewModel.connectJ2534Bridge(host, port, token, requestId, responseId, baud)
                    },
                    onDisconnectJ2534Bridge = { viewModel.disconnectJ2534Bridge() },
                    onCaptureJ2534Memory = { addressText, lengthText, session, region ->
                        val address = addressText.toLongOrNull(16)
                        val bytes = lengthText.toIntOrNull()
                        if (address != null && bytes != null && bytes in 1..(4 * 1024 * 1024) &&
                            address in 0L..0xFFFFFFFFL &&
                            address + bytes <= 0x1_0000_0000L &&
                            vehicle.vin.uppercase().matches(Regex("[A-HJ-NPR-Z0-9]{17}"))
                        ) {
                            val request = J2534LiveMemoryBackup.Request(
                                expectedVin = vehicle.vin.uppercase(),
                                region = region,
                                address = address,
                                lengthBytes = bytes,
                                diagnosticSession = session,
                                // The selected engine profile is not an observed
                                // ECU controller / firmware identity.
                                controllerLabel = null
                            )
                            withExportFolder {
                                viewModel.captureJ2534Memory("CamaroDiag_ECU_memory_${System.currentTimeMillis()}.zip", request)
                            }
                        }
                    },
                    onReadObdbSignal = { signalId, profile -> viewModel.readObdbSignal(signalId, profile) },
                    onExportPhysicalRecovery = { identity, report ->
                        withExportFolder { viewModel.exportPhysicalRecoveryInspection(identity, report) }
                    },
                    onInspectPhysicalRecoveryHistory = { identity, trees ->
                        viewModel.inspectPhysicalRecoveryHistory(identity, trees)
                    },
                    onExportPhysicalRecoveryHistory = { report ->
                        withExportFolder { viewModel.exportPhysicalRecoveryHistory(report) }
                    },
                    onInspectPhysicalRecovery = { identity ->
                        viewModel.activeAccountUid.value?.let { owner ->
                            pendingPhysicalRecovery = (owner to viewModel.exportAccountGeneration()) to identity
                            physicalRecoveryPicker.launch(null)
                        }
                    },
                    onVerifyEcuBackup = { ecuBackupPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onImportFile = { calibrationFilePicker.launch(arrayOf("*/*")) },
                    onImportTrace = { canTracePicker.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel", "*/*")) },
                    onImportComparisonFile = { calibrationComparisonPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                    onPreviewPartialWrite = { start, eraseBytes, programBytes ->
                        viewModel.previewPartialCalibrationWrite(start, eraseBytes, programBytes)
                    },
                    onExportExternalDumpPair = { method, region, address ->
                        val firstPath = calibrationWorkspace.importedFilePath
                        val secondPath = calibrationWorkspace.comparisonFilePath
                        if (firstPath != null && secondPath != null) {
                            withExportFolder {
                                viewModel.exportExternalDumpPair(method, region, address, firstPath, secondPath)
                            }
                        }
                    },
                    onImportDefinitions = {
                        calibrationDefinitionsPicker.launch(arrayOf("text/*", "text/csv", "text/tab-separated-values", "*/*"))
                    },
                    onExportDefinitionTemplate = {
                        withExportFolder { viewModel.exportCalibrationDefinitionTemplate("CamaroDiag_calibration_tables_template.tsv") }
                    },
                    onSelectMap = { viewModel.selectCalibrationMap(it) },
                    onCellChange = { row, column, value -> viewModel.updateCalibrationCell(row, column, value) },
                    onApplyMap = { viewModel.applyCalibrationTableEdits() },
                    onDiscardMap = { viewModel.discardCalibrationTableChanges() },
                    onExportFile = {
                        calibrationWorkspace.importedFileName?.let { fileName ->
                            withExportFolder { viewModel.exportCalibrationFile(fileName) }
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
                    followSystemLanguage = followSystemLanguage,
                    onFollowSystemLanguage = { viewModel.setFollowSystemLanguage(systemLocale) },
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

@Composable
fun VehicleSetupScreen(
    vehicle: VehicleProfile,
    savedVehicles: List<SavedVehicleProfile>,
    legacyVehicleImportAvailable: Boolean,
    selectedSavedVehicleId: String?,
    onChange: (Int, CamaroEngine, TransmissionType, MarketRegion) -> Unit,
    onVinChange: (String) -> Unit,
    onCaptureVinPhoto: () -> Unit,
    onSaveVehicle: (String) -> Unit,
    onImportLegacyVehicles: () -> Unit,
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
                    if (legacyVehicleImportAvailable) {
                        OutlinedButton(onClick = onImportLegacyVehicles, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.saved_vehicle_import_legacy))
                        }
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
    isJ2534BridgeConnected: Boolean,
    isBusy: Boolean,
    pairedDevices: List<Pair<String, String>>,
    errorMsg: String?,
    onRefreshDevices: () -> Unit,
    onConnectMac: (String) -> Unit,
    onSelectSim: (String) -> Unit,
    usbDevices: List<String>,
    onRefreshUsb: () -> Unit,
    onConnectUsb: (String) -> Unit,
    onDisconnectAdapter: () -> Unit,
    onConnectWifi: (String, Int) -> Unit,
    onDiscoverConnector: ((String) -> Unit)? = null,
    onDiscoverBinaryConnector: ((String) -> Unit)? = null
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
                    Text(
                        when {
                            isJ2534BridgeConnected -> stringResource(R.string.adapter_j2534_bridge_connected)
                            adapter.tier == AdapterTier.DISCONNECTED -> stringResource(R.string.adapter_tier_disconnected)
                            else -> adapter.deviceName
                        },
                        fontWeight = FontWeight.Bold, color = CamaroAmber, fontSize = 16.sp
                    )
                    if (!isJ2534BridgeConnected && adapter.tier != AdapterTier.DISCONNECTED) {
                        Text(localizedAdapterTierName(adapter.tier), fontSize = 13.sp)
                    }
                    if (isJ2534BridgeConnected || adapter.tier != AdapterTier.DISCONNECTED) {
                        OutlinedButton(
                            onClick = onDisconnectAdapter,
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.adapter_disconnect_label))
                        }
                    }
                    if (isJ2534BridgeConnected) {
                        Text(
                            stringResource(R.string.adapter_j2534_capabilities_guidance),
                            fontSize = 12.sp, color = TextMuted
                        )
                    } else {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AdapterCapabilityLine(stringResource(R.string.adapter_can_headers), adapter.supportsCanHeaders)
                            AdapterCapabilityLine(stringResource(R.string.adapter_can_tx_label), adapter.supportsCanTransmit)
                            AdapterCapabilityLine(stringResource(R.string.swcan_wiring_verified), adapter.supportsSwCan)
                            AdapterCapabilityLine(stringResource(R.string.long_response_mode), adapter.supportsLongFrames)
                        }
                    }
                    if (adapter.acceptedElmProtocols.isNotEmpty()) {
                        val names = adapter.acceptedElmProtocols.sorted().mapNotNull { number ->
                            when (number) {
                                1 -> "J1850 PWM"
                                2 -> "J1850 VPW"
                                3 -> "ISO 9141-2"
                                4 -> "KWP2000 5-baud"
                                5 -> "KWP2000 fast init"
                                6 -> "CAN 11-bit / 500k"
                                7 -> "CAN 29-bit / 500k"
                                8 -> "CAN 11-bit / 250k"
                                9 -> "CAN 29-bit / 250k"
                                10 -> "J1939"
                                else -> null
                            }
                        }
                        Text(stringResource(R.string.adapter_config_accepted, names.joinToString(", ")),
                            fontSize = 11.sp, color = TextMuted)
                    }
                    // if (adapter.rawProbeLog.isNotEmpty()) {
                    //     Spacer(Modifier.height(6.dp))
                    //     adapter.rawProbeLog.forEach { line ->
                    //         Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted)
                    //     }
                    // }
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
                    usbDevices.forEach { label ->
                        val kklOnly = com.camarodiag.gen5.data.obd.CamaroDiagConnectorDiscovery.isLegacyKklUsbLabel(label)
                        if (kklOnly) {
                            Text(label, fontSize = 12.sp)
                            Text(stringResource(R.string.status_not_verified), fontSize = 11.sp, color = TextMuted)
                        }
                        if (!kklOnly && (!label.contains("CamaroDiagConnectors", ignoreCase = true) || onDiscoverConnector == null)) {
                            OutlinedButton(onClick = { onConnectUsb(label) }, enabled = !isBusy,
                                modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.adapter_connect_label, label))
                            }
                        }
                        if (!kklOnly && label.contains("CamaroDiagConnectors", ignoreCase = true) && onDiscoverConnector != null) {
                            OutlinedButton(onClick = { onDiscoverConnector(label) }, enabled = !isBusy,
                                modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    Text("CamaroDiagConnectors • USB ASCII")
                                    Text(label, fontSize = 11.sp)
                                    Text(stringResource(R.string.adapter_discovery_only), fontSize = 11.sp, color = TextMuted)
                                }
                            }
                        }
                        if (!kklOnly && label.contains("CamaroDiagConnectors", ignoreCase = true) && onDiscoverBinaryConnector != null) {
                            OutlinedButton(onClick = { onDiscoverBinaryConnector(label) }, enabled = !isBusy,
                                modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    Text("CamaroDiagConnectors • USB CD/1")
                                    Text(label, fontSize = 11.sp)
                                    Text(stringResource(R.string.adapter_discovery_only), fontSize = 11.sp, color = TextMuted)
                                }
                            }
                        }
                    }
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
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { onSelectSim("MX_PLUS") }, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) { Text("OBDLink MX+") }
                OutlinedButton(onClick = { onSelectSim("ELM_15") }, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) { Text("ELM327 v1.5") }
                OutlinedButton(onClick = { onSelectSim("CLONE") }, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.adapter_tier_clone_elm)) }
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
                        ControlAvailability.NOT_IMPLEMENTED -> stringResource(R.string.status_not_implemented)
                        ControlAvailability.NOT_VERIFIED -> stringResource(R.string.status_not_verified)
                        ControlAvailability.AVAILABLE -> stringResource(R.string.status_available)
                        ControlAvailability.NOT_EQUIPPED -> stringResource(R.string.status_not_equipped)
                    }
                    val availabilityColor = when (capability.availability) {
                        ControlAvailability.NOT_IMPLEMENTED -> TextMuted
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
fun ModulesScreen(
    modules: List<EcuModule>,
    report: com.camarodiag.gen5.data.obd.ObdScanReport? = null,
    scanning: Boolean = false,
    onScan: (() -> Unit)? = null,
    testedResponders: List<String> = emptyList(),
    scanMessage: String? = null,
    observedResponders: List<String> = emptyList()
) {
    val respondingAddresses = (report?.respondingEcuAddresses ?: (observedResponders + testedResponders)).distinct().sorted()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionTitle(stringResource(R.string.modules_vehicle_title))
            if (onScan != null) {
                Button(onClick = onScan, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.obd_run_full_scan))
                }
            }
            scanMessage?.let { Text(it, color = TextMuted, fontSize = 12.sp) }
            if (modules.isEmpty() && respondingAddresses.isEmpty()) {
                Text(stringResource(R.string.modules_no_observed_responders), color = TextMuted)
            }
        }
        if (respondingAddresses.isNotEmpty()) {
            item {
                SectionTitle(stringResource(R.string.modules_obd_responders))
                if (report?.adapterInfo?.get("IDENTIFICATION").orEmpty().contains("SIMULATED", ignoreCase = true)) {
                    Text(stringResource(R.string.obd_offline_simulation_notice), color = CamaroAmber)
                }
            }
            items(respondingAddresses) { address ->
                Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(address, color = CamaroBlue, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.modules_oem_identity_unknown), color = TextMuted)
                        report?.supportedPidsByResponder?.get(address)?.let { pids ->
                            Text(stringResource(R.string.obd_pid_count_by_ecu, address) + ": " + pids.size)
                        }
                    }
                }
            }
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
            // Always show the complete ECU topology. Unsupported, unverified
            // and unequipped modules remain visible but visually disabled.
            val unavailable = mod.status != ModuleStatus.ACCESSIBLE
            Card(
                modifier = Modifier.alpha(if (unavailable) 0.48f else 1f),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
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
        "ETHANOL" -> stringResource(R.string.live_pid_ethanol)
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
    vehicleIdentity: String?,
    accountUid: String?,
    detectedSensors: List<LiveSensorDefinition>,
    connectionGeneration: Long,
    onToggleStream: () -> Unit,
    onToggleUnits: () -> Unit,
    onToggleRecording: () -> Unit,
    onExportRecording: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("live_sensor_selection", android.content.Context.MODE_PRIVATE)
    }
    val storageKey = SensorSelectionScope.key(accountUid, vehicleIdentity)
    val storedSelection = remember(storageKey, preferences) {
        storageKey?.let { key ->
            preferences.getString(key, null)?.split(",")?.filter(String::isNotBlank)?.toSet()
        }
    }
    // Without a verified VIN, a selection belongs only to this connection.
    val transientConnection = connectionGeneration.takeIf { storageKey == null }
    var selectedPidIds by remember(storageKey, transientConnection) {
        mutableStateOf<Set<String>?>(storedSelection)
    }
    var choosingSensors by remember(storageKey, connectionGeneration) { mutableStateOf(false) }
    val liveCatalog = remember(detectedSensors) {
        LiveSensorCatalog.availableEntries(detectedSensors)
    }
    val allPidIds = liveCatalog.map { it.id }.toSet()
    val localeFrench = LocalConfiguration.current.locales[0].language.equals("fr", ignoreCase = true)
    // Android resources are authoritative for known signals. Legacy bilingual labels
    // remain a fallback only for signals discovered outside the known catalog.
    val sensorLabelResources = mapOf(
        "RPM" to R.string.live_pid_rpm,
        "ECT" to R.string.live_pid_ect,
        "STFT" to R.string.live_pid_stft,
        "LTFT" to R.string.live_pid_ltft,
        "BAT" to R.string.live_pid_bat,
        "HYBRID_BAT_LIFE" to R.string.live_pid_hybrid_bat_life,
        "ODOMETER" to R.string.live_pid_odometer,
        "SPD" to R.string.live_pid_speed,
        "TPS" to R.string.live_pid_throttle,
        "LOAD" to R.string.live_pid_load,
        "MAP" to R.string.live_pid_map,
        "IAT" to R.string.live_pid_iat,
        "MAF" to R.string.live_pid_maf,
        "ETHANOL" to R.string.live_pid_ethanol,
        "OIL" to R.string.live_pid_oil,
        "STFT_B2" to R.string.live_pid_stft_b2,
        "LTFT_B2" to R.string.live_pid_ltft_b2,
        "REL_TPS" to R.string.live_pid_rel_tps,
        "CMD_TPS" to R.string.live_pid_cmd_tps,
        "ABS_TPS_B" to R.string.live_pid_abs_tps_b,
        "ABS_TPS_C" to R.string.live_pid_abs_tps_c,
        "APP_D" to R.string.live_pid_app_d,
        "APP_E" to R.string.live_pid_app_e,
        "APP_F" to R.string.live_pid_app_f,
        "ABS_LOAD" to R.string.live_pid_abs_load,
        "AMBIENT" to R.string.live_pid_ambient,
        "FUEL_PRESS" to R.string.live_pid_fuel_press,
        "TIMING" to R.string.live_pid_timing,
        "EQ_RATIO" to R.string.live_pid_eq_ratio,
        "O2_B1S1" to R.string.live_pid_o2_b1s1,
        "O2_B1S2" to R.string.live_pid_o2_b1s2,
        "O2_B1S3" to R.string.live_pid_o2_b1s3,
        "O2_B1S4" to R.string.live_pid_o2_b1s4,
        "WBO2_B1S1" to R.string.live_pid_wbo2_b1s1,
        "WBO2_B1S2" to R.string.live_pid_wbo2_b1s2,
        "WBO2_B2S1" to R.string.live_pid_wbo2_b2s1,
        "WBO2_B2S2" to R.string.live_pid_wbo2_b2s2,
        "WBO2_I_B1S1" to R.string.live_pid_wbo2_i_b1s1,
        "WBO2_I_B1S2" to R.string.live_pid_wbo2_i_b1s2,
        "WBO2_I_B2S1" to R.string.live_pid_wbo2_i_b2s1,
        "WBO2_I_B2S2" to R.string.live_pid_wbo2_i_b2s2
    )
    val sensorContext = LocalContext.current
    val sensorLabels = liveCatalog.associate { signal ->
        signal.id to (sensorLabelResources[signal.id]?.let(sensorContext::getString)
            ?: if (localeFrench) signal.labelFr else signal.labelEn)
    }
    val activePidIds = (selectedPidIds ?: allPidIds).intersect(allPidIds)
    val visiblePids = pids.filter { it.id in activePidIds }

    LaunchedEffect(allPidIds) {
        if (allPidIds.isEmpty()) choosingSensors = false
    }
    if (choosingSensors && allPidIds.isNotEmpty()) {
        var sensorQuery by remember(storageKey, connectionGeneration, choosingSensors) { mutableStateOf("") }
        var pendingIds by remember(storageKey, connectionGeneration, choosingSensors) {
            mutableStateOf(activePidIds)
        }
        AlertDialog(
            onDismissRequest = { choosingSensors = false },
            title = { Text(stringResource(R.string.obd_choose_sensors)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sensorQuery,
                        onValueChange = { sensorQuery = it.take(48) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.live_sensor_search)) },
                        singleLine = true
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        items(
                            liveCatalog.filter { signal ->
                                sensorQuery.isBlank() || signal.id.contains(sensorQuery, true) ||
                                    sensorLabels[signal.id].orEmpty().contains(sensorQuery, true)
                            },
                            key = { it.id }
                        ) { signal ->
                            val updateChecked: (Boolean) -> Unit = { checked ->
                                pendingIds = if (checked) pendingIds + signal.id else pendingIds - signal.id
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = signal.id in pendingIds, onCheckedChange = updateChecked)
                                Column(Modifier.weight(1f).clickable { updateChecked(signal.id !in pendingIds) }) {
                                    Text(sensorLabels[signal.id] ?: signal.id)
                                    Text(signal.id, color = TextMuted, fontSize = 11.sp)
                                    // Only session-observed signals are listed; no disabled catalogue rows.
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val accepted = pendingIds.intersect(allPidIds)
                    selectedPidIds = accepted
                    storageKey?.let { key ->
                        preferences.edit { putString(key, accepted.sorted().joinToString(",")) }
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
                                Text(stringResource(R.string.can_sniffer_export_raw))
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
                        // Text(
                            // stringResource(R.string.live_recording_notice),
                            // color = TextMuted, fontSize = 10.sp
                        // )
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.live_selected_sensors, activePidIds.size, allPidIds.size),
                        color = CamaroAmber,
                        fontWeight = FontWeight.Bold
                    )
                    Button(onClick = { choosingSensors = true }, enabled = liveCatalog.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.obd_choose_sensors))
                    }
                    TextButton(onClick = {
                        selectedPidIds = null
                        storageKey?.let { preferences.edit { remove(it) } }
                    }) {
                        Text(stringResource(R.string.live_sensor_show_all), color = CamaroBlue)
                    }
                }
            }
        }
        items(visiblePids) { pid ->
            val displayVal = if (units == UnitSystem.METRIC) pid.metricValue else pid.imperialValue
            val displayUnit = if (units == UnitSystem.METRIC) pid.metricUnit else pid.imperialUnit
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        val source = Regex("\\[([0-9A-F]{3}|[0-9A-F]{8})\\]$").find(pid.labelEn)?.groupValues?.get(1)
                        val label = if (pid.labelEn.contains("(DEMO)", ignoreCase = true))
                            localizedLivePidLabel(pid)
                        else sensorLabels[pid.id] ?: localizedLivePidLabel(pid)
                        Column(Modifier.weight(1f)) {
                            Text(label, color = TextMuted, fontSize = 13.sp)
                            source?.let { Text(stringResource(R.string.obd_value_source, it),
                                color = TextMuted, fontSize = 11.sp) }
                        }
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
    availableRawCanProfiles: Set<Int>,
    canReadObdAvailable: Boolean,
    canTransmitAvailable: Boolean,
    canExportAvailable: Boolean,
    knownModules: List<EcuModule>,
    onStart: (Boolean) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onStop: () -> Unit,
    onSendRequest: (String) -> Unit,
    onConfirmRawFrame: (String, Long, EcuWriteConsentFlow.Approval) -> Unit,
    onClearHistory: () -> Unit,
    onExport: () -> Unit
) {
    var requestInput by remember { mutableStateOf("01 0C") }
    var monitorCan29Bit by remember { mutableStateOf(false) }
    var monitorFormatMenuExpanded by remember { mutableStateOf(false) }
    // var bitrateMenuExpanded by remember { mutableStateOf(false) }
    var pendingRawFrameInput by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var writeConsentStep by remember { mutableStateOf(EcuWriteConsentFlow.Step.WARNING) }
    var frameSearch by remember { mutableStateOf("") }
    var canIdFilter by remember { mutableStateOf("") }
    var dataPrefixFilter by remember { mutableStateOf("") }
    var directionFilter by remember { mutableStateOf("ALL") }
    var selectedObservedCanId by remember { mutableStateOf<ObservedCanId?>(null) }
    var observedEcuMenuExpanded by remember { mutableStateOf(false) }
    var expandedFrameLine by remember { mutableStateOf<Int?>(null) }
    val report = state.report
    // A vehicle or adapter change invalidates even an already opened TX dialog.
    LaunchedEffect(state.consoleContextGeneration) {
        pendingRawFrameInput = null
        writeConsentStep = EcuWriteConsentFlow.Step.WARNING
    }
    // The menu describes CAN IDs seen in this session, not assumed ECU identities.
    val observedCanIds = remember(state.observedCanIds, report) {
        // Old log entries remain visible in the global monitor, but cannot
        // advertise an ECU as present in a newly started physical capture.
        CanConsoleDisplayFilter.observedFrameIds(
            emptyList(),
            report?.frames.orEmpty(),
            state.observedCanIds
        )
    }
    // If capture/vehicle changes, do not retain a stale selected identifier.
    LaunchedEffect(observedCanIds) {
        if (selectedObservedCanId != null && selectedObservedCanId !in observedCanIds) {
            selectedObservedCanId = null
        }
    }
    // Only identify a module by name if its actual CAN responder was observed
    // during a verified physical scan, not from the predicted vehicle topology.
    val physicallyIdentifiedModules = remember(knownModules, observedCanIds, state.bitrate) {
        // A CAN ID on SW-CAN/250k is not the same physical responder as on HS-CAN 500k.
        knownModules.filter { module ->
            state.bitrate == 500_000 && module.status == ModuleStatus.ACCESSIBLE &&
                module.notesEn.startsWith("Observed ") &&
                observedCanIds.any { it.hex.equals(module.rxHeader, ignoreCase = true) }
        }.associateBy { module ->
            observedCanIds.first { it.hex.equals(module.rxHeader, ignoreCase = true) }
        }
    }
    val parsedRawFrame = remember(requestInput) { RawCanFrameParser.parse(requestInput) }
    val rawCanFrameInput = parsedRawFrame != null
    fun profile(extended: Boolean, bitrate: Int) =
        com.camarodiag.gen5.data.obd.DiagnosticRouteSelector.rawCanProfile(extended, bitrate)
    fun speedForProfile(selected: Int) = when (selected) {
        61, 62 -> 33_333
        8, 9 -> 250_000
        else -> 500_000
    }
    val selectedMonitorProfile = profile(monitorCan29Bit, state.bitrate)
    val rawProfileAvailable = state.bitrate != 33_333 && parsedRawFrame?.let {
        profile(it.isExtended, state.bitrate) in availableRawCanProfiles
    } == true
    LaunchedEffect(availableRawCanProfiles, state.bitrate, state.isCapturing, state.isSendingRequest) {
        if (!state.isDoIpActive && !state.isCapturing && !state.isSendingRequest &&
            selectedMonitorProfile !in availableRawCanProfiles && availableRawCanProfiles.size == 1) {
            val selected = availableRawCanProfiles.single()
            monitorCan29Bit = selected in setOf(7, 9, 62)
            onBitrateChange(speedForProfile(selected))
        }
    }
    val unsafeRawCanInput = remember(requestInput) {
        RawCanFrameParser.parse(requestInput)?.let {
            !SafeRawCanTransmitPolicy.allows(it)
        } == true
    }
    val parsedCanId = remember(canIdFilter) {
        if (canIdFilter.isBlank()) null else CanTraceFilter.parseCanId(canIdFilter)
    }
    val parsedDataPrefix = remember(dataPrefixFilter) {
        CanTraceFilter.parseDataPrefix(dataPrefixFilter)
    }
    val filterInputValid = (canIdFilter.isBlank() || parsedCanId != null) &&
        parsedDataPrefix != null
    val traceFilter = remember(frameSearch, parsedCanId, parsedDataPrefix, directionFilter) {
        CanTraceFilter(
            canId = parsedCanId,
            direction = when (directionFilter) {
                "RX" -> CanTraceDirection.RX
                "TX" -> CanTraceDirection.TX
                else -> null
            },
            dataPrefix = parsedDataPrefix.orEmpty(),
            searchText = frameSearch
        )
    }
    val visibleFrames = if (filterInputValid) {
        report?.frames.orEmpty().filterCanTrace(traceFilter)
            .filter { CanConsoleDisplayFilter.visibleTrace(it, selectedObservedCanId) }
            .asReversed()
    } else emptyList()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.can_sniffer_title))
                    if (state.isDoIpActive) Text(stringResource(R.string.cal_doip_console_active), color = CamaroAmber, fontSize = 11.sp)
                    // Text(stringResource(R.string.can_sniffer_description), color = TextMuted, fontSize = 12.sp)
                    Text(stringResource(R.string.can_sniffer_safety), color = CamaroAmber, fontSize = 11.sp)
                    Text(stringResource(R.string.can_sniffer_bus_limit), color = TextMuted, fontSize = 10.sp)
                    Text(stringResource(R.string.can_sniffer_stn_filter_notice), color = TextMuted, fontSize = 10.sp)
                    Box {
                        OutlinedButton(onClick = { monitorFormatMenuExpanded = true },
                            enabled = !state.isDoIpActive && !state.isCapturing && !state.isSendingRequest && availableRawCanProfiles.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (selectedMonitorProfile in availableRawCanProfiles)
                                if (state.bitrate == 33_333) stringResource(R.string.can_console_swcan_profile, if (monitorCan29Bit) 29 else 11)
                                else stringResource(R.string.can_console_capture_profile, state.bitrate / 1000, if (monitorCan29Bit) 29 else 11)
                            else stringResource(R.string.cal_choose_compatible_profile))
                        }
                        DropdownMenu(expanded = monitorFormatMenuExpanded,
                            onDismissRequest = { monitorFormatMenuExpanded = false }) {
                            availableRawCanProfiles.sorted().forEach { selected ->
                                val extended = selected in setOf(7, 9, 62)
                                val speed = speedForProfile(selected)
                                DropdownMenuItem(text = {
                                    Text(if (speed == 33_333) stringResource(R.string.can_console_swcan_profile, if (extended) 29 else 11)
                                        else stringResource(R.string.can_console_capture_profile, speed / 1000, if (extended) 29 else 11))
                                }, onClick = {
                                    monitorCan29Bit = extended
                                    onBitrateChange(speed)
                                    monitorFormatMenuExpanded = false
                                })
                            }
                        }
                    }
                    Text(stringResource(R.string.can_monitor_format_notice),
                        color = TextMuted, fontSize = 10.sp)
                    if (state.bitrate == 33_333)
                        Text(stringResource(R.string.can_console_swcan_receive_only), color = CamaroAmber, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onStart(monitorCan29Bit) },
                            enabled = isAvailable && selectedMonitorProfile in availableRawCanProfiles && !state.isDoIpActive && !state.isCapturing && !state.isSendingRequest,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.can_sniffer_start))
                        }
                        OutlinedButton(
                            onClick = onStop,
                            enabled = state.isCapturing || state.isDoIpActive,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.can_sniffer_stop))
                        }
                    }
                    if (!isAvailable) {
                        Text(
                            stringResource(if (canReadObdAvailable)
                                R.string.can_console_read_only_available
                            else R.string.can_sniffer_unavailable),
                            color = if (canReadObdAvailable) TextMuted else WarnRed,
                            fontSize = 11.sp
                        )
                    } else if (!canReadObdAvailable) {
                        Text(stringResource(R.string.can_console_obd_unavailable),
                            color = TextMuted, fontSize = 11.sp)
                    }
                    state.message?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = TextMuted, fontSize = 11.sp)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.can_console_history), color = CamaroAmber, fontWeight = FontWeight.Bold)
                        TextButton(
                            onClick = onExport,
                            enabled = canExportAvailable
                        ) {
                            Text(stringResource(R.string.can_sniffer_export_raw), color = CamaroBlue)
                        }
                        TextButton(
                            onClick = onClearHistory,
                            enabled = !state.isDoIpActive && !state.isCapturing && !state.isSendingRequest &&
                                (state.consoleHistory.isNotEmpty() || state.rawText.isNotBlank())
                        ) {
                            Text(stringResource(R.string.can_console_clear), color = CamaroBlue)
                        }
                    }
                    Box {
                        OutlinedButton(
                            onClick = { observedEcuMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (selectedObservedCanId == null)
                                    stringResource(R.string.can_console_filter_all_ecus)
                                else {
                                    val id = selectedObservedCanId!!
                                    val module = physicallyIdentifiedModules[id]
                                    if (module == null) stringResource(
                                        R.string.can_console_filter_observed_id,
                                        CanConsoleDisplayFilter.displayId(id)
                                    ) else stringResource(
                                        R.string.can_console_filter_verified_ecu,
                                        if (LocalConfiguration.current.locales[0].language == "fr")
                                            module.nameFr else module.nameEn,
                                        CanConsoleDisplayFilter.displayId(id)
                                    )
                                }
                            )
                        }
                        DropdownMenu(
                            expanded = observedEcuMenuExpanded,
                            onDismissRequest = { observedEcuMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.can_console_filter_all_ecus)) },
                                onClick = {
                                    selectedObservedCanId = null
                                    observedEcuMenuExpanded = false
                                }
                            )
                            observedCanIds.forEach { id ->
                                DropdownMenuItem(
                                    text = {
                                        val module = physicallyIdentifiedModules[id]
                                        Text(
                                            if (module == null)
                                                stringResource(
                                                    R.string.can_console_filter_observed_id,
                                                    CanConsoleDisplayFilter.displayId(id)
                                                )
                                            else stringResource(
                                                R.string.can_console_filter_verified_ecu,
                                                if (LocalConfiguration.current.locales[0].language == "fr")
                                                    module.nameFr else module.nameEn,
                                                CanConsoleDisplayFilter.displayId(id)
                                            )
                                        )
                                    },
                                    onClick = {
                                        selectedObservedCanId = id
                                        observedEcuMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    // Text(
                        // stringResource(R.string.can_console_filter_observed_note),
                        // color = TextMuted, fontSize = 10.sp
                    // )
                    if (state.consoleHistory.isNotEmpty()) {
                        Text(
                            stringResource(R.string.can_console_history_recent_scope),
                            color = TextMuted, fontSize = 10.sp
                        )
                    }
                    val visibleConsoleHistory = state.consoleHistory.filter { entry ->
                        CanConsoleDisplayFilter.visible(
                            ParsedCanConsoleRecord(entry.direction, entry.content, entry.succeeded),
                            selectedObservedCanId
                        )
                    }
                    Column(
                        Modifier.fillMaxWidth().height(220.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (visibleConsoleHistory.isEmpty()) {
                            Text(stringResource(R.string.can_console_empty), color = TextMuted, fontSize = 11.sp)
                        } else {
                            visibleConsoleHistory.forEach { entry ->
                                val time = java.text.DateFormat.getTimeInstance(
                                    java.text.DateFormat.MEDIUM,
                                    LocalConfiguration.current.locales[0]
                                ).format(java.util.Date(entry.timestampMillis))
                                val direction = when (entry.direction) {
                                    "TX" -> stringResource(R.string.can_console_tx)
                                    "RX" -> stringResource(R.string.can_console_rx)
                                    else -> stringResource(R.string.can_console_system)
                                }
                                Text(
                                    "$time  $direction",
                                    color = when (entry.direction) {
                                        "TX" -> CamaroBlue
                                        "RX" -> CamaroAmber
                                        else -> WarnRed
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    entry.content.ifBlank { "—" },
                                    color = if (entry.succeeded) TextPrimary else WarnRed,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Color.DarkGray)
                    OutlinedTextField(
                        value = requestInput,
                        onValueChange = { requestInput = it.take(48) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.can_console_input_label)) },
                        placeholder = { Text("7E0 02 01 0C  •  01 0C") }
                    )
                    // Text(stringResource(R.string.can_console_input_hint), color = TextMuted, fontSize = 10.sp)
                    if (rawCanFrameInput && !canTransmitAvailable) {
                        Text(stringResource(R.string.can_console_tx_unverified), color = WarnRed, fontSize = 11.sp)
                    }
                    if (unsafeRawCanInput) {
                        Text(stringResource(R.string.can_console_unsafe_can_payload),
                            color = WarnRed, fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            if (RawCanFrameParser.parse(requestInput) != null) {
                                writeConsentStep = EcuWriteConsentFlow.Step.WARNING
                                pendingRawFrameInput =
                                    requestInput.trim() to state.consoleContextGeneration
                            } else {
                                onSendRequest(requestInput)
                            }
                        },
                        enabled = CanConsoleCapabilityPolicy.canSubmit(
                            rawCanFrame = rawCanFrameInput,
                            monitorAvailable = isAvailable && rawProfileAvailable,
                            canTransmit = canTransmitAvailable,
                            readObdAvailable = canReadObdAvailable,
                            inputPresent = requestInput.isNotBlank(),
                            busy = state.isDoIpActive || state.isCapturing || state.isSendingRequest
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.can_sniffer_send))
                    }
                    if (state.isSendingRequest) {
                        Text(stringResource(R.string.can_console_sending), color = CamaroAmber, fontSize = 11.sp)
                    }
                    state.message?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = TextMuted, fontSize = 11.sp)
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = directionFilter == "ALL",
                        onClick = { directionFilter = "ALL" },
                        label = { Text(stringResource(R.string.can_sniffer_direction_all)) }
                    )
                    FilterChip(
                        selected = directionFilter == "RX",
                        onClick = { directionFilter = "RX" },
                        label = { Text(stringResource(R.string.can_sniffer_direction_rx)) }
                    )
                    FilterChip(
                        selected = directionFilter == "TX",
                        onClick = { directionFilter = "TX" },
                        label = { Text(stringResource(R.string.can_sniffer_direction_tx)) }
                    )
                }
                OutlinedTextField(
                    value = frameSearch,
                    onValueChange = { frameSearch = it.take(32) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.can_sniffer_search)) },
                    placeholder = { Text("7E8 or 41 0C") },
                )
                OutlinedTextField(
                    value = canIdFilter,
                    onValueChange = { canIdFilter = it.take(8) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.can_trace_id_filter)) },
                    placeholder = { Text(stringResource(R.string.can_trace_id_hint)) },
                    isError = canIdFilter.isNotBlank() && parsedCanId == null
                )
                OutlinedTextField(
                    value = dataPrefixFilter,
                    onValueChange = { dataPrefixFilter = it.take(48) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.can_trace_data_prefix)) },
                    placeholder = { Text(stringResource(R.string.can_trace_data_hint)) },
                    isError = parsedDataPrefix == null
                )
                if (!filterInputValid) {
                    Text(stringResource(R.string.can_trace_filter_invalid), color = WarnRed, fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = {
                        frameSearch = ""
                        canIdFilter = ""
                        dataPrefixFilter = ""
                        directionFilter = "ALL"
                        selectedObservedCanId = null
                    },
                    enabled = frameSearch.isNotBlank() || canIdFilter.isNotBlank() ||
                        dataPrefixFilter.isNotBlank() || directionFilter != "ALL",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.can_trace_clear_filters))
                }
                Text(
                    stringResource(R.string.can_sniffer_filtered_count, visibleFrames.size, report.frames.size),
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
            items(visibleFrames, key = { it.lineNumber }) { frame ->
                val expanded = expandedFrameLine == frame.lineNumber
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        expandedFrameLine = if (expanded) null else frame.lineNumber
                    },
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "#${frame.lineNumber} • ${frame.direction.name} • 0x${String.format(Locale.ROOT, "%03X", frame.canId)} • DLC ${frame.data.size}",
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
                        if (expanded) {
                            Text(
                                "Timestamp: ${frame.timestampSeconds?.let { String.format(Locale.ROOT, "%.6f s", it) } ?: "not provided"}",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                            frame.data.forEachIndexed { index, byte ->
                                Text(
                                    "Byte ${index.toString().padStart(2, '0')}  •  0x${String.format(Locale.ROOT, "%02X", byte)}  •  ${byte.toString().padStart(3, '0')}  •  ${byte.toString(2).padStart(8, '0')}",
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
            if (report.incompleteIsoTpMessages > 0) {
                item {
                    Text(
                        stringResource(
                            R.string.can_trace_iso_tp_incomplete_count,
                            report.incompleteIsoTpMessages
                        ),
                        color = WarnRed,
                        fontSize = 11.sp
                    )
                }
            }
            if (report.dtcObservations.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.can_sniffer_dtc_heading), color = CamaroAmber, fontWeight = FontWeight.Bold)
                }
                items(report.dtcObservations.takeLast(30).asReversed()) { observation ->
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(observation.code, color = TextPrimary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(
                                stringResource(R.string.can_sniffer_dtc_service, observation.service),
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
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
                                Text(canTracePidDisplayName(observation.pid, observation.name), fontSize = 12.sp, color = TextMuted)
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
                    val services = report.udsServices.joinToString { "0x" + it.toString(16).uppercase().padStart(2, '0') }
                    Text(stringResource(R.string.cal_trace_uds_services, services), color = CamaroAmber, fontWeight = FontWeight.Bold)
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
                            message.negativeResponseCode?.let { nrc ->
                                val language = LocalConfiguration.current.locales[0].language
                                Text(
                                    stringResource(
                                        R.string.can_trace_nrc_detail,
                                        nrc,
                                        UdsNegativeResponse.description(nrc, language)
                                    ),
                                    color = WarnRed,
                                    fontSize = 11.sp
                                )
                            }
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
            if (report.exchanges.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.can_trace_exchange_heading), color = CamaroAmber, fontWeight = FontWeight.Bold)
                }
                items(report.exchanges.takeLast(30).asReversed()) { exchange ->
                    val request = exchange.request
                    val didLabel = request.did?.let { " • DID 0x${String.format(Locale.ROOT, "%04X", it)}" }.orEmpty()
                    val parameterLabel = request.parameterId?.let {
                        stringResource(R.string.can_trace_exchange_parameter_id, it)
                    }.orEmpty()
                    val statusLabel = when (exchange.status) {
                        com.camarodiag.gen5.data.obd.UdsExchangeStatus.COMPLETED -> stringResource(R.string.can_trace_exchange_completed)
                        com.camarodiag.gen5.data.obd.UdsExchangeStatus.RESPONSE_PENDING -> stringResource(R.string.can_trace_exchange_pending)
                        com.camarodiag.gen5.data.obd.UdsExchangeStatus.UNANSWERED -> stringResource(R.string.can_trace_exchange_unanswered)
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${request.serviceName}$didLabel$parameterLabel", color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Text(stringResource(R.string.can_trace_exchange_summary, request.direction.name, request.canId, statusLabel), color = TextPrimary, fontSize = 11.sp)
                            Text(stringResource(R.string.can_trace_exchange_request, request.payload.joinToString(" ") { String.format(Locale.ROOT, "%02X", it) }), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            exchange.responses.forEach { response ->
                                Text(stringResource(R.string.can_trace_exchange_response, response.canId, response.payload.joinToString(" ") { String.format(Locale.ROOT, "%02X", it) }), color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }
                            exchange.elapsedMilliseconds?.let { elapsed ->
                                Text(stringResource(R.string.can_trace_exchange_elapsed, elapsed), color = TextMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            if (report.flowControlObservations.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.can_trace_flow_control_heading),
                        color = CamaroAmber,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(report.flowControlObservations.takeLast(30).asReversed()) { flow ->
                    val statusLabel = when (flow.status) {
                        com.camarodiag.gen5.data.obd.IsoTpFlowStatus.CONTINUE_TO_SEND ->
                            stringResource(R.string.can_trace_flow_status_cts)
                        com.camarodiag.gen5.data.obd.IsoTpFlowStatus.WAIT ->
                            stringResource(R.string.can_trace_flow_status_wait)
                        com.camarodiag.gen5.data.obd.IsoTpFlowStatus.OVERFLOW ->
                            stringResource(R.string.can_trace_flow_status_overflow)
                        com.camarodiag.gen5.data.obd.IsoTpFlowStatus.RESERVED ->
                            stringResource(R.string.can_trace_flow_status_reserved)
                    }
                    val stMin = flow.separationTimeMicros?.let { micros ->
                        if (micros % 1_000 == 0) {
                            stringResource(R.string.can_trace_flow_stmin_ms, micros / 1_000)
                        } else {
                            stringResource(R.string.can_trace_flow_stmin_us, micros)
                        }
                    } ?: stringResource(R.string.can_trace_flow_stmin_reserved, flow.separationTimeRaw)
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                        Text(
                            stringResource(
                                R.string.can_trace_flow_control_detail,
                                String.format(Locale.ROOT, "%03X", flow.canId),
                                statusLabel,
                                flow.blockSize,
                                stMin
                            ),
                            modifier = Modifier.padding(10.dp),
                            color = TextPrimary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
    pendingRawFrameInput?.let { (frame, pendingEpoch) ->
        val canConfirm = CanConsoleCapabilityPolicy.canConfirmPhysicalTx(
            frame = RawCanFrameParser.parse(frame),
            pendingContextGeneration = pendingEpoch,
            currentContextGeneration = state.consoleContextGeneration,
            monitorAvailable = isAvailable && state.bitrate != 33_333 && RawCanFrameParser.parse(frame)?.let {
                profile(it.isExtended, state.bitrate) in availableRawCanProfiles
            } == true,
            canTransmit = canTransmitAvailable,
            busy = state.isDoIpActive || state.isCapturing || state.isSendingRequest
        )
        AlertDialog(
            onDismissRequest = {
                pendingRawFrameInput = null
                writeConsentStep = EcuWriteConsentFlow.Step.WARNING
            },
            title = {
                Text(stringResource(
                    if (writeConsentStep == EcuWriteConsentFlow.Step.WARNING)
                        R.string.ecu_write_warning_title
                    else R.string.ecu_write_confirm_title
                ))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(frame, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(stringResource(
                        if (writeConsentStep == EcuWriteConsentFlow.Step.WARNING)
                            R.string.ecu_write_warning_message
                        else R.string.ecu_write_confirm_message
                    ))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canConfirm,
                    onClick = {
                        if (writeConsentStep == EcuWriteConsentFlow.Step.WARNING) {
                            // OK only acknowledges the risk notice: it NEVER sends.
                            writeConsentStep = EcuWriteConsentFlow.afterWarningOk(writeConsentStep)
                        } else {
                            EcuWriteConsentFlow.approval(
                                writeConsentStep, confirmedYes = true,
                                sameConnection = canConfirm
                            )?.let { approval ->
                                onConfirmRawFrame(frame, pendingEpoch, approval)
                            }
                            pendingRawFrameInput = null
                            writeConsentStep = EcuWriteConsentFlow.Step.WARNING
                        }
                    }
                ) {
                    Text(stringResource(
                        if (writeConsentStep == EcuWriteConsentFlow.Step.WARNING)
                            R.string.ecu_write_ok
                        else R.string.ecu_write_yes
                    ))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingRawFrameInput = null
                    writeConsentStep = EcuWriteConsentFlow.Step.WARNING
                }) {
                    Text(stringResource(
                        if (writeConsentStep == EcuWriteConsentFlow.Step.WARNING)
                            R.string.ecu_write_cancel
                        else R.string.ecu_write_no
                    ))
                }
            }
        )
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
fun DtcScreen(dtcs: List<DiagnosticDtc>, vehicle: VehicleProfile, ecuVinComparison: VehicleVinComparison?, connectionGeneration: Long, scanNotice: String?, onScan: () -> Unit, onClear: (EcuWriteConsentFlow.Approval, Long, VehicleProfile) -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    var pendingClearGeneration by remember { mutableStateOf<Long?>(null) }
    var pendingClearVehicle by remember { mutableStateOf<VehicleProfile?>(null) }
    var clearConsentStep by remember { mutableStateOf(EcuWriteConsentFlow.Step.WARNING) }
    LaunchedEffect(vehicle.vin, vehicle.modelYear, connectionGeneration) {
        confirmClear = false
        pendingClearGeneration = null
        pendingClearVehicle = null
        clearConsentStep = EcuWriteConsentFlow.Step.WARNING
    }
    val isFrench = LocalConfiguration.current.locales[0].language.equals("fr", ignoreCase = true)
    var selectedDtc by remember { mutableStateOf<DiagnosticDtc?>(null) }
    selectedDtc?.let { selected ->
        val mayApply = DtcCatalogRegistry.mayApplyVehicleSpecificDefinitions(
            vehicle, selected.module, ecuVinComparison
        )
        val explanation = if (mayApply) {
            CamaroDtcCatalog.find(selected.code.substringBefore('-'), vehicle)
        } else null
        // GM monitor details are shown only for an identified, matching vehicle/ECU.
        val gmMonitor = if (mayApply) {
            GmEngineMonitorCatalog.find(selected.code.substringBefore('-'), vehicle)
        } else null
        val gmSourceLabel = GmEngineMonitorCatalog.sourceLabel(vehicle) ?: "GM"
        AlertDialog(
            onDismissRequest = { selectedDtc = null },
            title = { Text(selected.code) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(localizedDtcDescription(selected))
                    if (explanation == null) {
                        if (gmMonitor == null) Text(stringResource(R.string.obd_dtc_help_unavailable))
                        if (!mayApply) Text(stringResource(R.string.dtc_vehicle_identity_required))
                    } else {
                        Text(if (isFrench) explanation.meaningFr else explanation.meaningEn)
                        Text(stringResource(R.string.dtc_severity), fontWeight = FontWeight.Bold)
                        Text(if (isFrench) explanation.severityFr else explanation.severityEn)
                        Text(stringResource(R.string.dtc_possible_causes), fontWeight = FontWeight.Bold)
                        (if (isFrench) explanation.causesFr else explanation.causesEn).forEach {
                            Text("• $it")
                        }
                        Text(stringResource(R.string.dtc_recommended_checks), fontWeight = FontWeight.Bold)
                        (if (isFrench) explanation.checksFr else explanation.checksEn).forEachIndexed { index, check ->
                            Text("${index + 1}. $check")
                        }
                        val applicability = if (isFrench) explanation.applicabilityFr else explanation.applicabilityEn
                        if (!applicability.isNullOrBlank()) Text(applicability, color = WarnRed)
                    }
                    if (gmMonitor != null) {
                        HorizontalDivider(color = TextMuted.copy(alpha = 0.25f))
                        Text(stringResource(R.string.dtc_gm_monitor_title, gmSourceLabel),
                            fontWeight = FontWeight.Bold, color = CamaroAmber)
                        Text(if (isFrench) gmMonitor.componentFr else gmMonitor.componentEn,
                            fontWeight = FontWeight.SemiBold)
                        Text(if (isFrench) gmMonitor.strategyFr else gmMonitor.strategyEn)
                        Text(stringResource(R.string.dtc_gm_monitor_criteria,
                            if (isFrench) gmMonitor.malfunctionCriteriaFr else gmMonitor.malfunctionCriteriaEn))
                        Text(stringResource(R.string.dtc_gm_monitor_enable,
                            if (isFrench) gmMonitor.enableConditionsFr else gmMonitor.enableConditionsEn))
                        Text(stringResource(R.string.dtc_gm_monitor_failure,
                            if (isFrench) gmMonitor.failureCriteriaFr else gmMonitor.failureCriteriaEn))
                        Text(stringResource(R.string.dtc_gm_monitor_mil, gmMonitor.milClassification))
                        Text(stringResource(R.string.dtc_gm_monitor_source, gmSourceLabel, gmMonitor.sourcePage),
                            color = TextMuted, fontSize = 10.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedDtc = null }) {
                    Text(stringResource(R.string.obd_dtc_help_close))
                }
            }
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.dtc_title))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onScan) { Text(stringResource(R.string.dtc_scan)) }
                    Button(onClick = {
                        clearConsentStep = EcuWriteConsentFlow.Step.WARNING
                        pendingClearGeneration = connectionGeneration
                        pendingClearVehicle = vehicle
                        confirmClear = true
                    }, colors = ButtonDefaults.buttonColors(containerColor = WarnRed)) {
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
            if (dtcs.any { DtcCatalogRegistry.mayApplyVehicleSpecificDefinitions(vehicle, it.module, ecuVinComparison) }) {
                Text(
                    stringResource(R.string.dtc_gm_calibration_not_validated),
                    color = CamaroAmber,
                    fontSize = 11.sp
                )
            }
        }
        if (scanNotice != null && scanNotice.isNotEmpty()) {
            item { Text(scanNotice, color = CamaroAmber) }
        }
        if (dtcs.isEmpty()) {
            item {
                if (scanNotice == "") Text(stringResource(R.string.dtc_none_returned), color = OkGreen)
                else if (scanNotice == null) Text(stringResource(R.string.obd_data_unavailable), color = CamaroAmber)
            }
        } else {
            items(dtcs) { dtc ->
                Card(modifier = Modifier.clickable { selectedDtc = dtc }, colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${dtc.code} (${dtc.module})", fontWeight = FontWeight.Bold, color = if (dtc.isPending) CamaroAmber else WarnRed, fontSize = 16.sp)
                            Text(localizedDtcStatus(dtc), fontSize = 12.sp, color = TextMuted)
                        }
                        Text(localizedDtcDescription(dtc))
                        val mayApplyVehicleSpecific =
                            DtcCatalogRegistry.mayApplyVehicleSpecificDefinitions(
                                vehicle, dtc.module, ecuVinComparison
                            )
                        if (!mayApplyVehicleSpecific) {
                            Text(stringResource(R.string.dtc_vehicle_identity_required),
                                color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = {
                confirmClear = false
                pendingClearGeneration = null
                pendingClearVehicle = null
                clearConsentStep = EcuWriteConsentFlow.Step.WARNING
            },
            title = {
                Text(stringResource(
                    if (clearConsentStep == EcuWriteConsentFlow.Step.WARNING)
                        R.string.ecu_write_warning_title
                    else R.string.dtc_confirm_clear
                ))
            },
            text = {
                Text(stringResource(
                    if (clearConsentStep == EcuWriteConsentFlow.Step.WARNING)
                        R.string.ecu_write_warning_message
                    else R.string.dtc_clear_description
                ))
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (clearConsentStep == EcuWriteConsentFlow.Step.WARNING) {
                            // Acknowledging the warning never clears DTCs.
                            clearConsentStep = EcuWriteConsentFlow.afterWarningOk(clearConsentStep)
                        } else {
                            val expectedVehicle = pendingClearVehicle
                            val approval = EcuWriteConsentFlow.approvalForContext(
                                clearConsentStep, confirmedYes = true,
                                warningConnection = pendingClearGeneration,
                                currentConnection = connectionGeneration,
                                sameVehicle = expectedVehicle != null && expectedVehicle == vehicle
                            )
                            val epoch = pendingClearGeneration
                            confirmClear = false
                            pendingClearGeneration = null
                            pendingClearVehicle = null
                            clearConsentStep = EcuWriteConsentFlow.Step.WARNING
                            if (approval != null && epoch != null && expectedVehicle != null) {
                                onClear(approval, epoch, expectedVehicle)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarnRed)
                ) {
                    Text(stringResource(
                        if (clearConsentStep == EcuWriteConsentFlow.Step.WARNING)
                            R.string.ecu_write_ok else R.string.ecu_write_yes
                    ))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmClear = false
                    pendingClearGeneration = null
                    pendingClearVehicle = null
                    clearConsentStep = EcuWriteConsentFlow.Step.WARNING
                }) {
                    Text(stringResource(
                        if (clearConsentStep == EcuWriteConsentFlow.Step.WARNING)
                            R.string.ecu_write_cancel else R.string.ecu_write_no
                    ))
                }
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
                            text = stringResource(R.string.mileage_engineering_summary,
                                rep.vin.ifBlank { stringResource(R.string.obd_not_available) }),
                            fontWeight = FontWeight.Bold,
                            color = if (rep.hasDiscrepancy) WarnRed else OkGreen
                        )
                        Text(localizedMileageSummary(rep), fontSize = 13.sp)
                    }
                }
            }
            // Keep all evidence in the saved report. Hide unqueried or
            // unavailable sources when a usable reading exists, not the caveat.
            val usable = rep.readings.any { it.kmValue?.let { km -> km.isFinite() && km >= 0 } == true }
            items(rep.readings.filter { !usable || it.kmValue != null }) { item ->
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
                        Text(if (item.moduleId == "ECM_A6" && item.kmValue != null)
                            stringResource(R.string.mileage_reported_odometer_confidence)
                        else localizedMileageConfidence(item.confidence),
                            color = confColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
    followSystemLanguage: Boolean,
    onFollowSystemLanguage: () -> Unit,
    onUnits: (UnitSystem) -> Unit,
    onDeleteSession: (com.camarodiag.gen5.data.db.SavedSessionEntity) -> Unit,
    onOpenVehicle: () -> Unit,
    onOpenAdapter: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    var languageExpanded by remember { mutableStateOf(false) }
    val languageFlags = mapOf(
        AppLanguage.EN to "🇬🇧", AppLanguage.FR to "🇫🇷", AppLanguage.DE to "🇩🇪",
        AppLanguage.IT to "🇮🇹", AppLanguage.ES to "🇪🇸", AppLanguage.HE to "🇮🇱",
        AppLanguage.RU to "🇷🇺", AppLanguage.ZH to "🇨🇳", AppLanguage.JA to "🇯🇵",
        AppLanguage.KO to "🇰🇷", AppLanguage.AR to "🇸🇦", AppLanguage.PT to "🇵🇹",
        AppLanguage.NL to "🇳🇱", AppLanguage.EL to "🇬🇷",
        AppLanguage.BG to "🇧🇬",
        AppLanguage.HR to "🇭🇷",
        AppLanguage.CS to "🇨🇿",
        AppLanguage.DA to "🇩🇰",
        AppLanguage.ET to "🇪🇪",
        AppLanguage.FI to "🇫🇮",
        AppLanguage.HU to "🇭🇺",
        AppLanguage.GA to "🇮🇪",
        AppLanguage.LV to "🇱🇻",
        AppLanguage.LT to "🇱🇹",
        AppLanguage.MT to "🇲🇹",
        AppLanguage.PL to "🇵🇱",
        AppLanguage.RO to "🇷🇴",
        AppLanguage.SK to "🇸🇰",
        AppLanguage.SL to "🇸🇮",
        AppLanguage.SV to "🇸🇪"
    )
    val selectedLanguageLabel = if (followSystemLanguage) {
        "📱 ${stringResource(R.string.system_language_follow_phone)}"
    } else {
        "${languageFlags[lang].orEmpty()} ${lang.label}"
    }
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
                    Text(stringResource(R.string.system_language_current, selectedLanguageLabel), color = CamaroAmber)
                    Box {
                        OutlinedButton(onClick = { languageExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selectedLanguageLabel)
                        }
                        DropdownMenu(
                            expanded = languageExpanded,
                            onDismissRequest = { languageExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("📱 ${stringResource(R.string.system_language_follow_phone)}") },
                                onClick = {
                                    onFollowSystemLanguage()
                                    languageExpanded = false
                                }
                            )
                            AppLanguage.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text("${languageFlags[option].orEmpty()} ${option.label}") },
                                    onClick = {
                                        onLang(option)
                                        languageExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    // Text(stringResource(R.string.system_language_hint), color = TextMuted, fontSize = 12.sp)
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
private fun AdapterCapabilityLine(label: String, active: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(0.6f), fontSize = 13.sp)
        // Constrain the chip too, so translated status text cannot starve the label.
        Box(Modifier.weight(0.4f), contentAlignment = Alignment.CenterEnd) {
            BadgeChip(stringResource(if (active) R.string.adapter_capability_detected else R.string.adapter_capability_unverified), active)
        }
    }
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
