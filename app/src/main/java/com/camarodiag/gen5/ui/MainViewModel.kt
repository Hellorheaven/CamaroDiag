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
import com.camarodiag.gen5.data.obd.DoIpDirectRead
import com.camarodiag.gen5.data.obd.DoIpMemoryBackup
import com.camarodiag.gen5.data.obd.DiagnosticRouteSelector
import com.camarodiag.gen5.data.obd.Lfx2012ControllerReferences
import com.google.firebase.auth.FirebaseAuth
import com.camarodiag.gen5.auth.LicenseLevel
import com.camarodiag.gen5.auth.LicenseRolloutConfig
import com.camarodiag.gen5.auth.AccountStorageKeys
import com.camarodiag.gen5.data.obd.CamaroUdsProtocol
import com.camarodiag.gen5.data.obd.CalibrationFileInspector
import com.camarodiag.gen5.data.obd.CalibrationFileFormat
import com.camarodiag.gen5.data.obd.CalibrationFileInspection
import com.camarodiag.gen5.data.obd.CalibrationBinaryComparer
import com.camarodiag.gen5.data.obd.EcuBackupArchive
import com.camarodiag.gen5.data.obd.EcuBackupCoverage
import com.camarodiag.gen5.data.obd.EcuBackupManifest
import com.camarodiag.gen5.data.obd.Lfx2012FuelReadOnly
import com.camarodiag.gen5.data.obd.Lfx2012FuelSessionHistory
import com.camarodiag.gen5.data.obd.Lfx2012FuelHistoryWindow
import com.camarodiag.gen5.data.obd.Lfx2012FuelHistoryCsv
import com.camarodiag.gen5.data.obd.Lfx2012FuelHistoryExportPolicy
import com.camarodiag.gen5.data.obd.EcuExternalDumpPair
import com.camarodiag.gen5.data.obd.EcuBenchExpertPlan
import com.camarodiag.gen5.data.obd.EcuPartialWritePlanner
import com.camarodiag.gen5.data.obd.J2534LiveMemoryBackup
import com.camarodiag.gen5.data.obd.CalibrationTableCodec
import com.camarodiag.gen5.data.obd.ObdbCamaroSignalSet
import com.camarodiag.gen5.data.obd.CamaroSignalCatalog
import com.camarodiag.gen5.data.obd.toDiagnosticDtc
import com.camarodiag.gen5.data.obd.ObdDtc
import com.camarodiag.gen5.data.obd.ObdScanReport
import com.camarodiag.gen5.data.obd.ObdTransportFailurePolicy
import com.camarodiag.gen5.data.obd.ObdTransportDisconnectedException
import com.camarodiag.gen5.data.obd.ObdSupersededSessionException
import com.camarodiag.gen5.data.obd.CanTraceAnalyzer
import com.camarodiag.gen5.data.obd.CanTraceAnalysisReport
import com.camarodiag.gen5.data.obd.SafeObdRequestPolicy
import com.camarodiag.gen5.data.obd.RawCanFrameParser
import com.camarodiag.gen5.data.obd.SafeRawCanTransmitPolicy
import com.camarodiag.gen5.data.obd.CanConsoleRecordParser
import com.camarodiag.gen5.data.obd.CanConsoleCapabilityPolicy
import com.camarodiag.gen5.data.obd.EcuWriteConsentFlow
import com.camarodiag.gen5.data.obd.CanConsoleDiagnosticStatus
import com.camarodiag.gen5.data.obd.CanAdapterRestorationException
import com.camarodiag.gen5.data.obd.CanConsoleDisplayFilter
import com.camarodiag.gen5.data.obd.CanConsoleStreamFramer
import com.camarodiag.gen5.data.obd.CanCaptureArchiveWriteException
import com.camarodiag.gen5.data.obd.CanConsoleRawArchiveWriter
import com.camarodiag.gen5.data.obd.J2534BridgeConnectionForm
import com.camarodiag.gen5.data.obd.AdapterEndpointPreflight
import com.camarodiag.gen5.data.obd.AdapterConnectionFailurePolicy
import com.camarodiag.gen5.data.obd.UsbPermissionResultBus
import com.camarodiag.gen5.data.obd.UsbPermissionReconnectPolicy
import com.camarodiag.gen5.data.obd.CanConsolePhysicalExchangeException
import com.camarodiag.gen5.data.obd.CanConsolePhysicalExchangeCancelled
import com.camarodiag.gen5.data.obd.CanConsoleExportPolicy
import com.camarodiag.gen5.data.obd.ObservedCanId
import com.camarodiag.gen5.data.obd.ParsedCanConsoleRecord
import com.camarodiag.gen5.mileageaudit.MileageAuditEngine
import com.camarodiag.gen5.domain.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random
import java.security.MessageDigest
import kotlin.coroutines.resume
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

data class CanConsoleHistoryEntry(
    val id: Long,
    val direction: String,
    val timestampMillis: Long,
    val content: String,
    val succeeded: Boolean = true
)

/** Completed, VIN-verified DoIP monitoring capture; partial runs are never exported. */
data class DoIpMonitorReport(val vin: String, val gateway: Int, val tester: Int, val ecu: Int,
    val did: Int, val tls: Boolean, val completedAtUtcMillis: Long,
    val samples: List<DoIpMonitorRow>)
data class DoIpMonitorRow(val index: Int, val elapsedMs: Long, val payloadHex: String)

