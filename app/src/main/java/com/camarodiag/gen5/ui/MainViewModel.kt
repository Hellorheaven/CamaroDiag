package com.camarodiag.gen5.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.edit
import com.camarodiag.gen5.R
import androidx.lifecycle.viewModelScope
import com.camarodiag.gen5.data.db.DiagnosticDatabase
import com.camarodiag.gen5.data.db.SavedSessionEntity
import com.camarodiag.gen5.data.obd.BluetoothObdManager
import com.camarodiag.gen5.data.obd.CamaroUdsProtocol
import com.camarodiag.gen5.data.obd.CalibrationFileInspector
import com.camarodiag.gen5.data.obd.CalibrationFileFormat
import com.camarodiag.gen5.data.obd.CalibrationFileInspection
import com.camarodiag.gen5.data.obd.CalibrationBinaryComparer
import com.camarodiag.gen5.data.obd.CalibrationTableCodec
import com.camarodiag.gen5.data.obd.ObdbCamaroSignalSet
import com.camarodiag.gen5.data.obd.CamaroSignalCatalog
import com.camarodiag.gen5.data.obd.UdsAccessPolicy
import com.camarodiag.gen5.data.obd.toDiagnosticDtc
import com.camarodiag.gen5.data.obd.ObdDtc
import com.camarodiag.gen5.data.obd.ObdScanReport
import com.camarodiag.gen5.data.obd.ObdTransportFailurePolicy
import com.camarodiag.gen5.data.obd.CanTraceAnalyzer
import com.camarodiag.gen5.data.obd.CanTraceAnalysisReport
import com.camarodiag.gen5.data.obd.SafeObdRequestPolicy
import com.camarodiag.gen5.data.obd.RawCanFrameParser
import com.camarodiag.gen5.mileageaudit.MileageAuditEngine
import com.camarodiag.gen5.domain.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random
import java.security.MessageDigest
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class SavedObdConnectionProfile(
    val transport: String,
    val adapterName: String,
    val endpoint: String
)

data class SavedVehicleProfile(
    val id: String,
    val name: String,
    val vehicle: VehicleProfile,
    val requiresReview: Boolean = false
)

data class CanConsoleRequestHistoryEntry(
    val command: String,
    val response: String?,
    val completedAtMillis: Long,
    val succeeded: Boolean
)

data class CanConsoleHistoryEntry(
    val id: Long,
    val direction: String,
    val timestampMillis: Long,
    val content: String,
    val succeeded: Boolean = true
)