data class LiveCanMonitorState(
    val isCapturing: Boolean = false,
    val isDoIpActive: Boolean = false,
    val bitrate: Int = 500_000,
    val rawText: String = "",
    val report: CanTraceAnalysisReport? = null,
    val isSendingRequest: Boolean = false,
    val consoleHistory: List<CanConsoleHistoryEntry> = emptyList(),
    val observedCanIds: Set<ObservedCanId> = emptySet(),
    val rawArchiveFailed: Boolean = false,
    val captureArchiveRequired: Boolean = false,
    /** Invalidates any CAN TX confirmation opened under an older adapter or vehicle. */
    val consoleContextGeneration: Long = 0L,
    val message: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsPreferences = application.getSharedPreferences("camarodiag_settings", Context.MODE_PRIVATE)
    private val obdManager = BluetoothObdManager(application)
    private var authenticatedAccountUid: String? = FirebaseAuth.getInstance().currentUser?.uid
    @Volatile private var currentLicenseLevel: LicenseLevel = LicenseLevel.FREE

    /**
     * Only CamaroAuthGate supplies this tier, from a signed Firebase token
     * and, for paying customers, a freshly fetched SERVER Firestore document.
     * Device-side checks are defense in depth; the backend enforces billing.
     */
    fun setLicenseLevel(level: LicenseLevel, accountUid: String?) {
        if (accountUid != authenticatedAccountUid) {
            currentLicenseLevel = LicenseLevel.FREE
            return
        }
        currentLicenseLevel = if (!LicenseRolloutConfig.ENABLED &&
            accountUid != null && level != LicenseLevel.OWNER
        ) LicenseLevel.PREMIUM else level
        if (LicenseRolloutConfig.ENABLED && level == LicenseLevel.FREE) {
            invalidateEnhancedUdsReads()
            gmlanIdentityGeneration++
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                isGmlanReading = false, gmlanIdentity = null, gmlanIdentityFailed = false
            )
            if (_liveRecordingState.value.isRecording) stopLiveRecording()
            discardCanMonitor()
            detachCanRequest()
        }
    }

    private fun hasPremiumLicense(): Boolean =
        authenticatedAccountUid != null &&
            FirebaseAuth.getInstance().currentUser?.uid == authenticatedAccountUid &&
            (!LicenseRolloutConfig.ENABLED || currentLicenseLevel != LicenseLevel.FREE)

    private fun requirePremiumLicense(): Boolean {
        if (hasPremiumLicense()) return true
        _connectionMessage.value = localizedContext.getString(R.string.license_required_for_feature)
        return false
    }

    @Volatile private var accountSessionGeneration: Long = 0L
    private fun isCurrentAccountGeneration(started: Long): Boolean =
        started == accountSessionGeneration && authenticatedAccountUid != null

    /** A pending folder-picker action is valid only in the original login session. */
    internal fun exportAccountGeneration(): Long = accountSessionGeneration
    internal fun canResumeExport(started: Long, owner: String): Boolean =
        isCurrentAccountGeneration(started) && authenticatedAccountUid == owner &&
            FirebaseAuth.getInstance().currentUser?.uid == owner

    private val _activeAccountUid = MutableStateFlow(authenticatedAccountUid)
    val activeAccountUid: StateFlow<String?> = _activeAccountUid.asStateFlow()

    /**
     * Keep existing pre-authentication vehicles untouched in the legacy settings.
     * Each authenticated Firebase UID receives a distinct local garage. Old data
     * can only be imported through an explicit user action.
     */
    private fun accountVehiclePreferences(
        ownerUid: String? = authenticatedAccountUid
    ): android.content.SharedPreferences =
        getApplication<Application>().getSharedPreferences(
            AccountStorageKeys.garageName(ownerUid),
            Context.MODE_PRIVATE
        )

    private fun selectedVehiclePreference(): String? =
        accountVehiclePreferences().getString("selected_vehicle_id", null)


    /**
     * A login change must never leave an OBD operation running in the background,
     * including when the Firebase SDK revokes a previously valid session.
     */
    fun onAccountSessionChanged(accountUid: String?) {
        if (authenticatedAccountUid == accountUid) return
        authenticatedAccountUid = accountUid
        currentLicenseLevel = LicenseLevel.FREE
        accountSessionGeneration++
        traceAnalysisJob?.cancel()
        functionalTestGeneration++
        functionalTestJob?.cancel()
        functionalTestJob = null
        _functionalTestState.value = FunctionalTestState()
        // A connection started under another Firebase account must never
        // complete after the garage and session ownership have changed.
        nextConnectionGeneration()
        invalidateFuelHistoryForConnectionChange()
        // Retain the cancelled job until the next connection can join its
        // transport cleanup; clearing it would allow overlapping handshakes.
        connectionAttemptJob?.cancel()
        _savedSessions.value = emptyList()
        _activeAccountUid.value = accountUid
        discardCanMonitor()
        detachCanRequest()
        _liveCanMonitorState.value = _liveCanMonitorState.value.copy(
            isCapturing = false,
            isSendingRequest = false
        )
        _isStreamingLive.value = false
        if (_liveRecordingState.value.isRecording) stopLiveRecording()
        recordedLiveSamples.clear()
        _liveRecordingState.value = LiveRecordingState()
        obdManager.disconnect()
        clearVehicleEvidence()
        _calibrationWorkspace.value = CalibrationWorkspaceState()
        _savedVehicles.value = loadSavedVehicleProfiles()
        _selectedSavedVehicleId.value = selectedVehiclePreference()
        _legacyVehicleImportAvailable.value = canImportLegacyVehicles()
        refreshLegacyImportAvailability()
        _vehicle.value = loadVehicleProfile()
        _lastConnectionProfile.value = loadLastConnectionProfile()
        _connectionMessage.value = null
        _connectionLossMessage.value = null
        _isObdScanning.value = false
    }

    /** Invoked only after Firebase confirms the remote account was deleted. */
    fun onAccountDeleted(deletedUid: String) {
        if (deletedUid.isBlank()) return
        val prefs = accountVehiclePreferences(deletedUid)
        prefs.edit { clear() }
        if (settingsPreferences.getString("legacy_vehicles_claimed_uid", null) == deletedUid) {
            settingsPreferences.edit {
                remove("saved_vehicle_profiles")
                remove("selected_vehicle_id")
                remove("vehicle_year")
                remove("vehicle_engine")
                remove("vehicle_transmission")
                remove("vehicle_market")
                remove("vehicle_vin")
                remove("last_connection_transport")
                remove("last_connection_adapter")
                remove("last_connection_endpoint")
                // Do not offer deleted pre-login records to another account.
                putString("legacy_vehicles_claimed_uid", "deleted")
            }
        }
        // Invalidate the active identity before scheduling the asynchronous
        // database purge: a deleted account must not keep an OBD session alive.
        if (authenticatedAccountUid == deletedUid) {
            onAccountSessionChanged(null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            sessionDao.deleteAllForOwner(deletedUid)
        }
    }

    @Volatile private var obdbCamaroSignalSet: ObdbCamaroSignalSet? = null
    @Volatile private var camaroSignalCatalog: CamaroSignalCatalog? = null
    private val sessionDao = DiagnosticDatabase.getInstance(application).sessionDao()

    private val _language = MutableStateFlow(
        AppLanguage.entries.firstOrNull { it.code == settingsPreferences.getString("language", null) }
            ?: AppLanguage.fromLocale(application.resources.configuration.locales[0])
    )
    val language: StateFlow<AppLanguage> = _language.asStateFlow()
    private val localizedContext: Context
        get() = getApplication<Application>().forAppLanguage(_language.value)
    // No saved manual override means follow the device language.
    private val _followSystemLanguage = MutableStateFlow(
        settingsPreferences.getString("language", null) == null
    )
    val followSystemLanguage: StateFlow<Boolean> = _followSystemLanguage.asStateFlow()

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

    private val _selectedSavedVehicleId = MutableStateFlow(selectedVehiclePreference())
    val selectedSavedVehicleId: StateFlow<String?> = _selectedSavedVehicleId.asStateFlow()

    private val _legacyVehicleImportAvailable = MutableStateFlow(canImportLegacyVehicles())
    val legacyVehicleImportAvailable: StateFlow<Boolean> = _legacyVehicleImportAvailable.asStateFlow()

    private fun canImportLegacyVehicles(): Boolean {
        if (authenticatedAccountUid == null) return false
        if (settingsPreferences.contains("legacy_vehicles_claimed_uid")) return false
        return loadSavedVehicleProfiles(
            settingsPreferences.getString("saved_vehicle_profiles", "[]").orEmpty()
        ).isNotEmpty() ||
            settingsPreferences.contains("vehicle_vin") ||
            settingsPreferences.contains("vehicle_year")
    }

    private fun refreshLegacyImportAvailability() {
        val uid = authenticatedAccountUid ?: return
        viewModelScope.launch {
            val hasHistory = runCatching { sessionDao.countUnassignedSessions() > 0 }.getOrDefault(false)
            if (authenticatedAccountUid == uid &&
                !settingsPreferences.contains("legacy_vehicles_claimed_uid")
            ) {
                _legacyVehicleImportAvailable.value = canImportLegacyVehicles() || hasHistory
            }
        }
    }

    /** The unassigned garage remains untouched until the user claims it. */
    private var legacyImportInProgress = false

    fun importLegacyVehicles() {
        val uid = authenticatedAccountUid ?: return
        val importGeneration = accountSessionGeneration
        if (legacyImportInProgress || settingsPreferences.contains("legacy_vehicles_claimed_uid")) return
        legacyImportInProgress = true
        _legacyVehicleImportAvailable.value = false
        val legacy = loadSavedVehicleProfiles(
            settingsPreferences.getString("saved_vehicle_profiles", "[]").orEmpty()
        )
        viewModelScope.launch {
            try {
                val historyCount = sessionDao.countUnassignedSessions()
                // A sign-out or account switch while Room is loading must not
                // assign unclaimed legacy data to a stale identity.
                if (authenticatedAccountUid != uid ||
                    accountSessionGeneration != importGeneration
                ) return@launch
                val hasLegacyCurrentVehicle = settingsPreferences.contains("vehicle_vin") ||
                    settingsPreferences.contains("vehicle_year")
                if (legacy.isEmpty() && historyCount == 0 && !hasLegacyCurrentVehicle) return@launch
                // Keep retries idempotent: a partial import must never create
                // duplicate vehicles when Room or settings persistence fails.
                val existing = loadSavedVehicleProfiles(
                    accountVehiclePreferences(uid).getString("saved_vehicle_profiles", "[]").orEmpty()
                )
                val ids = existing.mapTo(mutableSetOf()) { it.id }
                val imported = legacy.filter { profile -> ids.add(profile.id) }
                val oldCurrent = if (hasLegacyCurrentVehicle) {
                    loadVehicleProfile(settingsPreferences)
                } else null
                val missingCurrent = oldCurrent?.takeUnless { vehicle ->
                    val stableId = UUID.nameUUIDFromBytes(
                        "legacy-current:$uid:${vehicle.modelYear}:${vehicle.engine}:${vehicle.transmission}:${vehicle.market}:${vehicle.vin}".toByteArray(Charsets.UTF_8)
                    ).toString()
                    (existing + imported).any { it.id == stableId ||
                        (vehicle.vin.isNotBlank() && it.vehicle.vin.equals(vehicle.vin, ignoreCase = true)) }
                }
                val currentProfile = missingCurrent?.let { vehicle ->
                    SavedVehicleProfile(
                        id = UUID.nameUUIDFromBytes(
                            "legacy-current:$uid:${vehicle.modelYear}:${vehicle.engine}:${vehicle.transmission}:${vehicle.market}:${vehicle.vin}".toByteArray(Charsets.UTF_8)
                        ).toString(),
                        name = localizedContext.getString(R.string.saved_vehicle_legacy_default_name),
                        vehicle = vehicle,
                        requiresReview = true
                    )
                }
                val merged = existing + imported + listOfNotNull(currentProfile)
                if (!persistSavedVehicleProfiles(merged, ownerUid = uid, durable = true)) {
                    throw IllegalStateException("Unable to persist imported garage")
                }
                // Claim the historical Room rows only after the destination garage
                // is persisted; a failure remains retryable without duplicates.
                // Do not claim history if the account changed during preparation.
                if (authenticatedAccountUid != uid ||
                    accountSessionGeneration != importGeneration
                ) return@launch
                sessionDao.claimUnassignedSessions(uid)
                settingsPreferences.edit(commit = true) { putString("legacy_vehicles_claimed_uid", uid) }
                if (authenticatedAccountUid == uid) {
                    _savedVehicles.value = merged
                    _legacyVehicleImportAvailable.value = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _connectionMessage.value =
                    localizedContext.getString(R.string.msg_session_save_failed)
            } finally {
                legacyImportInProgress = false
                if (authenticatedAccountUid == uid &&
                    !settingsPreferences.contains("legacy_vehicles_claimed_uid")
                ) {
                    refreshLegacyImportAvailability()
                }
            }
        }
    }

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
        // An operation from an earlier connection may fail after a new one
        // succeeds. Never tear down the replacement adapter for that old error.
        if (error is ObdSupersededSessionException) return
        if (!ObdTransportFailurePolicy.isConnectionLoss(error)) return
        nextConnectionGeneration()
        invalidateFuelHistoryForConnectionChange()
        // Retain the cancelled job until the next connection can join its
        // transport cleanup; clearing it would allow overlapping handshakes.
        connectionAttemptJob?.cancel()
        val interruptedCapture = canMonitorJob != null || _liveCanMonitorState.value.isCapturing
        discardCanMonitor(deleteArchive = false)
        detachCanRequest()
        _liveCanMonitorState.value = _liveCanMonitorState.value.copy(
            isCapturing = false,
            isSendingRequest = false,
            rawArchiveFailed = CanConsoleExportPolicy.archiveFailedAfterDisconnect(
                    _liveCanMonitorState.value.rawArchiveFailed, interruptedCapture
                )
        )
        _isStreamingLive.value = false
        _livePids.value = emptyList()
        if (_liveRecordingState.value.isRecording) {
            stopLiveRecording()
        }
        _liveTelemetryMessage.value = localizedContext.getString(R.string.msg_live_recording_channel_lost)
        _isObdScanning.value = false
        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
            isReading = false,
            isUdsReading = false,
            isObdbSignalReading = false,
            isJ2534BridgeConnecting = false,
            isJ2534BridgeConnected = false,
            isJ2534MemoryReadAvailable = false
        )
        val restoration = when (error) {
            is CanAdapterRestorationException -> error
            is CanConsolePhysicalExchangeException -> error.cause as? CanAdapterRestorationException
            else -> null
        }
        val failureTransport = restoration?.originalTransportKind ?: obdManager.currentTransportKind
        val messageId = when (failureTransport) {
            "BLUETOOTH", "BLE" -> R.string.connection_lost_bluetooth
            "USB" -> R.string.connection_lost_usb
            "WIFI" -> R.string.connection_lost_wifi
            "J2534_BRIDGE" -> R.string.connection_lost_pc_bridge
            else -> R.string.connection_lost_vehicle
        }
        _connectionLossMessage.value = localizedContext.getString(messageId)
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

    private val _functionalTestState = MutableStateFlow(FunctionalTestState())
    val functionalTestState: StateFlow<FunctionalTestState> = _functionalTestState.asStateFlow()
    private var functionalTestJob: Job? = null
    private var functionalTestGeneration: Long = 0L

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

    private val _savedSessions = MutableStateFlow<List<SavedSessionEntity>>(emptyList())
    val savedSessions: StateFlow<List<SavedSessionEntity>> = _savedSessions.asStateFlow()

    init {
        refreshLegacyImportAvailability()
        viewModelScope.launch {
            _activeAccountUid.collectLatest { uid ->
                _savedSessions.value = emptyList()
                if (uid != null) {
                    sessionDao.observeSessionsForOwner(uid).collect { rows ->
                        if (authenticatedAccountUid == uid) _savedSessions.value = rows
                    }
                }
            }
        }
        viewModelScope.launch {
            UsbPermissionResultBus.results.collect { outcome ->
                val pending = pendingUsbPermissionRetry ?: return@collect
                if (pending.deviceId != outcome.deviceId) return@collect
                // Wait for the original USB handshake to release its handle.
                connectionAttemptJob?.join()
                if (pendingUsbPermissionRetry != pending) return@collect
                if (!outcome.granted) {
                    pendingUsbPermissionRetry = null
                    if (pending.generation == connectionAttemptGeneration) {
                        _connectionMessage.value = localizedContext.getString(R.string.msg_usb_permission_denied)
                    }
                    return@collect
                }
                val devices = runCatching { obdManager.getUsbDevices() }.getOrDefault(emptyList())
                _usbDevices.value = devices
                val mayResume = UsbPermissionReconnectPolicy.mayResume(
                    pending, outcome.deviceId, outcome.granted, connectionAttemptGeneration, devices
                )
                pendingUsbPermissionRetry = null
                if (!mayResume) {
                    if (pending.generation == connectionAttemptGeneration) {
                        _connectionMessage.value = localizedContext.getString(R.string.msg_usb_identifier_unavailable)
                    }
                    return@collect
                }
                when (pending.transport) {
                    "usb" -> connectUsb(pending.endpoint)
                    "connector_usb" -> discoverCamaroDiagConnector(pending.endpoint)
                    "connector_usb_binary" -> discoverCamaroDiagConnector(pending.endpoint, binary = true)
                }
            }
        }
        refreshTopologyAndDefaults()
        loadObdbSignalCatalog()
        loadNativeCamaroSignalCatalog()
        startLiveTelemetryLoop()
    }

    private fun loadVehicleProfile(
        settingsPreferences: android.content.SharedPreferences = accountVehiclePreferences()
    ): VehicleProfile {
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
        accountVehiclePreferences().edit {
            putInt("vehicle_year", profile.modelYear)
            putString("vehicle_engine", profile.engine.name)
            putString("vehicle_transmission", profile.transmission.name)
            putString("vehicle_market", profile.market.name)
            putString("vehicle_vin", profile.vin)
        }
    }

    private fun loadLastConnectionProfile(): SavedObdConnectionProfile? {
        val settingsPreferences = accountVehiclePreferences()
        val transport = settingsPreferences.getString("last_connection_transport", null) ?: return null
        val adapterName = settingsPreferences.getString("last_connection_adapter", null) ?: return null
        val endpoint = settingsPreferences.getString("last_connection_endpoint", "").orEmpty()
        return SavedObdConnectionProfile(transport, adapterName, endpoint)
    }

    private fun loadSavedVehicleProfiles(
        source: String = accountVehiclePreferences().getString("saved_vehicle_profiles", "[]").orEmpty()
    ): List<SavedVehicleProfile> = runCatching {
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

    private fun persistSavedVehicleProfiles(
        profiles: List<SavedVehicleProfile>,
        ownerUid: String? = authenticatedAccountUid,
        durable: Boolean = false
    ): Boolean {
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
        if (durable) {
            accountVehiclePreferences(ownerUid).edit(commit = true) {
                putString("saved_vehicle_profiles", array.toString())
            }
        } else {
            accountVehiclePreferences(ownerUid).edit {
                putString("saved_vehicle_profiles", array.toString())
            }
        }
        return true
    }

    fun saveCurrentVehicle(name: String) {
        val cleanName = name.trim().take(60)
        if (cleanName.isBlank()) {
            _connectionMessage.value = localizedContext.getString(R.string.msg_vehicle_profile_name_required)
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
        accountVehiclePreferences().edit { putString("selected_vehicle_id", profile.id) }
        _connectionMessage.value = localizedContext.getString(R.string.msg_vehicle_profile_saved)
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
        accountVehiclePreferences().edit { putString("selected_vehicle_id", saved.id) }
        _connectionMessage.value = if (saved.requiresReview) {
            localizedContext.getString(R.string.msg_vehicle_profile_review_required)
        } else {
            localizedContext.getString(R.string.msg_vehicle_profile_loaded, saved.name)
        }
    }

    fun deleteSavedVehicle(id: String) {
        val updated = _savedVehicles.value.filterNot { it.id == id }
        if (updated.size == _savedVehicles.value.size) return
        _savedVehicles.value = updated
        persistSavedVehicleProfiles(updated)
        if (_selectedSavedVehicleId.value == id) {
            _selectedSavedVehicleId.value = null
            accountVehiclePreferences().edit { remove("selected_vehicle_id") }
        }
    }

    fun syncVehicleVinFromEcu(ecuVin: String) {
        val normalizedVin = VehicleVinMatcher.normalize(ecuVin)
        if (normalizedVin == null) {
            _connectionMessage.value = localizedContext.getString(R.string.msg_vehicle_ecu_vin_invalid)
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
            accountVehiclePreferences().edit { putString("selected_vehicle_id", selectedId) }
        }
    }

    fun createVehicleFromEcuVin(ecuVin: String) {
        val normalizedVin = VehicleVinMatcher.normalize(ecuVin)
        if (normalizedVin == null) {
            _connectionMessage.value = localizedContext.getString(R.string.msg_vehicle_ecu_vin_invalid)
            return
        }
        val vehicleFromEcu = _vehicle.value.copy(vin = normalizedVin)
        val suffix = normalizedVin.takeLast(6)
        val saved = SavedVehicleProfile(
            id = UUID.randomUUID().toString(),
            name = localizedContext.getString(R.string.saved_vehicle_ecu_default_name, suffix),
            vehicle = vehicleFromEcu,
            requiresReview = true
        )
        val updated = _savedVehicles.value + saved
        _savedVehicles.value = updated
        persistSavedVehicleProfiles(updated)
        updateVin(normalizedVin)
        _selectedSavedVehicleId.value = saved.id
        accountVehiclePreferences().edit { putString("selected_vehicle_id", saved.id) }
        _connectionMessage.value = localizedContext.getString(R.string.msg_vehicle_profile_review_required)
    }

    private fun rememberConnectionProfile(transport: String, capabilities: AdapterCapabilities, endpoint: String) {
        val profile = SavedObdConnectionProfile(transport, capabilities.deviceName, endpoint)
        _lastConnectionProfile.value = profile
        accountVehiclePreferences().edit {
            putString("last_connection_transport", profile.transport)
            putString("last_connection_adapter", profile.adapterName)
            putString("last_connection_endpoint", profile.endpoint)
        }
    }

    fun setLanguage(lang: AppLanguage) {
        _followSystemLanguage.value = false
        _language.value = lang
        settingsPreferences.edit { putString("language", lang.code) }
    }

    /** Restore automatic language choice and remove any saved manual override. */
    fun setFollowSystemLanguage(systemLocale: java.util.Locale) {
        settingsPreferences.edit { remove("language") }
        _followSystemLanguage.value = true
        _language.value = AppLanguage.fromLocale(systemLocale)
    }

    /** Update automatic language after Android reports a device configuration change. */
    fun refreshSystemLanguage(systemLocale: java.util.Locale) {
        if (_followSystemLanguage.value) {
            _language.value = AppLanguage.fromLocale(systemLocale)
        }
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
        val context = localizedContext
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
        accountVehiclePreferences().edit { remove("selected_vehicle_id") }
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
        accountVehiclePreferences().edit { putString("vehicle_vin", normalizedVin) }
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
                message = localizedContext.getString(
                    R.string.msg_live_recording_stopped_count,
                    previousSampleCount
                )
            )
        } else {
            LiveRecordingState()
        }
    }

    private var traceAnalysisJob: Job? = null
    private var calibrationIdentityGeneration = 0L
    private var gmlanIdentityGeneration = 0L
    private var fuelSnapshotGeneration = 0L
    private val fuelHistory = Lfx2012FuelHistoryWindow()

    /** Drop observations immediately when the authenticated/transport context changes. */
    private fun invalidateFuelHistoryForConnectionChange() {
        fuelSnapshotGeneration++
        fuelHistory.clear()
        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
            isFuelSnapshotReading = false,
            fuelReadOnlySnapshot = null,
            fuelHistoryCount = 0,
            fuelHistorySummary = null,
            fuelSnapshotError = ""
        )
    }


    private fun clearVehicleEvidence(preserveCanConsole: Boolean = false) {
        invalidateEnhancedUdsReads()
        calibrationIdentityGeneration++
        gmlanIdentityGeneration++
        fuelSnapshotGeneration++
        fuelHistory.clear()
        val newCanContext = ++canConsoleContextGeneration
        val interruptedCapture = canMonitorJob != null || _liveCanMonitorState.value.isCapturing
        discardCanMonitor(deleteArchive = !preserveCanConsole)
        detachCanRequest()
        _liveCanMonitorState.value = if (preserveCanConsole) {
            _liveCanMonitorState.value.copy(
                consoleContextGeneration = newCanContext,
                isCapturing = false,
                isSendingRequest = false,
                rawArchiveFailed = CanConsoleExportPolicy.archiveFailedAfterDisconnect(
                    _liveCanMonitorState.value.rawArchiveFailed, interruptedCapture
                )
            )
        } else {
            resetConsoleEventJournal()
            LiveCanMonitorState(consoleContextGeneration = newCanContext)
        }
        obdManager.invalidateObdPidSupportCache()
        if (obdManager.isJ2534BridgeConnected()) {
            obdManager.disconnect()
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                isJ2534BridgeConnecting = false,
                isJ2534BridgeConnected = false,
                isJ2534MemoryReadAvailable = false,
                j2534BridgeMessage = localizedContext.getString(R.string.vm_bridge_disconnected_vehicle_changed)
            )
        }
        _dtcs.value = emptyList()
        _livePids.value = emptyList()
        _mileageReport.value = null
        _obdScanReport.value = null
        _obdScanMessage.value = null
        _liveTelemetryMessage.value = null
        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
            isReading = false,
            isGmlanReading = false,
            isFuelSnapshotReading = false,
            fuelReadOnlySnapshot = null,
            fuelHistoryCount = 0,
            fuelHistorySummary = null,
            fuelSnapshotError = "",
            gmlanIdentity = null,
            gmlanIdentityFailed = false,
            ecuVinResponse = "",
            ecuIdentityFromSimulation = false,
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
    private var canConsoleContextGeneration = 0L
    val liveCanMonitorState: StateFlow<LiveCanMonitorState> = _liveCanMonitorState.asStateFlow()
    private var canMonitorJob: Job? = null
    /** Complete raw recording on private storage; rawText is only a bounded UI preview. */
    @Volatile private var activeCanCaptureFile: File? = null
    private val consoleEventJournal = CanConsoleEventJournalStore(application.cacheDir)
    @Volatile private var consoleEventJournalFailed = false

    /** Persistence failures affect export completeness, never adapter communications. */
    private fun recordConsoleEvents(events: List<CanConsoleHistoryEntry>) {
        if (events.isEmpty() || consoleEventJournalFailed) return
        try {
            consoleEventJournal.append(events)
        } catch (_: Exception) {
            consoleEventJournalFailed = true
        }
    }

    private fun resetConsoleEventJournal() {
        consoleEventJournal.reset()
        consoleEventJournalFailed = false
    }
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
                        nativeSignalMessage = localizedContext.resources.getQuantityString(R.plurals.vm_native_catalog_loaded, signals.size, signals.size, _vehicle.value.modelYear)
                    )
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                        nativeSignalMessage = localizedContext.getString(R.string.msg_native_catalog_load_failed)
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
                            obdbSignalMessage = localizedContext.resources.getQuantityString(R.plurals.vm_community_catalog_loaded, signals.size, signals.size, _vehicle.value.modelYear)
                        )
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                            obdbSignalMessage = localizedContext.getString(R.string.msg_obdb_catalog_load_failed)
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
            nativeSignalMessage = localizedContext.resources.getQuantityString(
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
            obdbSignalMessage = localizedContext.resources.getQuantityString(
                R.plurals.vm_community_catalog_loaded,
                signals.size,
                signals.size,
                _vehicle.value.modelYear
            )
        )
    }

    private var enhancedUdsJob: Job? = null
    @Volatile private var enhancedUdsGeneration = 0L

    private fun invalidateEnhancedUdsReads() {
        synchronized(consoleEventJournal) {
            enhancedUdsGeneration++
            _liveCanMonitorState.update { it.copy(isDoIpActive = false) }
        }
        enhancedUdsJob?.cancel()
        enhancedUdsJob = null
        _calibrationWorkspace.update {
            it.copy(isUdsReading = false, isObdbSignalReading = false,
                udsDidResponse = "", udsDtcResponse = "", udsMessage = "",
                obdbSignalValue = "", obdbSignalMessage = "",
                doIpReading = false, doIpResult = "", doIpTrace = emptyList(),
                doIpBackupScope = null, doIpBackupBytes = 0, doIpMonitorReport = null, doIpLiveSamples = emptyList(),
                doIpGatewayReading = false, doIpDiscoveredVin = "", doIpGatewayHost = "", doIpGatewayResult = "")
        }
    }

    // The three read-only entrypoints share one context-bound operation slot.
    // Cancellation propagates through the adapter session and prevents queued
    // work or returned bytes from being attached to a replacement vehicle.
    private fun launchEnhancedUdsRead(journalDoIp: Boolean = false, block: suspend (() -> Boolean) -> Unit) {
        if (enhancedUdsJob?.isActive == true) return
        if (journalDoIp && (_liveCanMonitorState.value.isCapturing || _liveCanMonitorState.value.isSendingRequest ||
                canMonitorJob?.isCompleted == false || canRequestJob?.isCompleted == false ||
                detachedCanMonitorJob?.isCompleted == false || detachedCanRequestJob?.isCompleted == false)) return
        val generation = ++enhancedUdsGeneration
        val account = accountSessionGeneration
        val connection = connectionAttemptGeneration
        val vehicle = _vehicle.value
        fun current() = generation == enhancedUdsGeneration &&
            isCurrentAccountGeneration(account) && connection == connectionAttemptGeneration &&
            vehicle == _vehicle.value && hasPremiumLicense()
        if (journalDoIp) _liveCanMonitorState.update {
            it.copy(isDoIpActive = true, consoleContextGeneration = ++canConsoleContextGeneration)
        }
        enhancedUdsJob = viewModelScope.launch {
            try {
                if (current()) block(::current)
            } finally {
                if (journalDoIp && generation == enhancedUdsGeneration)
                    _liveCanMonitorState.update { it.copy(isDoIpActive = false) }
            }
        }
    }

    fun cancelDirectDoIp() {
        if (!_calibrationWorkspace.value.doIpReading) return
        // A user cancellation is not a vehicle/account change: keep already
        // received trace and samples visible, but invalidate every late callback.
        synchronized(consoleEventJournal) {
            enhancedUdsGeneration++
            if (_liveCanMonitorState.value.isDoIpActive) {
                appendCanConsoleSystemEvent(localizedContext.getString(R.string.cal_doip_cancel), succeeded = false)
                _liveCanMonitorState.update { it.copy(isDoIpActive = false) }
            }
        }
        enhancedUdsJob?.cancel()
        enhancedUdsJob = null
        _calibrationWorkspace.update {
            val message = localizedContext.getString(R.string.cal_doip_cancel)
            if (it.doIpGatewayReading) it.copy(doIpReading = false, doIpGatewayReading = false,
                doIpGatewayResult = message)
            else it.copy(doIpReading = false, doIpMonitorReport = null,
                doIpBackupBytes = 0, doIpResult = message)
        }
    }

    fun inspectDirectDoIpGateway(host: String, operation: DoIpDirectRead.GatewayOperation) {
        if (!requirePremiumLicense()) return
        launchEnhancedUdsRead(journalDoIp = true) { current ->
            _calibrationWorkspace.update { it.copy(doIpReading = true, doIpGatewayReading = true,
                doIpGatewayResult = "", doIpDiscoveredVin = "", doIpGatewayHost = host) }
            try {
                val result = DoIpDirectRead.inspectGateway(host, _vehicle.value.vin, operation,
                    onControlTrace = { trace -> journalDoIpControlTrace(trace, current) })
                if (!current()) return@launchEnhancedUdsRead
                val unavailable = localizedContext.getString(R.string.cal_status_unresolved)
                val identity = localizedContext.getString(R.string.cal_doip_gateway_identity,
                    "%04X".format(result.gateway.logicalAddress), "%02X".format(result.gateway.version),
                    result.gateway.vin ?: unavailable)
                val status = result.status?.let { observed ->
                    localizedContext.getString(R.string.cal_doip_gateway_status,
                        observed.nodeType, observed.currentSockets, observed.maxSockets,
                        observed.maxDataSize?.toString() ?: unavailable)
                }
                val power = result.power?.let { observed ->
                    localizedContext.getString(when (observed) {
                        0 -> R.string.cal_doip_power_not_ready
                        1 -> R.string.cal_doip_power_ready
                        else -> R.string.cal_doip_power_unsupported
                    })
                }
                _calibrationWorkspace.update { it.copy(doIpReading = false, doIpGatewayReading = false,
                    doIpGatewayHost = result.host ?: host,
                    doIpDiscoveredVin = if (result.host != null) result.gateway.vin.orEmpty() else "",
                    doIpGatewayResult = listOfNotNull(identity, status, power).joinToString("\n")) }
                if (current()) appendCanConsoleSystemEvent(localizedContext.getString(R.string.cal_doip_console_complete))
            } catch (cancelled: CancellationException) {
                if (current()) _calibrationWorkspace.update { it.copy(doIpReading = false, doIpGatewayReading = false) }
                throw cancelled
            } catch (_: Exception) {
                if (current()) appendCanConsoleSystemEvent(localizedContext.getString(R.string.cal_doip_failed), succeeded = false)
                if (current()) _calibrationWorkspace.update { it.copy(doIpReading = false, doIpGatewayReading = false,
                    doIpGatewayResult = localizedContext.getString(R.string.cal_doip_failed)) }
            }
        }
    }

    fun importDoIpBackupProfile(uri: Uri) {
        if (!requirePremiumLicense()) return
        launchEnhancedUdsRead { current ->
            _calibrationWorkspace.update { it.copy(doIpReading = true, doIpBackupScope = null,
                doIpResult = "", doIpTrace = emptyList(), doIpBackupBytes = 0) }
            try {
                val scope = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    requireNotNull(resolver.openInputStream(uri)).use(DoIpMemoryBackup::load)
                }
                if (!current()) return@launchEnhancedUdsRead
                require(scope.vin == _vehicle.value.vin.uppercase())
                _calibrationWorkspace.update { it.copy(doIpReading = false, doIpBackupScope = scope) }
            } catch (cancelled: CancellationException) {
                if (current()) _calibrationWorkspace.update { it.copy(doIpReading = false) }
                throw cancelled
            } catch (_: Exception) {
                if (current()) _calibrationWorkspace.update { it.copy(doIpReading = false,
                    doIpResult = localizedContext.getString(R.string.cal_doip_profile_failed)) }
            }
        }
    }

    fun verifyDirectDoIpBackup(uri: Uri, scope: DoIpMemoryBackup.Scope) {
        if (!requirePremiumLicense() || _calibrationWorkspace.value.doIpBackupScope != scope) return
        launchEnhancedUdsRead { current ->
            _calibrationWorkspace.update { it.copy(doIpReading = true, doIpResult = "",
                doIpBackupBytes = 0, doIpTrace = emptyList()) }
            try {
                require(scope.vin == _vehicle.value.vin.uppercase())
                val result = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    requireNotNull(resolver.openInputStream(uri)).use {
                        DoIpMemoryBackup.verify(it, scope)
                    }
                }
                if (!current() || _calibrationWorkspace.value.doIpBackupScope != scope)
                    return@launchEnhancedUdsRead
                _calibrationWorkspace.update { it.copy(doIpReading = false,
                    doIpResult = localizedContext.getString(R.string.cal_doip_backup_verified,
                        result.scope.length, result.sha256)) }
            } catch (cancelled: CancellationException) {
                if (current()) _calibrationWorkspace.update { it.copy(doIpReading = false) }
                throw cancelled
            } catch (_: Exception) {
                if (current()) _calibrationWorkspace.update { it.copy(doIpReading = false,
                    doIpResult = localizedContext.getString(R.string.cal_doip_verify_failed)) }
            }
        }
    }

    fun captureDirectDoIpBackup(host: String, tester: String, ecu: String, tls: Boolean,
        scope: DoIpMemoryBackup.Scope) {
        if (!requirePremiumLicense() || _calibrationWorkspace.value.doIpBackupScope != scope) return
        launchEnhancedUdsRead(journalDoIp = true) { current ->
            _calibrationWorkspace.update { it.copy(doIpReading = true, doIpResult = "",
                doIpTrace = emptyList(), doIpBackupBytes = 0) }
            try {
                require(current() && _calibrationWorkspace.value.doIpBackupScope == scope)
                val target = DoIpDirectRead.resolveTarget(host, tester, ecu, _vehicle.value.vin, tls,
                    expectedGateway = scope.gateway, onControlTrace = { trace -> journalDoIpControlTrace(trace, current) })
                check(current())
                require(scope.vin == target.expectedVin && scope.tester == target.tester && scope.ecu == target.ecu)
                val result = DoIpDirectRead.read(target, DoIpDirectRead.Operation.BACKUP,
                    backupScope = scope, onBackupProgress = { pass, offset ->
                        check(current())
                        _calibrationWorkspace.update { if (current()) it.copy(doIpBackupBytes = (pass - 1) * scope.length + offset) else it }
                    }, onTrace = { trace ->
                        journalDoIpTrace(target, trace, current)
                        check(current())
                        _calibrationWorkspace.update {
                            if (current()) it.copy(doIpTrace = (it.doIpTrace + doIpTraceLine(trace)).takeLast(128)) else it
                        }
                    }, onControlTrace = { trace -> journalDoIpControlTrace(trace, current) })
                if (!current()) return@launchEnhancedUdsRead
                val capture = checkNotNull(result.capture)
                val path = CamaroDiagExportStorage.write(getApplication<Application>(),
                    "CamaroDiag_DoIP_${scope.vin}_${System.currentTimeMillis()}.zip", "application/zip",
                    category = CamaroDiagExportStorage.Category.ECU_BACKUPS,
                    beforePublish = { check(current() && _calibrationWorkspace.value.doIpBackupScope == scope) },
                    verifyStaged = { input ->
                        check(current())
                        DoIpMemoryBackup.verifyStaged(input, capture)
                    }) { output ->
                    check(current())
                    DoIpMemoryBackup.write(output, capture)
                }
                if (!current()) return@launchEnhancedUdsRead
                _calibrationWorkspace.update { it.copy(doIpReading = false,
                    doIpResult = localizedContext.getString(R.string.cal_doip_backup_saved,
                        scope.length, capture.sha256, path),
                    doIpTrace = result.trace.map(::doIpTraceLine)) }
                if (current()) appendCanConsoleSystemEvent(localizedContext.getString(R.string.cal_doip_console_complete))
            } catch (cancelled: CancellationException) {
                if (current()) _calibrationWorkspace.update { it.copy(doIpReading = false, doIpBackupBytes = 0) }
                throw cancelled
            } catch (_: Exception) {
                if (current()) appendCanConsoleSystemEvent(localizedContext.getString(R.string.cal_doip_failed), succeeded = false)
                if (current()) _calibrationWorkspace.update { it.copy(doIpReading = false, doIpBackupBytes = 0,
                    doIpResult = localizedContext.getString(R.string.cal_doip_backup_failed)) }
            }
        }
    }

    /** Logical DoIP addresses are deliberately not formatted as CAN IDs.
     * Every observed diagnostic PDU is persisted before the UI ring trims it.
     * The shared lock prevents a cancelled context appending to a cleared journal.
     */
    private fun journalDoIpTrace(target: DoIpDirectRead.Target, trace: DoIpDirectRead.Trace,
        current: () -> Boolean) {
        synchronized(consoleEventJournal) {
            check(current() && _liveCanMonitorState.value.isDoIpActive)
            val source = if (trace.direction == "TX") target.tester else target.ecu
            val destination = if (trace.direction == "TX") target.ecu else target.tester
            val content = "DoIP " + (if (target.tls) "TLS" else "TCP") +
                " ${target.address.hostAddress} %04X>%04X ".format(source, destination) + trace.payloadHex
            val event = CanConsoleHistoryEntry(System.nanoTime(), trace.direction,
                trace.observedAtUtcMillis, content)
            recordConsoleEvents(listOf(event))
            _liveCanMonitorState.update { it.copy(consoleHistory = (it.consoleHistory + event).takeLast(300)) }
        }
    }

    /** DoIP transport control PDUs are not CAN frames or UDS service data. */
    private fun journalDoIpControlTrace(trace: DoIpDirectRead.ControlTrace, current: () -> Boolean) {
        synchronized(consoleEventJournal) {
            check(current() && _liveCanMonitorState.value.isDoIpActive)
            val content = "DoIP " + trace.transport + " " + trace.host + " PDU " + trace.payloadHex
            val event = CanConsoleHistoryEntry(System.nanoTime(), trace.direction,
                trace.observedAtUtcMillis, content)
            recordConsoleEvents(listOf(event))
            _liveCanMonitorState.update { it.copy(consoleHistory = (it.consoleHistory + event).takeLast(300)) }
        }
    }

    private fun doIpTraceLine(trace: DoIpDirectRead.Trace): String =
        "${trace.observedAtUtcMillis} ${if (trace.direction == "TX") "TX_REQUEST" else "RX"} ${trace.payloadHex}"

    private fun doIpSampleRow(sample: DoIpDirectRead.Sample) = DoIpMonitorRow(sample.index,
        sample.elapsedMs, sample.data.joinToString("") { "%02X".format(it.toInt() and 255) })

    fun exportDoIpMonitoring(report: DoIpMonitorReport) {
        if (!requirePremiumLicense() || _calibrationWorkspace.value.doIpMonitorReport !== report) return
        val owner = authenticatedAccountUid ?: return
        val account = accountSessionGeneration
        val connection = connectionAttemptGeneration
        val vehicle = _vehicle.value
        fun current() = canResumeExport(account, owner) && hasPremiumLicense() &&
            connection == connectionAttemptGeneration && _vehicle.value == vehicle &&
            report.vin == vehicle.vin.uppercase() && !_calibrationWorkspace.value.doIpReading &&
            _calibrationWorkspace.value.doIpMonitorReport === report
        viewModelScope.launch(Dispatchers.IO) {
            try {
                check(current() && report.samples.isNotEmpty())
                val path = CamaroDiagExportStorage.write(getApplication<Application>(),
                    "CamaroDiag_DoIP_monitor_${report.completedAtUtcMillis}.csv", "text/csv",
                    category = CamaroDiagExportStorage.Category.REPORTS,
                    beforePublish = { check(current()) }) { output ->
                    val writer = output.bufferedWriter(Charsets.UTF_8)
                    writer.appendLine("vin,gateway_logical,tester_logical,ecu_logical,did,transport,completed_utc_ms,sample,elapsed_ms,payload_hex")
                    for (sample in report.samples) {
                        check(current())
                        writer.appendLine(listOf(report.vin, "%04X".format(report.gateway),
                            "%04X".format(report.tester), "%04X".format(report.ecu),
                            "%04X".format(report.did), if (report.tls) "TLS" else "TCP",
                            report.completedAtUtcMillis, sample.index, sample.elapsedMs, sample.payloadHex).joinToString(","))
                    }
                    writer.flush()
                }
                _calibrationWorkspace.update { if (current()) it.copy(doIpResult =
                    localizedContext.getString(R.string.cal_doip_monitor_exported, path)) else it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _calibrationWorkspace.update { if (current()) it.copy(doIpResult =
                    localizedContext.getString(R.string.cal_doip_monitor_export_failed)) else it }
            }
        }
    }

    fun confirmDirectDoIpCommand(command: DoIpConsoleCommand, approval: EcuWriteConsentFlow.Approval) {
        if (!requirePremiumLicense() || command.vehicle != _vehicle.value ||
            command.generation != _liveCanMonitorState.value.consoleContextGeneration) return
        val payload = runCatching { DoIpDirectRead.parseCommand(command.payload) }.getOrNull() ?: return
        readDirectDoIp(command.host, command.tester, command.ecu, "", command.tls,
            DoIpDirectRead.Operation.EXCHANGE, command = payload, approval = approval)
    }

    fun readDirectDoIp(host: String, tester: String, ecu: String, didHex: String,
        tls: Boolean, operation: DoIpDirectRead.Operation, sampleCount: Int = 10, intervalMs: Int = 1000,
        command: ByteArray? = null, approval: EcuWriteConsentFlow.Approval? = null) {
        if (!requirePremiumLicense() || (operation == DoIpDirectRead.Operation.EXCHANGE &&
                (command == null || approval == null))) return
        val fixedCommand = command?.copyOf()
        launchEnhancedUdsRead(journalDoIp = true) { current ->
            _calibrationWorkspace.update { it.copy(doIpReading = true, doIpResult = "", doIpTrace = emptyList(), doIpBackupBytes = 0,
                doIpMonitorReport = null, doIpLiveSamples = emptyList()) }
            try {
                val target = DoIpDirectRead.resolveTarget(host, tester, ecu, _vehicle.value.vin, tls,
                    onControlTrace = { trace -> journalDoIpControlTrace(trace, current) })
                check(current())
                val did = if (operation in setOf(DoIpDirectRead.Operation.DID, DoIpDirectRead.Operation.MONITOR)) {
                    require(didHex.matches(Regex("[0-9A-Fa-f]{4}")))
                    didHex.toInt(16)
                } else 0xF190
                val mask = if (operation in setOf(DoIpDirectRead.Operation.DTC, DoIpDirectRead.Operation.DTC_COUNT)) {
                    require(didHex.matches(Regex("[0-9A-Fa-f]{2}")))
                    didHex.toInt(16)
                } else 255
                val isDetail = operation in setOf(DoIpDirectRead.Operation.DTC_SNAPSHOT, DoIpDirectRead.Operation.DTC_EXTENDED)
                if (isDetail) require(didHex.matches(Regex("[0-9A-Fa-f]{8}")))
                val detailDtc = if (isDetail) didHex.take(6).toInt(16) else 0
                val detailRecord = if (isDetail) didHex.takeLast(2).toInt(16) else 1
                val result = DoIpDirectRead.read(target, operation, did, mask = mask, sampleCount = sampleCount, intervalMs = intervalMs,
                    onTrace = { trace ->
                        journalDoIpTrace(target, trace, current)
                        check(current())
                        _calibrationWorkspace.update {
                            if (current()) it.copy(doIpTrace = (it.doIpTrace + doIpTraceLine(trace)).takeLast(128))
                            else it
                        }
                    }, onSample = { sample ->
                        check(current())
                        _calibrationWorkspace.update {
                            if (current()) it.copy(doIpLiveSamples = it.doIpLiveSamples + doIpSampleRow(sample)) else it
                        }
                    }, onControlTrace = { trace -> journalDoIpControlTrace(trace, current) },
                    command = fixedCommand, approval = approval, isContextCurrent = current,
                    dtc = detailDtc, record = detailRecord)
                if (!current()) return@launchEnhancedUdsRead
                val module = "DoIP 0x" + ecu.uppercase()
                val report = result.dtcs
                // A filtered report is not a complete replacement of the ECU's DTC list.
                if (report != null && mask == 255) {
                    val mapped = report.records.map { it.toDiagnosticDtc(module) }
                    _dtcs.value = _dtcs.value.filterNot { it.module == module } + mapped
                }
                val count = result.dtcCount
                val detail = result.dtcDetail
                val rendered = when {
                    detail != null -> localizedContext.getString(R.string.cal_doip_detail_result,
                        detail.dtc, detail.status, detail.records.joinToString(" ") { "%02X".format(it.toInt() and 255) })
                    count != null -> localizedContext.getString(R.string.cal_doip_count_result,
                        count.count, mask, count.availabilityMask, count.formatIdentifier)
                    result.samples.isNotEmpty() -> localizedContext.getString(R.string.cal_doip_monitor_complete, result.samples.size)
                    report != null -> localizedContext.getString(R.string.cal_doip_filtered_dtc_result,
                        report.records.size, mask, report.statusAvailabilityMask) +
                        report.records.joinToString(separator = "\n", prefix = "\n") {
                            "0x" + it.rawDtc.toString(16).uppercase().padStart(6, '0')
                        }
                    operation == DoIpDirectRead.Operation.VIN -> result.vin.orEmpty()
                    else -> result.data?.joinToString(" ") { "%02X".format(it.toInt() and 255) }.orEmpty()
                }
                val monitorReport = if (operation == DoIpDirectRead.Operation.MONITOR)
                    DoIpMonitorReport(checkNotNull(result.vin), result.gateway.logicalAddress,
                        target.tester, target.ecu, did, tls, System.currentTimeMillis(),
                        result.samples.map(::doIpSampleRow)) else null
                _calibrationWorkspace.update { it.copy(doIpReading = false, doIpResult = rendered,
                    doIpTrace = result.trace.map(::doIpTraceLine), doIpMonitorReport = monitorReport) }
                if (current()) appendCanConsoleSystemEvent(localizedContext.getString(R.string.cal_doip_console_complete))
            } catch (cancelled: CancellationException) {
                if (current()) _calibrationWorkspace.update { it.copy(doIpReading = false) }
                throw cancelled
            } catch (_: Exception) {
                if (current()) appendCanConsoleSystemEvent(localizedContext.getString(R.string.cal_doip_failed), succeeded = false)
                if (current()) _calibrationWorkspace.update { it.copy(doIpReading = false,
                    doIpResult = localizedContext.getString(R.string.cal_doip_failed)) }
            }
        }
    }

    /** Reads and decodes one read-only Camaro signal from the imported OBDb signalset. */
    fun readObdbSignal(signalId: String, protocolNumberText: String) {
        if (!requirePremiumLicense()) return
        launchEnhancedUdsRead { current ->
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
                val protocolNumber = protocolNumberText.toIntOrNull()
                    ?: error("Profil CAN invalide.")
                // Imported OBDb headers currently describe normal 11-bit ISO 15765.
                // A different physical bus/format must not be inferred from the UI.
                require(protocolNumber in setOf(33, 35) &&
                    protocolNumber in obdManager.availableUdsReadProfiles(requestId, responseId)) {
                    "The selected profile is incompatible with this OBDb signal."
                }
                val raw = obdManager.withSelectedUdsReadSession(
                        requestCanId = requestId,
                        responseCanId = responseId,
                        protocolNumber = protocolNumber,
                        timeoutMs = 5000L,
                        readDids = setOf(did)
                    ) { client -> client.readDataByIdentifier(did) }
                if (!current()) return@launchEnhancedUdsRead
                val decoded = signal.decode(raw)
                val rendered = when {
                    decoded.unavailable -> localizedContext.getString(R.string.vm_sentinel_unavailable)
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
                    obdbSignalMessage = localizedContext.getString(
                        R.string.vm_obdb_signal_read, signal.name,
                        requestId.toString(16).uppercase(), responseId.toString(16).uppercase()
                    )
                )
            } catch (cancelled: CancellationException) {
                if (current()) _calibrationWorkspace.value = _calibrationWorkspace.value.copy(isObdbSignalReading = false)
                throw cancelled
            } catch (error: Exception) {
                if (!current()) return@launchEnhancedUdsRead
                reportConnectionLoss(error)
                if (!current()) return@launchEnhancedUdsRead
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isObdbSignalReading = false,
                    obdbSignalValue = "",
                    obdbSignalMessage = localizedContext.getString(R.string.msg_obdb_signal_read_failed)
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
        if (!requirePremiumLicense()) return
        // Validate all form fields BEFORE invalidating an active OBD/J2534
        // session; malformed input must not disconnect a working adapter.
        val connectionForm = try {
            J2534BridgeConnectionForm.parse(
                host, portText, pairingToken,
                requestCanIdHex, responseCanIdHex, protocolNumberText
            )
        } catch (_: IllegalArgumentException) {
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                j2534BridgeMessage = localizedContext.getString(
                    R.string.msg_j2534_bridge_invalid_form
                )
            )
            return
        }
        val attempt = nextConnectionGeneration()
        invalidateFuelHistoryForConnectionChange()
        val previousAttempt = connectionAttemptJob
        previousAttempt?.cancel()
        connectionAttemptJob = viewModelScope.launch {
            beginBusyOperation()
            try {
                try {
                    previousAttempt?.join()
                    if (attempt != connectionAttemptGeneration) return@launch
                    val activeMonitor = canMonitorJob
                    val activeRequest = detachCanRequest()
                    markAdapterDisconnected()
                    val detachedMonitor = detachedCanMonitorJob
                    val detachedRequest = detachedCanRequestJob
                    activeMonitor?.join()
                    activeRequest?.join()
                    detachedMonitor?.join()
                    detachedRequest?.join()
                    if (attempt != connectionAttemptGeneration) return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isJ2534BridgeConnecting = true,
                    isJ2534BridgeConnected = false,
                    isJ2534MemoryReadAvailable = false,
                    j2534BridgeMessage = ""
                )
                try {
                    val result = obdManager.connectJ2534Bridge(
                        host = connectionForm.host,
                        port = connectionForm.port,
                        pairingToken = connectionForm.pairingCode,
                        requestCanId = connectionForm.requestCanId,
                        responseCanId = connectionForm.responseCanId,
                        can29Bit = connectionForm.can29Bit,
                        protocolNumber = connectionForm.protocolNumber
                    ).getOrThrow()
                    if (attempt != connectionAttemptGeneration) {
                        obdManager.disconnect()
                        return@launch
                    }
                    _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                        isJ2534BridgeConnecting = false,
                        isJ2534BridgeConnected = true,
                        isJ2534MemoryReadAvailable = obdManager.isJ2534MemoryReadAvailable(),
                        j2534BridgeMessage = localizedContext.getString(R.string.vm_j2534_connected, result)
                    )
                } catch (cancelled: CancellationException) {
                    if (attempt == connectionAttemptGeneration) {
                        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(isJ2534BridgeConnecting = false)
                    }
                    throw cancelled
                } catch (error: Exception) {
                    if (attempt == connectionAttemptGeneration) {
                        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                            isJ2534BridgeConnecting = false,
                            isJ2534BridgeConnected = false,
                            isJ2534MemoryReadAvailable = false,
                            j2534BridgeMessage = localizedContext.getString(R.string.msg_j2534_bridge_connect_failed)
                        )
                    }
                } finally {
                    if (attempt == connectionAttemptGeneration) {
                        connectionAttemptJob = null
                    }
                }
            } finally {
                endBusyOperation()
            }
        }
    }

    /** Disconnect any selected adapter (USB, BLE, Wi-Fi, J2534 or simulation).
     * Obsolete connection attempts and console jobs must complete teardown
     * before the manager socket/USB handle can be closed.
     */
    fun disconnectSelectedAdapter() {
        val attempt = nextConnectionGeneration()
        invalidateFuelHistoryForConnectionChange()
        val previousAttempt = connectionAttemptJob
        previousAttempt?.cancel()
        // Register the disconnect itself as the active transition. A subsequent
        // connection must cancel/join this cleanup before opening new hardware.
        connectionAttemptJob = viewModelScope.launch {
            beginBusyOperation()
            try {
                previousAttempt?.join()
                if (attempt != connectionAttemptGeneration) return@launch
                val activeMonitor = canMonitorJob
                val activeRequest = detachCanRequest()
                stopCanMonitor()
                val detachedMonitor = detachedCanMonitorJob
                val detachedRequest = detachedCanRequestJob
                activeMonitor?.join()
                activeRequest?.join()
                detachedMonitor?.join()
                detachedRequest?.join()
                if (attempt != connectionAttemptGeneration) return@launch
                obdManager.disconnect()
                markAdapterDisconnected()
                _connectionMessage.value = null
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isJ2534BridgeConnecting = false,
                    isJ2534BridgeConnected = false,
                    isJ2534MemoryReadAvailable = false,
                    j2534BridgeMessage = localizedContext.getString(R.string.vm_pc_bridge_disconnected)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (attempt == connectionAttemptGeneration) connectionAttemptJob = null
                endBusyOperation()
            }
        }
    }

    /** The bridge uses the same serialized teardown as USB, BLE and Wi-Fi. */
    fun disconnectJ2534Bridge() = disconnectSelectedAdapter()

    /**
     * Runs two REAL ECU memory reads through a connected J2534 PC bridge, then
     * saves the resulting integrity-checked ZIP through Android SAF. The PC
     * must independently authorize the target VIN, channel and address map.
     */
    @Volatile private var memoryCaptureGeneration = 0L

    fun captureJ2534Memory(fileName: String, request: J2534LiveMemoryBackup.Request) {
        if (!requirePremiumLicense()) return
        if (_calibrationWorkspace.value.isJ2534MemoryCapturing) return
        val captureGeneration = ++memoryCaptureGeneration
        val accountGeneration = accountSessionGeneration
        val connectionGeneration = connectionAttemptGeneration
        fun current() = captureGeneration == memoryCaptureGeneration &&
            isCurrentAccountGeneration(accountGeneration) &&
            connectionGeneration == connectionAttemptGeneration &&
            request.expectedVin == _vehicle.value.vin.uppercase() && hasPremiumLicense()
        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
            isJ2534MemoryCapturing = true,
            j2534MemoryCaptureBytesRead = 0L,
            j2534MemoryCaptureTotalBytes = request.lengthBytes.toLong() * 2,
            j2534MemoryCaptureMessage = ""
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                check(current()) {
                    "Selected vehicle VIN has changed."
                }
                check(obdManager.isJ2534BridgeConnected()) {
                    "A real J2534 adapter must be connected."
                }
                var acquired: J2534LiveMemoryBackup.CaptureResult? = null
                val savedPath = CamaroDiagExportStorage.write(getApplication<Application>(), fileName, "application/zip",
                    category = CamaroDiagExportStorage.Category.ECU_BACKUPS,
                    beforePublish = { check(current()) },
                    verifyStaged = { staged ->
                        check(current()) { "ECU backup context changed before ZIP verification." }
                        val capture = checkNotNull(acquired) {
                            "No complete ECU acquisition was returned for this ZIP."
                        }
                        val restored = EcuBackupArchive.import(
                            staged, EcuBackupArchive.MAX_J2534_IMPORT_UNCOMPRESSED_BYTES
                        )
                        val original = capture.manifest.entries.single()
                        val entry = restored.manifest.entries.single()
                        check(restored.verification.consistent &&
                            restored.manifest.vehicleVin == request.expectedVin &&
                            restored.manifest.channel == capture.manifest.channel &&
                            restored.manifest.channel?.diagnosticSession == request.diagnosticSession &&
                            restored.manifest.firmwareEvidence == capture.manifest.firmwareEvidence &&
                            restored.manifest.createdAtUtcMillis == capture.manifest.createdAtUtcMillis &&
                            restored.manifest.controllerLabel == capture.manifest.controllerLabel &&
                            entry == original &&
                            entry.origin == EcuBackupManifest.Origin.REPORTED_HARDWARE_READ &&
                            entry.region == request.region &&
                            entry.startAddress == request.address &&
                            entry.expectedBytes == request.lengthBytes.toLong() &&
                            restored.manifest.coverage.status == EcuBackupCoverage.Status.PARTIAL &&
                            !restored.manifest.hardwareProvenanceVerified
                        ) { "Staged ECU backup ZIP differs from the verified physical acquisition." }
                    }
                ) { output ->
                    acquired = obdManager.captureAndExportJ2534Memory(request, output) { progress ->
                        check(current()) { "Vehicle or account changed while ECU memory was being read." }
                        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                            j2534MemoryCaptureBytesRead = progress.totalBytesRead,
                            j2534MemoryCaptureTotalBytes = progress.requiredBytesRead
                        )
                    }
                }
                check(current()) {
                    "Vehicle or account changed during ECU capture."
                }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isJ2534MemoryCapturing = false,
                    j2534MemoryCaptureMessage =
                        localizedContext.getString(R.string.cal_live_memory_saved) + "\n" +
                            localizedContext.getString(R.string.cal_live_memory_partial_notice) + "\n" +
                            savedPath
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!current()) return@launch
                // A full folder/revoked grant is not an ECU disconnection.
                if (error !is CamaroDiagExportStorage.ExportStorageException) reportConnectionLoss(error)
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isJ2534MemoryCapturing = false,
                    j2534MemoryCaptureMessage =
                        localizedContext.getString(R.string.cal_live_memory_failed) +
                            " " + error.message.orEmpty().take(160)
                )
            } finally {
                // A disconnected capture must release its busy flag, without
                // allowing an old operation to clear a new account's capture.
                if (captureGeneration == memoryCaptureGeneration &&
                    isCurrentAccountGeneration(accountGeneration)) {
                    _calibrationWorkspace.update { it.copy(isJ2534MemoryCapturing = false) }
                }
            }
        }
    }

    /** Reads one UDS DID using the selected physical transport and service-data CAN IDs. */
    fun readUdsDid(requestCanIdHex: String, responseCanIdHex: String, didHex: String, protocolNumberText: String) {
        if (!requirePremiumLicense()) return
        launchEnhancedUdsRead { current ->
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
                val data = obdManager.withSelectedUdsReadSession(
                        requestCanId = requestId,
                        responseCanId = responseId,
                        protocolNumber = protocolNumber,
                        timeoutMs = 5000L,
                        readDids = setOf(did)
                    ) { client -> client.readDataByIdentifier(did) }
                if (!current()) return@launchEnhancedUdsRead
                val hex = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                val isVinDid = did == 0xF190
                val rendered = if (isVinDid) {
                    val ascii = data.toString(Charsets.US_ASCII).trim().trim('\u0000')
                    if (ascii.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) "$ascii\n$hex" else hex
                } else hex
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isUdsReading = false,
                    udsDidResponse = rendered,
                    udsMessage = localizedContext.getString(
                        R.string.vm_uds_response_received, responseId.toString(16).uppercase()
                    )
                )
            } catch (cancelled: CancellationException) {
                if (current()) _calibrationWorkspace.value = _calibrationWorkspace.value.copy(isUdsReading = false)
                throw cancelled
            } catch (error: Exception) {
                if (!current()) return@launchEnhancedUdsRead
                reportConnectionLoss(error)
                if (!current()) return@launchEnhancedUdsRead
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isUdsReading = false,
                    udsDidResponse = "",
                    udsMessage = localizedContext.getString(R.string.msg_uds_read_failed)
                )
            }
        }
    }

    /** Reads UDS ReadDTCInformation (0x19/0x02) from the selected physical ECU. */
    fun readUdsDtcs(requestCanIdHex: String, responseCanIdHex: String, protocolNumberText: String) {
        if (!requirePremiumLicense()) return
        launchEnhancedUdsRead { current ->
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
                val report = obdManager.withSelectedUdsReadSession(
                        requestCanId = requestId,
                        responseCanId = responseId,
                        protocolNumber = protocolNumber,
                        timeoutMs = 5000L
                    ) { client -> client.readDtcReport() }
                if (!current()) return@launchEnhancedUdsRead
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
                    localizedContext.getString(R.string.vm_no_dtcs_returned, responseCanIdHex.uppercase())
                } else {
                    report.records.joinToString("\n") { record ->
                        "${record.code}  status=0x${record.statusByte.toString(16).uppercase().padStart(2, '0')}"
                    }
                }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isUdsReading = false,
                    udsDtcResponse = rendered,
                    udsMessage = localizedContext.getString(R.string.vm_uds_read_complete)
                )
            } catch (cancelled: CancellationException) {
                if (current()) _calibrationWorkspace.value = _calibrationWorkspace.value.copy(isUdsReading = false)
                throw cancelled
            } catch (error: Exception) {
                if (!current()) return@launchEnhancedUdsRead
                reportConnectionLoss(error)
                if (!current()) return@launchEnhancedUdsRead
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isUdsReading = false,
                    udsDtcResponse = "",
                    udsMessage = localizedContext.getString(R.string.msg_uds_dtc_read_failed)
                )
            }
        }
    }

    /** Reads only standardized OBD Mode 09 calibration identifiers; this is not a binary map read. */
    /**
     * Export SAE Mode 09 identification only; this is explicitly NOT an ECU-memory
     * backup and never grants any permission to write calibrations.
     */
    fun exportEcuIdentification(fileName: String) {
        if (!requirePremiumLicense()) return
        val accountUid = authenticatedAccountUid ?: return
        val profile = _vehicle.value
        val observation = _calibrationWorkspace.value
        val accountGeneration = accountSessionGeneration
        val connectionGeneration = connectionAttemptGeneration
        val mode09Generation = calibrationIdentityGeneration
        val gmGeneration = gmlanIdentityGeneration
        fun current() = authenticatedAccountUid == accountUid &&
            isCurrentAccountGeneration(accountGeneration) &&
            connectionGeneration == connectionAttemptGeneration &&
            mode09Generation == calibrationIdentityGeneration &&
            gmGeneration == gmlanIdentityGeneration &&
            _vehicle.value == profile && hasPremiumLicense()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!current()) return@launch
                require(observation.canExportIdentification) { "No completed ECU identity can be exported." }
                fun lines(value: String) = JSONArray(value.lines().map(String::trim).filter(String::isNotEmpty))
                val report = JSONObject().apply {
                    put("format", "CamaroDiag-ecu-identity-v1")
                    put("source", if (observation.hasMode09Identification) "SAE-J1979-Mode09-read-only" else "J2534-GMLAN-read-only")
                    observation.gmlanIdentity?.let { identity ->
                        put("gmlanIdentity", JSONObject().apply {
                            put("source", "GMLAN-1A-physical-J2534")
                            put("vin", identity.vin)
                            put("operatingSystemId", identity.operatingSystemId)
                            put("osDid", identity.osDid)
                            put("responseCanId", identity.responseCanId)
                            put("physicalRead", true)
                            put("readOnly", true)
                            put("flashQualified", false)
                        })
                    }
                    put("exportedAtUtcMillis", System.currentTimeMillis())
                    put("vehicleYear", profile.modelYear)
                    put("vehicleEngine", profile.engine.rpo)
                    put("selectedVehicleVin", profile.vin)
                    put("observedEcuVin", observation.ecuVinResponse.trim().takeIf(String::isNotEmpty) ?: JSONObject.NULL)
                    put("ecuNames", lines(observation.ecuNameResponse))
                    put("calibrationIds", lines(observation.calibrationIdResponse))
                    put("calibrationVerificationNumbers", lines(observation.cvnResponse))
                    put("respondingCanAddresses", JSONArray(observation.respondingEcuAddresses))
                    put("observationFromSimulation", observation.ecuIdentityFromSimulation)
                    put(
                        "responderAttribution",
                        Lfx2012ControllerReferences
                            .classifyResponderAttribution(observation.respondingEcuAddresses).name
                    )
                    // Mode 09 aggregated fields do not prove that each identifier
                    // was returned by the same ECU, even with one responder address.
                    put("calibrationIdCvnAttributionVerified", false)
                    put(
                        "observedFingerprintComplete",
                        Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint(
                            Lfx2012ControllerReferences.ObservedIdentity(
                                ecuName = observation.ecuNameResponse.trim().takeIf(String::isNotEmpty),
                                calibrationIds = observation.calibrationIdResponse.lines(),
                                calibrationVerificationNumbers = observation.cvnResponse.lines(),
                                simulated = observation.ecuIdentityFromSimulation
                            )
                        )
                    )
                    put("ecuMemoryBackedUp", false)
                    put("firmwareCompatibilityValidated", false)
                    put("calibrationWritingPermitted", false)
                }
                if (!current()) return@launch
                val savedPath = CamaroDiagExportStorage.write(getApplication<Application>(), fileName, "application/json",
                    category = CamaroDiagExportStorage.Category.REPORTS, beforePublish = { check(current() && hasPremiumLicense()) }
                ) { it.write(report.toString(2).toByteArray(Charsets.UTF_8)) }
                if (current()) {
                    _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                        message = getApplication<Application>()
                            .getString(R.string.flexfuel_lfx2012_export_success) + "\n" + savedPath
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) {
                    _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                        message = getApplication<Application>()
                            .getString(R.string.flexfuel_lfx2012_export_failed)
                    )
                }
            }
        }
    }

    /**
     * C4: one physical, read-only fuel composition/trim survey for the selected
     * 2012 LFX. No changes to ECU calibration, adaptive fuel cells or VIN.
     * The UI never promotes this reading to proof of an installed sensor.
     */
    fun readLfx2012FuelSnapshot() {
        if (!requirePremiumLicense()) return
        val vehicle = _vehicle.value
        if (vehicle.modelYear != 2012 ||
            !vehicle.engine.rpo.equals("LFX", ignoreCase = true)) return
        if (_calibrationWorkspace.value.isFuelSnapshotReading) return
        val account = accountSessionGeneration
        val connection = connectionAttemptGeneration
        val generation = ++fuelSnapshotGeneration
        fun current() = isCurrentAccountGeneration(account) &&
            generation == fuelSnapshotGeneration &&
            connection == connectionAttemptGeneration &&
            _vehicle.value == vehicle && hasPremiumLicense()
        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
            isFuelSnapshotReading = true, fuelReadOnlySnapshot = null,
            fuelSnapshotError = ""
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!current()) return@launch
                check(!obdManager.isDemoMode && !obdManager.isSimulationActive &&
                    obdManager.isStandardObdReadAvailable()) {
                    "A connected physical, read-only OBD channel is required."
                }
                val survey = obdManager.readPhysicalFuelSurvey()
                if (!current()) return@launch
                val raw = Lfx2012FuelReadOnly.PIDS.associateWith { pid ->
                    survey.responses[pid]?.let { response ->
                        // Some adapters return NO DATA or malformed text while
                        // still advertising the PID. Show 'no valid response',
                        // never 0% or a simulated measurement in that case.
                        runCatching {
                            CamaroUdsProtocol.parsePidBytes(
                                response, "%02X".format(java.util.Locale.ROOT, pid),
                                primaryPowertrainResponderOnly = true
                            )
                        }.getOrNull()
                    }
                }
                val captured = Lfx2012FuelReadOnly.inspect(
                    survey, raw, System.currentTimeMillis()
                )
                // Serialize publishing a completed physical read with vehicle/
                // connection changes on the UI dispatcher. Otherwise the VIN
                // could change between the IO-thread guard and history append.
                withContext(Dispatchers.Main) {
                    if (current()) {
                        val validVin = vehicle.vin.trim().uppercase(java.util.Locale.ROOT)
                            .takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) }
                        val historySummary = if (validVin != null) {
                            fuelHistory.append(
                                Lfx2012FuelSessionHistory.SessionIdentity(
                                    validVin, "account-$account-connection-$connection",
                                    captured.source
                                ), captured
                            )
                        } else null
                        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                            isFuelSnapshotReading = false,
                            fuelReadOnlySnapshot = captured,
                            fuelHistoryCount = historySummary?.count ?: 0,
                            fuelHistorySummary = historySummary,
                            fuelSnapshotError = ""
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) {
                    reportConnectionLoss(error)
                    if (current()) _calibrationWorkspace.value =
                        _calibrationWorkspace.value.copy(
                            isFuelSnapshotReading = false,
                            fuelReadOnlySnapshot = null,
                            fuelSnapshotError = error.message.orEmpty().take(180)
                        )
                }
            } finally {
                if (current() && _calibrationWorkspace.value.isFuelSnapshotReading) {
                    _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                        isFuelSnapshotReading = false
                    )
                }
            }
        }
    }

    /**
     * Export only readings captured in the active vehicle/connection context.
     * This uses the same private export store as ECU identity reports.
     */
    fun exportLfx2012FuelHistory(fileName: String) {
        if (!requirePremiumLicense()) return
        val vehicle = _vehicle.value
        val account = accountSessionGeneration
        val connection = connectionAttemptGeneration
        val generation = fuelSnapshotGeneration
        val observed = fuelHistory.copySamples()
        fun current() = isCurrentAccountGeneration(account) &&
            connectionAttemptGeneration == connection &&
            fuelSnapshotGeneration == generation &&
            _vehicle.value == vehicle && hasPremiumLicense() &&
            Lfx2012FuelHistoryExportPolicy.canPublish(
                observed, fuelHistory.copySamples(),
                _calibrationWorkspace.value.isFuelSnapshotReading
            )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!current()) return@launch
                check(observed.isNotEmpty()) { "No fuel observations to export" }
                val expectedVin = vehicle.vin.trim().uppercase(java.util.Locale.ROOT)
                check(observed.all {
                    it.identity.vin == expectedVin &&
                        it.identity.sessionId == "account-$account-connection-$connection"
                }) { "Fuel history session no longer matches the selected vehicle" }
                val path = CamaroDiagExportStorage.write(
                    getApplication<Application>(), fileName, "text/csv",
                    category = CamaroDiagExportStorage.Category.REPORTS,
                    beforePublish = { check(current()) }
                ) { stream ->
                    java.io.OutputStreamWriter(stream, Charsets.UTF_8).apply {
                        Lfx2012FuelHistoryCsv.export(observed, this)
                        flush()
                    }
                }
                if (current()) _calibrationWorkspace.value =
                    _calibrationWorkspace.value.copy(
                        message = getApplication<Application>()
                            .getString(R.string.flexfuel_lfx2012_history_saved) + "\n" + path
                    )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current()) _calibrationWorkspace.value =
                    _calibrationWorkspace.value.copy(
                        message = getApplication<Application>()
                            .getString(R.string.flexfuel_lfx2012_history_failed)
                    )
            }
        }
    }

    /** Keep the legacy GM observation separate from generic OBD Mode 09. */
    fun readGmlanIdentification() {
        if (!requirePremiumLicense()) return
        if (_calibrationWorkspace.value.isGmlanReading) return
        val account = accountSessionGeneration
        val connection = connectionAttemptGeneration
        val vehicle = _vehicle.value
        val generation = ++gmlanIdentityGeneration
        fun current(): Boolean =
            isCurrentAccountGeneration(account) &&
                generation == gmlanIdentityGeneration &&
                connection == connectionAttemptGeneration &&
                _vehicle.value == vehicle &&
                hasPremiumLicense()
        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
            isGmlanReading = true, gmlanIdentity = null, gmlanIdentityFailed = false
        )
        viewModelScope.launch {
            try {
                if (!current()) return@launch
                check(obdManager.isJ2534BridgeConnected())
                val identity = obdManager.readGmlanIdentification()
                if (current() && obdManager.isJ2534BridgeConnected()) {
                    _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                        isGmlanReading = false, gmlanIdentity = identity
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) {
                    reportConnectionLoss(error)
                    if (current()) _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                        isGmlanReading = false, gmlanIdentity = null, gmlanIdentityFailed = true
                    )
                }
            } finally {
                if (current() && _calibrationWorkspace.value.isGmlanReading) {
                    _calibrationWorkspace.value = _calibrationWorkspace.value.copy(isGmlanReading = false)
                }
            }
        }
    }

    fun readCalibrationIdentification() {
        if (!requirePremiumLicense()) return
        val startedGeneration = accountSessionGeneration
        val requestId = ++calibrationIdentityGeneration
        val startedConnection = connectionAttemptGeneration
        viewModelScope.launch {
            if (!isCurrentAccountGeneration(startedGeneration) || startedConnection != connectionAttemptGeneration ||
                requestId != calibrationIdentityGeneration) return@launch
            if (!obdManager.isObdCommandChannelAvailable && !obdManager.isJ2534BridgeConnected()) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isReading = false,
                    ecuVinResponse = "",
                    ecuIdentityFromSimulation = false,
                    vinComparison = null,
                    vinModelYear = null,
                    ecuNameResponse = "",
                    respondingEcuAddresses = emptyList(),
                    calibrationIdResponse = "",
                    cvnResponse = "",
                    message = localizedContext.getString(R.string.vm_no_obd_data_read)
                )
                return@launch
            }
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                isReading = true,
                ecuVinResponse = "",
                ecuIdentityFromSimulation = false,
                vinComparison = null,
                vinModelYear = null,
                ecuNameResponse = "",
                respondingEcuAddresses = emptyList(),
                calibrationIdResponse = "",
                cvnResponse = "",
                message = ""
            )
            try {
                val sourceWasSimulated = obdManager.isSimulationActive || obdManager.isDemoMode
                val identity = obdManager.readCalibrationIdentification()
                if (!isCurrentAccountGeneration(startedGeneration) || startedConnection != connectionAttemptGeneration ||
                requestId != calibrationIdentityGeneration) return@launch
                val ecuVin = identity.vin
                val validCal = identity.calibrationIds.isNotEmpty()
                val validCvn = identity.calibrationVerificationNumbers.isNotEmpty()
                val vinComparison = VehicleVinMatcher.compare(_vehicle.value.vin, ecuVin)
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isReading = false,
                    ecuVinResponse = ecuVin.orEmpty(),
                    ecuIdentityFromSimulation = sourceWasSimulated ||
                        obdManager.isSimulationActive || obdManager.isDemoMode,
                    vinComparison = vinComparison,
                    vinModelYear = VehicleVinMatcher.modelYearFromVin(ecuVin),
                    ecuNameResponse = identity.ecuNames.joinToString("\n"),
                    respondingEcuAddresses = identity.respondingEcuAddresses,
                    calibrationIdResponse = identity.calibrationIds.joinToString("\n"),
                    cvnResponse = identity.calibrationVerificationNumbers.joinToString("\n"),
                    message = localizedContext.getString(
                        if (validCal || validCvn) R.string.msg_cal_identifiers_read
                        else R.string.msg_cal_identifiers_unavailable
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrentAccountGeneration(startedGeneration) || startedConnection != connectionAttemptGeneration ||
                requestId != calibrationIdentityGeneration) return@launch
                reportConnectionLoss(error)
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isReading = false,
                    ecuVinResponse = "",
                    ecuIdentityFromSimulation = false,
                    vinComparison = null,
                    vinModelYear = null,
                    ecuNameResponse = "",
                    respondingEcuAddresses = emptyList(),
                    calibrationIdResponse = "",
                    cvnResponse = "",
                    message = localizedContext.getString(R.string.msg_cal_identifiers_failed)
                )
            }
        }
    }

    /** Imports and analyzes a local Vector ASC or CSV CAN trace without connecting to a vehicle. */
    fun analyzeCanTrace(uri: Uri) {
        if (!requirePremiumLicense()) return
        traceAnalysisJob?.cancel()
        traceAnalysisJob = viewModelScope.launch {
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                isTraceAnalyzing = true,
                canTraceReport = null,
                traceMessage = ""
            )
            try {
                val report = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val displayName = resolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment ?: "can-trace.asc"
                    val trace = resolver.openInputStream(uri)?.use { it.readCanTraceText(32 * 1024 * 1024) }
                        ?: throw CanTraceInputException(R.string.msg_trace_open_failed)
                    CanTraceAnalyzer.analyze(trace, displayName)
                }
                val localizedErrors = report.errors.map(::localizedCanTraceError)
                val localizedReport = report.copy(errors = localizedErrors)
                val summary = if (report.frames.isEmpty()) {
                    localizedContext.getString(R.string.vm_trace_no_frames)
                } else {
                    localizedContext.getString(
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
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                val message = when (error) {
                    is CanTraceInputException -> error.messageRes
                    is java.io.IOException, is SecurityException -> R.string.msg_trace_open_failed
                    else -> R.string.msg_trace_analysis_failed
                }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isTraceAnalyzing = false,
                    canTraceReport = null,
                    traceMessage = localizedContext.getString(message)
                )
            }
        }
    }

    fun availableRawCanProfiles(): Set<Int> = obdManager.availableRawCanProfiles()

    fun canMonitorAvailable(): Boolean = availableRawCanProfiles().isNotEmpty()

    /** Read-only UDS transport candidates; physical target responses remain authoritative. */
    fun canReadEnhancedUds(): Boolean =
        obdManager.isEnhancedUdsReadAvailable

    fun availableUdsReadProfiles(requestHex: String, responseHex: String): Set<Int> {
        val request = requestHex.trim().toIntOrNull(16)
        val response = responseHex.trim().toIntOrNull(16)
        if ((requestHex.isNotBlank() && request == null) ||
            (responseHex.isNotBlank() && response == null)) return emptySet()
        return obdManager.availableUdsReadProfiles(request, response)
    }

    private fun appendCanConsoleSystemEvent(message: String, succeeded: Boolean = true) {
        val current = _liveCanMonitorState.value
        val event = CanConsoleHistoryEntry(
            id = System.nanoTime(),
            direction = "SYSTEM",
            timestampMillis = System.currentTimeMillis(),
            content = message,
            succeeded = succeeded
        )
        recordConsoleEvents(listOf(event))
        _liveCanMonitorState.value = current.copy(
            consoleHistory = (current.consoleHistory + event).takeLast(300)
        )
    }

    /**
     * Converts adapter monitor output into truthful console events. Only validated
     * arbitration-ID/DLC/data records are RX; prompts, status text and malformed
     * records remain SYSTEM entries instead of being presented as ECU traffic.
     */
    private fun parseCanConsoleReceiveEvents(output: String): List<CanConsoleHistoryEntry> =
        CanConsoleRecordParser.parse(output).map { record ->
            CanConsoleHistoryEntry(
                id = System.nanoTime(),
                direction = record.direction,
                timestampMillis = System.currentTimeMillis(),
                content = record.content,
                succeeded = record.succeeded
            )
        }

    private fun retainObservedCanIds(
        known: Set<ObservedCanId>,
        events: List<CanConsoleHistoryEntry>
    ): Set<ObservedCanId> = CanConsoleDisplayFilter.accumulateObserved(
        known,
        events.map { ParsedCanConsoleRecord(it.direction, it.content, it.succeeded) }
    )

    /** Changing the physical network invalidates outstanding raw TX consent and
     * observed-ID attribution, while preserving the global event history.
     */
    fun setCanConsoleBitrate(bitrate: Int) {
        if (bitrate !in setOf(250_000, 500_000, DiagnosticRouteSelector.SW_CAN_BITRATE) ||
            listOf(false, true).none { DiagnosticRouteSelector.rawCanProfile(it, bitrate) in availableRawCanProfiles() }) return
        val state = _liveCanMonitorState.value
        if (state.isDoIpActive || state.isCapturing || state.isSendingRequest || state.bitrate == bitrate ||
            canMonitorJob?.isActive == true || canRequestJob?.isActive == true ||
            detachedCanMonitorJob?.isCompleted == false || detachedCanRequestJob?.isCompleted == false) return
        _liveCanMonitorState.value = state.copy(bitrate = bitrate,
            consoleContextGeneration = ++canConsoleContextGeneration,
            observedCanIds = emptySet(), report = null)
        appendCanConsoleSystemEvent(if (bitrate == DiagnosticRouteSelector.SW_CAN_BITRATE)
            localizedContext.getString(R.string.can_console_swcan_changed)
            else localizedContext.getString(R.string.can_console_bitrate_changed, bitrate / 1000))
    }

    /** Starts passive monitoring until Stop, disconnect, adapter exit or an explicit timeout. */
    fun startCanMonitor(durationMs: Long = Long.MAX_VALUE, can29Bit: Boolean = false) {
        if (!requirePremiumLicense() || _liveCanMonitorState.value.isDoIpActive) return
        if (canMonitorJob != null || detachedCanMonitorJob?.isCompleted == false ||
            canRequestJob?.isCompleted == false || detachedCanRequestJob?.isCompleted == false
        ) return
        if (DiagnosticRouteSelector.rawCanProfile(can29Bit, _liveCanMonitorState.value.bitrate) !in availableRawCanProfiles()) {
            val unavailable = localizedContext.getString(R.string.can_sniffer_unavailable)
            appendCanConsoleSystemEvent(unavailable, succeeded = false)
            _liveCanMonitorState.value = _liveCanMonitorState.value.copy(message = unavailable)
            return
        }
        if (_liveCanMonitorState.value.isSendingRequest) return
        // A cancelled-before-start session must not leave an earlier session's
        // archive exportable as if it belonged to this recording.
        activeCanCaptureFile?.delete()
        activeCanCaptureFile = null
        val previousState = _liveCanMonitorState.value
        val startEvent = CanConsoleHistoryEntry(
            id = System.nanoTime(),
            direction = "SYSTEM",
            timestampMillis = System.currentTimeMillis(),
            content = if (previousState.bitrate == DiagnosticRouteSelector.SW_CAN_BITRATE)
                localizedContext.getString(R.string.can_console_swcan_profile, if (can29Bit) 29 else 11)
                else localizedContext.getString(R.string.can_console_capture_profile, previousState.bitrate / 1000, if (can29Bit) 29 else 11)
        )
        _liveCanMonitorState.value = previousState.copy(
            consoleContextGeneration = ++canConsoleContextGeneration,
            isCapturing = true,
            rawArchiveFailed = false,
            captureArchiveRequired = true,
            rawText = "",
            observedCanIds = emptySet(),
            report = null,
            message = null,
            consoleHistory = (previousState.consoleHistory + startEvent).takeLast(300)
        )
        recordConsoleEvents(listOf(startEvent))
        // Install the job reference before it can execute. A lazy start also lets the
        // finally block compare its identity, so an older cancelled capture cannot clear
        // or overwrite the state of a newer capture started immediately afterward.
        lateinit var monitorJob: Job
        monitorJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val framer = CanConsoleStreamFramer()
            fun framedEvents(batch: CanConsoleStreamFramer.Batch): List<CanConsoleHistoryEntry> {
                val frames = parseCanConsoleReceiveEvents(batch.completeLines)
                if (batch.discardedLines == 0) return frames
                return frames + CanConsoleHistoryEntry(
                    id = System.nanoTime(),
                    direction = "SYSTEM",
                    timestampMillis = System.currentTimeMillis(),
                    content = localizedContext.getString(
                        R.string.can_console_oversized_line, batch.discardedLines
                    ),
                    succeeded = false
                )
            }
            var captureArchive: CanConsoleRawArchiveWriter? = null
            try {
                // A quickly cancelled or superseded session must not create or claim a new archive.
                if (canMonitorJob !== monitorJob || !currentCoroutineContext().isActive) return@launch
                val archive = try {
                    File.createTempFile("camarodiag-can-", ".log", getApplication<Application>().cacheDir)
                } catch (error: java.io.IOException) {
                    throw CanCaptureArchiveWriteException(error)
                }
                val previousArchive = activeCanCaptureFile
                activeCanCaptureFile = archive
                previousArchive?.delete()
                val archiveStream = try {
                    CanConsoleRawArchiveWriter(archive.bufferedWriter(Charsets.US_ASCII))
                } catch (error: java.io.IOException) {
                    throw CanCaptureArchiveWriteException(error)
                }
                captureArchive = archiveStream
                val captured = archiveStream.use { writer ->
                    obdManager.captureCanMonitor(durationMs, can29Bit, previousState.bitrate) { chunk ->
                    if (canMonitorJob === monitorJob) {
                        writer.append(chunk)
                        // Keep the raw stream unchanged, but decode only complete lines:
                        // Bluetooth/USB reads may split a CAN record at any character.
                        val current = _liveCanMonitorState.value
                        val raw = (current.rawText + chunk).takeLast(131_072)
                        // Parse complete lines before enforcing the incomplete-line limit.
                        // The raw archive always receives the original unmodified chunk.
                        val receiveEvents = framedEvents(framer.append(chunk))
                        recordConsoleEvents(receiveEvents)
                        val history = (current.consoleHistory + receiveEvents).takeLast(300)
                        _liveCanMonitorState.value = current.copy(
                            rawText = raw, consoleHistory = history,
                            observedCanIds = retainObservedCanIds(current.observedCanIds, receiveEvents)
                        )
                    }
                    }
                }
                // Publication of a complete CAN capture requires successful
                // final flush and close, not merely a returned transport string.
                check(archiveStream.isComplete) {
                    "CAN capture archive did not finish writing successfully"
                }
                if (canMonitorJob === monitorJob) {
                    val raw = captured.takeLast(131_072)
                    val currentBeforeReport = _liveCanMonitorState.value
                    val trailingEvents = framedEvents(framer.drainTrailing())
                    recordConsoleEvents(trailingEvents)
                    val historyWithTrailing =
                        (currentBeforeReport.consoleHistory + trailingEvents).takeLast(300)
                    val report = CanTraceAnalyzer.analyze(
                        raw.replace("\r\n", "\n").replace('\r', '\n'),
                        "live-can-monitor.txt"
                    )
                    val current = _liveCanMonitorState.value.copy(
                        consoleHistory = historyWithTrailing.takeLast(300),
                        observedCanIds = retainObservedCanIds(
                            currentBeforeReport.observedCanIds, trailingEvents
                        )
                    )
                    val completeEvent = CanConsoleHistoryEntry(
                        id = System.nanoTime(),
                        direction = "SYSTEM",
                        timestampMillis = System.currentTimeMillis(),
                        content = localizedContext.getString(R.string.can_sniffer_capture_complete)
                    )
                    recordConsoleEvents(listOf(completeEvent))
                    _liveCanMonitorState.value = current.copy(
                        isCapturing = false,
                        rawText = raw,
                        report = report,
                        message = completeEvent.content,
                        consoleHistory = (current.consoleHistory + completeEvent).takeLast(300)
                    )
                }
            } catch (cancelled: CancellationException) {
                if (canMonitorJob === monitorJob) {
                    val current = _liveCanMonitorState.value
                    // Raw chunks were archived before parsing. Drain only the
                    // unframed final line; do not append it to rawText a second time.
                    val raw = current.rawText.takeLast(131_072)
                    val trailingEvents = framedEvents(framer.drainTrailing())
                    val archiveFailed = CanConsoleExportPolicy.archiveFailedAfterStop(
                        current.rawArchiveFailed,
                        captureArchive?.isComplete == true
                    )
                    val stoppedEvent = CanConsoleHistoryEntry(
                        id = System.nanoTime(),
                        direction = "SYSTEM",
                        timestampMillis = System.currentTimeMillis(),
                        content = localizedContext.getString(
                            if (archiveFailed) R.string.can_sniffer_capture_failed
                            else R.string.can_sniffer_stop
                        ),
                        succeeded = !archiveFailed
                    )
                    recordConsoleEvents(trailingEvents + stoppedEvent)
                    val report = runCatching {
                        CanTraceAnalyzer.analyze(
                            raw.replace("\r\n", "\n").replace('\r', '\n'),
                            "live-can-monitor.txt"
                        )
                    }.getOrNull()
                    _liveCanMonitorState.value = current.copy(
                        isCapturing = false,
                        rawText = raw,
                        report = report,
                        message = stoppedEvent.content,
                        rawArchiveFailed = archiveFailed,
                        consoleHistory = (current.consoleHistory + trailingEvents + stoppedEvent).takeLast(300),
                        observedCanIds = retainObservedCanIds(current.observedCanIds, trailingEvents)
                    )
                }
                throw cancelled
            } catch (error: Exception) {
                reportConnectionLoss(error)
                if (canMonitorJob === monitorJob) {
                    val current = _liveCanMonitorState.value
                    // Raw chunks were archived before parsing. Drain only the
                    // unframed final line; do not append it to rawText a second time.
                    val raw = current.rawText.takeLast(131_072)
                    val trailingEvents = framedEvents(framer.drainTrailing())
                    val failureMessage = localizedContext.getString(R.string.can_sniffer_capture_failed)
                    val failureEvent = CanConsoleHistoryEntry(
                        id = System.nanoTime(),
                        direction = "SYSTEM",
                        timestampMillis = System.currentTimeMillis(),
                        content = failureMessage,
                        succeeded = false
                    )
                    recordConsoleEvents(trailingEvents + failureEvent)
                    _liveCanMonitorState.value = current.copy(
                        isCapturing = false,
                        rawText = raw,
                        message = failureMessage,
                        // Any abnormal capture termination leaves the physical stream incomplete.
                        // Keep partial RX visible for diagnostics, but never advertise
                        // its archive as a completed physical CAN acquisition.
                        rawArchiveFailed = true,
                        consoleHistory = (current.consoleHistory + trailingEvents + failureEvent).takeLast(300),
                        observedCanIds = retainObservedCanIds(current.observedCanIds, trailingEvents)
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
    // Detached jobs no longer own the console UI, but their transport cleanup
    // must finish before a new adapter or CAN command can use the channel.
    private var detachedCanMonitorJob: Job? = null
    private var detachedCanRequestJob: Job? = null

    private fun discardCanMonitor(deleteArchive: Boolean = true) {
        // Vehicle/VIN changes delete old evidence; link disconnects retain it.
        if (deleteArchive) {
            activeCanCaptureFile?.delete()
            activeCanCaptureFile = null
        }
        val activeJob = canMonitorJob ?: return
        canMonitorJob = null
        detachedCanMonitorJob = activeJob
        activeJob.cancel()
    }

    /** Detaches and cancels a manual console request so its result cannot leak into a new vehicle context. */
    private fun detachCanRequest(): Job? {
        val activeJob = canRequestJob ?: return null
        canRequestJob = null
        detachedCanRequestJob = activeJob
        activeJob.cancel()
        return activeJob
    }

    /** Clears the single chronological console history and its raw capture buffer. */
    fun clearCanConsoleHistory() {
        val current = _liveCanMonitorState.value
        // Keep an in-flight TX paired with its eventual RX/SYSTEM result.
        // The UI disables Clear while sending; this guard protects other callers too.
        if (current.isDoIpActive || current.isSendingRequest || current.isCapturing) return
        activeCanCaptureFile?.delete()
        activeCanCaptureFile = null
        resetConsoleEventJournal()
        _liveCanMonitorState.value = current.copy(
            consoleContextGeneration = ++canConsoleContextGeneration,
            rawText = "",
            report = null,
            consoleHistory = emptyList(),
            observedCanIds = emptySet(),
            rawArchiveFailed = false,
            captureArchiveRequired = false
        )
    }

    /** Cancels monitor capture; the transport sends ESC in a non-cancellable cleanup block. */
    fun stopCanMonitor() {
        val activeJob = canMonitorJob ?: return
        activeJob.cancel()
        // Leave capture marked active until ESC cleanup and the file writer have closed.
        // If the lazy job was cancelled before entering its body, no finally block runs:
        // the join fallback clears that case without enabling export too early.
        viewModelScope.launch {
            activeJob.join()
            if (canMonitorJob === activeJob) {
                // A LAZY coroutine cancelled before its first instruction never
                // executes its use/finally cleanup or appends a Stop event.
                // Treat that case as an aborted capture, not a successful one.
                canMonitorJob = null
                val current = _liveCanMonitorState.value
                val failureMessage = localizedContext.getString(R.string.can_sniffer_capture_failed)
                val event = CanConsoleHistoryEntry(
                    id = System.nanoTime(),
                    direction = "SYSTEM",
                    timestampMillis = System.currentTimeMillis(),
                    content = failureMessage,
                    succeeded = false
                )
                recordConsoleEvents(listOf(event))
                _liveCanMonitorState.value = current.copy(
                    isCapturing = false,
                    rawArchiveFailed = CanConsoleExportPolicy.archiveFailedAfterStop(
                        current.rawArchiveFailed, writerCompleted = false
                    ),
                    message = failureMessage,
                    consoleHistory = (current.consoleHistory + event).takeLast(300)
                )
            }
        }
        // Do not detach: a later request must not overtake the adapter cleanup.
    }

    /** Standard OBD read availability is distinct from raw CAN monitor/transmit support. */
    fun canConsoleReadObd(): Boolean = obdManager.isStandardObdReadAvailable()

    /**
     * Public console input accepts read-only OBD directly. A raw CAN frame is
     * never transmitted here: it must pass through the session-bound explicit
     * confirmation entry point, even if another UI caller invokes this method.
     */
    fun sendCanConsoleInput(input: String) {
        if (!requirePremiumLicense() || _liveCanMonitorState.value.isDoIpActive) return
        if (RawCanFrameParser.parse(input) != null) {
            val message = localizedContext.getString(R.string.can_console_raw_tx_confirm_title)
            appendCanConsoleSystemEvent(message, succeeded = false)
            _liveCanMonitorState.value = _liveCanMonitorState.value.copy(message = message)
            return
        }
        sendSafeObdRequest(input)
    }

    /**
     * A confirmation dialog must not authorize TX on a replacement adapter or
     * another vehicle. Recheck the live generation when the user confirms.
     */
    fun confirmRawCanConsoleFrame(
        input: String,
        expectedContextGeneration: Long,
        approval: EcuWriteConsentFlow.Approval
    ) {
        if (!requirePremiumLicense()) return
        val state = _liveCanMonitorState.value
        val frame = RawCanFrameParser.parse(input)
        if (!CanConsoleCapabilityPolicy.canConfirmPhysicalTx(
                frame = frame,
                pendingContextGeneration = expectedContextGeneration,
                currentContextGeneration = state.consoleContextGeneration,
                monitorAvailable = frame != null && state.bitrate in setOf(250_000, 500_000) &&
                    DiagnosticRouteSelector.rawCanProfile(frame.isExtended, state.bitrate) in availableRawCanProfiles(),
                canTransmit = _adapter.value.supportsCanTransmit,
                busy = state.isDoIpActive || state.isCapturing || state.isSendingRequest
            )) return
        sendRawCanConsoleFrame(input, requireNotNull(frame), expectedContextGeneration, approval)
    }

    private fun sendRawCanConsoleFrame(
        input: String,
        frame: com.camarodiag.gen5.data.obd.RawCanFrame,
        confirmedConsoleGeneration: Long,
        approval: EcuWriteConsentFlow.Approval
    ) {
        if (!SafeRawCanTransmitPolicy.allows(frame)) {
            val message = localizedContext.getString(R.string.can_console_unsafe_can_payload)
            appendCanConsoleSystemEvent(message, succeeded = false)
            _liveCanMonitorState.value = _liveCanMonitorState.value.copy(message = message)
            return
        }
        val currentState = _liveCanMonitorState.value
        if (currentState.isSendingRequest || canRequestJob?.isActive == true ||
            detachedCanRequestJob?.isActive == true
        ) return
        // Raw CAN transmission requires a verified header-capable adapter, not
        // merely an open OBD command channel (which may support OBD only).
        if (!obdManager.isObdCommandChannelAvailable || !_adapter.value.supportsCanHeaders ||
            currentState.isCapturing || canMonitorJob?.isActive == true ||
            detachedCanMonitorJob?.isActive == true
        ) {
            val message = localizedContext.getString(R.string.can_sniffer_unavailable)
            appendCanConsoleSystemEvent(message, succeeded = false)
            _liveCanMonitorState.value = _liveCanMonitorState.value.copy(message = message)
            return
        }
        val confirmedAccountGeneration = accountSessionGeneration
        val confirmedAdapterGeneration = connectionAttemptGeneration
        val confirmedVehicle = _vehicle.value
        val txEvent = CanConsoleHistoryEntry(
            id = System.nanoTime(),
            direction = "TX",
            timestampMillis = System.currentTimeMillis(),
            content = input.trim(),
            succeeded = false // Pending until the adapter operation completes.
        )
        _liveCanMonitorState.value = currentState.copy(
            isSendingRequest = true,
            message = null,
            consoleHistory = (currentState.consoleHistory + txEvent).takeLast(300)
        )
        recordConsoleEvents(listOf(txEvent))
        lateinit var requestJob: Job
        requestJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val rawRxStream = CanConsoleRawRxStream()
            var rxStreamFinished = false
            fun persistResponseBatch(batch: CanConsoleRawRxStream.Batch) {
                if (canRequestJob !== requestJob ||
                    confirmedConsoleGeneration != _liveCanMonitorState.value.consoleContextGeneration ||
                    !isCurrentAccountGeneration(confirmedAccountGeneration) ||
                    confirmedAdapterGeneration != connectionAttemptGeneration ||
                    confirmedVehicle != _vehicle.value
                ) return
                val records = batch.records.map { received ->
                    CanConsoleHistoryEntry(
                        id = System.nanoTime(),
                        direction = received.direction,
                        timestampMillis = batch.receivedAtMillis,
                        content = received.content,
                        succeeded = received.succeeded
                    )
                }
                val discarded = if (batch.discardedOversizedLines > 0) {
                    listOf(CanConsoleHistoryEntry(
                        id = System.nanoTime(),
                        direction = "SYSTEM",
                        timestampMillis = batch.receivedAtMillis,
                        content = localizedContext.getString(
                            R.string.can_console_oversized_line, batch.discardedOversizedLines
                        ),
                        succeeded = false
                    ))
                } else emptyList()
                // Persist each physical RX batch, not just the diagnostic
                // matcher’s recent 300-record UI window. The callback runs
                // on IO and keeps constant memory during long ECU replies.
                recordConsoleEvents(records + discarded)
            }
            fun drainResponseStream() {
                if (!rxStreamFinished) {
                    rxStreamFinished = true
                    persistResponseBatch(rawRxStream.finish())
                }
            }
            try {
                val transmission = obdManager.sendRawCanFrameWithStatus(
                    frame, approval,
                    bitrate = currentState.bitrate,
                    onPhysicalResponseChunk = { chunk, receivedAtMillis ->
                        persistResponseBatch(rawRxStream.append(chunk, receivedAtMillis))
                    }
                ) {
                    isCurrentAccountGeneration(confirmedAccountGeneration) &&
                        confirmedAdapterGeneration == connectionAttemptGeneration &&
                        confirmedVehicle == _vehicle.value &&
                        confirmedConsoleGeneration == _liveCanMonitorState.value.consoleContextGeneration &&
                        currentState.bitrate == _liveCanMonitorState.value.bitrate
                }
                drainResponseStream()
                val response = transmission.adapterOutput
                if (canRequestJob === requestJob) {
                    val current = _liveCanMonitorState.value
                    val diagnosticStatus = transmission.diagnosticStatus
                    val streamedRecords = transmission.receivedRecords
                    // Only frames observed through the physical receive callback
                    // have a trustworthy RX provenance and reception timestamp.
                    // Adapter output alone can contain echoes, prompts or truncated
                    // text; never reconstruct ECU RX from that fallback string.
                    val receivedEvents = streamedRecords.map { timed ->
                        CanConsoleHistoryEntry(
                            id = System.nanoTime(),
                            direction = timed.record.direction,
                            timestampMillis = timed.timestampMillis,
                            content = timed.record.content,
                            succeeded = timed.record.succeeded
                        )
                    }
                    val displayedReceivedEvents = receivedEvents.ifEmpty {
                        listOf(CanConsoleHistoryEntry(
                            id = System.nanoTime(),
                            direction = "SYSTEM",
                            timestampMillis = System.currentTimeMillis(),
                            content = localizedContext.getString(
                                R.string.can_console_adapter_ack,
                                response.ifBlank {
                                    localizedContext.getString(R.string.can_console_no_adapter_text)
                                }
                            )
                        ))
                    }
                    // Live RX was already written in full through the
                    // transport callback; only a fallback acknowledgement
                    // needs to be appended when no physical record arrived.
                    if (rawRxStream.recordCount == 0L)
                        recordConsoleEvents(displayedReceivedEvents)
                    _liveCanMonitorState.value = current.copy(
                        isSendingRequest = false,
                        consoleHistory = (
                            current.consoleHistory.map { if (it.id == txEvent.id) it.copy(succeeded = true) else it } +
                                displayedReceivedEvents
                        ).takeLast(300),
                        observedCanIds = retainObservedCanIds(current.observedCanIds, displayedReceivedEvents),
                        message = localizedContext.getString(when (diagnosticStatus) {
                            CanConsoleDiagnosticStatus.POSITIVE -> R.string.can_sniffer_request_complete
                            CanConsoleDiagnosticStatus.NEGATIVE -> R.string.can_console_negative_ecu_response
                            CanConsoleDiagnosticStatus.NONE -> R.string.can_console_tx_ack_only
                        })
                    )
                }
            } catch (cancelled: CancellationException) {
                drainResponseStream()
                if (canRequestJob === requestJob) {
                    val current = _liveCanMonitorState.value
                    val receivedEvents = (cancelled as? CanConsolePhysicalExchangeCancelled)
                        ?.receivedRecords.orEmpty().map { timed ->
                            CanConsoleHistoryEntry(
                                id = System.nanoTime(),
                                direction = timed.record.direction,
                                timestampMillis = timed.timestampMillis,
                                content = timed.record.content,
                                succeeded = timed.record.succeeded
                            )
                        }
                    if (rawRxStream.recordCount == 0L) recordConsoleEvents(receivedEvents)
                    // A cancelled TX stays pending/unsuccessful. Old vehicle jobs
                    // cannot reach this branch after detachCanRequest().
                    _liveCanMonitorState.value = current.copy(
                        isSendingRequest = false,
                        consoleHistory = (current.consoleHistory + receivedEvents).takeLast(300),
                        observedCanIds = retainObservedCanIds(current.observedCanIds, receivedEvents)
                    )
                }
                throw cancelled
            } catch (error: Exception) {
                drainResponseStream()
                if (canRequestJob === requestJob) {
                    reportConnectionLoss(error)
                    val current = _liveCanMonitorState.value
                    val receivedEvents = (error as? CanConsolePhysicalExchangeException)
                        ?.receivedRecords.orEmpty().map { timed ->
                            CanConsoleHistoryEntry(
                                id = System.nanoTime(),
                                direction = timed.record.direction,
                                timestampMillis = timed.timestampMillis,
                                content = timed.record.content,
                                succeeded = timed.record.succeeded
                            )
                        }
                    val errorMessage = localizedContext.getString(
                        if ((error as? CanConsolePhysicalExchangeException)?.isResponseTimeout() == true ||
                            error is java.net.SocketTimeoutException
                        ) R.string.can_console_response_timeout
                        else R.string.can_sniffer_request_failed
                    )
                    val errorEvent = CanConsoleHistoryEntry(
                        id = System.nanoTime(),
                        direction = "SYSTEM",
                        timestampMillis = System.currentTimeMillis(),
                        content = errorMessage,
                        succeeded = false
                    )
                    // Only the error outcome is new: physical RX was
                    // already persisted before this late transport failure.
                    recordConsoleEvents(
                        (if (rawRxStream.recordCount == 0L) receivedEvents else emptyList()) +
                            errorEvent
                    )
                    _liveCanMonitorState.value = current.copy(
                        isSendingRequest = false,
                        consoleHistory = (
                            current.consoleHistory.map {
                                if (it.id == txEvent.id) it.copy(succeeded = false) else it
                            } + receivedEvents + errorEvent
                        ).takeLast(300),
                        observedCanIds = retainObservedCanIds(current.observedCanIds, receivedEvents),
                        message = errorMessage
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
        if (_liveCanMonitorState.value.isDoIpActive) return
        val request = SafeObdRequestPolicy.parse(input)
        if (request == null) {
            val message = localizedContext.getString(R.string.can_sniffer_invalid_request)
            // Invalid input is still part of the operator's console session: retain it
            // as a failed SYSTEM event rather than silently dropping it from the history.
            appendCanConsoleSystemEvent("$message — ${input.trim().take(120)}", succeeded = false)
            _liveCanMonitorState.value = _liveCanMonitorState.value.copy(message = message)
            return
        }
        val currentState = _liveCanMonitorState.value
        if (currentState.isSendingRequest || canRequestJob?.isActive == true ||
            detachedCanRequestJob?.isActive == true
        ) return
        if (!obdManager.isStandardObdReadAvailable() ||
            currentState.isCapturing ||
            canMonitorJob?.isActive == true ||
            detachedCanMonitorJob?.isActive == true
        ) {
            val message = localizedContext.getString(R.string.can_sniffer_unavailable)
            appendCanConsoleSystemEvent(message, succeeded = false)
            _liveCanMonitorState.value = _liveCanMonitorState.value.copy(message = message)
            return
        }
        // Claim the request slot synchronously before dispatching to IO. A lazy job lets
        // us install its identity before it can complete, preventing duplicate sends and
        // stale results from a request cancelled during vehicle/adapter changes.
        val txEvent = CanConsoleHistoryEntry(
            id = System.nanoTime(),
            direction = "TX",
            timestampMillis = System.currentTimeMillis(),
            content = request.elmCommand,
            succeeded = false // Pending until a response is received.
        )
        _liveCanMonitorState.value = currentState.copy(
            isSendingRequest = true,
            message = null,
            consoleHistory = (currentState.consoleHistory + txEvent).takeLast(300)
        )
        recordConsoleEvents(listOf(txEvent))
        lateinit var requestJob: Job
        requestJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val response = obdManager.sendReadOnlyConsoleCommand(request)
                if (canRequestJob === requestJob) {
                    val current = _liveCanMonitorState.value
                    val kind = SafeObdRequestPolicy.classifyResponse(request, response)
                    val hasEcuResponse = kind == SafeObdRequestPolicy.ReadResponseKind.POSITIVE_ECU
                    // An ECU negative response proves the TX was delivered,
                    // although the requested diagnostic read was refused.
                    val isEcuTraffic = kind != SafeObdRequestPolicy.ReadResponseKind.UNMATCHED_OR_ADAPTER
                    val responseEvent = CanConsoleHistoryEntry(
                        id = System.nanoTime(),
                        direction = if (isEcuTraffic) "RX" else "SYSTEM",
                        timestampMillis = System.currentTimeMillis(),
                        content = if (isEcuTraffic) {
                            response
                        } else {
                            localizedContext.getString(
                                R.string.can_console_adapter_ack,
                                response.ifBlank { localizedContext.getString(R.string.can_console_no_adapter_text) }
                            )
                        },
                        succeeded = hasEcuResponse
                    )
                    recordConsoleEvents(listOf(responseEvent))
                    _liveCanMonitorState.value = current.copy(
                        isSendingRequest = false,
                        consoleHistory = (
                            current.consoleHistory.map { if (it.id == txEvent.id) it.copy(succeeded = isEcuTraffic) else it } +
                                responseEvent
                        ).takeLast(300),
                        message = localizedContext.getString(
                            if (hasEcuResponse) R.string.can_sniffer_request_complete
                            else R.string.can_sniffer_request_failed
                        )
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
                        content = localizedContext.getString(R.string.can_sniffer_request_failed),
                        succeeded = false
                    )
                    recordConsoleEvents(listOf(errorEvent))
                    _liveCanMonitorState.value = current.copy(
                        isSendingRequest = false,
                        consoleHistory = (current.consoleHistory.map { if (it.id == txEvent.id) it.copy(succeeded = false) else it } + errorEvent).takeLast(300),
                        message = localizedContext.getString(R.string.can_sniffer_request_failed)
                    )
                }
            } finally {
                if (canRequestJob === requestJob) canRequestJob = null
            }
        }
        canRequestJob = requestJob
        requestJob.start()
    }

    /** The console button and the export worker must enforce identical archive rules. */
    fun canExportCanCapture(): Boolean {
        val state = _liveCanMonitorState.value
        val archive = activeCanCaptureFile?.takeIf { it.isFile }
        return !state.isDoIpActive && !consoleEventJournalFailed && !consoleEventJournal.hasFailed() &&
            (state.consoleHistory.isEmpty() || consoleEventJournal.isAvailable()) &&
            CanConsoleExportPolicy.canExport(
            isCapturing = state.isCapturing,
            archiveFailed = state.rawArchiveFailed,
            hasHistory = state.consoleHistory.isNotEmpty(),
            hasRawData = state.rawText.isNotBlank() || (archive?.length() ?: 0L) > 0L,
            isSendingRequest = state.isSendingRequest,
            requiresRawArchive = state.captureArchiveRequired,
            hasRawArchive = archive != null
        )
    }

    /** Export the latest raw CAN capture without transforming adapter output. */
    fun exportCanCapture(fileName: String) {
        if (!requirePremiumLicense()) return
        val owner = authenticatedAccountUid ?: return
        val exportGeneration = accountSessionGeneration
        val profile = _vehicle.value
        val snapshot = _liveCanMonitorState.value
        val history = snapshot.consoleHistory.toList()
        val selectedArchive = activeCanCaptureFile
        // The UI must never flush and SHA-256 hash a multi-hour event archive.
        // Capture only a cheap journal revision; the worker reads and hashes
        // the actual bytes on Dispatchers.IO before publishing the export.
        val journalRevisionAtClick = consoleEventJournal.revision()
        var expectedJournalSnapshot: CanConsoleEventJournalStore.Snapshot? = null
        var expectedRawArchiveSnapshot: CanConsoleRawArchiveIntegrity.Snapshot? = null
        // Do not publish an old snapshot after Clear, a new recording, or a
        // new physical TX has changed the active console since export started.
        fun sameConsoleContext() = canResumeExport(exportGeneration, owner) &&
            _vehicle.value == profile && hasPremiumLicense() &&
            activeCanCaptureFile == selectedArchive &&
            _liveCanMonitorState.value.consoleContextGeneration == snapshot.consoleContextGeneration
        fun current() = sameConsoleContext() &&
            (expectedRawArchiveSnapshot?.let { CanConsoleRawArchiveIntegrity.isCurrent(it) } != false) &&
            !consoleEventJournalFailed && !consoleEventJournal.hasFailed() &&
            consoleEventJournal.revision() == journalRevisionAtClick &&
            (expectedJournalSnapshot?.let { consoleEventJournal.isCurrent(it) } != false) &&
            !_liveCanMonitorState.value.isDoIpActive &&
            !_liveCanMonitorState.value.isCapturing &&
            !_liveCanMonitorState.value.isSendingRequest &&
            !_liveCanMonitorState.value.rawArchiveFailed &&
            _liveCanMonitorState.value.captureArchiveRequired == snapshot.captureArchiveRequired &&
            _liveCanMonitorState.value.consoleContextGeneration == snapshot.consoleContextGeneration &&
            _liveCanMonitorState.value.consoleHistory == history &&
            _liveCanMonitorState.value.rawText == snapshot.rawText
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!current()) return@launch
                val journalSnapshot = consoleEventJournal.snapshot()
                expectedJournalSnapshot = journalSnapshot
                check(current()) { "Console event journal changed before export" }
                val archive = selectedArchive?.takeIf { it.isFile }
                check(selectedArchive == null || archive != null) {
                    "The selected complete CAN raw archive disappeared before export"
                }
                check(history.isEmpty() || journalSnapshot != null) {
                    "Complete console event journal is unavailable"
                }
                check(CanConsoleExportPolicy.canExport(
                    isCapturing = snapshot.isCapturing,
                    archiveFailed = snapshot.rawArchiveFailed,
                    hasHistory = history.isNotEmpty(),
                    hasRawData = snapshot.rawText.isNotBlank() || (archive?.length() ?: 0L) > 0L,
                    isSendingRequest = snapshot.isSendingRequest,
                    requiresRawArchive = snapshot.captureArchiveRequired,
                    hasRawArchive = archive != null
                )) {
                    "CAN capture export is not available."
                }
                // The raw capture must have the same exact bytes at export
                // completion as it had at the start. This hashes on IO only;
                // no entire CAN archive is loaded into RAM.
                expectedRawArchiveSnapshot = archive?.let {
                    CanConsoleRawArchiveIntegrity.capture(it)
                }
                check(current()) { "CAN raw archive changed before export" }
                val outputText = buildString {
                    appendLine(localizedContext.getString(R.string.can_console_export_header))
                    appendLine(localizedContext.getString(
                        if (journalSnapshot != null) R.string.can_console_export_events_full
                        else R.string.can_console_export_events_limited
                    ))
                    appendLine(localizedContext.getString(R.string.can_console_export_direction_legend))
                }
                val savedPath = CamaroDiagExportStorage.write(getApplication<Application>(), fileName, "text/plain",
                    category = CamaroDiagExportStorage.Category.CONSOLE, beforePublish = { check(current()) }
                ) { sink ->
                    sink.write(outputText.toByteArray(Charsets.UTF_8))
                    if (journalSnapshot != null) {
                        journalSnapshot.file.inputStream().use { source -> source.copyTo(sink) }
                    }
                    if (archive != null || snapshot.rawText.isNotBlank()) {
                        val rawHeader = "\n" + localizedContext.getString(
                            if (archive != null) R.string.can_console_export_full_raw_rx
                            else R.string.can_console_export_recent_raw_rx
                        ) + "\n"
                        sink.write(rawHeader.toByteArray(Charsets.UTF_8))
                    }
                    if (archive != null) {
                        archive.inputStream().use { source -> source.copyTo(sink) }
                    } else if (snapshot.rawText.isNotBlank()) {
                        sink.write(snapshot.rawText.toByteArray(Charsets.UTF_8))
                    }
                }
                if (current()) _liveCanMonitorState.update {
                    it.copy(message = localizedContext.getString(R.string.can_sniffer_capture_complete) + "\n" + savedPath)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A corrupted journal or changed raw archive intentionally
                // makes current() false. Still tell the user that this export
                // failed, but never overwrite a new account/vehicle/session.
                if (sameConsoleContext() &&
                    !_liveCanMonitorState.value.isCapturing &&
                    !_liveCanMonitorState.value.isSendingRequest
                ) _liveCanMonitorState.update {
                    it.copy(message = localizedContext.getString(R.string.can_sniffer_capture_failed))
                }
            }
        }
    }

    private fun localizedCanTraceError(message: String): String {
        val app = localizedContext
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
        return localizedContext.getString(resource)
    }

    private fun localizedInspectionSummary(inspection: CalibrationFileInspection, fileName: String): String {
        val context = localizedContext
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
        return localizedContext.getString(resource)
    }

    private fun localizedInspectionWarnings(inspection: CalibrationFileInspection): List<String> {
        val context = localizedContext
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

    fun verifyEcuBackup(uri: Uri) {
        if (!requirePremiumLicense()) return
        if (_calibrationWorkspace.value.archiveVerification == ArchiveVerification.Reading) return
        val generation = accountSessionGeneration
        _calibrationWorkspace.update { it.copy(archiveVerification = ArchiveVerification.Reading) }
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val stream = requireNotNull(resolver.openInputStream(uri))
                    // The mobile importer keeps both reads in memory. The limit includes the manifest.
                    val manifest = stream.use {
                        EcuBackupArchive.import(
                            it, EcuBackupArchive.MAX_J2534_IMPORT_UNCOMPRESSED_BYTES
                        ).manifest
                    }
                    ArchiveVerification.Verified(manifest)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ArchiveVerification.Failed
            }
            if (generation == accountSessionGeneration) {
                _calibrationWorkspace.update { it.copy(archiveVerification = result) }
            }
        }
    }

    @Volatile private var calibrationFileImportGeneration = 0L

    fun importCalibrationFile(uri: Uri) {
        if (!requirePremiumLicense()) return
        val owner = authenticatedAccountUid ?: return
        val accountGeneration = accountSessionGeneration
        val profile = _vehicle.value
        val importGeneration = ++calibrationFileImportGeneration
        fun current() = canResumeExport(accountGeneration, owner) &&
            _vehicle.value == profile && importGeneration == calibrationFileImportGeneration &&
            hasPremiumLicense()
        viewModelScope.launch(Dispatchers.IO) {
            var staged: File? = null
            try {
                if (!current()) return@launch
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
                        currentCoroutineContext().ensureActive()
                        check(current())
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
                val storedFile = File.createTempFile("original_", "_$safeName", storageDir)
                staged = storedFile
                storedFile.writeBytes(bytes)
                val preview = bytes.take(64).joinToString(" ") { "%02X".format(it) }
                withContext(Dispatchers.Main) {
                    if (!current()) return@withContext
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
                    inspectionAddressRanges = inspection.addressRanges.map { localizedContext.getString(R.string.cal_address_range, it.start.toString(16).uppercase(), it.endInclusive.toString(16).uppercase(), it.length) },
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
                    partialWriteSummary = null,
                    partialWriteChangedBytes = null,
                    partialWriteSectorAddresses = emptyList(),
                    message = localizedContext.getString(R.string.msg_cal_file_imported)
                )
                    staged = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) _calibrationWorkspace.update {
                    it.copy(message = localizedContext.getString(R.string.msg_cal_file_import_failed))
                }
            } finally {
                staged?.let { runCatching { it.delete() } }
            }
        }
    }

    /** Imports a second raw BIN image and compares it byte-for-byte with the loaded reference. */
    fun importComparisonCalibrationFile(uri: Uri) {
        if (!requirePremiumLicense()) return
        val owner = authenticatedAccountUid ?: return
        val accountGeneration = accountSessionGeneration
        val profile = _vehicle.value
        val snapshot = _calibrationWorkspace.value
        val importGeneration = ++calibrationFileImportGeneration
        fun current() = canResumeExport(accountGeneration, owner) &&
            _vehicle.value == profile && importGeneration == calibrationFileImportGeneration &&
            hasPremiumLicense() && _calibrationWorkspace.value.importedFilePath == snapshot.importedFilePath &&
            _calibrationWorkspace.value.activeFilePath == snapshot.activeFilePath
        viewModelScope.launch(Dispatchers.IO) {
            var staged: File? = null
            try {
                if (!current()) return@launch
                require(snapshot.importedFilePath != null && snapshot.inspectionFormat == "RAW_BINARY") {
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
                        currentCoroutineContext().ensureActive()
                        check(current())
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
                val reference = File(snapshot.activeFilePath ?: snapshot.importedFilePath)
                check(reference.isFile && reference.exists()) { "Le fichier BIN de référence est introuvable." }
                val referenceBytes = reference.readBytes()
                val comparison = CalibrationBinaryComparer.compare(referenceBytes, bytes)
                val storageDir = File(getApplication<Application>().filesDir, "calibrations")
                check(storageDir.exists() || storageDir.mkdirs()) { "Impossible de créer le dossier local des cartographies." }
                val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "modified.bin" }
                val storedFile = File.createTempFile("comparison_", "_$safeName", storageDir)
                staged = storedFile
                storedFile.writeBytes(bytes)
                val summary = when {
                    comparison.identical -> localizedContext.getString(R.string.cal_comparison_identical)
                    comparison.referenceSize != comparison.modifiedSize -> localizedContext.getString(
                        R.string.cal_comparison_size_diff, comparison.comparedBytes
                    )
                    else -> localizedContext.getString(
                        R.string.cal_comparison_different_ranges,
                        comparison.changedBytes,
                        comparison.changedRanges.size + comparison.omittedRangeCount
                    )
                }
                val ranges = comparison.changedRanges.take(25).map {
                    localizedContext.getString(
                        R.string.cal_comparison_range,
                        it.startOffset.toString(16).uppercase().padStart(8, '0'),
                        it.endOffsetInclusive.toString(16).uppercase().padStart(8, '0'),
                        it.changedBytes
                    )
                } + if (comparison.omittedRangeCount > 0) listOf(
                    localizedContext.getString(R.string.cal_comparison_more_ranges, comparison.omittedRangeCount)
                ) else emptyList()
                withContext(Dispatchers.Main) {
                    if (!current()) return@withContext
                    val previous = _calibrationWorkspace.value
                    _calibrationWorkspace.value = previous.copy(
                    comparisonFileName = displayName,
                    comparisonFilePath = storedFile.absolutePath,
                    comparisonFileSize = bytes.size.toLong(),
                    comparisonSummary = summary,
                    comparisonChangedBytes = comparison.changedBytes,
                    comparisonAppendedBytes = comparison.appendedBytes,
                    comparisonMissingBytes = comparison.missingBytes,
                    comparisonRanges = ranges,
                    comparisonIdentical = comparison.identical,
                    partialWriteSummary = null,
                    partialWriteChangedBytes = null,
                    partialWriteSectorAddresses = emptyList(),
                    message = localizedContext.getString(R.string.msg_cal_comparison_complete)
                )
                    staged = null
                    previous.comparisonFilePath?.let { previousPath ->
                        if (previousPath != storedFile.absolutePath) runCatching { File(previousPath).delete() }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) _calibrationWorkspace.update {
                    it.copy(message = localizedContext.getString(R.string.msg_cal_comparison_failed))
                }
            } finally {
                staged?.let { runCatching { it.delete() } }
            }
        }
    }

    /**
     * Calculates a read-only preview of physical erase-sector impact.
     * Geometry is user supplied and remains UNVERIFIED, even when the calculation succeeds.
     */
    fun previewPartialCalibrationWrite(
        regionStartAddress: Long,
        eraseBlockBytes: Int,
        programBlockBytes: Int
    ) {
        if (!requirePremiumLicense()) return
        val ownerGeneration = accountSessionGeneration
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = _calibrationWorkspace.value
            try {
                require(snapshot.inspectionFormat == "RAW_BINARY" &&
                    snapshot.comparisonFilePath != null) { "Two raw BIN files are required." }
                val original = File(
                    snapshot.activeFilePath ?: snapshot.importedFilePath
                    ?: error("No original image is available.")
                )
                val modified = File(snapshot.comparisonFilePath)
                require(original.isFile && modified.isFile &&
                    original.length() in 1..(32L * 1024 * 1024) &&
                    modified.length() in 1..(32L * 1024 * 1024)
                ) { "Unsupported or missing ECU image." }
                val originalBytes = original.readBytes()
                val modifiedBytes = modified.readBytes()
                val plan = EcuPartialWritePlanner.preview(
                    EcuPartialWritePlanner.Layout(
                        regionStartAddress = regionStartAddress,
                        regionSize = originalBytes.size.toLong(),
                        eraseBlockBytes = eraseBlockBytes,
                        programBlockBytes = programBlockBytes,
                        mapVerifiedForTargetEcu = false
                    ),
                    originalBytes,
                    modifiedBytes
                )
                if (!isCurrentAccountGeneration(ownerGeneration)) return@launch
                if (_calibrationWorkspace.value.comparisonFilePath != snapshot.comparisonFilePath ||
                    _calibrationWorkspace.value.importedFilePath != snapshot.importedFilePath
                ) return@launch
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    partialWriteSummary = localizedContext.getString(
                        R.string.cal_expert_patch_summary,
                        plan.changedBytes,
                        plan.affectedSectors.size
                    ),
                    partialWriteChangedBytes = plan.changedBytes,
                    partialWriteSectorAddresses = plan.affectedSectors.take(16).map {
                        "0x" + it.absoluteAddress.toString(16).uppercase()
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrentAccountGeneration(ownerGeneration)) return@launch
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    partialWriteSummary = getApplication<Application>()
                        .getString(R.string.cal_expert_patch_invalid),
                    partialWriteChangedBytes = null,
                    partialWriteSectorAddresses = emptyList()
                )
            }
        }
    }

    fun exportCalibrationDefinitionTemplate(fileName: String) {
        if (!requirePremiumLicense()) return
        val exportGeneration = accountSessionGeneration
        viewModelScope.launch {
            try {
                val savedPath = CamaroDiagExportStorage.write(getApplication<Application>(), fileName, "text/tab-separated-values",
                    category = CamaroDiagExportStorage.Category.CALIBRATIONS, beforePublish = { check(isCurrentAccountGeneration(exportGeneration) && hasPremiumLicense()) }
                ) { it.write(CalibrationTableCodec.template.toByteArray(Charsets.UTF_8)) }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    message = localizedContext.getString(R.string.msg_cal_template_exported) + "\n" + savedPath
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    mapError = localizedContext.getString(R.string.msg_cal_template_export_failed)
                )
            }
        }
    }

    /** Imports a semicolon-separated table-definition file supplied for the exact calibration image. */
    fun importCalibrationDefinitions(uri: Uri) {
        if (!requirePremiumLicense()) return
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
                                localizedContext.getString(R.string.cal_warning_metadata_missing)
                            warning.startsWith("OS et référence matérielle saisis") ->
                                localizedContext.getString(R.string.cal_warning_os_hardware_metadata)
                            warning.startsWith("Des zones de tables ou d'axes") ->
                                localizedContext.getString(R.string.cal_warning_overlapping_regions)
                            else -> localizedContext.getString(R.string.cal_warning_import_review)
                        }
                    } + localizedContext.getString(R.string.cal_warning_os_hardware_not_read),
                    mapDirty = false,
                    mapError = null,
                    message = localizedContext.getString(R.string.msg_cal_definitions_loaded, parsed.definitions.size)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    mapError = localizedContext.getString(R.string.msg_cal_definitions_import_failed),
                    message = localizedContext.getString(R.string.msg_cal_definitions_import_failed)
                )
            }
        }
    }

    fun selectCalibrationMap(mapId: String) {
        if (!requirePremiumLicense()) return
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
                    mapError = localizedContext.getString(R.string.msg_cal_map_read_failed)
                )
            }
        }
    }

    fun updateCalibrationCell(row: Int, column: Int, value: String) {
        if (!requirePremiumLicense()) return
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
        if (!requirePremiumLicense()) return
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
                        localizedContext.getString(R.string.cal_address_range, it.start.toString(16).uppercase(), it.endInclusive.toString(16).uppercase(), it.length)
                    },
                    inspectionWarnings = localizedInspectionWarnings(inspection),
                    mapValues = decoded.values.map { row -> row.map(CalibrationTableCodec::formatEngineeringValue) },
                    mapXAxisValues = decoded.xAxisValues.map(CalibrationTableCodec::formatEngineeringValue),
                    mapYAxisValues = decoded.yAxisValues.map(CalibrationTableCodec::formatEngineeringValue),
                    mapDirty = false,
                    mapError = null,
                    message = localizedContext.getString(R.string.msg_cal_table_saved)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                createdFile?.let { runCatching { it.delete() } }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    mapError = localizedContext.getString(R.string.msg_cal_table_edit_failed),
                    message = localizedContext.getString(R.string.msg_cal_table_edit_failed)
                )
            }
        }
    }

    fun clearCalibrationFile() {
        calibrationFileImportGeneration++
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
            partialWriteSummary = null,
            partialWriteChangedBytes = null,
            partialWriteSectorAddresses = emptyList(),
            message = ""
        )
    }

    /** Exports the selected local calibration file through Android's Storage Access Framework. */
    fun exportExternalDumpPair(
        method: EcuBenchExpertPlan.AccessMethod,
        region: EcuBenchExpertPlan.MemoryRegion,
        startAddress: Long,
        originalPath: String,
        comparisonPath: String
    ) {
        if (!requirePremiumLicense()) return
        val owner = authenticatedAccountUid ?: return
        val generation = accountSessionGeneration
        val profile = _vehicle.value
        val snapshot = _calibrationWorkspace.value
        fun current() = canResumeExport(generation, owner) &&
            _vehicle.value == profile && hasPremiumLicense() &&
            _calibrationWorkspace.value.importedFilePath == originalPath &&
            _calibrationWorkspace.value.activeFilePath == originalPath &&
            _calibrationWorkspace.value.comparisonFilePath == comparisonPath &&
            _calibrationWorkspace.value.comparisonIdentical == true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!current()) return@launch
                require(snapshot.inspectionFormat == "RAW_BINARY")
                val original = File(originalPath)
                val repeated = File(comparisonPath)
                require(original.isFile && repeated.isFile && original.canonicalFile != repeated.canonicalFile)
                val length = original.length()
                require(length in 1L..EcuExternalDumpPair.MAX_DUMP_BYTES.toLong() &&
                    repeated.length() == length)
                val savedPath = CamaroDiagExportStorage.write(getApplication<Application>(),
                    "CamaroDiag_ECU_import_${method.name}_${System.currentTimeMillis()}.zip",
                    "application/zip", category = CamaroDiagExportStorage.Category.ECU_BACKUPS, beforePublish = { check(current()) }
                ) { output ->
                    original.inputStream().use { first ->
                        repeated.inputStream().use { second ->
                            EcuExternalDumpPair.export(output, first, second, method, region,
                                startAddress, length.toInt(),
                                controllerLabel = profile.engine.ecmType + " / " + method.name)
                        }
                    }
                }
                if (current()) _calibrationWorkspace.update {
                    it.copy(message = localizedContext.getString(R.string.export_folder_success) + "\n" + savedPath)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current()) _calibrationWorkspace.update {
                    it.copy(message = localizedContext.getString(R.string.export_folder_failed))
                }
            }
        }
    }

    fun exportCalibrationFile(fileName: String) {
        if (!requirePremiumLicense()) return
        val owner = authenticatedAccountUid ?: return
        val exportGeneration = accountSessionGeneration
        val profile = _vehicle.value
        fun current() = canResumeExport(exportGeneration, owner) &&
            _vehicle.value == profile && hasPremiumLicense()
        val workspace = _calibrationWorkspace.value
        val selectedPath = workspace.activeFilePath ?: workspace.importedFilePath
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!current()) return@launch
                val sourcePath = selectedPath ?: error("Aucun fichier de calibration n'est chargé.")
                val source = File(sourcePath)
                check(source.isFile && source.exists()) { "Le fichier local de calibration est introuvable." }
                val savedPath = CamaroDiagExportStorage.write(getApplication<Application>(), fileName, "application/octet-stream",
                    category = CamaroDiagExportStorage.Category.CALIBRATIONS, beforePublish = { check(current()) }
                ) { target -> source.inputStream().use { it.copyTo(target) } }
                if (current()) _calibrationWorkspace.update {
                    it.copy(message = localizedContext.getString(R.string.msg_cal_file_exported) + "\n" + savedPath)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) _calibrationWorkspace.update {
                    it.copy(message = localizedContext.getString(R.string.msg_cal_file_export_failed))
                }
            }
        }
    }

    private fun markAdapterDisconnected() {
        // Capture the active state *before* Stop can complete asynchronously.
        // Otherwise a partially archived monitor session could be exported as complete.
        val wasCapturing = canMonitorJob != null || _liveCanMonitorState.value.isCapturing
        stopCanMonitor()
        // Keep RX/TX history and the raw archive across adapter disconnects;
        // explicit vehicle/VIN changes still clear both to prevent data mixing.
        _liveCanMonitorState.value = _liveCanMonitorState.value.copy(
            isCapturing = false,
            isSendingRequest = false,
            rawArchiveFailed = CanConsoleExportPolicy.archiveFailedAfterDisconnect(
                _liveCanMonitorState.value.rawArchiveFailed, wasCapturing
            )
        )
        _adapter.value = AdapterCapabilities()
        _modules.value = CamaroUdsProtocol.buildTopology(_vehicle.value, _adapter.value)
        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
            isJ2534BridgeConnecting = false,
            isJ2534BridgeConnected = false,
            isJ2534MemoryReadAvailable = false
        )
        clearVehicleEvidence(preserveCanConsole = true)
        _isObdScanning.value = false
    }

    @Volatile private var connectionAttemptGeneration: Long = 0L
    private val _connectionGeneration = MutableStateFlow(0L)
    private var pendingUsbPermissionRetry: UsbPermissionReconnectPolicy.Pending? = null
    val connectionGeneration: StateFlow<Long> = _connectionGeneration.asStateFlow()

    /** Shared revision for all physical/simulated adapter transitions.
     * A consent dialog from an older revision can never authorize a write.
     */
    private fun nextConnectionGeneration(): Long {
        invalidateEnhancedUdsReads()
        val next = ++connectionAttemptGeneration
        pendingUsbPermissionRetry = null
        _connectionGeneration.value = next
        return next
    }
    private var connectionAttemptJob: Job? = null

    private fun connectWithResult(
        connect: suspend () -> Result<AdapterCapabilities>,
        fallbackMessage: String,
        transport: String,
        endpoint: String,
        discoveryOnly: Boolean = false
    ) {
        val attempt = nextConnectionGeneration()
        pendingUsbPermissionRetry = UsbPermissionReconnectPolicy.forSelection(
            transport, endpoint, attempt
        )
        invalidateFuelHistoryForConnectionChange()
        val previousAttempt = connectionAttemptJob
        previousAttempt?.cancel()
        connectionAttemptJob = viewModelScope.launch {
            beginBusyOperation()
            try {
                // A cancelled transport handshake may still be closing its
                // socket. Wait for that cleanup before opening the replacement.
                previousAttempt?.join()
                if (attempt != connectionAttemptGeneration) return@launch
                _connectionMessage.value = null
                val activeMonitor = canMonitorJob
                val activeRequest = detachCanRequest()
                markAdapterDisconnected()
                val detachedMonitor = detachedCanMonitorJob
                val detachedRequest = detachedCanRequestJob
                activeMonitor?.join()
                activeRequest?.join()
                detachedMonitor?.join()
                detachedRequest?.join()
                if (attempt != connectionAttemptGeneration) return@launch
                connect()
                    .onSuccess { capabilities ->
                        if (attempt == connectionAttemptGeneration) {
                            pendingUsbPermissionRetry = null
                            _adapter.value = capabilities
                            if (!discoveryOnly) {
                                rememberConnectionProfile(transport, capabilities, endpoint)
                                refreshTopologyAndDefaults(runInitialScans = true)
                            }
                        }
                    }
                    .onFailure { error ->
                        if (attempt == connectionAttemptGeneration) {
                            val permissionRequired = AdapterConnectionFailurePolicy.classify(error) ==
                                AdapterConnectionFailurePolicy.Kind.USB_PERMISSION_REQUIRED
                            if (!permissionRequired) pendingUsbPermissionRetry = null
                            _connectionMessage.value = if (permissionRequired) {
                                localizedContext.getString(R.string.msg_usb_permission_pending)
                            } else fallbackMessage
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (attempt == connectionAttemptGeneration) {
                    pendingUsbPermissionRetry = null
                    _connectionMessage.value = fallbackMessage
                }
            } finally {
                if (attempt == connectionAttemptGeneration) {
                    connectionAttemptJob = null
                }
                endBusyOperation()
            }
        }
    }

    /** USB discovery does not require Bluetooth or location permissions. */
    fun refreshUsbDevices() {
        viewModelScope.launch {
            try {
                _usbDevices.value = obdManager.getUsbDevices()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _connectionMessage.value = localizedContext.getString(R.string.msg_refresh_adapters_failed)
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
                _connectionMessage.value = localizedContext.getString(R.string.msg_refresh_adapters_failed)
            }
        }
    }

    fun connectWifi(host: String, port: Int) {
        val endpoint = try {
            AdapterEndpointPreflight.wifi(host, port)
        } catch (_: IllegalArgumentException) {
            _connectionMessage.value = localizedContext.getString(R.string.msg_wifi_connection_failed)
            return
        }
        connectWithResult(
            connect = { obdManager.connectWifi(endpoint.host, endpoint.port) },
            fallbackMessage = localizedContext.getString(R.string.msg_wifi_connection_failed),
            transport = "wifi",
            endpoint = "${endpoint.host}:${endpoint.port}"
        )
    }

    fun discoverCamaroDiagConnector(deviceLabel: String, binary: Boolean = false) {
        val id = com.camarodiag.gen5.data.obd.CamaroDiagConnectorDiscovery.deviceIdFromLabel(deviceLabel)
        if (id == null || !com.camarodiag.gen5.data.obd.CamaroDiagConnectorDiscovery
                .allowsDedicatedUsbDiscovery(id, _usbDevices.value)) {
            // Reject a mismatched/stale UI entry without switching away from
            // an active OBD/J2534 adapter. Manager rechecks the live USB list.
            _connectionMessage.value = localizedContext.getString(R.string.msg_usb_identifier_unavailable)
            return
        }
        connectWithResult(
            connect = { obdManager.discoverCamaroDiagConnector(id, binary) },
            fallbackMessage = localizedContext.getString(R.string.msg_usb_connection_failed),
            transport = if (binary) "connector_usb_binary" else "connector_usb",
            endpoint = deviceLabel,
            discoveryOnly = true
        )
    }

    /** CDJ/1 physical acquisition is not implemented in the connector
     * firmware. Do not switch transports or produce a placeholder backup.
     */
    @Suppress("UNUSED_PARAMETER")
    fun captureConnectorCheckpoint(deviceLabel: String) {
        _connectionMessage.value = localizedContext.getString(R.string.status_not_verified)
    }

    fun connectUsb(deviceLabel: String) {
        val enumerated = _usbDevices.value
        if (deviceLabel !in enumerated) {
            _connectionMessage.value = localizedContext.getString(R.string.msg_usb_identifier_unavailable)
            return
        }
        if (com.camarodiag.gen5.data.obd.CamaroDiagConnectorDiscovery.isLegacyKklUsbLabel(deviceLabel)) {
            _connectionMessage.value = localizedContext.getString(R.string.status_not_verified)
            return
        }
        if (deviceLabel.contains("CamaroDiagConnectors", ignoreCase = true)) {
            discoverCamaroDiagConnector(deviceLabel)
            return
        }
        val id = com.camarodiag.gen5.data.obd.CamaroDiagConnectorDiscovery.deviceIdFromLabel(deviceLabel)
        if (id == null) {
            _connectionMessage.value = localizedContext.getString(R.string.msg_usb_identifier_unavailable)
            return
        }
        val baudRate = if (deviceLabel.contains("OBDLink", true)) 115200 else 38400
        connectWithResult(
            connect = { obdManager.connectUsb(id, baudRate) },
            fallbackMessage = localizedContext.getString(R.string.msg_usb_connection_failed),
            transport = "usb",
            endpoint = deviceLabel
        )
    }

    fun selectSimulatedAdapter(preset: String) {
        val attempt = nextConnectionGeneration()
        invalidateFuelHistoryForConnectionChange()
        val previousAttempt = connectionAttemptJob
        previousAttempt?.cancel()
        connectionAttemptJob = viewModelScope.launch {
            beginBusyOperation()
            try {
                // Do not activate the simulator while a cancelled physical
                // handshake is still closing its transport.
                previousAttempt?.join()
                if (attempt != connectionAttemptGeneration) return@launch
                val activeMonitor = canMonitorJob
                val activeRequest = detachCanRequest()
                markAdapterDisconnected()
                val detachedMonitor = detachedCanMonitorJob
                val detachedRequest = detachedCanRequestJob
                activeMonitor?.join()
                activeRequest?.join()
                detachedMonitor?.join()
                detachedRequest?.join()
                if (attempt != connectionAttemptGeneration) return@launch
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    ecuVinResponse = "",
                    ecuIdentityFromSimulation = false,
                    calibrationIdResponse = "",
                    cvnResponse = ""
                )
                val caps = obdManager.activateSimulatedAdapter(preset)
                if (attempt != connectionAttemptGeneration) return@launch
                _adapter.value = caps
                _connectionMessage.value = null
                refreshTopologyAndDefaults()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (attempt == connectionAttemptGeneration) {
                    _connectionMessage.value = localizedContext.getString(R.string.msg_simulation_activation_failed)
                }
            } finally {
                if (attempt == connectionAttemptGeneration) {
                    connectionAttemptJob = null
                }
                endBusyOperation()
            }
        }
    }

    fun connectRealBluetooth(macAddress: String) {
        val selectedAddress = try {
            AdapterEndpointPreflight.bluetoothMac(macAddress)
        } catch (_: IllegalArgumentException) {
            _connectionMessage.value = localizedContext.getString(R.string.msg_bluetooth_connection_failed)
            return
        }
        val selectedName = _pairedDevices.value.firstOrNull {
            it.second.equals(selectedAddress, ignoreCase = true)
        }?.first.orEmpty()
        // An OBDLink CX may support standard OBD over its BLE UART even when
        // GM enhanced services are unavailable. Let the physical BLE probe
        // determine capabilities instead of refusing the adapter by product name.
        connectWithResult(
            connect = { obdManager.connectAndProbe(selectedAddress) },
            fallbackMessage = localizedContext.getString(R.string.msg_bluetooth_connection_failed),
            transport = "bluetooth",
            endpoint = "$selectedName ($selectedAddress)"
        )
    }

    fun toggleLiveStream() {
        _isStreamingLive.value = !_isStreamingLive.value
    }

    /** Records actual live OBD samples; demo values are deliberately excluded. */
    fun toggleLiveRecording() {
        if (!requirePremiumLicense()) return
        if (_liveRecordingState.value.isRecording) {
            stopLiveRecording()
            return
        }
        if (!obdManager.isStandardObdReadAvailable()) {
            _liveRecordingState.value = _liveRecordingState.value.copy(
                message = localizedContext.getString(if (obdManager.isJ2534BridgeConnected()) R.string.msg_live_recording_j2534_block else R.string.msg_live_recording_unavailable)
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
            message = localizedContext.getString(R.string.msg_live_recording_real_started)
        )
    }

    fun stopLiveRecording() {
        if (!_liveRecordingState.value.isRecording) return
        _liveRecordingState.value = _liveRecordingState.value.copy(
            isRecording = false,
            stoppedAtMillis = System.currentTimeMillis(),
            message = localizedContext.getString(R.string.msg_live_recording_stopped_count, recordedLiveSamples.size)
        )
    }

    fun exportLiveRecording(fileName: String) {
        if (!requirePremiumLicense()) return
        val owner = authenticatedAccountUid ?: return
        val exportGeneration = accountSessionGeneration
        val profile = _vehicle.value
        fun current() = canResumeExport(exportGeneration, owner) &&
            _vehicle.value == profile && hasPremiumLicense()
        viewModelScope.launch {
            try {
                if (!current()) return@launch
                if (_liveRecordingState.value.isRecording) stopLiveRecording()
                val snapshot = recordedLiveSamples.toList()
                check(snapshot.isNotEmpty()) { "Aucune donnée enregistrée à exporter." }
                val v = profile
                val csv = LiveTelemetryCsv.encode(
                    samples = snapshot,
                    vin = v.vin,
                    vehicleLabel = "${v.modelYear} Camaro ${v.engine.rpo} • ${v.generation.label} • ${v.transmission.name}"
                )
                val savedPath = CamaroDiagExportStorage.write(getApplication<Application>(), fileName, "text/csv",
                    category = CamaroDiagExportStorage.Category.TELEMETRY, beforePublish = { check(current()) }
                ) { it.write(csv.toByteArray(Charsets.UTF_8)) }
                if (current()) _liveRecordingState.update {
                    it.copy(message = localizedContext.getString(R.string.msg_live_csv_exported, snapshot.size) + "\n" + savedPath)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) _liveRecordingState.update {
                    it.copy(message = localizedContext.getString(R.string.msg_live_csv_export_failed))
                }
            }
        }
    }

    private fun captureLiveTelemetrySample(values: List<LivePidValue>) {
        if (!_liveRecordingState.value.isRecording) return
        if (!obdManager.isStandardObdReadAvailable()) {
            stopLiveRecording()
            _liveRecordingState.value = _liveRecordingState.value.copy(
                message = localizedContext.getString(R.string.msg_live_recording_channel_lost)
            )
            return
        }
        if (values.isEmpty()) return
        if (recordedLiveSamples.size >= maxLiveRecordingSamples) {
            _liveRecordingState.value = _liveRecordingState.value.copy(
                isRecording = false,
                stoppedAtMillis = System.currentTimeMillis(),
                sampleCount = recordedLiveSamples.size,
                message = localizedContext.getString(R.string.msg_live_recording_max_samples, maxLiveRecordingSamples)
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
            message = localizedContext.getString(R.string.msg_live_recording_started, recordedLiveSamples.size)
        )
    }

    fun scanDtcs() {
        val startedGeneration = accountSessionGeneration
        viewModelScope.launch {
            if (!isCurrentAccountGeneration(startedGeneration)) return@launch
            beginBusyOperation()
            try {
                if (obdManager.isSimulationActive) {
                    val (confirmed, pending, permanent) =
                        obdManager.createSimulatedObd2FeatureEngine().readDtcs()
                    _dtcs.value = mapObdDtcs(confirmed + pending + permanent)
                    _connectionMessage.value = localizedContext.getString(R.string.msg_dtc_simulated_lfx)
                } else if (!obdManager.isStandardObdReadAvailable()) {
                    // No SAE OBD channel is available; keep independently read UDS ECU records.
                    _dtcs.value = _dtcs.value.filterNot { it.module.equals("ECM", ignoreCase = true) }
                    _connectionMessage.value = if (obdManager.isJ2534BridgeConnected())
                        localizedContext.getString(R.string.msg_dtc_j2534_unavailable)
                    else localizedContext.getString(R.string.msg_dtc_adapter_required)
                } else {
                    if (obdManager.isJ2534StandardObdAvailable()) {
                        val snapshot = obdManager.readJ2534StandardDtcs()
                        if (!isCurrentAccountGeneration(startedGeneration)) return@launch
                        replaceStandardDtcRecords(snapshot.records, snapshot.unavailableModes)
                        _connectionMessage.value = unavailableDtcNotice(snapshot.unavailableModes)
                    } else {
                        val (confirmed, pending, permanent) = obdManager.withObdCommandSession { engine ->
                            engine.readDtcs()
                        }
                        if (!isCurrentAccountGeneration(startedGeneration)) return@launch
                        replaceStandardDtcRecords(confirmed + pending + permanent, emptySet())
                        _connectionMessage.value = null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrentAccountGeneration(startedGeneration)) {
                    reportConnectionLoss(error)
                    _connectionMessage.value = localizedContext.getString(R.string.msg_dtc_scan_failed)
                }
            } finally {
                endBusyOperation()
            }
        }
    }

    /** Keep the prior records for an explicitly unavailable optional read mode. */
    private fun replaceStandardDtcRecords(records: List<ObdDtc>, unavailableModes: Set<Int>) {
        _dtcs.value = _dtcs.value.filter { previous ->
            !previous.module.equals("ECM", ignoreCase = true) ||
                (7 in unavailableModes && previous.statusEn.equals("Pending", ignoreCase = true)) ||
                (10 in unavailableModes && previous.statusEn.equals("Permanent", ignoreCase = true))
        } + mapObdDtcs(records)
    }

    private fun unavailableDtcNotice(modes: Set<Int>): String? =
        modes.takeIf { it.isNotEmpty() }?.let {
            localizedContext.getString(R.string.obd_data_unavailable) + ": OBD " +
                it.sorted().joinToString(" / ") { mode -> "%02X".format(java.util.Locale.ROOT, mode) }
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

    fun clearDtcs(
        approval: EcuWriteConsentFlow.Approval,
        confirmedConnection: Long,
        confirmedVehicle: VehicleProfile
    ) {
        require(approval.warningAcknowledged && approval.confirmedYes) {
            "The ECU write warning OK and subsequent YES are required."
        }
        // The warning, YES and eventual command must refer to exactly the
        // same connection and vehicle identity, including VIN/profile changes.
        if (confirmedConnection != connectionAttemptGeneration ||
            confirmedVehicle != _vehicle.value) return
        val account = accountSessionGeneration
        val connection = confirmedConnection
        fun current() = isCurrentAccountGeneration(account) &&
            connection == connectionAttemptGeneration &&
            confirmedVehicle == _vehicle.value
        viewModelScope.launch {
            if (!current()) return@launch
            beginBusyOperation()
            try {
                if (obdManager.isSimulationActive) {
                    _dtcs.value = emptyList()
                    _connectionMessage.value = localizedContext.getString(R.string.msg_dtc_demo_cleared)
                } else if (obdManager.isJ2534StandardObdClearAvailable()) {
                    obdManager.clearJ2534StandardDtcs(approval) {
                        EcuWriteConsentFlow.requireCurrentForTransmit(approval) { current() }
                    }
                    if (!current()) return@launch
                    // Mode 04 does not erase permanent emissions DTCs. Preserve
                    // all other module records and require a new scan to refresh.
                    _dtcs.value = _dtcs.value.filterNot {
                        it.module.equals("ECM", ignoreCase = true) &&
                            !it.statusEn.equals("Permanent", ignoreCase = true)
                    }
                    _connectionMessage.value = null
                } else if (!obdManager.isObdCommandChannelAvailable) {
                    _connectionMessage.value = when {
                        obdManager.isJ2534StandardObdAvailable() ->
                            localizedContext.getString(R.string.msg_dtc_j2534_clear_requires_permission)
                        obdManager.isJ2534BridgeConnected() ->
                            localizedContext.getString(R.string.msg_dtc_clear_j2534_unavailable)
                        else -> localizedContext.getString(R.string.msg_dtc_clear_adapter_required)
                    }
                } else {
                    val response = obdManager.sendAtCommand("04", 2500L) {
                        EcuWriteConsentFlow.requireCurrentForTransmit(approval) { current() }
                    }
                    if (!current()) return@launch
                    if (com.camarodiag.gen5.data.obd.StandardObdClearEvidence.acknowledged(response)) {
                        // SAE Mode 04 clears standardized emissions DTCs only.
                        // Preserve enhanced UDS records from other ECUs: no GM UDS clear is issued here.
                        _dtcs.value = _dtcs.value.filterNot {
                            it.module.equals("ECM", ignoreCase = true) &&
                                !it.statusEn.equals("Permanent", ignoreCase = true)
                        }
                        _connectionMessage.value = null
                    } else {
                        _connectionMessage.value = localizedContext.getString(R.string.msg_dtc_clear_not_confirmed, response)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) {
                    reportConnectionLoss(error)
                    _connectionMessage.value = localizedContext.getString(R.string.msg_dtc_clear_failed)
                }
            } finally {
                endBusyOperation()
            }
        }
    }

    fun runMileageAudit() {
        if (!requirePremiumLicense()) return
        val startedGeneration = accountSessionGeneration
        val connection = connectionAttemptGeneration
        val selectedVin = _vehicle.value.vin
        fun current() = isCurrentAccountGeneration(startedGeneration) &&
            connection == connectionAttemptGeneration && selectedVin == _vehicle.value.vin
        viewModelScope.launch {
            if (!current()) return@launch
            beginBusyOperation()
            _mileageReport.value = null
            val readings = mutableListOf<MileageSourceReading>()
            try {
                if (!obdManager.isStandardObdReadAvailable()) {
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
                    val (a6Response, d3Response) = if (obdManager.isJ2534StandardObdAvailable()) {
                        obdManager.readJ2534OdometerPids()
                    } else obdManager.withObdCommandSession { engine ->
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
                if (!current()) return@launch
                _mileageReport.value = MileageAuditEngine.evaluate(selectedVin, readings)
                _connectionMessage.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) {
                    reportConnectionLoss(error)
                    _mileageReport.value = MileageAuditEngine.evaluate(selectedVin, readings)
                    _connectionMessage.value = localizedContext.getString(R.string.msg_mileage_audit_failed)
                }
            } finally {
                endBusyOperation()
            }
        }
    }

    /** Executes a complete, standards-based read-only OBD-II scan on the connected Camaro. */

    /**
     * Owner-only read-only hardware verification.
     * A valid Firebase ID token with a backend-issued custom claim is required
     * for EACH run. UI visibility alone is never treated as authorization.
     */
    private suspend fun confirmFunctionalTestOwner(expectedUid: String): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null || user.uid != expectedUid) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            user.getIdToken(true).addOnCompleteListener { task ->
                if (continuation.isActive) {
                    continuation.resume(
                        task.isSuccessful &&
                            FirebaseAuth.getInstance().currentUser?.uid == expectedUid &&
                            com.camarodiag.gen5.auth.OwnerFunctionalTestPolicy.isAllowed(
                                user.providerData.map { it.providerId },
                                task.result?.claims.orEmpty()
                            )
                    )
                }
            }
        }

    fun runFunctionalTests() {
        if (functionalTestJob?.isActive == true) return
        val accountUid = authenticatedAccountUid ?: return
        val sessionGeneration = accountSessionGeneration
        val connection = connectionAttemptGeneration
        val selectedVin = _vehicle.value.vin
        val adapterName = _adapter.value.deviceName
        val execution = ++functionalTestGeneration
        functionalTestJob = viewModelScope.launch {
            if (!confirmFunctionalTestOwner(accountUid)) {
                if (execution == functionalTestGeneration) {
                    _functionalTestState.value = FunctionalTestState(
                        error = localizedContext.getString(R.string.functional_tests_denied)
                    )
                }
                return@launch
            }
            if (!isCurrentAccountGeneration(sessionGeneration) || execution != functionalTestGeneration ||
                connection != connectionAttemptGeneration || selectedVin != _vehicle.value.vin) {
                return@launch
            }
            val connected = !obdManager.isDemoMode && !obdManager.isSimulationActive &&
                (obdManager.isObdCommandChannelAvailable || obdManager.isJ2534BridgeConnected())
            val transport = obdManager.currentTransportKind
            val topology = _modules.value.ifEmpty {
                CamaroUdsProtocol.buildTopology(_vehicle.value, _adapter.value)
            }
            _functionalTestState.value = FunctionalTestState(
                running = true, startedAtMillis = System.currentTimeMillis(),
                checks = FunctionalTestPlanner.initial(topology, transport, connected),
                context = FunctionalTestContext(selectedVin, adapterName, transport, connection)
            )
            fun current(): Boolean = execution == functionalTestGeneration &&
                isCurrentAccountGeneration(sessionGeneration) &&
                connection == connectionAttemptGeneration && selectedVin == _vehicle.value.vin
            fun mark(
                key: String, outcome: FunctionalTestOutcome,
                english: String, french: String
            ) {
                if (current()) {
                    _functionalTestState.value = _functionalTestState.value.copy(
                        checks = FunctionalTestPlanner.updated(
                            _functionalTestState.value.checks, key, outcome, english, french
                        )
                    )
                }
            }
            suspend fun checkRead(
                key: String, read: suspend () -> Pair<String, String>
            ) {
                if (!current()) throw CancellationException("Account or run changed")
                try {
                    val (english, french) = read()
                    if (!current()) throw CancellationException("Account or run changed")
                    mark(key, FunctionalTestOutcome.PASSED, english, french)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    mark(
                        key, FunctionalTestOutcome.FAILED,
                        "Physical read failed (" + failure.javaClass.simpleName + ").",
                        "Lecture physique échouée (" + failure.javaClass.simpleName + ")."
                    )
                }
            }
            try {
                if (!connected) {
                    if (current()) _functionalTestState.value = _functionalTestState.value.copy(
                        error = localizedContext.getString(R.string.functional_tests_not_connected)
                    )
                } else if (obdManager.isObdCommandChannelAvailable) {
                    // Only a physically observed 7E8 reply to SAE 0100 permits
                    // confirmation of the standard 11-bit powertrain OBD responder.
                    // It does not certify GM enhanced addressing or ECU programming.
                    if (current()) {
                        try {
                            val responders = obdManager.withObdCommandSession {
                                it.discoverFunctionalObdResponders()
                            }
                            if ("7E8" in responders) {
                                mark(
                                    "module:ECM", FunctionalTestOutcome.PASSED,
                                    "Physical 7E8 reply to standard 0100 observed; standard OBD only.",
                                    "Réponse physique 7E8 à 0100 observée ; OBD standard uniquement."
                                )
                            } else {
                                mark(
                                    "module:ECM", FunctionalTestOutcome.NOT_TESTED,
                                    "No verified 7E8 response; enhanced ECM access not tested.",
                                    "Aucune réponse 7E8 confirmée ; accès ECM étendu non testé."
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            mark(
                                "module:ECM", FunctionalTestOutcome.FAILED,
                                "Physical OBD responder discovery failed.",
                                "Échec de la découverte des réponses OBD physiques."
                            )
                        }
                    }
                    checkRead("service:vin") {
                        val vin = obdManager.withObdCommandSession { it.readVin() }
                            ?.trim()?.uppercase(java.util.Locale.ROOT)
                            ?.takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) }
                            ?: error("VIN missing from physical response")
                        val chosen = _vehicle.value.vin.trim().uppercase(java.util.Locale.ROOT)
                        require(chosen.isEmpty() || chosen == vin) {
                            "VIN mismatch"
                        }
                        "Vehicle VIN read and checked against selected profile." to
                            "VIN physique lu et comparé au véhicule sélectionné."
                    }
                    checkRead("service:pids") {
                        val pids = obdManager.withObdCommandSession { it.discoverSupportedPids() }
                        require(pids.isNotEmpty()) { "No supported PID response" }
                        ("ECU responded with " + pids.size + " supported PIDs.") to
                            ("Réponse OBD physique : " + pids.size + " PID pris en charge.")
                    }
                    checkRead("service:dtcs") {
                        val (dtcResults, positiveResponse) =
                            obdManager.withObdCommandSession { it.readDtcsWithPositiveResponseProof() }
                        require(positiveResponse) { "No positive physical DTC service response" }
                        val (confirmed, pending, permanent) = dtcResults
                        val count = confirmed.size + pending.size + permanent.size
                        ("Physical DTC response verified: " + count + " codes, no clearing.") to
                            ("Réponse physique DTC vérifiée : " + count + " défauts, sans effacement.")
                    }
                    checkRead("service:readiness") {
                        val response = obdManager.withObdCommandSession { it.readReadiness() }
                        require(!response.isNullOrBlank()) { "Readiness response missing" }
                        "Physical emissions readiness response received." to
                            "Réponse physique des moniteurs antipollution reçue."
                    }
                    // Functional 7DF/Mode 09 responses cannot prove which individual
                    // GM controller answered. Keep all per-ECU rows NOT TESTED until
                    // VIN-specific physical addresses have been independently verified.
                } else if (obdManager.isJ2534BridgeConnected()) {
                    checkRead("service:vin") {
                        val identity = obdManager.readCalibrationIdentification()
                        val vin = identity.vin?.trim()?.uppercase(java.util.Locale.ROOT)
                            ?.takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) }
                            ?: error("J2534 ECU VIN missing")
                        val chosen = _vehicle.value.vin.trim().uppercase(java.util.Locale.ROOT)
                        require(chosen.isEmpty() || chosen == vin) { "VIN mismatch" }
                        "ECU VIN received over the selected physical J2534 UDS profile." to
                            "VIN reçu via le profil UDS J2534 physique sélectionné."
                    }
                    // A capable bridge now offers standard OBD payloads without
                    // an ELM command channel. Test each service independently.
                    if (obdManager.isJ2534StandardObdAvailable()) {
                        checkRead("service:pids") {
                            val pids = obdManager.probeJ2534StandardPids()
                            require(pids.isNotEmpty()) { "No supported physical PIDs" }
                            ("Physical J2534 PID bitmap: " + pids.size + " supported PIDs.") to
                                ("Bitmap physique J2534 : " + pids.size + " PID pris en charge.")
                        }
                        checkRead("service:dtcs") {
                            val snapshot = obdManager.readJ2534StandardDtcs()
                            require(snapshot.unavailableModes.isEmpty()) {
                                "Complete standard DTC coverage not confirmed"
                            }
                            ("Physical J2534 DTC reads verified: " + snapshot.records.size + " codes, no clearing.") to
                                ("Lectures physiques J2534 vérifiées : " + snapshot.records.size + " défauts, sans effacement.")
                        }
                        checkRead("service:readiness") {
                            obdManager.probeJ2534StandardReadiness()
                            "Physical J2534 emissions readiness response verified." to
                                "Réponse physique J2534 des moniteurs antipollution vérifiée."
                        }
                    }
                    // A physical standard OBD read is not proof of every GM
                    // module or of programming, actuation or DTC reset.
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (execution == functionalTestGeneration && isCurrentAccountGeneration(sessionGeneration)) {
                    _functionalTestState.value = _functionalTestState.value.copy(
                        running = false, finishedAtMillis = System.currentTimeMillis(),
                        error = if (!current()) localizedContext.getString(R.string.functional_tests_cancelled)
                            else _functionalTestState.value.error
                    )
                }
            }
        }
    }

    fun cancelFunctionalTests() {
        functionalTestGeneration++
        functionalTestJob?.cancel()
        functionalTestJob = null
        if (_functionalTestState.value.running) {
            _functionalTestState.value = _functionalTestState.value.copy(
                running = false,
                finishedAtMillis = System.currentTimeMillis(),
                error = localizedContext.getString(R.string.functional_tests_cancelled)
            )
        }
    }

    fun exportFunctionalTests(fileName: String) {
        val accountUid = authenticatedAccountUid ?: return
        val sessionGeneration = accountSessionGeneration
        viewModelScope.launch {
            if (!confirmFunctionalTestOwner(accountUid) ||
                !isCurrentAccountGeneration(sessionGeneration)) return@launch
            val snapshot = _functionalTestState.value
            if (!snapshot.canExport) return@launch
            val evidenceContext = snapshot.context ?: return@launch
            val json = JSONObject()
                .put("schema", "camarodiag-functional-test-v1")
                .put("startedAtMillis", snapshot.startedAtMillis)
                .put("finishedAtMillis", snapshot.finishedAtMillis)
                .put("vin", evidenceContext.selectedVin)
                .put("adapter", evidenceContext.adapterName)
                .put("transport", evidenceContext.transport)
                .put("connectionGeneration", evidenceContext.connectionGeneration)
                .put("completedWithoutError", snapshot.error == null)
                .put("warning", "NOT_TESTED is not a PASS. No write or actuation was tested.")
            val checks = JSONArray()
            snapshot.checks.forEach { check ->
                checks.put(
                    JSONObject()
                        .put("key", check.key)
                        .put("group", check.group)
                        .put("outcome", check.outcome.name)
                        .put("label", check.labelEn)
                        .put("detail", check.detailEn)
                )
            }
            json.put("checks", checks)
            try {
                val savedPath = CamaroDiagExportStorage.write(getApplication<Application>(), fileName, "application/json",
                    category = CamaroDiagExportStorage.Category.TESTS, beforePublish = { check(isCurrentAccountGeneration(sessionGeneration) &&
                        authenticatedAccountUid == accountUid && currentLicenseLevel == LicenseLevel.OWNER) }
                ) { it.write(json.toString(2).toByteArray(Charsets.UTF_8)) }
                if (isCurrentAccountGeneration(sessionGeneration)) {
                    _connectionMessage.value = localizedContext.getString(R.string.export_folder_success) + "\n" + savedPath
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrentAccountGeneration(sessionGeneration)) {
                    _connectionMessage.value = localizedContext.getString(R.string.export_folder_failed)
                }
            }
        }
    }

    fun runFullObdScan() {
        if (!requirePremiumLicense()) return
        if (_isObdScanning.value) return
        val startedGeneration = accountSessionGeneration
        val connection = connectionAttemptGeneration
        val selectedVin = _vehicle.value.vin
        fun current() = isCurrentAccountGeneration(startedGeneration) &&
            connection == connectionAttemptGeneration && selectedVin == _vehicle.value.vin
        viewModelScope.launch {
            if (!current()) return@launch
            if (!obdManager.isSimulationActive && !obdManager.isStandardObdReadAvailable()) {
                _obdScanReport.value = null
                _obdScanMessage.value = if (obdManager.isJ2534BridgeConnected())
                    localizedContext.getString(R.string.msg_full_scan_j2534_only)
                else localizedContext.getString(R.string.msg_full_scan_adapter_required)
                return@launch
            }
            if (_isObdScanning.value) return@launch
            _isObdScanning.value = true
            _obdScanReport.value = null
            _obdScanMessage.value = null
            try {
                val report = if (obdManager.isSimulationActive) {
                    obdManager.createSimulatedObd2FeatureEngine().fullReadOnlyScan()
                } else if (obdManager.isJ2534StandardObdAvailable()) {
                    obdManager.fullJ2534StandardObdScan()
                } else {
                    obdManager.withObdCommandSession { engine ->
                        engine.fullReadOnlyScan()
                    }
                }
                if (!current()) return@launch
                _obdScanReport.value = report
                if (report.physicalCanResponder == null) {
                    _modules.value = CamaroUdsProtocol.applyStandardObdDiscovery(
                        topology = _modules.value,
                        respondingAddresses = report.respondingEcuAddresses
                    )
                }
                val allDtcs = report.confirmedDtcs + report.pendingDtcs + report.permanentDtcs
                if (obdManager.isSimulationActive) {
                    _dtcs.value = mapObdDtcs(allDtcs)
                } else {
                    replaceStandardDtcRecords(allDtcs, report.unavailableDtcModes)
                }
                _obdScanMessage.value = if (obdManager.isSimulationActive) {
                    localizedContext.getString(R.string.msg_full_scan_simulation_complete, report.supportedPids.size, allDtcs.size)
                } else {
                    listOfNotNull(
                        localizedContext.getString(R.string.msg_full_scan_complete, report.supportedPids.size, allDtcs.size),
                        unavailableDtcNotice(report.unavailableDtcModes)
                    ).joinToString("\n")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) {
                    reportConnectionLoss(error)
                    _obdScanMessage.value = localizedContext.getString(R.string.msg_full_scan_failed)
                }
            } finally {
                // An old scan finishing after account switch must not reset
                // the busy state of a new scan already started for that user.
                if (isCurrentAccountGeneration(startedGeneration)) {
                    _isObdScanning.value = false
                }
            }
        }
    }

    fun saveCurrentSession() {
        if (!requirePremiumLicense()) return
        // Capture the identity and evidence at the user's action, never after
        // a queued coroutine may have crossed a Firebase account switch.
        val ownerUid = authenticatedAccountUid ?: return
        val exportGeneration = accountSessionGeneration
        val v = _vehicle.value
        val report = _mileageReport.value
        val adapterName = _adapter.value.deviceName
        val diagnosticCodes = _dtcs.value.map { it.code }
        viewModelScope.launch {
            try {
                if (authenticatedAccountUid != ownerUid) return@launch
                val entity = SavedSessionEntity(
                    ownerUid = ownerUid,
                    timestamp = System.currentTimeMillis(),
                    vehicleLabel = "${v.modelYear} Camaro ${v.engine.rpo} (${v.market.code})",
                    vin = v.vin,
                    adapterName = adapterName,
                    dtcSummary = if (diagnosticCodes.isEmpty()) "0 DTC" else diagnosticCodes.joinToString(", "),
                    mileageSummary = report?.let {
                        localizedMileageSummaryForStorage(it)
                    } ?: localizedContext.getString(R.string.mileage_audit_not_run),
                    hasMileageDiscrepancy = report?.hasDiscrepancy ?: false,
                    maxDeltaKm = report?.maxDeltaKm ?: 0.0
                )
                if (authenticatedAccountUid != ownerUid ||
                    !isCurrentAccountGeneration(exportGeneration) || !hasPremiumLicense()) return@launch
                val backup = JSONObject()
                    .put("schema", "camarodiag-diagnostic-session-v1")
                    .put("timestamp", entity.timestamp)
                    .put("vehicle", entity.vehicleLabel)
                    .put("vin", entity.vin)
                    .put("adapter", entity.adapterName)
                    .put("dtcs", JSONArray(diagnosticCodes))
                    .put("mileageAuditPerformed", report != null)
                    .put("mileageSummary", entity.mileageSummary)
                    .put("hasMileageDiscrepancy", entity.hasMileageDiscrepancy)
                    .put("maxDeltaKm", entity.maxDeltaKm)
                val savedPath = CamaroDiagExportStorage.write(getApplication<Application>(),
                    "CamaroDiag_session_${entity.timestamp}.json", "application/json",
                    category = CamaroDiagExportStorage.Category.DIAGNOSTIC_SESSIONS, beforePublish = { check(authenticatedAccountUid == ownerUid &&
                        isCurrentAccountGeneration(exportGeneration) && hasPremiumLicense()) }
                ) { it.write(backup.toString(2).toByteArray(Charsets.UTF_8)) }
                if (authenticatedAccountUid != ownerUid ||
                    !isCurrentAccountGeneration(exportGeneration) || !hasPremiumLicense()) return@launch
                sessionDao.insertSession(entity)
                if (isCurrentAccountGeneration(exportGeneration)) {
                    _connectionMessage.value = localizedContext.getString(R.string.msg_session_saved) +
                        "\n" + savedPath
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _connectionMessage.value = localizedContext.getString(R.string.msg_session_save_failed)
            }
        }
    }

    fun deleteSavedSession(session: SavedSessionEntity) {
        val ownerUid = authenticatedAccountUid ?: return
        if (session.ownerUid != ownerUid) return
        viewModelScope.launch {
            try {
                if (authenticatedAccountUid != ownerUid) return@launch
                sessionDao.deleteSessionForOwner(session.id, ownerUid)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _connectionMessage.value = localizedContext.getString(R.string.msg_session_delete_failed)
            }
        }
    }

    private fun refreshTopologyAndDefaults(runInitialScans: Boolean = false) {
        _modules.value = CamaroUdsProtocol.buildTopology(_vehicle.value, _adapter.value)
        if (runInitialScans && obdManager.isObdCommandChannelAvailable) {
            // Read the ECU VIN immediately after a successful OBD connection so that
            // the selected vehicle profile can be checked before the user starts diagnosis.
            // Keep this request ahead of the initial scans to avoid competing for the ELM channel.
            val account = accountSessionGeneration
            val connection = connectionAttemptGeneration
            val request = ++calibrationIdentityGeneration
            val profile = _vehicle.value
            fun current() = account == accountSessionGeneration && connection == connectionAttemptGeneration &&
                request == calibrationIdentityGeneration && profile == _vehicle.value
            viewModelScope.launch {
                if (!current()) return@launch
                try {
                    val sourceWasSimulated = obdManager.isSimulationActive || obdManager.isDemoMode
                    val identity = obdManager.withObdCommandSession { engine -> engine.readEcuIdentification() }
                    if (!current()) return@launch
                    val comparison = VehicleVinMatcher.compare(profile.vin, identity.vin)
                    _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                        ecuVinResponse = identity.vin.orEmpty(),
                        ecuIdentityFromSimulation = sourceWasSimulated ||
                        obdManager.isSimulationActive || obdManager.isDemoMode,
                        vinComparison = comparison,
                        vinModelYear = VehicleVinMatcher.modelYearFromVin(identity.vin),
                        ecuNameResponse = identity.ecuNames.joinToString("\n"),
                        respondingEcuAddresses = identity.respondingEcuAddresses,
                        calibrationIdResponse = identity.calibrationIds.joinToString("\n"),
                        cvnResponse = identity.calibrationVerificationNumbers.joinToString("\n")
                    )
                    // Do not automatically interpret or audit data against the wrong saved vehicle.
                    // The user can resolve the mismatch first, then start diagnostics explicitly.
                    if (comparison?.matches != false) {
                        scanDtcs()
                        runMileageAudit()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!current()) return@launch
                    val connectionLost = ObdTransportFailurePolicy.isConnectionLoss(error)
                    if (connectionLost) reportConnectionLoss(error)
                    _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                        ecuVinResponse = "",
                        ecuIdentityFromSimulation = false,
                        vinComparison = null,
                        vinModelYear = null,
                        ecuNameResponse = "",
                        respondingEcuAddresses = emptyList(),
                        calibrationIdResponse = "",
                        cvnResponse = ""
                    )
                    if (!connectionLost) {
                        scanDtcs()
                        runMileageAudit()
                    }
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
            val sensorRecency = LiveSignalRecency()

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
                    throw ObdTransportDisconnectedException("Live OBD transport is disconnected")
                }
                return CamaroUdsProtocol.parsePidBytes(
                    response, pid, primaryPowertrainResponderOnly = true
                )
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
                                _liveTelemetryMessage.value = localizedContext.getString(R.string.msg_live_simulation_active)
                            }
                            obdManager.isDemoMode -> {
                                _livePids.value = emptyList()
                                _liveTelemetryMessage.value = localizedContext.getString(R.string.msg_live_no_adapter)
                            }
                            !obdManager.isStandardObdReadAvailable() -> {
                                _livePids.value = emptyList()
                                _liveTelemetryMessage.value = localizedContext.getString(R.string.msg_live_j2534_only)
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
                                // SAE J1979 Mode 01 PID 0x52: ethanol fuel percentage (A * 100 / 255).
                                // This is reported only when the ECU advertises/supports the PID; trims are not an ethanol sensor.
                                CamaroUdsProtocol.decodeEthanolPercent(read("52"))?.let { ethanolPercent ->
                                    values += LivePidValue(
                                        "ETHANOL",
                                        "Ethanol content (SAE PID 0x52)",
                                        "Teneur en éthanol (PID SAE 0x52)",
                                        ethanolPercent,
                                        "%",
                                        ethanolPercent,
                                        "%",
                                        0.0,
                                        100.0,
                                        push("ETHANOL", ethanolPercent)
                                    )
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
                                // Two-bank SAE J1979 wideband voltage family:
                                // 24/25 = B1S1/B1S2, 28/29 = B2S1/B2S2.
                                for ((pid, id) in listOf(
                                    "24" to "WBO2_B1S1", "25" to "WBO2_B1S2",
                                    "28" to "WBO2_B2S1", "29" to "WBO2_B2S2"
                                )) {
                                    CamaroUdsProtocol.decodeWidebandLambda(read(pid))?.let { v ->
                                        values += LivePidValue(id, id, id, v, "λ", v, "λ",
                                            0.0, 2.0, push(id, v))
                                    }
                                }
                                // The current family is distinct: 34/35 = B1, 38/39 = B2.
                                // Do not pass these amperage readings off as voltage data.
                                for ((pid, id) in listOf(
                                    "34" to "WBO2_I_B1S1", "35" to "WBO2_I_B1S2",
                                    "38" to "WBO2_I_B2S1", "39" to "WBO2_I_B2S2"
                                )) {
                                    CamaroUdsProtocol.decodeWidebandCurrent(read(pid))?.let { v ->
                                        values += LivePidValue(id, id, id, v, "mA", v, "mA",
                                            -128.0, 128.0, push(id, v))
                                    }
                                }
                                CamaroUdsProtocol.decodeAbsoluteLoadPercent(read("43"))?.let { v ->
                                    // Graph-scale ceiling is not the SAE encoded maximum (25700%). 
                                    // Keep normal engine-load changes visible without clipping measured values.
                                    values += LivePidValue("ABS_LOAD", "Absolute Engine Load", "Charge moteur absolue", v, "%", v, "%", 0.0, maxOf(300.0, v * 1.2), push("ABS_LOAD", v))
                                }
                                CamaroUdsProtocol.decodeCommandedEquivalenceRatio(read("44"))?.let { v ->
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
                                val now = System.currentTimeMillis()
                                values.forEach {
                                    mergedValues[it.id] = it
                                    sensorRecency.record(it.id, now)
                                }
                                _livePids.value = mergedValues.values.filter {
                                    sensorRecency.isRecent(it.id, now)
                                }
                                valuesForRecording = values
                                _liveTelemetryMessage.value = if (_livePids.value.isEmpty()) {
                                    localizedContext.getString(R.string.msg_live_no_pid_response)
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
                        val connectionLost = ObdTransportFailurePolicy.isConnectionLoss(error)
                        if (connectionLost) {
                            // A physical disconnect stops polling; retain the dedicated
                            // connection-loss message instead of displaying a generic PID error.
                            reportConnectionLoss(error)
                        } else {
                            consecutiveFailures++
                            _liveTelemetryMessage.value =
                                localizedContext.getString(R.string.msg_live_read_failed)
                            if (_liveRecordingState.value.isRecording) {
                                stopLiveRecording()
                                _liveRecordingState.value = _liveRecordingState.value.copy(
                                    message = localizedContext.getString(R.string.msg_live_recording_stopped)
                                )
                            }
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
        nextConnectionGeneration()
        val activeJobs = listOfNotNull(
            canMonitorJob, canRequestJob, detachedCanMonitorJob,
            detachedCanRequestJob, connectionAttemptJob
        )
            .distinct()
        canMonitorJob = null
        canRequestJob = null
        detachedCanMonitorJob = null
        detachedCanRequestJob = null
        connectionAttemptJob = null
        activeJobs.forEach(Job::cancel)
        consoleEventJournal.dispose()
        consoleEventJournalFailed = true
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