data class LiveCanMonitorState(
    val isCapturing: Boolean = false,
    val rawText: String = "",
    val report: CanTraceAnalysisReport? = null,
    val isSendingRequest: Boolean = false,
    val requestResponse: String? = null,
    val requestHistory: List<CanConsoleRequestHistoryEntry> = emptyList(),
    val consoleHistory: List<CanConsoleHistoryEntry> = emptyList(),
    val message: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsPreferences = application.getSharedPreferences("camarodiag_settings", Context.MODE_PRIVATE)
    private val obdManager = BluetoothObdManager(application)
    @Volatile private var obdbCamaroSignalSet: ObdbCamaroSignalSet? = null
    @Volatile private var camaroSignalCatalog: CamaroSignalCatalog? = null
    private val sessionDao = DiagnosticDatabase.getInstance(application).sessionDao()

    private val _language = MutableStateFlow(
        AppLanguage.entries.firstOrNull { it.code == settingsPreferences.getString("language", null) }
            ?: AppLanguage.fromLocale(application.resources.configuration.locales[0])
    )
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private val _unitSystem = MutableStateFlow(
        runCatching { UnitSystem.valueOf(settingsPreferences.getString("unit_system", UnitSystem.METRIC.name)!!) }
            .getOrDefault(UnitSystem.METRIC)
    )
    val unitSystem: StateFlow<UnitSystem> = _unitSystem.asStateFlow()

    private val _vehicle = MutableStateFlow(loadVehicleProfile())
    val vehicle: StateFlow<VehicleProfile> = _vehicle.asStateFlow()

    private val _adapter = MutableStateFlow(AdapterCapabilities())
    val adapter: StateFlow<AdapterCapabilities> = _adapter.asStateFlow()

    private val _lastConnectionProfile = MutableStateFlow(loadLastConnectionProfile())
    val lastConnectionProfile: StateFlow<SavedObdConnectionProfile?> = _lastConnectionProfile.asStateFlow()

    private val _savedVehicles = MutableStateFlow(loadSavedVehicleProfiles())
    val savedVehicles: StateFlow<List<SavedVehicleProfile>> = _savedVehicles.asStateFlow()

    private val _selectedSavedVehicleId = MutableStateFlow(settingsPreferences.getString("selected_vehicle_id", null))
    val selectedSavedVehicleId: StateFlow<String?> = _selectedSavedVehicleId.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val pairedDevices: StateFlow<List<Pair<String, String>>> = _pairedDevices.asStateFlow()
    private val _usbDevices = MutableStateFlow<List<String>>(emptyList())
    val usbDevices: StateFlow<List<String>> = _usbDevices.asStateFlow()

    private val _connectionMessage = MutableStateFlow<String?>(null)
    val connectionMessage: StateFlow<String?> = _connectionMessage.asStateFlow()

    private val _connectionLossMessage = MutableStateFlow<String?>(null)
    val connectionLossMessage: StateFlow<String?> = _connectionLossMessage.asStateFlow()

    fun dismissConnectionLoss() {
        _connectionLossMessage.value = null
    }

    private fun reportConnectionLoss(error: Throwable) {
        if (!ObdTransportFailurePolicy.isConnectionLoss(error)) return
        stopCanMonitor()
        _isObdScanning.value = false
        val messageId = if (_lastConnectionProfile.value?.transport.equals("bluetooth", ignoreCase = true)) {
            R.string.connection_lost_bluetooth
        } else {
            R.string.connection_lost_vehicle
        }
        _connectionLossMessage.value = getApplication<Application>().getString(messageId)
        // Reset the physical transport too; USB/Wi-Fi availability otherwise remains optimistic.
        obdManager.disconnect()
        markAdapterDisconnected()
    }

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()
    private var activeBusyOperations = 0

    private fun beginBusyOperation() {
        activeBusyOperations += 1
        _isBusy.value = true
    }

    private fun endBusyOperation() {
        activeBusyOperations = (activeBusyOperations - 1).coerceAtLeast(0)
        _isBusy.value = activeBusyOperations > 0
    }

    private val _modules = MutableStateFlow<List<EcuModule>>(emptyList())
    val modules: StateFlow<List<EcuModule>> = _modules.asStateFlow()

    private val _dtcs = MutableStateFlow<List<DiagnosticDtc>>(emptyList())
    val dtcs: StateFlow<List<DiagnosticDtc>> = _dtcs.asStateFlow()

    private val _livePids = MutableStateFlow<List<LivePidValue>>(emptyList())
    val livePids: StateFlow<List<LivePidValue>> = _livePids.asStateFlow()

    private val _isStreamingLive = MutableStateFlow(true)
    val isStreamingLive: StateFlow<Boolean> = _isStreamingLive.asStateFlow()
    private val _liveTelemetryMessage = MutableStateFlow<String?>(null)
    val liveTelemetryMessage: StateFlow<String?> = _liveTelemetryMessage.asStateFlow()

    private val recordedLiveSamples = mutableListOf<RecordedLiveSample>()
    private val maxLiveRecordingSamples = 10_000
    private val _liveRecordingState = MutableStateFlow(LiveRecordingState())
    val liveRecordingState: StateFlow<LiveRecordingState> = _liveRecordingState.asStateFlow()

    private val _mileageReport = MutableStateFlow<MileageAuditReport?>(null)
    val mileageReport: StateFlow<MileageAuditReport?> = _mileageReport.asStateFlow()

    private val _obdScanReport = MutableStateFlow<ObdScanReport?>(null)
    val obdScanReport: StateFlow<ObdScanReport?> = _obdScanReport.asStateFlow()

    private val _obdScanMessage = MutableStateFlow<String?>(null)
    val obdScanMessage: StateFlow<String?> = _obdScanMessage.asStateFlow()

    private val _isObdScanning = MutableStateFlow(false)
    val isObdScanning: StateFlow<Boolean> = _isObdScanning.asStateFlow()

    val savedSessions: StateFlow<List<SavedSessionEntity>> = sessionDao.observeAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshTopologyAndDefaults()
        loadObdbSignalCatalog()
        loadNativeCamaroSignalCatalog()
        startLiveTelemetryLoop()
    }

    private fun loadVehicleProfile(): VehicleProfile {
        val year = settingsPreferences.getInt("vehicle_year", 2012).coerceIn(2010, 2024)
        val savedEngine = runCatching {
            CamaroEngine.valueOf(settingsPreferences.getString("vehicle_engine", CamaroEngine.LFX.name)!!)
        }.getOrDefault(CamaroEngine.LFX)
        val engine = if (year in savedEngine.years) savedEngine else CamaroEngine.entries.first { year in it.years }
        val transmission = runCatching {
            TransmissionType.valueOf(settingsPreferences.getString("vehicle_transmission", TransmissionType.MANUAL.name)!!)
        }.getOrDefault(TransmissionType.MANUAL)
        val market = runCatching {
            MarketRegion.valueOf(settingsPreferences.getString("vehicle_market", MarketRegion.EU_ECE.name)!!)
        }.getOrDefault(MarketRegion.EU_ECE)
        val vin = settingsPreferences.getString("vehicle_vin", "").orEmpty()
            .uppercase().filter { it.isLetterOrDigit() }.take(17)
        return VehicleProfile(year, engine, transmission, market, vin)
    }

    private fun persistVehicleProfile(profile: VehicleProfile) {
        settingsPreferences.edit {
            putInt("vehicle_year", profile.modelYear)
            putString("vehicle_engine", profile.engine.name)
            putString("vehicle_transmission", profile.transmission.name)
            putString("vehicle_market", profile.market.name)
            putString("vehicle_vin", profile.vin)
        }
    }

    private fun loadLastConnectionProfile(): SavedObdConnectionProfile? {
        val transport = settingsPreferences.getString("last_connection_transport", null) ?: return null
        val adapterName = settingsPreferences.getString("last_connection_adapter", null) ?: return null
        val endpoint = settingsPreferences.getString("last_connection_endpoint", "").orEmpty()
        return SavedObdConnectionProfile(transport, adapterName, endpoint)
    }

    private fun loadSavedVehicleProfiles(): List<SavedVehicleProfile> = runCatching {
        val source = settingsPreferences.getString("saved_vehicle_profiles", "[]").orEmpty()
        val array = JSONArray(source)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                val year = item.optInt("year", 2012).coerceIn(2010, 2024)
                val storedEngine = runCatching {
                    CamaroEngine.valueOf(item.optString("engine", CamaroEngine.LFX.name))
                }.getOrDefault(CamaroEngine.LFX)
                val engine = if (year in storedEngine.years) storedEngine
                    else CamaroEngine.entries.first { year in it.years }
                val transmission = runCatching {
                    TransmissionType.valueOf(item.optString("transmission", TransmissionType.MANUAL.name))
                }.getOrDefault(TransmissionType.MANUAL)
                val market = runCatching {
                    MarketRegion.valueOf(item.optString("market", MarketRegion.EU_ECE.name))
                }.getOrDefault(MarketRegion.EU_ECE)
                val vin = item.optString("vin").uppercase().filter { it.isLetterOrDigit() }.take(17)
                add(
                    SavedVehicleProfile(
                        id = id,
                        name = name,
                        vehicle = VehicleProfile(year, engine, transmission, market, vin),
                        requiresReview = item.optBoolean("requiresReview", false)
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun persistSavedVehicleProfiles(profiles: List<SavedVehicleProfile>) {
        val array = JSONArray()
        profiles.forEach { saved ->
            array.put(
                JSONObject().apply {
                    put("id", saved.id)
                    put("name", saved.name)
                    put("year", saved.vehicle.modelYear)
                    put("engine", saved.vehicle.engine.name)
                    put("transmission", saved.vehicle.transmission.name)
                    put("market", saved.vehicle.market.name)
                    put("vin", saved.vehicle.vin)
                    put("requiresReview", saved.requiresReview)
                }
            )
        }
        settingsPreferences.edit { putString("saved_vehicle_profiles", array.toString()) }
    }

    fun saveCurrentVehicle(name: String) {
        val cleanName = name.trim().take(60)
        if (cleanName.isBlank()) {
            _connectionMessage.value = getApplication<Application>().getString(R.string.msg_vehicle_profile_name_required)
            return
        }
        val current = _vehicle.value
        val existingId = _selectedSavedVehicleId.value
            ?.takeIf { id -> _savedVehicles.value.any { it.id == id } }
            ?: current.vin.takeIf { it.isNotBlank() }?.let { vin ->
                _savedVehicles.value.firstOrNull { it.vehicle.vin.equals(vin, ignoreCase = true) }?.id
            }
        val profile = SavedVehicleProfile(
            id = existingId ?: UUID.randomUUID().toString(),
            name = cleanName,
            vehicle = current,
            requiresReview = false
        )
        val updated = if (_savedVehicles.value.any { it.id == profile.id }) {
            _savedVehicles.value.map { if (it.id == profile.id) profile else it }
        } else {
            _savedVehicles.value + profile
        }
        _savedVehicles.value = updated
        persistSavedVehicleProfiles(updated)
        _selectedSavedVehicleId.value = profile.id
        settingsPreferences.edit { putString("selected_vehicle_id", profile.id) }
        _connectionMessage.value = getApplication<Application>().getString(R.string.msg_vehicle_profile_saved)
    }

    fun selectSavedVehicle(id: String) {
        val saved = _savedVehicles.value.firstOrNull { it.id == id } ?: return
        updateVehicle(
            year = saved.vehicle.modelYear,
            engine = saved.vehicle.engine,
            transmission = saved.vehicle.transmission,
            market = saved.vehicle.market
        )
        updateVin(saved.vehicle.vin)
        _selectedSavedVehicleId.value = saved.id
        settingsPreferences.edit { putString("selected_vehicle_id", saved.id) }
        _connectionMessage.value = if (saved.requiresReview) {
            getApplication<Application>().getString(R.string.msg_vehicle_profile_review_required)
        } else {
            getApplication<Application>().getString(R.string.msg_vehicle_profile_loaded, saved.name)
        }
    }

    fun deleteSavedVehicle(id: String) {
        val updated = _savedVehicles.value.filterNot { it.id == id }
        if (updated.size == _savedVehicles.value.size) return
        _savedVehicles.value = updated
        persistSavedVehicleProfiles(updated)
        if (_selectedSavedVehicleId.value == id) {
            _selectedSavedVehicleId.value = null
            settingsPreferences.edit { remove("selected_vehicle_id") }
        }
    }

    fun syncVehicleVinFromEcu(ecuVin: String) {
        val normalizedVin = VehicleVinMatcher.normalize(ecuVin)
        if (normalizedVin == null) {
            _connectionMessage.value = getApplication<Application>().getString(R.string.msg_vehicle_ecu_vin_invalid)
            return
        }
        val selectedId = _selectedSavedVehicleId.value
        updateVin(normalizedVin)
        if (selectedId != null) {
            val updated = _savedVehicles.value.map { saved ->
                if (saved.id == selectedId) saved.copy(vehicle = _vehicle.value) else saved
            }
            _savedVehicles.value = updated
            persistSavedVehicleProfiles(updated)
            _selectedSavedVehicleId.value = selectedId
            settingsPreferences.edit { putString("selected_vehicle_id", selectedId) }
        }
    }

    fun createVehicleFromEcuVin(ecuVin: String) {
        val normalizedVin = VehicleVinMatcher.normalize(ecuVin)
        if (normalizedVin == null) {
            _connectionMessage.value = getApplication<Application>().getString(R.string.msg_vehicle_ecu_vin_invalid)
            return
        }
        val vehicleFromEcu = _vehicle.value.copy(vin = normalizedVin)
        val suffix = normalizedVin.takeLast(6)
        val saved = SavedVehicleProfile(
            id = UUID.randomUUID().toString(),
            name = getApplication<Application>().getString(R.string.saved_vehicle_ecu_default_name, suffix),
            vehicle = vehicleFromEcu,
            requiresReview = true
        )
        val updated = _savedVehicles.value + saved
        _savedVehicles.value = updated
        persistSavedVehicleProfiles(updated)
        updateVin(normalizedVin)
        _selectedSavedVehicleId.value = saved.id
        settingsPreferences.edit { putString("selected_vehicle_id", saved.id) }
        _connectionMessage.value = getApplication<Application>().getString(R.string.msg_vehicle_profile_review_required)
    }

    private fun rememberConnectionProfile(transport: String, capabilities: AdapterCapabilities, endpoint: String) {
        val profile = SavedObdConnectionProfile(transport, capabilities.deviceName, endpoint)
        _lastConnectionProfile.value = profile
        settingsPreferences.edit {
            putString("last_connection_transport", profile.transport)
            putString("last_connection_adapter", profile.adapterName)
            putString("last_connection_endpoint", profile.endpoint)
        }
    }

    fun setLanguage(lang: AppLanguage) {
        _language.value = lang
        settingsPreferences.edit { putString("language", lang.code) }
    }

    private fun localizedMileageSummaryForStorage(report: com.camarodiag.gen5.domain.model.MileageAuditReport): String {
        val valid = report.readings.filter { reading ->
            reading.kmValue?.let { it.isFinite() && it >= 0.0 } == true
        }
        val independent = valid.filter {
            !it.profileEvidenceReference.isNullOrBlank() &&
                it.confidence == com.camarodiag.gen5.domain.model.ConfidenceLevel.HIGH &&
                it.moduleId.uppercase() in setOf("BCM", "IPC", "ECM_TOTAL_ODO")
        }.distinctBy { it.moduleId.uppercase() }
        val values = independent.mapNotNull { it.kmValue }
        val delta = if (values.size >= 2) values.maxOrNull()!! - values.minOrNull()!! else 0.0
        val context = getApplication<Application>()
        return when {
            report.hasDiscrepancy -> context.getString(R.string.mileage_summary_discrepancy, delta)
            values.size >= 2 -> context.getString(R.string.mileage_summary_agreement, delta)
            valid.isEmpty() -> context.getString(R.string.mileage_summary_no_data)
            else -> context.getString(R.string.mileage_summary_insufficient, valid.size, independent.size)
        }
    }

    private fun isSystemFrench(): Boolean =
        getApplication<Application>().resources.configuration.locales[0].language.equals("fr", ignoreCase = true)
    fun setUnitSystem(units: UnitSystem) {
        _unitSystem.value = units
        settingsPreferences.edit { putString("unit_system", units.name) }
    }

    private fun markSavedVehicleSelectionDirty() {
        if (_selectedSavedVehicleId.value == null) return
        _selectedSavedVehicleId.value = null
        settingsPreferences.edit { remove("selected_vehicle_id") }
    }

    fun updateVehicle(
        year: Int = _vehicle.value.modelYear,
        engine: CamaroEngine = _vehicle.value.engine,
        transmission: TransmissionType = _vehicle.value.transmission,
        market: MarketRegion = _vehicle.value.market
    ) {
        val compatibleEngines = CamaroEngine.entries.filter { year in it.years }
        val safeEngine = if (engine in compatibleEngines) engine else compatibleEngines.first()
        val updated = _vehicle.value.copy(
            modelYear = year,
            engine = safeEngine,
            transmission = transmission,
            market = market
        )
        if (updated == _vehicle.value) return
        markSavedVehicleSelectionDirty()
        _vehicle.value = updated
        persistVehicleProfile(updated)
        invalidateLiveRecordingForVehicleChange()
        clearVehicleEvidence()
        refreshTopologyAndDefaults()
        refreshObdbSignalYear()
        refreshNativeSignalYear()
    }

    fun updateVin(vin: String) {
        val normalizedVin = vin.uppercase().filter { it.isLetterOrDigit() }.take(17)
        if (normalizedVin == _vehicle.value.vin) return
        markSavedVehicleSelectionDirty()
        _vehicle.value = _vehicle.value.copy(vin = normalizedVin)
        settingsPreferences.edit { putString("vehicle_vin", normalizedVin) }
        invalidateLiveRecordingForVehicleChange()
        clearVehicleEvidence()
    }

    private fun invalidateLiveRecordingForVehicleChange() {
        val wasRecording = _liveRecordingState.value.isRecording
        val previousSampleCount = recordedLiveSamples.size
        recordedLiveSamples.clear()
        _liveRecordingState.value = if (wasRecording) {
            _liveRecordingState.value.copy(
                isRecording = false,
                sampleCount = 0,
                stoppedAtMillis = System.currentTimeMillis(),
                message = getApplication<Application>().getString(
                    R.string.msg_live_recording_stopped_count,
                    previousSampleCount
                )
            )
        } else {
            LiveRecordingState()
        }
    }

    private fun clearVehicleEvidence() {
        discardCanMonitor()
        detachCanRequest()
        _liveCanMonitorState.value = LiveCanMonitorState()
        obdManager.invalidateObdPidSupportCache()
        if (obdManager.isJ2534BridgeConnected()) {
            obdManager.disconnect()
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                isJ2534BridgeConnecting = false,
                isJ2534BridgeConnected = false,
                j2534BridgeMessage = getApplication<Application>().getString(R.string.vm_bridge_disconnected_vehicle_changed)
            )
        }
        _dtcs.value = emptyList()
        _livePids.value = emptyList()
        _mileageReport.value = null
        _obdScanReport.value = null
        _obdScanMessage.value = null
        _liveTelemetryMessage.value = null
        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
            ecuVinResponse = "",
            vinComparison = null,
            vinModelYear = null,
            ecuNameResponse = "",
            respondingEcuAddresses = emptyList(),
            calibrationIdResponse = "",
            cvnResponse = "",
            udsDidResponse = "",
            udsDtcResponse = "",
            udsMessage = ""
        )
    }

    private val _calibrationWorkspace = MutableStateFlow(CalibrationWorkspaceState())
    val calibrationWorkspace: StateFlow<CalibrationWorkspaceState> = _calibrationWorkspace.asStateFlow()

    private val _liveCanMonitorState = MutableStateFlow(LiveCanMonitorState())
    val liveCanMonitorState: StateFlow<LiveCanMonitorState> = _liveCanMonitorState.asStateFlow()
    private var canMonitorJob: Job? = null
    private var canRequestJob: Job? = null

    private fun loadNativeCamaroSignalCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().assets.open(CamaroSignalCatalog.ASSET_NAME)
                    .bufferedReader(Charsets.UTF_8).use { CamaroSignalCatalog.fromJson(it.readText()) }
            }.onSuccess { catalog ->
                camaroSignalCatalog = catalog
                val signals = catalog.forYear(_vehicle.value.modelYear)
                withContext(Dispatchers.Main) {
                    _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                        nativeSignals = signals,
                        nativeSignalMessage = getApplication<Application>().resources.getQuantityString(R.plurals.vm_native_catalog_loaded, signals.size, signals.size, _vehicle.value.modelYear)
                    )
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                        nativeSignalMessage = getApplication<Application>().getString(R.string.msg_native_catalog_load_failed)
                    )
                }
            }
        }
    }

    private fun loadObdbSignalCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ObdbCamaroSignalSet.load(getApplication<Application>()) }
                .onSuccess { catalog ->
                    obdbCamaroSignalSet = catalog
                    val signals = catalog.forYear(_vehicle.value.modelYear)
                    withContext(Dispatchers.Main) {
                        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                            obdbSignals = signals,
                            obdbSignalMessage = getApplication<Application>().resources.getQuantityString(R.plurals.vm_community_catalog_loaded, signals.size, signals.size, _vehicle.value.modelYear)
                        )
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                            obdbSignalMessage = getApplication<Application>().getString(R.string.msg_obdb_catalog_load_failed)
                        )
                    }
                }
        }
    }

    private fun refreshNativeSignalYear() {
        val catalog = camaroSignalCatalog ?: return
        val signals = catalog.forYear(_vehicle.value.modelYear)
        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
            nativeSignals = signals,
            nativeSignalMessage = getApplication<Application>().resources.getQuantityString(
                R.plurals.vm_native_catalog_loaded,
                signals.size,
                signals.size,
                _vehicle.value.modelYear
            )
        )
    }

    private fun refreshObdbSignalYear() {
        val catalog = obdbCamaroSignalSet ?: return
        val signals = catalog.forYear(_vehicle.value.modelYear)
        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
            obdbSignals = signals,
            obdbSignalValue = "",
            obdbSignalMessage = getApplication<Application>().resources.getQuantityString(
                R.plurals.vm_community_catalog_loaded,
                signals.size,
                signals.size,
                _vehicle.value.modelYear
            )
        )
    }

    /** Reads and decodes one read-only Camaro signal from the imported OBDb signalset. */
    fun readObdbSignal(signalId: String, protocolNumberText: String) {
        viewModelScope.launch {
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                isObdbSignalReading = true,
                obdbSignalValue = "",
                obdbSignalMessage = ""
            )
            try {
                val catalog = obdbCamaroSignalSet ?: ObdbCamaroSignalSet.load(getApplication<Application>()).also {
                    obdbCamaroSignalSet = it
                }
                val signal = catalog.find(signalId) ?: error("Signal OBDb inconnu : $signalId")
                require(signal.isAvailableForYear(_vehicle.value.modelYear)) {
                    "Cette définition n'est pas disponible pour l'année du véhicule sélectionné."
                }
                require(signal.hasSupportedPhysicalCanAddressing) {
                    "Adresse CAN non résolue dans la source OBDb : lecture désactivée pour éviter d'inventer une adresse."
                }
                require(signal.service == 0x22) { "Seuls les DID UDS 0x22 de ce catalogue sont actuellement pris en charge." }
                val requestId = signal.requestCanId!!
                val responseId = signal.responseCanId!!
                val did = signal.identifierHex.toInt(16)
                val policy = UdsAccessPolicy.readOnly(setOf(did))
                val protocolNumber = protocolNumberText.toIntOrNull()
                    ?: error("Profil CAN invalide.")
                require(protocolNumber in setOf(33, 34, 35, 36, 63, 64)) { "Profil CAN non pris en charge." }
                val client = if (obdManager.isJ2534BridgeConnected()) {
                    obdManager.createJ2534BridgeUdsClient(
                        requestCanId = requestId,
                        responseCanId = responseId,
                        protocolNumber = protocolNumber,
                        policy = policy,
                        timeoutMs = 5000L
                    )
                } else {
                    obdManager.createStnUdsClient(
                        requestCanId = requestId,
                        responseCanId = responseId,
                        protocolNumber = protocolNumber,
                        policy = policy,
                        timeoutMs = 5000L
                    )
                }
                val raw = client.readDataByIdentifier(did)
                val decoded = signal.decode(raw)
                val rendered = when {
                    decoded.unavailable -> getApplication<Application>().getString(R.string.vm_sentinel_unavailable)
                    decoded.mappedLabel != null -> decoded.mappedLabel
                    decoded.value != null -> {
                        val number = java.text.DecimalFormat("0.###", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT))
                            .format(decoded.value)
                        "$number ${decoded.unit}"
                    }
                    else -> decoded.raw
                }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isObdbSignalReading = false,
                    obdbSignalValue = "$rendered\nRAW_DEC=${decoded.raw} • DID=0x${signal.identifierHex}",
                    obdbSignalMessage = getApplication<Application>().getString(R.string.vm_obdb_signal_read)
                )
            } catch (cancelled: CancellationException) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(isObdbSignalReading = false)
                throw cancelled
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isObdbSignalReading = false,
                    obdbSignalValue = "",
                    obdbSignalMessage = getApplication<Application>().getString(R.string.msg_obdb_signal_read_failed)
                )
            }
        }
    }

    /** Opens the Windows J2534 PC bridge for a real VXDIAG PassThru provider. */
    fun connectJ2534Bridge(
        host: String,
        portText: String,
        pairingToken: String,
        requestCanIdHex: String,
        responseCanIdHex: String,
        protocolNumberText: String
    ) {
        viewModelScope.launch {
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                isJ2534BridgeConnecting = true,
                isJ2534BridgeConnected = false,
                j2534BridgeMessage = ""
            )
            try {
                fun parseCanId(value: String): Pair<Int, String> {
                    val normalized = value.trim().removePrefix("0x").removePrefix("0X").uppercase()
                    require(normalized.length in setOf(3, 8) &&
                        normalized.all { it.digitToIntOrNull(16) != null }) {
                        "CAN ID doit contenir 3 chiffres hexadécimaux (11 bits) ou 8 (29 bits)."
                    }
                    return normalized.toInt(16) to normalized
                }
                val (requestId, requestText) = parseCanId(requestCanIdHex)
                val (responseId, responseText) = parseCanId(responseCanIdHex)
                require(requestText.length == responseText.length) {
                    "Les deux identifiants CAN doivent utiliser le même format 11 ou 29 bits."
                }
                require(requestId != responseId) { "Les CAN ID de requête et de réponse doivent être différents." }
                val can29Bit = requestText.length == 8
                val protocolNumber = protocolNumberText.toIntOrNull()
                    ?: error("Profil CAN invalide.")
                require(protocolNumber in setOf(33, 34, 35, 36, 63, 64)) { "Profil CAN non pris en charge." }
                require(can29Bit == (protocolNumber in setOf(34, 36, 64))) {
                    "Le profil CAN sélectionné ne correspond pas à la longueur des identifiants CAN."
                }
                val port = portText.toIntOrNull() ?: error("Port TCP invalide.")
                val result = obdManager.connectJ2534Bridge(
                    host = host.trim(),
                    port = port,
                    pairingToken = pairingToken.trim(),
                    requestCanId = requestId,
                    responseCanId = responseId,
                    can29Bit = can29Bit,
                    protocolNumber = protocolNumber
                ).getOrThrow()
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isJ2534BridgeConnecting = false,
                    isJ2534BridgeConnected = true,
                    j2534BridgeMessage = getApplication<Application>().getString(R.string.vm_j2534_connected, result)
                )
            } catch (cancelled: CancellationException) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(isJ2534BridgeConnecting = false)
                throw cancelled
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isJ2534BridgeConnecting = false,
                    isJ2534BridgeConnected = false,
                    j2534BridgeMessage = getApplication<Application>().getString(R.string.msg_j2534_bridge_connect_failed)
                )
            }
        }
    }

    fun disconnectJ2534Bridge() {
        viewModelScope.launch {
            val activeMonitor = canMonitorJob
            val activeRequest = detachCanRequest()
            stopCanMonitor()
            activeMonitor?.join()
            activeRequest?.join()
            obdManager.disconnect()
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                isJ2534BridgeConnecting = false,
                isJ2534BridgeConnected = false,
                j2534BridgeMessage = getApplication<Application>().getString(R.string.vm_pc_bridge_disconnected)
            )
        }
    }

    /** Reads one UDS DID over the verified STPX CAN transport; CAN IDs must be supplied from service data. */
    fun readUdsDid(requestCanIdHex: String, responseCanIdHex: String, didHex: String, protocolNumberText: String) {
        viewModelScope.launch {
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                isUdsReading = true,
                udsDidResponse = "",
                udsMessage = ""
            )
            try {
                fun parseHex(value: String, maxDigits: Int): Int {
                    val normalized = value.trim().removePrefix("0x").removePrefix("0X")
                    require(normalized.isNotEmpty() && normalized.length <= maxDigits &&
                        normalized.all { it.digitToIntOrNull(16) != null }) { "Identifiant hexadécimal invalide : $value" }
                    return normalized.toInt(16)
                }
                val requestId = parseHex(requestCanIdHex, 8)
                val responseId = parseHex(responseCanIdHex, 8)
                val did = parseHex(didHex, 4)
                require(requestId != responseId) { "Les CAN ID de requête et de réponse doivent être différents." }
                val protocolNumber = protocolNumberText.toIntOrNull()
                    ?: error("Profil CAN invalide.")
                require(protocolNumber in setOf(33, 34, 35, 36, 63, 64)) { "Profil CAN non pris en charge." }
                val client = if (obdManager.isJ2534BridgeConnected()) {
                    obdManager.createJ2534BridgeUdsClient(
                        requestCanId = requestId,
                        responseCanId = responseId,
                        protocolNumber = protocolNumber,
                        timeoutMs = 5000L
                    )
                } else {
                    obdManager.createStnUdsClient(
                        requestCanId = requestId,
                        responseCanId = responseId,
                        protocolNumber = protocolNumber,
                        timeoutMs = 5000L
                    )
                }
                val data = client.readDataByIdentifier(did)
                val hex = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                val isVinDid = did == 0xF190
                val rendered = if (isVinDid) {
                    val ascii = data.toString(Charsets.US_ASCII).trim().trim('\u0000')
                    if (ascii.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) "$ascii\n$hex" else hex
                } else hex
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isUdsReading = false,
                    udsDidResponse = rendered,
                    udsMessage = getApplication<Application>().getString(R.string.vm_uds_response_received)
                )
            } catch (cancelled: CancellationException) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(isUdsReading = false)
                throw cancelled
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isUdsReading = false,
                    udsDidResponse = "",
                    udsMessage = getApplication<Application>().getString(R.string.msg_uds_read_failed)
                )
            }
        }
    }

    /** Reads UDS ReadDTCInformation (0x19/0x02) from the selected physical ECU. */
    fun readUdsDtcs(requestCanIdHex: String, responseCanIdHex: String, protocolNumberText: String) {
        viewModelScope.launch {
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                isUdsReading = true,
                udsDtcResponse = "",
                udsMessage = ""
            )
            try {
                fun parseHex(value: String): Int {
                    val normalized = value.trim().removePrefix("0x").removePrefix("0X")
                    require(normalized.isNotEmpty() && normalized.length <= 8 &&
                        normalized.all { it.digitToIntOrNull(16) != null }) { "CAN ID hexadécimal invalide : $value" }
                    return normalized.toInt(16)
                }
                val requestId = parseHex(requestCanIdHex)
                val responseId = parseHex(responseCanIdHex)
                require(requestId != responseId)
                val protocolNumber = protocolNumberText.toIntOrNull()
                    ?: error("Profil CAN invalide.")
                require(protocolNumber in setOf(33, 34, 35, 36, 63, 64)) { "Profil CAN non pris en charge." }
                val client = if (obdManager.isJ2534BridgeConnected()) {
                    obdManager.createJ2534BridgeUdsClient(
                        requestCanId = requestId,
                        responseCanId = responseId,
                        protocolNumber = protocolNumber,
                        timeoutMs = 5000L
                    )
                } else {
                    obdManager.createStnUdsClient(
                        requestCanId = requestId,
                        responseCanId = responseId,
                        protocolNumber = protocolNumber,
                        timeoutMs = 5000L
                    )
                }
                val report = client.readDtcReport()
                val moduleId = "ECU ${responseCanIdHex.uppercase()}"
                val mappedDtcs = report.records.map { record ->
                    val baseCode = record.code.substringBefore('-')
                    val standard = DtcCatalogRegistry.genericDescription(baseCode)
                    // A raw CAN response address is not an identified ECU family.
                    // Keep UDS results generic until module discovery confirms ECM identity.
                    val vehicleSpecific = if (moduleId == "ECM") {
                        CamaroDtcCatalog.find(baseCode, _vehicle.value)
                    } else {
                        null
                    }
                    val rawCode = "0x${record.rawDtc.toString(16).uppercase().padStart(6, '0')}"
                    record.toDiagnosticDtc(moduleId).copy(
                        descEn = vehicleSpecific?.meaningEn ?: standard?.first
                            ?: "Raw OEM DTC $rawCode; no verified definition is loaded for this ECU.",
                        descFr = vehicleSpecific?.meaningFr ?: standard?.second
                            ?: "DTC constructeur brut $rawCode ; aucune définition vérifiée n'est chargée pour ce calculateur."
                    )
                }
                _dtcs.value = _dtcs.value.filterNot { it.module == moduleId } + mappedDtcs
                val rendered = if (report.records.isEmpty()) {
                    getApplication<Application>().getString(R.string.vm_no_dtcs_returned, responseCanIdHex.uppercase())
                } else {
                    report.records.joinToString("\\n") { record ->
                        "${record.code}  status=0x${record.statusByte.toString(16).uppercase().padStart(2, '0')}"
                    }
                }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isUdsReading = false,
                    udsDtcResponse = rendered,
                    udsMessage = getApplication<Application>().getString(R.string.vm_uds_read_complete)
                )
            } catch (cancelled: CancellationException) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(isUdsReading = false)
                throw cancelled
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isUdsReading = false,
                    udsDtcResponse = "",
                    udsMessage = getApplication<Application>().getString(R.string.msg_uds_dtc_read_failed)
                )
            }
        }
    }

    /** Reads only standardized OBD Mode 09 calibration identifiers; this is not a binary map read. */
    fun readCalibrationIdentification() {
        viewModelScope.launch {
            if (!obdManager.isObdCommandChannelAvailable) {
                val bridgeOnly = obdManager.isJ2534BridgeConnected()
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isReading = false,
                    ecuVinResponse = "",
                    vinComparison = null,
                    vinModelYear = null,
                    ecuNameResponse = "",
                    respondingEcuAddresses = emptyList(),
                    calibrationIdResponse = "",
                    cvnResponse = "",
                    message = if (bridgeOnly) {
                        getApplication<Application>().getString(R.string.vm_j2534_no_elm_channel)
                    } else {
                        getApplication<Application>().getString(R.string.vm_no_obd_data_read)
                    }
                )
                return@launch
            }
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                isReading = true,
                ecuVinResponse = "",
                vinComparison = null,
                vinModelYear = null,
                ecuNameResponse = "",
                respondingEcuAddresses = emptyList(),
                calibrationIdResponse = "",
                cvnResponse = "",
                message = ""
            )
            try {
                val identity = obdManager.withObdCommandSession { engine -> engine.readEcuIdentification() }
                val ecuVin = identity.vin
                val validCal = identity.calibrationIds.isNotEmpty()
                val validCvn = identity.calibrationVerificationNumbers.isNotEmpty()
                val vinComparison = VehicleVinMatcher.compare(_vehicle.value.vin, ecuVin)
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isReading = false,
                    ecuVinResponse = ecuVin ?: getApplication<Application>().getString(R.string.obd_not_available),
                    vinComparison = vinComparison,
                    vinModelYear = VehicleVinMatcher.modelYearFromVin(ecuVin),
                    ecuNameResponse = identity.ecuNames.joinToString("\n"),
                    respondingEcuAddresses = identity.respondingEcuAddresses,
                    calibrationIdResponse = identity.calibrationIds.joinToString("\n").ifBlank { getApplication<Application>().getString(R.string.obd_not_available) },
                    cvnResponse = identity.calibrationVerificationNumbers.joinToString("\n").ifBlank { getApplication<Application>().getString(R.string.obd_not_available) },
                    message = getApplication<Application>().getString(
                        if (validCal || validCvn) R.string.msg_cal_identifiers_read
                        else R.string.msg_cal_identifiers_unavailable
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isReading = false,
                    ecuVinResponse = "",
                    vinComparison = null,
                    vinModelYear = null,
                    ecuNameResponse = "",
                    respondingEcuAddresses = emptyList(),
                    calibrationIdResponse = "",
                    cvnResponse = "",
                    message = getApplication<Application>().getString(R.string.msg_cal_identifiers_failed)
                )
            }
        }
    }

    /** Imports and analyzes a local Vector ASC or CSV CAN trace without connecting to a vehicle. */
    fun analyzeCanTrace(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                isTraceAnalyzing = true,
                canTraceReport = null,
                traceMessage = ""
            )
            try {
                val resolver = getApplication<Application>().contentResolver
                val displayName = resolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: "can-trace.asc"
                val bytes = resolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 32 * 1024 * 1024) {
                            getApplication<Application>().getString(R.string.msg_trace_too_large)
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error(getApplication<Application>().getString(R.string.msg_trace_open_failed))
                require(bytes.isNotEmpty()) { getApplication<Application>().getString(R.string.msg_trace_empty) }
                val report = CanTraceAnalyzer.analyze(bytes.toString(Charsets.UTF_8), displayName)
                val localizedErrors = report.errors.map(::localizedCanTraceError)
                val localizedReport = report.copy(errors = localizedErrors)
                val summary = if (report.frames.isEmpty()) {
                    getApplication<Application>().getString(R.string.vm_trace_no_frames)
                } else {
                    getApplication<Application>().getString(
                        R.string.vm_trace_summary,
                        report.frames.size,
                        report.messages.size,
                        report.readDidCandidates.size,
                        report.incompleteIsoTpMessages,
                        report.errors.size
                    )
                }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isTraceAnalyzing = false,
                    canTraceReport = localizedReport,
                    traceMessage = summary
                )
            } catch (cancelled: CancellationException) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(isTraceAnalyzing = false)
                throw cancelled
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isTraceAnalyzing = false,
                    canTraceReport = null,
                    traceMessage = getApplication<Application>().getString(R.string.msg_trace_analysis_failed)
                )
            }
        }
    }

    fun canMonitorAvailable(): Boolean =
        obdManager.isObdCommandChannelAvailable && _adapter.value.supportsCanHeaders

    /** Starts a bounded, passive monitor session on a real header-capable ELM/STN adapter. */
    fun startCanMonitor(durationMs: Long = 10_000L) {
        if (canMonitorJob?.isActive == true) return
        if (!obdManager.isObdCommandChannelAvailable) {
            _liveCanMonitorState.value = LiveCanMonitorState(
                message = getApplication<Application>().getString(R.string.can_sniffer_unavailable)
            )
            return
        }
        if (!_adapter.value.supportsCanHeaders) {
            _liveCanMonitorState.value = LiveCanMonitorState(
                message = getApplication<Application>().getString(R.string.can_sniffer_unavailable)
            )
            return
        }
        if (_liveCanMonitorState.value.isSendingRequest) return
        val requestHistory = _liveCanMonitorState.value.requestHistory
        _liveCanMonitorState.value = LiveCanMonitorState(
            isCapturing = true,
            requestHistory = requestHistory
        )
        // Install the job reference before it can execute. A lazy start also lets the
        // finally block compare its identity, so an older cancelled capture cannot clear
        // or overwrite the state of a newer capture started immediately afterward.
        lateinit var monitorJob: Job
        monitorJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val pendingChunks = StringBuilder()
            try {
                val captured = obdManager.captureCanMonitor(durationMs) { chunk ->
                    if (canMonitorJob === monitorJob) {
                        pendingChunks.append(chunk)
                        val batch = pendingChunks.toString()
                        pendingChunks.clear()
                        val current = _liveCanMonitorState.value
                        val raw = (current.rawText + batch).takeLast(131_072)
                        val event = CanConsoleHistoryEntry(
                            id = System.nanoTime(),
                            direction = "RX",
                            timestampMillis = System.currentTimeMillis(),
                            content = batch.take(4_096)
                        )
                        _liveCanMonitorState.value = current.copy(
                            rawText = raw,
                            consoleHistory = (current.consoleHistory + event).takeLast(300)
                        )
                    }
                }
                if (canMonitorJob === monitorJob) {
                    val raw = captured.takeLast(131_072)
                    val report = CanTraceAnalyzer.analyze(
                        raw.replace("\r\n", "\n").replace('\r', '\n'),
                        "live-can-monitor.txt"
                    )
                    _liveCanMonitorState.value = _liveCanMonitorState.value.copy(
                        isCapturing = false,
                        rawText = raw,
                        report = report,
                        message = getApplication<Application>().getString(R.string.can_sniffer_capture_complete)
                    )
                }
            } catch (cancelled: CancellationException) {
                if (canMonitorJob === monitorJob) {
                    val current = _liveCanMonitorState.value
                    val raw = (current.rawText + pendingChunks).takeLast(131_072)
                    _liveCanMonitorState.value = current.copy(isCapturing = false, rawText = raw)
                }
                throw cancelled
            } catch (error: Exception) {
                reportConnectionLoss(error)
                if (canMonitorJob === monitorJob) {
                    val current = _liveCanMonitorState.value
                    val raw = (current.rawText + pendingChunks).takeLast(131_072)
                    _liveCanMonitorState.value = current.copy(
                        isCapturing = false,
                        rawText = raw,
                        message = getApplication<Application>().getString(R.string.can_sniffer_capture_failed)
                    )
                }
            } finally {
                if (canMonitorJob === monitorJob) canMonitorJob = null
            }
        }
        canMonitorJob = monitorJob
        monitorJob.start()
    }

    /**
     * Cancels and detaches a capture when its vehicle/adapter context is being discarded.
     * Detaching first prevents the cancelled job from publishing buffered frames into the
     * newly selected vehicle's empty CAN monitor state.
     */
    private fun discardCanMonitor() {
        val activeJob = canMonitorJob
        canMonitorJob = null
        activeJob?.cancel()
    }

    /** Detaches and cancels a manual console request so its result cannot leak into a new vehicle context. */
    private fun detachCanRequest(): Job? {
        val activeJob = canRequestJob
        canRequestJob = null
        activeJob?.cancel()
        return activeJob
    }

    /** Clears the single chronological console history and its raw capture buffer. */
    fun clearCanConsoleHistory() {
        val current = _liveCanMonitorState.value
        _liveCanMonitorState.value = current.copy(
            rawText = "",
            report = null,
            requestResponse = null,
            requestHistory = emptyList(),
            consoleHistory = emptyList()
        )
    }

    /** Cancels monitor capture; the transport sends ESC in a non-cancellable cleanup block. */
    fun stopCanMonitor() {
        canMonitorJob?.cancel()
    }

    /** Dispatches raw CAN frames and whitelisted OBD requests through the same console input. */
    fun sendCanConsoleInput(input: String) {
        val frame = RawCanFrameParser.parse(input)
        if (frame == null) {
            sendSafeObdRequest(input)
            return
        }
        sendRawCanConsoleFrame(input, frame)
    }

    private fun sendRawCanConsoleFrame(input: String, frame: com.camarodiag.gen5.data.obd.RawCanFrame) {
        val currentState = _liveCanMonitorState.value
        if (currentState.isSendingRequest || canRequestJob?.isActive == true) return
        if (!obdManager.isObdCommandChannelAvailable ||
            currentState.isCapturing || canMonitorJob?.isActive == true
        ) {
            _liveCanMonitorState.value = currentState.copy(
                message = getApplication<Application>().getString(R.string.can_sniffer_unavailable)
            )
            return
        }
        val txEvent = CanConsoleHistoryEntry(
            id = System.nanoTime(),
            direction = "TX",
            timestampMillis = System.currentTimeMillis(),
            content = input.trim()
        )
        _liveCanMonitorState.value = currentState.copy(
            isSendingRequest = true,
            message = null,
            consoleHistory = (currentState.consoleHistory + txEvent).takeLast(300)
        )
        lateinit var requestJob: Job
        requestJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val response = obdManager.sendRawCanFrame(frame)
                if (canRequestJob === requestJob) {
                    val current = _liveCanMonitorState.value
                    val rxEvent = CanConsoleHistoryEntry(
                        id = System.nanoTime(),
                        direction = "RX",
                        timestampMillis = System.currentTimeMillis(),
                        content = response.ifBlank { "No response text returned by adapter." }
                    )
                    _liveCanMonitorState.value = current.copy(
                        isSendingRequest = false,
                        requestResponse = response,
                        consoleHistory = (current.consoleHistory + rxEvent).takeLast(300),
                        message = getApplication<Application>().getString(R.string.can_sniffer_request_complete)
                    )
                }
            } catch (cancelled: CancellationException) {
                if (canRequestJob === requestJob) {
                    _liveCanMonitorState.value = _liveCanMonitorState.value.copy(isSendingRequest = false)
                }
                throw cancelled
            } catch (error: Exception) {
                if (canRequestJob === requestJob) {
                    reportConnectionLoss(error)
                    val current = _liveCanMonitorState.value
                    val errorEvent = CanConsoleHistoryEntry(
                        id = System.nanoTime(),
                        direction = "SYSTEM",
                        timestampMillis = System.currentTimeMillis(),
                        content = error.message ?: getApplication<Application>().getString(R.string.can_sniffer_request_failed),
                        succeeded = false
                    )
                    _liveCanMonitorState.value = current.copy(
                        isSendingRequest = false,
                        consoleHistory = (current.consoleHistory + errorEvent).takeLast(300),
                        message = getApplication<Application>().getString(R.string.can_sniffer_request_failed)
                    )
                }
            } finally {
                if (canRequestJob === requestJob) canRequestJob = null
            }
        }
        canRequestJob = requestJob
        requestJob.start()
    }

    /** Sends only a whitelisted, read-only SAE J1979 request; arbitrary CAN/UDS payloads are rejected. */
    fun sendSafeObdRequest(input: String) {
        val request = SafeObdRequestPolicy.parse(input)
        if (request == null) {
            _liveCanMonitorState.value = _liveCanMonitorState.value.copy(
                message = getApplication<Application>().getString(R.string.can_sniffer_invalid_request)
            )
            return
        }
        val currentState = _liveCanMonitorState.value
        if (currentState.isSendingRequest || canRequestJob?.isActive == true) return
        if (!obdManager.isObdCommandChannelAvailable ||
            currentState.isCapturing ||
            canMonitorJob?.isActive == true
        ) {
            _liveCanMonitorState.value = currentState.copy(
                message = getApplication<Application>().getString(R.string.can_sniffer_unavailable)
            )
            return
        }
        // Claim the request slot synchronously before dispatching to IO. A lazy job lets
        // us install its identity before it can complete, preventing duplicate sends and
        // stale results from a request cancelled during vehicle/adapter changes.
        val txEvent = CanConsoleHistoryEntry(
            id = System.nanoTime(),
            direction = "TX",
            timestampMillis = System.currentTimeMillis(),
            content = request.elmCommand
        )
        _liveCanMonitorState.value = currentState.copy(
            isSendingRequest = true,
            message = null,
            consoleHistory = (currentState.consoleHistory + txEvent).takeLast(300)
        )
        lateinit var requestJob: Job
        requestJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val response = obdManager.sendAtCommand(request.elmCommand, 4_000L)
                if (canRequestJob === requestJob) {
                    val current = _liveCanMonitorState.value
                    val historyEntry = CanConsoleRequestHistoryEntry(
                        command = request.elmCommand,
                        response = response.take(4_096),
                        completedAtMillis = System.currentTimeMillis(),
                        succeeded = true
                    )
                    val rxEvent = CanConsoleHistoryEntry(
                        id = System.nanoTime(),
                        direction = "RX",
                        timestampMillis = System.currentTimeMillis(),
                        content = response.ifBlank { "—" }
                    )
                    _liveCanMonitorState.value = current.copy(
                        isSendingRequest = false,
                        requestResponse = response,
                        requestHistory = (listOf(historyEntry) + current.requestHistory).take(20),
                        consoleHistory = (current.consoleHistory + rxEvent).takeLast(300),
                        message = getApplication<Application>().getString(R.string.can_sniffer_request_complete)
                    )
                }
            } catch (cancelled: CancellationException) {
                if (canRequestJob === requestJob) {
                    _liveCanMonitorState.value = _liveCanMonitorState.value.copy(isSendingRequest = false)
                }
                throw cancelled
            } catch (error: Exception) {
                if (canRequestJob === requestJob) {
                    reportConnectionLoss(error)
                    val current = _liveCanMonitorState.value
                    val historyEntry = CanConsoleRequestHistoryEntry(
                        command = request.elmCommand,
                        response = null,
                        completedAtMillis = System.currentTimeMillis(),
                        succeeded = false
                    )
                    val errorEvent = CanConsoleHistoryEntry(
                        id = System.nanoTime(),
                        direction = "SYSTEM",
                        timestampMillis = System.currentTimeMillis(),
                        content = getApplication<Application>().getString(R.string.can_sniffer_request_failed),
                        succeeded = false
                    )
                    _liveCanMonitorState.value = current.copy(
                        isSendingRequest = false,
                        requestHistory = (listOf(historyEntry) + current.requestHistory).take(20),
                        consoleHistory = (current.consoleHistory + errorEvent).takeLast(300),
                        message = getApplication<Application>().getString(R.string.can_sniffer_request_failed)
                    )
                }
            } finally {
                if (canRequestJob === requestJob) canRequestJob = null
            }
        }
        canRequestJob = requestJob
        requestJob.start()
    }

    /** Export the latest raw CAN capture without transforming adapter output. */
    fun exportCanCapture(destinationUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = _liveCanMonitorState.value
                val history = snapshot.consoleHistory
                require(history.isNotEmpty() || snapshot.rawText.isNotBlank()) {
                    "No CAN console history is available to export."
                }
                val outputText = buildString {
                    appendLine("# CamaroDiag unified CAN console")
                    appendLine("# TX = transmitted • RX = received • SYSTEM = application event")
                    history.sortedBy { it.timestampMillis }.forEach { entry ->
                        val timestamp = java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss.SSS",
                            java.util.Locale.ROOT
                        ).format(java.util.Date(entry.timestampMillis))
                        append(timestamp)
                        append(" [")
                        append(entry.direction)
                        append("] ")
                        appendLine(entry.content)
                    }
                    if (history.isEmpty() && snapshot.rawText.isNotBlank()) {
                        appendLine("# Raw adapter capture (direction not decoded by the adapter)")
                        append(snapshot.rawText)
                    }
                }
                val output = getApplication<Application>().contentResolver.openOutputStream(destinationUri, "w")
                    ?: error("Could not create the CAN console history file.")
                output.use { it.write(outputText.toByteArray(Charsets.UTF_8)) }
                _liveCanMonitorState.value = _liveCanMonitorState.value.copy(
                    message = getApplication<Application>().getString(R.string.can_sniffer_capture_complete)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _liveCanMonitorState.value = _liveCanMonitorState.value.copy(
                    message = getApplication<Application>().getString(R.string.can_sniffer_capture_failed)
                )
            }
        }
    }

    private fun localizedCanTraceError(message: String): String {
        val app = getApplication<Application>()
        val lineMatch = Regex("^Line (\\d+): (.*)$").matchEntire(message)
        val line = lineMatch?.groupValues?.get(1)?.toIntOrNull()
        val detail = lineMatch?.groupValues?.get(2)
        if (line != null && detail != null) {
            if (detail.startsWith("ISO-TP sequence mismatch")) {
                val values = Regex("expected (\\d+), got (\\d+)").find(detail)
                if (values != null) return app.getString(
                    R.string.trace_error_sequence,
                    line,
                    values.groupValues[1].toInt(),
                    values.groupValues[2].toInt()
                )
            }
            if (detail.startsWith("unsupported ISO-TP PCI type 0x")) {
                val value = Regex("0x([0-9A-Fa-f]+)").find(detail)?.groupValues?.get(1).orEmpty()
                return app.getString(R.string.trace_error_pci, line, value)
            }
            return app.getString(R.string.trace_error_invalid_line, line)
        }
        return when {
            message.startsWith("No ELM327 header records") -> app.getString(R.string.trace_error_elm_no_records)
            message == "CSV trace is empty." -> app.getString(R.string.trace_error_csv_empty)
            message.startsWith("CSV needs a CAN ID column") -> app.getString(R.string.trace_error_csv_columns)
            else -> app.getString(R.string.trace_error_generic)
        }
    }

    /** Imports a local calibration file for inventory and integrity checks only. */
    private fun localizedInspectionFormat(format: CalibrationFileFormat): String {
        val resource = when (format) {
            CalibrationFileFormat.RAW_BINARY -> R.string.cal_format_raw_binary
            CalibrationFileFormat.INTEL_HEX -> R.string.cal_format_intel_hex
            CalibrationFileFormat.MOTOROLA_S_RECORD -> R.string.cal_format_motorola_srecord
            CalibrationFileFormat.ZIP_ARCHIVE -> R.string.cal_format_zip_archive
            CalibrationFileFormat.EXTERNAL_DEFINITION -> R.string.cal_format_external_definition
            CalibrationFileFormat.EXTERNAL_TUNE_PROJECT -> R.string.cal_format_external_tune_project
            CalibrationFileFormat.UNKNOWN -> R.string.cal_format_unknown
        }
        return getApplication<Application>().getString(resource)
    }

    private fun localizedInspectionSummary(inspection: CalibrationFileInspection, fileName: String): String {
        val context = getApplication<Application>()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (!inspection.valid && inspection.format !in setOf(
                CalibrationFileFormat.EXTERNAL_TUNE_PROJECT,
                CalibrationFileFormat.EXTERNAL_DEFINITION,
                CalibrationFileFormat.UNKNOWN
            )
        ) return context.getString(R.string.cal_summary_invalid)
        return when (inspection.format) {
            CalibrationFileFormat.RAW_BINARY -> context.getString(R.string.cal_summary_raw_binary)
            CalibrationFileFormat.INTEL_HEX -> context.getString(R.string.cal_summary_intel_hex, inspection.recordCount, inspection.payloadBytes)
            CalibrationFileFormat.MOTOROLA_S_RECORD -> context.getString(R.string.cal_summary_motorola_srecord, inspection.recordCount, inspection.payloadBytes)
            CalibrationFileFormat.ZIP_ARCHIVE -> context.getString(R.string.cal_summary_zip_archive, inspection.recordCount, inspection.payloadBytes)
            CalibrationFileFormat.EXTERNAL_DEFINITION -> context.getString(R.string.cal_summary_external_definition, extension)
            CalibrationFileFormat.EXTERNAL_TUNE_PROJECT -> context.getString(R.string.cal_summary_external_tune, extension)
            CalibrationFileFormat.UNKNOWN -> context.getString(R.string.cal_summary_unknown)
        }
    }

    private fun localizedInspectionChecksum(inspection: CalibrationFileInspection): String {
        val resource = when {
            inspection.format == CalibrationFileFormat.RAW_BINARY -> R.string.cal_checksum_none
            inspection.format == CalibrationFileFormat.ZIP_ARCHIVE && inspection.valid -> R.string.cal_checksum_zip_readable
            inspection.valid && inspection.format in setOf(CalibrationFileFormat.INTEL_HEX, CalibrationFileFormat.MOTOROLA_S_RECORD) -> R.string.cal_checksum_records_valid
            inspection.format == CalibrationFileFormat.ZIP_ARCHIVE -> R.string.cal_checksum_interrupted
            else -> R.string.cal_checksum_not_verified
        }
        return getApplication<Application>().getString(resource)
    }

    private fun localizedInspectionWarnings(inspection: CalibrationFileInspection): List<String> {
        val context = getApplication<Application>()
        if (!inspection.valid && inspection.format in setOf(CalibrationFileFormat.INTEL_HEX, CalibrationFileFormat.MOTOROLA_S_RECORD)) {
            return listOf(context.getString(R.string.cal_warning_invalid_file))
        }
        return when (inspection.format) {
            CalibrationFileFormat.RAW_BINARY -> listOf(context.getString(R.string.cal_warning_binary_integrity))
            CalibrationFileFormat.UNKNOWN -> listOf(context.getString(R.string.cal_warning_unknown_format))
            CalibrationFileFormat.ZIP_ARCHIVE -> {
                if (!inspection.valid) {
                    val warning = inspection.warnings.firstOrNull().orEmpty()
                    listOf(context.getString(
                        if (warning.contains("limite", ignoreCase = true)) R.string.cal_warning_archive_limit
                        else R.string.cal_warning_original_file_preserved
                    ))
                } else {
                    buildList {
                        add(context.getString(R.string.cal_warning_archive_preserved))
                        inspection.warnings.firstOrNull { it.startsWith("Fichiers détectés :") }?.let {
                            add(context.getString(R.string.cal_warning_archive_files, it.substringAfter(':').trim()))
                        }
                    }
                }
            }
            CalibrationFileFormat.EXTERNAL_TUNE_PROJECT -> listOf(context.getString(R.string.cal_warning_external_project))
            CalibrationFileFormat.EXTERNAL_DEFINITION -> listOf(context.getString(R.string.cal_warning_external_definition))
            CalibrationFileFormat.INTEL_HEX -> if (inspection.warnings.isNotEmpty()) listOf(context.getString(R.string.cal_warning_address_span_large)) else emptyList()
            CalibrationFileFormat.MOTOROLA_S_RECORD -> emptyList()
        }
    }

    fun importCalibrationFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment ?: "calibration.bin"
                val bytes = resolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 32 * 1024 * 1024) { "Fichier trop volumineux (limite locale : 32 Mio)." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("Impossible d'ouvrir le fichier sélectionné.")
                require(bytes.isNotEmpty()) { "Le fichier sélectionné est vide." }
                val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                val inspection = CalibrationFileInspector.inspect(displayName, bytes)
                val storageDir = File(getApplication<Application>().filesDir, "calibrations")
                check(storageDir.exists() || storageDir.mkdirs()) { "Impossible de créer le dossier local des cartographies." }
                val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "calibration.bin" }
                val storedFile = File(storageDir, "${System.currentTimeMillis()}_${safeName}")
                storedFile.writeBytes(bytes)
                val preview = bytes.take(64).joinToString(" ") { "%02X".format(it) }
                val previousState = _calibrationWorkspace.value
                previousState.importedFilePath?.let { previousPath ->
                    if (previousPath != storedFile.absolutePath) runCatching { File(previousPath).delete() }
                }
                previousState.activeFilePath?.takeIf { it != previousState.importedFilePath }?.let { activePath ->
                    if (activePath != storedFile.absolutePath) runCatching { File(activePath).delete() }
                }
                _calibrationWorkspace.value.comparisonFilePath?.let { comparisonPath ->
                    runCatching { File(comparisonPath).delete() }
                }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    importedFileName = displayName,
                    importedFileSize = bytes.size.toLong(),
                    importedFileSha256 = sha256,
                    importedFilePreview = preview,
                    importedFilePath = storedFile.absolutePath,
                    activeFilePath = storedFile.absolutePath,
                    mapDefinitions = emptyList(),
                    selectedMapId = null,
                    mapValues = emptyList(),
                    mapXAxisValues = emptyList(),
                    mapYAxisValues = emptyList(),
                    mapDefinitionsWarnings = emptyList(),
                    mapDirty = false,
                    mapError = null,
                    inspectionFormat = inspection.format.name,
                    inspectionSummary = localizedInspectionSummary(inspection, displayName),
                    inspectionPayloadBytes = inspection.payloadBytes,
                    inspectionRecordCount = inspection.recordCount,
                    inspectionChecksumStatus = localizedInspectionChecksum(inspection),
                    inspectionAddressRanges = inspection.addressRanges.map { getApplication<Application>().getString(R.string.cal_address_range, it.start.toString(16).uppercase(), it.endInclusive.toString(16).uppercase(), it.length) },
                    inspectionWarnings = localizedInspectionWarnings(inspection),
                    comparisonFileName = null,
                    comparisonFilePath = null,
                    comparisonFileSize = null,
                    comparisonSummary = null,
                    comparisonChangedBytes = null,
                    comparisonAppendedBytes = null,
                    comparisonMissingBytes = null,
                    comparisonRanges = emptyList(),
                    comparisonIdentical = null,
                    message = getApplication<Application>().getString(R.string.msg_cal_file_imported)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    message = getApplication<Application>().getString(R.string.msg_cal_file_import_failed)
                )
            }
        }
    }

    /** Imports a second raw BIN image and compares it byte-for-byte with the loaded reference. */
    fun importComparisonCalibrationFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            var storedFile: File? = null
            try {
                val current = _calibrationWorkspace.value
                require(current.importedFilePath != null && current.inspectionFormat == "RAW_BINARY") {
                    "Charge d'abord une image de référence BIN brute."
                }
                val resolver = getApplication<Application>().contentResolver
                val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: uri.lastPathSegment ?: "modified.bin"
                val bytes = resolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 32 * 1024 * 1024) { "Fichier trop volumineux (limite locale : 32 Mio)." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("Impossible d'ouvrir le second fichier.")
                require(bytes.isNotEmpty()) { "Le fichier sélectionné est vide." }
                val inspection = CalibrationFileInspector.inspect(displayName, bytes)
                require(inspection.format.name == "RAW_BINARY") {
                    "La comparaison octet par octet nécessite un second fichier BIN brut."
                }
                val reference = File(current.activeFilePath ?: current.importedFilePath!!)
                check(reference.isFile && reference.exists()) { "Le fichier BIN de référence est introuvable." }
                val referenceBytes = reference.readBytes()
                val comparison = CalibrationBinaryComparer.compare(referenceBytes, bytes)
                val storageDir = File(getApplication<Application>().filesDir, "calibrations")
                check(storageDir.exists() || storageDir.mkdirs()) { "Impossible de créer le dossier local des cartographies." }
                val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "modified.bin" }
                storedFile = File(storageDir, "comparison_${System.currentTimeMillis()}_${safeName}")
                storedFile!!.writeBytes(bytes)
                val summary = when {
                    comparison.identical -> getApplication<Application>().getString(R.string.cal_comparison_identical)
                    comparison.referenceSize != comparison.modifiedSize -> getApplication<Application>().getString(
                        R.string.cal_comparison_size_diff, comparison.comparedBytes
                    )
                    else -> getApplication<Application>().getString(
                        R.string.cal_comparison_different_ranges,
                        comparison.changedBytes,
                        comparison.changedRanges.size + comparison.omittedRangeCount
                    )
                }
                val ranges = comparison.changedRanges.take(25).map {
                    getApplication<Application>().getString(
                        R.string.cal_comparison_range,
                        it.startOffset.toString(16).uppercase().padStart(8, '0'),
                        it.endOffsetInclusive.toString(16).uppercase().padStart(8, '0'),
                        it.changedBytes
                    )
                } + if (comparison.omittedRangeCount > 0) listOf(
                    getApplication<Application>().getString(R.string.cal_comparison_more_ranges, comparison.omittedRangeCount)
                ) else emptyList()
                _calibrationWorkspace.value = current.copy(
                    comparisonFileName = displayName,
                    comparisonFilePath = storedFile!!.absolutePath,
                    comparisonFileSize = bytes.size.toLong(),
                    comparisonSummary = summary,
                    comparisonChangedBytes = comparison.changedBytes,
                    comparisonAppendedBytes = comparison.appendedBytes,
                    comparisonMissingBytes = comparison.missingBytes,
                    comparisonRanges = ranges,
                    comparisonIdentical = comparison.identical,
                    message = getApplication<Application>().getString(R.string.msg_cal_comparison_complete)
                )
                current.comparisonFilePath?.let { previousPath ->
                    if (previousPath != storedFile!!.absolutePath) runCatching { File(previousPath).delete() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                storedFile?.let { runCatching { it.delete() } }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    message = getApplication<Application>().getString(R.string.msg_cal_comparison_failed)
                )
            }
        }
    }

    fun exportCalibrationDefinitionTemplate(destinationUri: Uri) {
        viewModelScope.launch {
            try {
                val output = getApplication<Application>().contentResolver.openOutputStream(destinationUri, "w")
                    ?: error("Impossible de créer le fichier modèle.")
                output.use { it.write(CalibrationTableCodec.template.toByteArray(Charsets.UTF_8)) }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    message = getApplication<Application>().getString(R.string.msg_cal_template_exported)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    mapError = getApplication<Application>().getString(R.string.msg_cal_template_export_failed)
                )
            }
        }
    }

    /** Imports a semicolon-separated table-definition file supplied for the exact calibration image. */
    fun importCalibrationDefinitions(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = _calibrationWorkspace.value
                require(current.importedFilePath != null && current.inspectionFormat == "RAW_BINARY") {
                    "Importe d'abord une image BIN brute."
                }
                val resolver = getApplication<Application>().contentResolver
                val text = resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val result = StringBuilder()
                    val buffer = CharArray(4096)
                    var total = 0
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 1024 * 1024) { "Le fichier de définitions dépasse 1 Mio." }
                        result.append(buffer, 0, count)
                    }
                    result.toString()
                } ?: error("Impossible d'ouvrir le fichier de définitions.")
                val parsed = CalibrationTableCodec.parseDefinitions(text)
                val metadata = parsed.metadata
                    ?: error("Métadonnées obligatoires absentes : VIN, CAL ID, CVN, OS, matériel et SHA-256 source requis.")
                val workingFile = File(current.activeFilePath ?: current.importedFilePath!!)
                check(workingFile.isFile && workingFile.exists()) { "L'image BIN active est introuvable." }
                val binary = workingFile.readBytes()
                val validationIssues = CalibrationTableCodec.validateMetadataForImage(
                    metadata = metadata,
                    selectedVin = _vehicle.value.vin,
                    ecuVin = current.ecuVinResponse,
                    binary = binary,
                    ecuCalibrationIds = current.calibrationIdResponse.lineSequence().toList(),
                    ecuCvns = current.cvnResponse.lineSequence().toList()
                )
                require(validationIssues.isEmpty()) {
                    "Définitions refusées : " + validationIssues.joinToString(" ")
                }
                val first = parsed.definitions.first()
                val decoded = CalibrationTableCodec.decode(binary, first)
                _calibrationWorkspace.value = current.copy(
                    mapDefinitions = parsed.definitions,
                    selectedMapId = first.id,
                    mapValues = decoded.values.map { row -> row.map(CalibrationTableCodec::formatEngineeringValue) },
                    mapXAxisValues = decoded.xAxisValues.map(CalibrationTableCodec::formatEngineeringValue),
                    mapYAxisValues = decoded.yAxisValues.map(CalibrationTableCodec::formatEngineeringValue),
                    mapDefinitionsWarnings = parsed.warnings.map { warning ->
                        when {
                            warning.startsWith("Métadonnées de calibration absentes") ->
                                getApplication<Application>().getString(R.string.cal_warning_metadata_missing)
                            warning.startsWith("OS et référence matérielle saisis") ->
                                getApplication<Application>().getString(R.string.cal_warning_os_hardware_metadata)
                            warning.startsWith("Des zones de tables ou d'axes") ->
                                getApplication<Application>().getString(R.string.cal_warning_overlapping_regions)
                            else -> getApplication<Application>().getString(R.string.cal_warning_import_review)
                        }
                    } + getApplication<Application>().getString(R.string.cal_warning_os_hardware_not_read),
                    mapDirty = false,
                    mapError = null,
                    message = getApplication<Application>().getString(R.string.msg_cal_definitions_loaded, parsed.definitions.size)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    mapError = getApplication<Application>().getString(R.string.msg_cal_definitions_import_failed),
                    message = getApplication<Application>().getString(R.string.msg_cal_definitions_import_failed)
                )
            }
        }
    }

    fun selectCalibrationMap(mapId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _calibrationWorkspace.value
            try {
            val definition = current.mapDefinitions.firstOrNull { it.id == mapId }
                ?: error("Définition de table introuvable.")
            val path = current.activeFilePath ?: current.importedFilePath
                ?: error("Aucun fichier BIN chargé.")
            val file = File(path)
            check(file.isFile && file.exists()) { "Le fichier BIN actif est introuvable." }
            val decoded = CalibrationTableCodec.decode(file.readBytes(), definition)
            _calibrationWorkspace.value = current.copy(
                selectedMapId = mapId,
                mapValues = decoded.values.map { row -> row.map(CalibrationTableCodec::formatEngineeringValue) },
                mapXAxisValues = decoded.xAxisValues.map(CalibrationTableCodec::formatEngineeringValue),
                mapYAxisValues = decoded.yAxisValues.map(CalibrationTableCodec::formatEngineeringValue),
                mapDirty = false,
                mapError = null
            )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _calibrationWorkspace.value = current.copy(
                    mapError = getApplication<Application>().getString(R.string.msg_cal_map_read_failed)
                )
            }
        }
    }

    fun updateCalibrationCell(row: Int, column: Int, value: String) {
        val current = _calibrationWorkspace.value
        if (row !in current.mapValues.indices || column !in current.mapValues[row].indices) return
        val updated = current.mapValues.mapIndexed { rowIndex, cells ->
            if (rowIndex != row) cells else cells.mapIndexed { columnIndex, old ->
                if (columnIndex == column) value else old
            }
        }
        _calibrationWorkspace.value = current.copy(mapValues = updated, mapDirty = true, mapError = null)
    }

    fun discardCalibrationTableChanges() {
        _calibrationWorkspace.value.selectedMapId?.let(::selectCalibrationMap)
    }

    /** Saves edited cells to a new local BIN image. No ECU programming command is sent. */
    fun applyCalibrationTableEdits() {
        viewModelScope.launch(Dispatchers.IO) {
            var createdFile: File? = null
            try {
                val current = _calibrationWorkspace.value
                val mapId = current.selectedMapId ?: error("Aucune table sélectionnée.")
                val definition = current.mapDefinitions.firstOrNull { it.id == mapId }
                    ?: error("Définition de table introuvable.")
                val activePath = current.activeFilePath ?: current.importedFilePath
                    ?: error("Aucun fichier BIN chargé.")
                val source = File(activePath)
                check(source.isFile && source.exists()) { "Fichier BIN actif introuvable." }
                val editedValues = current.mapValues.mapIndexed { row, cells ->
                    cells.mapIndexed { column, value ->
                        value.replace(',', '.').toDoubleOrNull()
                            ?: error("Valeur non numérique à la cellule ligne ${row + 1}, colonne ${column + 1}.")
                    }
                }
                val modifiedBytes = CalibrationTableCodec.applyEdits(source.readBytes(), definition, editedValues)
                val directory = File(getApplication<Application>().filesDir, "calibrations")
                check(directory.exists() || directory.mkdirs()) { "Impossible de créer le dossier de cartographies." }
                val name = current.importedFileName.orEmpty().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "calibration.bin" }
                createdFile = File(directory, "edited_${System.currentTimeMillis()}_${name}")
                createdFile!!.writeBytes(modifiedBytes)
                val hash = MessageDigest.getInstance("SHA-256").digest(modifiedBytes)
                    .joinToString("") { "%02x".format(it) }
                val inspection = CalibrationFileInspector.inspect(current.importedFileName ?: "calibration.bin", modifiedBytes)
                val decoded = CalibrationTableCodec.decode(modifiedBytes, definition)
                if (activePath != current.importedFilePath) runCatching { source.delete() }
                _calibrationWorkspace.value = current.copy(
                    activeFilePath = createdFile!!.absolutePath,
                    importedFileSize = modifiedBytes.size.toLong(),
                    importedFileSha256 = hash,
                    importedFilePreview = modifiedBytes.take(64).joinToString(" ") { "%02X".format(it) },
                    inspectionSummary = localizedInspectionSummary(inspection, current.importedFileName ?: "calibration.bin"),
                    inspectionPayloadBytes = inspection.payloadBytes,
                    inspectionRecordCount = inspection.recordCount,
                    inspectionChecksumStatus = localizedInspectionChecksum(inspection),
                    inspectionAddressRanges = inspection.addressRanges.map {
                        getApplication<Application>().getString(R.string.cal_address_range, it.start.toString(16).uppercase(), it.endInclusive.toString(16).uppercase(), it.length)
                    },
                    inspectionWarnings = localizedInspectionWarnings(inspection),
                    mapValues = decoded.values.map { row -> row.map(CalibrationTableCodec::formatEngineeringValue) },
                    mapXAxisValues = decoded.xAxisValues.map(CalibrationTableCodec::formatEngineeringValue),
                    mapYAxisValues = decoded.yAxisValues.map(CalibrationTableCodec::formatEngineeringValue),
                    mapDirty = false,
                    mapError = null,
                    message = getApplication<Application>().getString(R.string.msg_cal_table_saved)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                createdFile?.let { runCatching { it.delete() } }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    mapError = getApplication<Application>().getString(R.string.msg_cal_table_edit_failed),
                    message = getApplication<Application>().getString(R.string.msg_cal_table_edit_failed)
                )
            }
        }
    }

    fun clearCalibrationFile() {
        _calibrationWorkspace.value.importedFilePath?.let { path -> runCatching { File(path).delete() } }
        _calibrationWorkspace.value.activeFilePath?.takeIf { it != _calibrationWorkspace.value.importedFilePath }?.let { path ->
            runCatching { File(path).delete() }
        }
        _calibrationWorkspace.value.comparisonFilePath?.let { path -> runCatching { File(path).delete() } }
        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
            importedFileName = null,
            importedFileSize = null,
            importedFileSha256 = null,
            importedFilePreview = null,
            importedFilePath = null,
            activeFilePath = null,
            mapDefinitions = emptyList(),
            selectedMapId = null,
            mapValues = emptyList(),
            mapXAxisValues = emptyList(),
            mapYAxisValues = emptyList(),
            mapDefinitionsWarnings = emptyList(),
            mapDirty = false,
            mapError = null,
            inspectionFormat = null,
            inspectionSummary = null,
            inspectionPayloadBytes = null,
            inspectionRecordCount = null,
            inspectionChecksumStatus = null,
            inspectionAddressRanges = emptyList(),
            inspectionWarnings = emptyList(),
            comparisonFileName = null,
            comparisonFilePath = null,
            comparisonFileSize = null,
            comparisonSummary = null,
            comparisonChangedBytes = null,
            comparisonAppendedBytes = null,
            comparisonMissingBytes = null,
            comparisonRanges = emptyList(),
            comparisonIdentical = null,
            message = ""
        )
    }

    /** Exports the selected local calibration file through Android's Storage Access Framework. */
    fun exportCalibrationFile(destinationUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sourcePath = _calibrationWorkspace.value.activeFilePath
                    ?: _calibrationWorkspace.value.importedFilePath
                    ?: error("Aucun fichier de calibration n'est chargé.")
                val source = File(sourcePath)
                check(source.isFile && source.exists()) { "Le fichier local de calibration est introuvable." }
                val output = getApplication<Application>().contentResolver.openOutputStream(destinationUri, "w")
                    ?: error("Impossible de créer le fichier de destination.")
                source.inputStream().use { input ->
                    output.use { target -> input.copyTo(target) }
                }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    message = getApplication<Application>().getString(R.string.msg_cal_file_exported)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    message = getApplication<Application>().getString(R.string.msg_cal_file_export_failed)
                )
            }
        }
    }

    private fun markAdapterDisconnected() {
        stopCanMonitor()
        _liveCanMonitorState.value = LiveCanMonitorState()
        _adapter.value = AdapterCapabilities()
        _modules.value = CamaroUdsProtocol.buildTopology(_vehicle.value, _adapter.value)
        clearVehicleEvidence()
        _isObdScanning.value = false
    }

    private fun connectWithResult(
        connect: suspend () -> Result<AdapterCapabilities>,
        fallbackMessage: String,
        transport: String,
        endpoint: String
    ) {
        viewModelScope.launch {
            beginBusyOperation()
            _connectionMessage.value = null
            val activeMonitor = canMonitorJob
            val activeRequest = canRequestJob
            markAdapterDisconnected()
            activeMonitor?.join()
            activeRequest?.join()
            try {
                connect()
                    .onSuccess { capabilities ->
                        _adapter.value = capabilities
                        rememberConnectionProfile(transport, capabilities, endpoint)
                        refreshTopologyAndDefaults(runInitialScans = true)
                    }
                    .onFailure { error ->
                        _connectionMessage.value = fallbackMessage
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _connectionMessage.value = fallbackMessage
            } finally {
                endBusyOperation()
            }
        }
    }

    fun refreshPairedDevices() {
        viewModelScope.launch {
            try {
                _pairedDevices.value = obdManager.getPairedDevices()
                _usbDevices.value = obdManager.getUsbDevices()
                val nearbyBle = obdManager.scanNearbyBleDevices()
                _pairedDevices.value = (_pairedDevices.value + nearbyBle)
                    .distinctBy { it.second }
                    .sortedByDescending {
                        it.first.contains("OBD", true) ||
                            it.first.contains("ELM", true) ||
                            it.first.contains("vLinker", true)
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _connectionMessage.value = getApplication<Application>().getString(R.string.msg_refresh_adapters_failed)
            }
        }
    }

    fun connectWifi(host: String, port: Int) {
        connectWithResult(
            connect = { obdManager.connectWifi(host.trim(), port) },
            fallbackMessage = getApplication<Application>().getString(R.string.msg_wifi_connection_failed),
            transport = "wifi",
            endpoint = "${host.trim()}:$port"
        )
    }

    fun connectUsb(deviceLabel: String) {
        val id = Regex("#(\\d+)").find(deviceLabel)?.groupValues?.get(1)?.toIntOrNull()
        if (id == null) {
            _connectionMessage.value = getApplication<Application>().getString(R.string.msg_usb_identifier_unavailable)
            return
        }
        val baudRate = if (deviceLabel.contains("OBDLink", true)) 115200 else 38400
        connectWithResult(
            connect = { obdManager.connectUsb(id, baudRate) },
            fallbackMessage = getApplication<Application>().getString(R.string.msg_usb_connection_failed),
            transport = "usb",
            endpoint = deviceLabel
        )
    }

    fun selectSimulatedAdapter(preset: String) {
        viewModelScope.launch {
            beginBusyOperation()
            val activeMonitor = canMonitorJob
            val activeRequest = canRequestJob
            markAdapterDisconnected()
            activeMonitor?.join()
            activeRequest?.join()
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                ecuVinResponse = "",
                calibrationIdResponse = "",
                cvnResponse = ""
            )
            try {
                val caps = obdManager.activateSimulatedAdapter(preset)
                _adapter.value = caps
                _connectionMessage.value = null
                refreshTopologyAndDefaults()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _connectionMessage.value = getApplication<Application>().getString(R.string.msg_simulation_activation_failed)
            } finally {
                endBusyOperation()
            }
        }
    }

    fun connectRealBluetooth(macAddress: String) {
        val selectedName = _pairedDevices.value.firstOrNull { it.second == macAddress }?.first.orEmpty()
        if (selectedName.contains("OBDLink CX", ignoreCase = true)) {
            _connectionMessage.value = getApplication<Application>().getString(R.string.msg_obdlink_cx_not_gm_compatible)
            return
        }
        connectWithResult(
            connect = { obdManager.connectAndProbe(macAddress) },
            fallbackMessage = getApplication<Application>().getString(R.string.msg_bluetooth_connection_failed),
            transport = "bluetooth",
            endpoint = "$selectedName ($macAddress)"
        )
    }

    fun toggleLiveStream() {
        _isStreamingLive.value = !_isStreamingLive.value
    }

    /** Records actual live OBD samples; demo values are deliberately excluded. */
    fun toggleLiveRecording() {
        if (_liveRecordingState.value.isRecording) {
            stopLiveRecording()
            return
        }
        if (!obdManager.isObdCommandChannelAvailable) {
            _liveRecordingState.value = _liveRecordingState.value.copy(
                message = getApplication<Application>().getString(if (obdManager.isJ2534BridgeConnected()) R.string.msg_live_recording_j2534_block else R.string.msg_live_recording_unavailable)
            )
            return
        }
        recordedLiveSamples.clear()
        _isStreamingLive.value = true
        val now = System.currentTimeMillis()
        _liveRecordingState.value = LiveRecordingState(
            isRecording = true,
            sampleCount = 0,
            startedAtMillis = now,
            message = getApplication<Application>().getString(R.string.msg_live_recording_real_started)
        )
    }

    fun stopLiveRecording() {
        if (!_liveRecordingState.value.isRecording) return
        _liveRecordingState.value = _liveRecordingState.value.copy(
            isRecording = false,
            stoppedAtMillis = System.currentTimeMillis(),
            message = getApplication<Application>().getString(R.string.msg_live_recording_stopped_count, recordedLiveSamples.size)
        )
    }

    fun exportLiveRecording(destinationUri: Uri) {
        viewModelScope.launch {
            try {
                if (_liveRecordingState.value.isRecording) stopLiveRecording()
                val snapshot = recordedLiveSamples.toList()
                check(snapshot.isNotEmpty()) { "Aucune donnée enregistrée à exporter." }
                val v = _vehicle.value
                val csv = LiveTelemetryCsv.encode(
                    samples = snapshot,
                    vin = v.vin,
                    vehicleLabel = "${v.modelYear} Camaro ${v.engine.rpo} • ${v.generation.label} • ${v.transmission.name}"
                )
                withContext(Dispatchers.IO) {
                    val output = getApplication<Application>().contentResolver.openOutputStream(destinationUri, "w")
                        ?: error("Impossible de créer le fichier CSV.")
                    output.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                }
                _liveRecordingState.value = _liveRecordingState.value.copy(
                    message = getApplication<Application>().getString(R.string.msg_live_csv_exported, snapshot.size)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _liveRecordingState.value = _liveRecordingState.value.copy(
                    message = getApplication<Application>().getString(R.string.msg_live_csv_export_failed)
                )
            }
        }
    }

    private fun captureLiveTelemetrySample(values: List<LivePidValue>) {
        if (!_liveRecordingState.value.isRecording) return
        if (!obdManager.isObdCommandChannelAvailable) {
            stopLiveRecording()
            _liveRecordingState.value = _liveRecordingState.value.copy(
                message = getApplication<Application>().getString(R.string.msg_live_recording_channel_lost)
            )
            return
        }
        if (values.isEmpty()) return
        if (recordedLiveSamples.size >= maxLiveRecordingSamples) {
            _liveRecordingState.value = _liveRecordingState.value.copy(
                isRecording = false,
                stoppedAtMillis = System.currentTimeMillis(),
                sampleCount = recordedLiveSamples.size,
                message = getApplication<Application>().getString(R.string.msg_live_recording_max_samples, maxLiveRecordingSamples)
            )
            return
        }
        val snapshot = values.map { pid ->
            RecordedLivePid(
                id = pid.id,
                label = pid.labelEn,
                value = pid.metricValue,
                unit = pid.metricUnit
            )
        }
        recordedLiveSamples += RecordedLiveSample(System.currentTimeMillis(), snapshot)
        _liveRecordingState.value = _liveRecordingState.value.copy(
            sampleCount = recordedLiveSamples.size,
            message = getApplication<Application>().getString(R.string.msg_live_recording_started, recordedLiveSamples.size)
        )
    }

    fun scanDtcs() {
        viewModelScope.launch {
            beginBusyOperation()
            try {
                if (obdManager.isSimulationActive) {
                    val (confirmed, pending, permanent) =
                        obdManager.createSimulatedObd2FeatureEngine().readDtcs()
                    _dtcs.value = mapObdDtcs(confirmed + pending + permanent)
                    _connectionMessage.value = getApplication<Application>().getString(R.string.msg_dtc_simulated_lfx)
                } else if (!obdManager.isObdCommandChannelAvailable) {
                    // No SAE OBD channel is available; keep independently read UDS ECU records.
                    _dtcs.value = _dtcs.value.filterNot { it.module.equals("ECM", ignoreCase = true) }
                    _connectionMessage.value = if (obdManager.isJ2534BridgeConnected())
                        getApplication<Application>().getString(R.string.msg_dtc_j2534_unavailable)
                    else getApplication<Application>().getString(R.string.msg_dtc_adapter_required)
                } else {
                    val (confirmed, pending, permanent) = obdManager.withObdCommandSession { engine ->
                        engine.readDtcs()
                    }
                    _dtcs.value = _dtcs.value.filterNot { it.module.equals("ECM", ignoreCase = true) } +
                        mapObdDtcs(confirmed + pending + permanent)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _connectionMessage.value = getApplication<Application>().getString(R.string.msg_dtc_scan_failed)
            } finally {
                endBusyOperation()
            }
        }
    }

    /** Converts standard OBD DTC records once for both the quick and full scan screens. */
    private fun mapObdDtcs(records: List<ObdDtc>): List<DiagnosticDtc> =
        records.distinctBy { it.code to it.status }.map { dtc ->
            DiagnosticDtc(
                code = dtc.code,
                module = "ECM",
                statusEn = when (dtc.status) {
                    "pending" -> "Pending"
                    "permanent" -> "Permanent"
                    else -> "Confirmed"
                },
                statusFr = when (dtc.status) {
                    "pending" -> "En attente"
                    "permanent" -> "Permanent"
                    else -> "Confirmé"
                },
                descEn = standardDtcDescription(dtc.code, false),
                descFr = standardDtcDescription(dtc.code, true),
                isPending = dtc.status == "pending"
            )
        }

    private fun standardDtcDescription(code: String, isFr: Boolean): String {
        val entry = DtcCatalogRegistry.genericDescription(code)
            ?: return if (isFr) {
                "Description normalisée indisponible ; vérifier la documentation GM correspondant au VIN et au calculateur."
            } else {
                "Generic description unavailable; check GM service information for this VIN and reporting ECU."
            }
        return if (isFr) entry.second else entry.first
    }

        fun clearDtcs() {
        viewModelScope.launch {
            beginBusyOperation()
            try {
                if (obdManager.isSimulationActive) {
                    _dtcs.value = emptyList()
                    _connectionMessage.value = getApplication<Application>().getString(R.string.msg_dtc_demo_cleared)
                } else if (!obdManager.isObdCommandChannelAvailable) {
                    _connectionMessage.value = if (obdManager.isJ2534BridgeConnected())
                        getApplication<Application>().getString(R.string.msg_dtc_clear_j2534_unavailable)
                    else getApplication<Application>().getString(R.string.msg_dtc_clear_adapter_required)
                } else {
                    val response = obdManager.sendAtCommand("04", 2500L)
                    if (Regex("\\b44\\b").containsMatchIn(response.uppercase())) {
                        // SAE Mode 04 clears standardized emissions DTCs only.
                        // Preserve enhanced UDS records from other ECUs: no GM UDS clear is issued here.
                        _dtcs.value = _dtcs.value.filterNot { it.module.equals("ECM", ignoreCase = true) }
                        _connectionMessage.value = null
                    } else {
                        _connectionMessage.value = getApplication<Application>().getString(R.string.msg_dtc_clear_not_confirmed, response)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _connectionMessage.value = getApplication<Application>().getString(R.string.msg_dtc_clear_failed)
            } finally {
                endBusyOperation()
            }
        }
    }

    fun runMileageAudit() {
        viewModelScope.launch {
            beginBusyOperation()
            _mileageReport.value = null
            val readings = mutableListOf<MileageSourceReading>()
            try {
                if (!obdManager.isObdCommandChannelAvailable) {
                    if (obdManager.isJ2534BridgeConnected()) {
                        readings += MileageSourceReading(
                            "J2534", "J2534 bridge (UDS only)", "Pont J2534 (UDS uniquement)",
                            "Mode 01 OBD-II unavailable on this transport", null, ConfidenceLevel.UNAVAILABLE,
                            "The connected PC bridge exposes UDS payload transport, not an ELM/SAE J1979 command channel. No OBD odometer PID was queried.",
                            "Le pont PC connecté expose le transport UDS, pas un canal ELM/SAE J1979. Aucun PID kilométrique OBD n'a été interrogé."
                        )
                    } else {
                        readings += MileageSourceReading(
                            "DEMO", "Demo data only", "Données de démonstration uniquement",
                            "No vehicle ECU queried", null, ConfidenceLevel.UNAVAILABLE,
                            "Demo mode never represents the actual vehicle mileage.",
                            "Le mode démo ne représente jamais le kilométrage réel du véhicule."
                        )
                    }
                } else {
                    val (a6Response, d3Response) = obdManager.withObdCommandSession { engine ->
                        val supportedPids = engine.discoverSupportedPids()
                        engine.readMode01PidIfSupported(0xA6, supportedPids) to
                            engine.readMode01PidIfSupported(0xD3, supportedPids)
                    }
                    val a6Km = a6Response?.let { CamaroUdsProtocol.parseOdometerPidA6(it) }
                    readings += MileageSourceReading(
                        "ECM_A6", "OBD odometer (PID A6)", "Odomètre OBD (PID A6)",
                        "Mode 01 PID A6 • ${a6Response ?: "not advertised by ECU"}", a6Km,
                        if (a6Km != null) ConfidenceLevel.MODERATE else ConfidenceLevel.UNAVAILABLE,
                        if (a6Km != null) "ECU-reported OBD odometer value. It is one ECU datum, not an independent cross-module check." else "PID A6 was not advertised by the ECU or returned no valid response.",
                        if (a6Km != null) "Valeur odométrique déclarée par l'ECU. Il s'agit d'une donnée d'un calculateur, pas d'une comparaison intermodules indépendante." else "PID A6 non annoncé par l'ECU ou réponse invalide."
                    )
                    val d3Km = d3Response?.let { CamaroUdsProtocol.parseOdometerPidD3(it) }
                    readings += MileageSourceReading(
                        "ECM_D3", "Engine odometer (PID D3)", "Odomètre moteur (PID D3)",
                        "Mode 01 PID D3 • ${d3Response ?: "not advertised by ECU"}", d3Km,
                        if (d3Km != null) ConfidenceLevel.MODERATE else ConfidenceLevel.UNAVAILABLE,
                        if (d3Km != null) "Engine ECU value only; not an independent BCM/IPC confirmation." else "PID D3 was not advertised by the ECU or returned no valid response.",
                        if (d3Km != null) "Valeur du calculateur moteur uniquement ; ce n'est pas une confirmation indépendante BCM/IPC." else "PID D3 non annoncé par l'ECU ou réponse invalide."
                    )
                    // Explicitly report unavailable module sources instead of implying they were read.
                    readings += MileageSourceReading(
                        "BCM", "Body Control Module", "Calculateur habitacle (BCM)",
                        "Non lu : adressage/DID GM validé requis", null, ConfidenceLevel.UNAVAILABLE,
                        "BCM odometer data was not queried: no verified VIN-specific address/DID profile is installed.",
                        "Donnée BCM non interrogée : aucun profil VIN avec adresse/DID vérifié n'est installé."
                    )
                    readings += MileageSourceReading(
                        "IPC", "Instrument Panel Cluster", "Combiné d'instruments (IPC)",
                        "Non lu : adressage/DID GM validé requis", null, ConfidenceLevel.UNAVAILABLE,
                        "IPC odometer data was not queried: no verified VIN-specific address/DID profile is installed.",
                        "Donnée IPC non interrogée : aucun profil VIN avec adresse/DID vérifié n'est installé."
                    )
                }
                _mileageReport.value = MileageAuditEngine.evaluate(_vehicle.value.vin, readings)
                _connectionMessage.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _mileageReport.value = MileageAuditEngine.evaluate(_vehicle.value.vin, readings)
                _connectionMessage.value = getApplication<Application>().getString(R.string.msg_mileage_audit_failed)
            } finally {
                endBusyOperation()
            }
        }
    }

    /** Executes a complete, standards-based read-only OBD-II scan on the connected Camaro. */
    fun runFullObdScan() {
        viewModelScope.launch {
            if (!obdManager.isSimulationActive && !obdManager.isObdCommandChannelAvailable) {
                _obdScanReport.value = null
                _obdScanMessage.value = if (obdManager.isJ2534BridgeConnected())
                    getApplication<Application>().getString(R.string.msg_full_scan_j2534_only)
                else getApplication<Application>().getString(R.string.msg_full_scan_adapter_required)
                return@launch
            }
            _isObdScanning.value = true
            _obdScanMessage.value = null
            try {
                val report = if (obdManager.isSimulationActive) {
                    obdManager.createSimulatedObd2FeatureEngine().fullReadOnlyScan()
                } else {
                    obdManager.withObdCommandSession { engine ->
                        engine.fullReadOnlyScan()
                    }
                }
                _obdScanReport.value = report
                _modules.value = CamaroUdsProtocol.applyStandardObdDiscovery(
                    topology = _modules.value,
                    respondingAddresses = report.respondingEcuAddresses
                )
                val allDtcs = report.confirmedDtcs + report.pendingDtcs + report.permanentDtcs
                _dtcs.value = if (obdManager.isSimulationActive) {
                    mapObdDtcs(allDtcs)
                } else {
                    _dtcs.value.filterNot { it.module.equals("ECM", ignoreCase = true) } + mapObdDtcs(allDtcs)
                }
                _obdScanMessage.value = if (obdManager.isSimulationActive) {
                    getApplication<Application>().getString(R.string.msg_full_scan_simulation_complete, report.supportedPids.size, allDtcs.size)
                } else {
                    getApplication<Application>().getString(R.string.msg_full_scan_complete, report.supportedPids.size, allDtcs.size)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _obdScanMessage.value = getApplication<Application>().getString(R.string.msg_full_scan_failed)
            } finally {
                _isObdScanning.value = false
            }
        }
    }

    fun saveCurrentSession() {
        viewModelScope.launch {
            try {
                val v = _vehicle.value
                val report = _mileageReport.value
                val entity = SavedSessionEntity(
                    timestamp = System.currentTimeMillis(),
                    vehicleLabel = "${v.modelYear} Camaro ${v.engine.rpo} (${v.market.code})",
                    vin = v.vin,
                    adapterName = _adapter.value.deviceName,
                    dtcSummary = if (_dtcs.value.isEmpty()) "0 DTC" else _dtcs.value.joinToString(", ") { it.code },
                    mileageSummary = report?.let {
                        localizedMileageSummaryForStorage(it)
                    } ?: getApplication<Application>().getString(R.string.mileage_audit_not_run),
                    hasMileageDiscrepancy = report?.hasDiscrepancy ?: false,
                    maxDeltaKm = report?.maxDeltaKm ?: 0.0
                )
                sessionDao.insertSession(entity)
                _connectionMessage.value = getApplication<Application>().getString(R.string.msg_session_saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _connectionMessage.value = getApplication<Application>().getString(R.string.msg_session_save_failed)
            }
        }
    }

    fun deleteSavedSession(session: SavedSessionEntity) {
        viewModelScope.launch {
            try {
                sessionDao.deleteSession(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _connectionMessage.value = getApplication<Application>().getString(R.string.msg_session_delete_failed)
            }
        }
    }

    private fun refreshTopologyAndDefaults(runInitialScans: Boolean = false) {
        _modules.value = CamaroUdsProtocol.buildTopology(_vehicle.value, _adapter.value)
        if (runInitialScans && obdManager.isObdCommandChannelAvailable) {
            // Read the ECU VIN immediately after a successful OBD connection so that
            // the selected vehicle profile can be checked before the user starts diagnosis.
            // Keep this request ahead of the initial scans to avoid competing for the ELM channel.
            viewModelScope.launch {
                try {
                    val identity = obdManager.withObdCommandSession { engine -> engine.readEcuIdentification() }
                    val comparison = VehicleVinMatcher.compare(_vehicle.value.vin, identity.vin)
                    _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                        ecuVinResponse = identity.vin ?: getApplication<Application>().getString(R.string.obd_not_available),
                        vinComparison = comparison,
                        vinModelYear = VehicleVinMatcher.modelYearFromVin(identity.vin),
                        ecuNameResponse = identity.ecuNames.joinToString("\n"),
                        respondingEcuAddresses = identity.respondingEcuAddresses,
                        calibrationIdResponse = identity.calibrationIds.joinToString("\n").ifBlank { getApplication<Application>().getString(R.string.obd_not_available) },
                        cvnResponse = identity.calibrationVerificationNumbers.joinToString("\n").ifBlank { getApplication<Application>().getString(R.string.obd_not_available) }
                    )
                    // Do not automatically interpret or audit data against the wrong saved vehicle.
                    // The user can resolve the mismatch first, then start diagnostics explicitly.
                    if (comparison?.matches != false) {
                        scanDtcs()
                        runMileageAudit()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                        ecuVinResponse = getApplication<Application>().getString(R.string.obd_not_available),
                        vinComparison = null,
                        vinModelYear = null,
                        ecuNameResponse = "",
                        respondingEcuAddresses = emptyList(),
                        calibrationIdResponse = "",
                        cvnResponse = ""
                    )
                    scanDtcs()
                    runMileageAudit()
                }
            }
        }
    }

    private fun startLiveTelemetryLoop() {
        viewModelScope.launch {
            var tick = 0.0
            var consecutiveFailures = 0
            var activeTelemetryBatch = 0
            val histories = mutableMapOf<String, ArrayDeque<Double>>()

            fun push(id: String, value: Double): List<Double> {
                val queue = histories.getOrPut(id) { ArrayDeque() }
                if (queue.size >= 24) queue.removeFirst()
                queue.addLast(value)
                return queue.toList()
            }

            suspend fun read(pid: String): IntArray? {
                val pidValue = pid.toIntOrNull(16)
                    ?: throw IllegalArgumentException("PID OBD invalide : $pid")
                // Spread the supported PID catalogue across four polling batches. This avoids
                // flooding slower ELM327 adapters with every optional sensor on each refresh.
                if (pidValue % 4 != activeTelemetryBatch) return null
                val response = obdManager.readMode01PidIfSupported(pidValue) ?: return null
                if (response.equals("NO_SOCKET", ignoreCase = true) ||
                    response.contains("UNABLE TO CONNECT", ignoreCase = true)) {
                    throw IllegalStateException("La connexion OBD a été interrompue.")
                }
                return CamaroUdsProtocol.parsePidBytes(response, pid)
            }

            while (currentCoroutineContext().isActive) {
                if (_isStreamingLive.value) {
                    activeTelemetryBatch = (activeTelemetryBatch + 1) % 4
                    var valuesForRecording = emptyList<LivePidValue>()
                    try {
                        when {
                            obdManager.isSimulationActive -> {
                                tick += 0.35
                                val rpm = (740.0 + sin(tick) * 45.0 + Random.nextDouble(-12.0, 12.0)).coerceAtLeast(620.0)
                                val coolantC = (91.0 + sin(tick * 0.2) * 2.5).coerceIn(85.0, 104.0)
                                val oilKpa = (245.0 + sin(tick * 0.8) * 18.0).coerceIn(180.0, 380.0)
                                val stft = (1.6 + sin(tick * 0.6) * 2.1).coerceIn(-6.0, 6.0)
                                val voltage = 14.1 + sin(tick * 0.3) * 0.15
                                _livePids.value = listOf(
                                    LivePidValue("RPM", "Engine Speed (DEMO)", "Régime moteur (DÉMO)", rpm, "tr/min", rpm, "RPM", 600.0, 7000.0, push("RPM", rpm)),
                                    LivePidValue("ECT", "Coolant Temp (DEMO)", "Temp. liquide (DÉMO)", coolantC, "°C", coolantC * 1.8 + 32.0, "°F", 40.0, 120.0, push("ECT", coolantC)),
                                    LivePidValue("OIL", "Oil Pressure (DEMO)", "Pression huile (DÉMO)", oilKpa, "kPa", oilKpa * 0.145038, "PSI", 100.0, 500.0, push("OIL", oilKpa)),
                                    LivePidValue("STFT", "Short Term Fuel Trim B1 (DEMO)", "Correction carburant B1 (DÉMO)", stft, "%", stft, "%", -6.0, 6.0, push("STFT", stft)),
                                    LivePidValue("BAT", "Control Module Voltage (DEMO)", "Tension calculateur (DÉMO)", voltage, "V", voltage, "V", 11.5, 15.2, push("BAT", voltage))
                                )
                                valuesForRecording = _livePids.value
                                _liveTelemetryMessage.value = getApplication<Application>().getString(R.string.msg_live_simulation_active)
                            }
                            obdManager.isDemoMode -> {
                                _livePids.value = emptyList()
                                _liveTelemetryMessage.value = getApplication<Application>().getString(R.string.msg_live_no_adapter)
                            }
                            !obdManager.isObdCommandChannelAvailable -> {
                                _livePids.value = emptyList()
                                _liveTelemetryMessage.value = getApplication<Application>().getString(R.string.msg_live_j2534_only)
                            }
                            else -> {
                                val values = mutableListOf<LivePidValue>()
                                read("0C")?.takeIf { it.size >= 2 }?.let { b ->
                                    val v = (b[0] * 256 + b[1]) / 4.0
                                    values += LivePidValue("RPM", "Engine Speed", "Régime moteur", v, "tr/min", v, "RPM", 0.0, 7000.0, push("RPM", v))
                                }
                                read("05")?.firstOrNull()?.let { b ->
                                    val v = (b - 40).toDouble()
                                    values += LivePidValue("ECT", "Coolant Temperature", "Température liquide", v, "°C", v * 1.8 + 32.0, "°F", -40.0, 215.0, push("ECT", v))
                                }
                                read("0D")?.firstOrNull()?.let { b ->
                                    val v = b.toDouble()
                                    values += LivePidValue("SPD", "Vehicle Speed", "Vitesse véhicule", v, "km/h", v / 1.609344, "mph", 0.0, 255.0, push("SPD", v))
                                }
                                read("11")?.firstOrNull()?.let { b ->
                                    val v = b * 100.0 / 255.0
                                    values += LivePidValue("TPS", "Throttle Position", "Position papillon", v, "%", v, "%", 0.0, 100.0, push("TPS", v))
                                }
                                read("04")?.firstOrNull()?.let { b ->
                                    val v = b * 100.0 / 255.0
                                    values += LivePidValue("LOAD", "Calculated Engine Load", "Charge moteur calculée", v, "%", v, "%", 0.0, 100.0, push("LOAD", v))
                                }
                                read("0B")?.firstOrNull()?.let { b ->
                                    val v = b.toDouble()
                                    values += LivePidValue("MAP", "Intake Manifold Pressure", "Pression collecteur admission", v, "kPa", v * 0.145038, "PSI", 0.0, 255.0, push("MAP", v))
                                }
                                read("0F")?.firstOrNull()?.let { b ->
                                    val v = (b - 40).toDouble()
                                    values += LivePidValue("IAT", "Intake Air Temperature", "Température air admission", v, "°C", v * 1.8 + 32.0, "°F", -40.0, 215.0, push("IAT", v))
                                }
                                read("10")?.takeIf { it.size >= 2 }?.let { b ->
                                    val v = (b[0] * 256 + b[1]) / 100.0
                                    values += LivePidValue("MAF", "Mass Air Flow", "Débit massique d'air", v, "g/s", v * 0.002205, "lb/s", 0.0, 655.35, push("MAF", v))
                                }
                                read("06")?.firstOrNull()?.let { b ->
                                    val v = b * 100.0 / 128.0 - 100.0
                                    values += LivePidValue("STFT", "Short Term Fuel Trim B1", "Correction carburant court terme B1", v, "%", v, "%", -100.0, 99.2, push("STFT", v))
                                }
                                read("07")?.firstOrNull()?.let { b ->
                                    val v = b * 100.0 / 128.0 - 100.0
                                    values += LivePidValue("LTFT", "Long Term Fuel Trim B1", "Correction carburant long terme B1", v, "%", v, "%", -100.0, 99.2, push("LTFT", v))
                                }
                                read("08")?.firstOrNull()?.let { b ->
                                    val v = b * 100.0 / 128.0 - 100.0
                                    values += LivePidValue("STFT_B2", "Short Term Fuel Trim B2", "Correction carburant court terme B2", v, "%", v, "%", -100.0, 99.2, push("STFT_B2", v))
                                }
                                read("09")?.firstOrNull()?.let { b ->
                                    val v = b * 100.0 / 128.0 - 100.0
                                    values += LivePidValue("LTFT_B2", "Long Term Fuel Trim B2", "Correction carburant long terme B2", v, "%", v, "%", -100.0, 99.2, push("LTFT_B2", v))
                                }
                                read("0A")?.firstOrNull()?.let { b ->
                                    val v = b * 3.0
                                    values += LivePidValue("FUEL_PRESS", "Fuel Pressure", "Pression carburant", v, "kPa", v * 0.145038, "PSI", 0.0, 765.0, push("FUEL_PRESS", v))
                                }
                                read("0E")?.firstOrNull()?.let { b ->
                                    val v = b / 2.0 - 64.0
                                    values += LivePidValue("TIMING", "Ignition Timing Advance", "Avance à l'allumage", v, "°", v, "°", -64.0, 63.5, push("TIMING", v))
                                }
                                read("14")?.takeIf { it.size >= 2 }?.let { b ->
                                    val v = b[0] / 200.0
                                    values += LivePidValue("O2_B1S1", "Oxygen Sensor B1S1 Voltage", "Sonde lambda B1S1 - tension", v, "V", v, "V", 0.0, 1.275, push("O2_B1S1", v))
                                }
                                read("15")?.takeIf { it.size >= 2 }?.let { b ->
                                    val v = b[0] / 200.0
                                    values += LivePidValue("O2_B1S2", "Oxygen Sensor B1S2 Voltage", "Sonde lambda B1S2 - tension", v, "V", v, "V", 0.0, 1.275, push("O2_B1S2", v))
                                }
                                read("16")?.takeIf { it.size >= 2 }?.let { b ->
                                    val v = b[0] / 200.0
                                    values += LivePidValue("O2_B1S3", "Oxygen Sensor B1S3 Voltage", "Sonde lambda B1S3 - tension", v, "V", v, "V", 0.0, 1.275, push("O2_B1S3", v))
                                }
                                read("17")?.takeIf { it.size >= 2 }?.let { b ->
                                    val v = b[0] / 200.0
                                    values += LivePidValue("O2_B1S4", "Oxygen Sensor B1S4 Voltage", "Sonde lambda B1S4 - tension", v, "V", v, "V", 0.0, 1.275, push("O2_B1S4", v))
                                }
                                read("24")?.takeIf { it.size >= 4 }?.let { b ->
                                    val v = ((b[0] * 256 + b[1]) * 2.0) / 65536.0
                                    values += LivePidValue("WBO2_B1S1", "Wideband Lambda B1S1", "Sonde large bande B1S1 - lambda", v, "λ", v, "λ", 0.0, 2.0, push("WBO2_B1S1", v))
                                }
                                read("25")?.takeIf { it.size >= 4 }?.let { b ->
                                    val v = ((b[0] * 256 + b[1]) * 2.0) / 65536.0
                                    values += LivePidValue("WBO2_B1S2", "Wideband Lambda B1S2", "Sonde large bande B1S2 - lambda", v, "λ", v, "λ", 0.0, 2.0, push("WBO2_B1S2", v))
                                }
                                read("34")?.takeIf { it.size >= 4 }?.let { b ->
                                    val v = ((b[0] * 256 + b[1]) * 2.0) / 65536.0
                                    values += LivePidValue("WBO2_B2S1", "Wideband Lambda B2S1", "Sonde large bande B2S1 - lambda", v, "λ", v, "λ", 0.0, 2.0, push("WBO2_B2S1", v))
                                }
                                read("35")?.takeIf { it.size >= 4 }?.let { b ->
                                    val v = ((b[0] * 256 + b[1]) * 2.0) / 65536.0
                                    values += LivePidValue("WBO2_B2S2", "Wideband Lambda B2S2", "Sonde large bande B2S2 - lambda", v, "λ", v, "λ", 0.0, 2.0, push("WBO2_B2S2", v))
                                }
                                read("43")?.takeIf { it.size >= 2 }?.let { b ->
                                    val v = ((b[0] * 256 + b[1]) * 100.0) / 65535.0
                                    values += LivePidValue("ABS_LOAD", "Absolute Engine Load", "Charge moteur absolue", v, "%", v, "%", 0.0, 100.0, push("ABS_LOAD", v))
                                }
                                read("44")?.takeIf { it.size >= 2 }?.let { b ->
                                    val v = ((b[0] * 256 + b[1]) * 2.0) / 65535.0
                                    values += LivePidValue("EQ_RATIO", "Commanded Equivalence Ratio", "Rapport d'équivalence demandé", v, "λ", v, "λ", 0.0, 2.0, push("EQ_RATIO", v))
                                }
                                read("45")?.firstOrNull()?.let { b ->
                                    val v = b * 100.0 / 255.0
                                    values += LivePidValue("REL_TPS", "Relative Throttle Position", "Position relative du papillon", v, "%", v, "%", 0.0, 100.0, push("REL_TPS", v))
                                }
                                read("46")?.firstOrNull()?.let { b ->
                                    val v = (b - 40).toDouble()
                                    values += LivePidValue("AMBIENT", "Ambient Air Temperature", "Température ambiante", v, "°C", v * 1.8 + 32.0, "°F", -40.0, 215.0, push("AMBIENT", v))
                                }
                                read("47")?.firstOrNull()?.let { b ->
                                    val v = b * 100.0 / 255.0
                                    values += LivePidValue("ABS_TPS_B", "Absolute Throttle Position B", "Position absolue papillon B", v, "%", v, "%", 0.0, 100.0, push("ABS_TPS_B", v))
                                }
                                read("48")?.firstOrNull()?.let { b ->
                                    val v = b * 100.0 / 255.0
                                    values += LivePidValue("ABS_TPS_C", "Absolute Throttle Position C", "Position absolue papillon C", v, "%", v, "%", 0.0, 100.0, push("ABS_TPS_C", v))
                                }
                                read("49")?.firstOrNull()?.let { b ->
                                    val v = b * 100.0 / 255.0
                                    values += LivePidValue("APP_D", "Accelerator Pedal Position D", "Position pédale accélérateur D", v, "%", v, "%", 0.0, 100.0, push("APP_D", v))
                                }
                                read("4A")?.firstOrNull()?.let { b ->
                                    val v = b * 100.0 / 255.0
                                    values += LivePidValue("APP_E", "Accelerator Pedal Position E", "Position pédale accélérateur E", v, "%", v, "%", 0.0, 100.0, push("APP_E", v))
                                }
                                read("4B")?.firstOrNull()?.let { b ->
                                    val v = b * 100.0 / 255.0
                                    values += LivePidValue("APP_F", "Accelerator Pedal Position F", "Position pédale accélérateur F", v, "%", v, "%", 0.0, 100.0, push("APP_F", v))
                                }
                                read("4C")?.firstOrNull()?.let { b ->
                                    val v = b * 100.0 / 255.0
                                    values += LivePidValue("CMD_TPS", "Commanded Throttle Actuator", "Commande actionneur papillon", v, "%", v, "%", 0.0, 100.0, push("CMD_TPS", v))
                                }
                                read("42")?.takeIf { it.size >= 2 }?.let { b ->
                                    val v = (b[0] * 256 + b[1]) / 1000.0
                                    values += LivePidValue("BAT", "Control Module Voltage", "Tension calculateur", v, "V", v, "V", 9.0, 16.0, push("BAT", v))
                                }
                                val mergedValues = _livePids.value.associateBy { it.id }.toMutableMap()
                                values.forEach { mergedValues[it.id] = it }
                                _livePids.value = mergedValues.values.toList()
                                valuesForRecording = values
                                _liveTelemetryMessage.value = if (_livePids.value.isEmpty()) {
                                    getApplication<Application>().getString(R.string.msg_live_no_pid_response)
                                } else {
                                    null
                                }
                            }
                        }
                        captureLiveTelemetrySample(valuesForRecording)
                        consecutiveFailures = 0
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        consecutiveFailures++
                        _liveTelemetryMessage.value = getApplication<Application>().getString(R.string.msg_live_read_failed)
                        if (_liveRecordingState.value.isRecording) {
                            stopLiveRecording()
                            _liveRecordingState.value = _liveRecordingState.value.copy(
                                message = getApplication<Application>().getString(R.string.msg_live_recording_stopped)
                            )
                        }
                    }
                }

                val pauseMs = when {
                    obdManager.isSimulationActive -> 450L
                    consecutiveFailures == 0 -> 900L
                    else -> (900L * (1L shl consecutiveFailures.coerceAtMost(3))).coerceAtMost(5_000L)
                }
                delay(pauseMs)
            }
        }
    }

    override fun onCleared() {
        val activeJobs = listOfNotNull(canMonitorJob, canRequestJob)
        canRequestJob = null
        activeJobs.forEach(Job::cancel)
        stopCanMonitor()
        if (activeJobs.isEmpty()) {
            obdManager.disconnect()
        } else {
            val remainingJobs = java.util.concurrent.atomic.AtomicInteger(activeJobs.size)
            activeJobs.forEach { job ->
                job.invokeOnCompletion {
                    if (remainingJobs.decrementAndGet() == 0) obdManager.disconnect()
                }
            }
        }
        super.onCleared()
    }
}
