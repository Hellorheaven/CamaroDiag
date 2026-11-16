package com.camarodiag.gen5.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import com.camarodiag.gen5.R
import androidx.lifecycle.viewModelScope
import com.camarodiag.gen5.data.db.DiagnosticDatabase
import com.camarodiag.gen5.data.db.SavedSessionEntity
import com.camarodiag.gen5.data.obd.BluetoothObdManager
import com.camarodiag.gen5.data.obd.CamaroUdsProtocol
import com.camarodiag.gen5.data.obd.CalibrationFileInspector
import com.camarodiag.gen5.data.obd.CalibrationBinaryComparer
import com.camarodiag.gen5.data.obd.CalibrationTableCodec
import com.camarodiag.gen5.data.obd.ObdbCamaroSignalSet
import com.camarodiag.gen5.data.obd.CamaroSignalCatalog
import com.camarodiag.gen5.data.obd.UdsAccessPolicy
import com.camarodiag.gen5.data.obd.ObdDtc
import com.camarodiag.gen5.data.obd.ObdScanReport
import com.camarodiag.gen5.mileageaudit.MileageAuditEngine
import com.camarodiag.gen5.domain.model.*
import kotlinx.coroutines.delay
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val obdManager = BluetoothObdManager(application)
    @Volatile private var obdbCamaroSignalSet: ObdbCamaroSignalSet? = null
    @Volatile private var camaroSignalCatalog: CamaroSignalCatalog? = null
    private val sessionDao = DiagnosticDatabase.getInstance(application).sessionDao()

    private val _language = MutableStateFlow(AppLanguage.fromLocale(application.resources.configuration.locales[0]))
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private val _unitSystem = MutableStateFlow(UnitSystem.METRIC)
    val unitSystem: StateFlow<UnitSystem> = _unitSystem.asStateFlow()

    private val _vehicle = MutableStateFlow(VehicleProfile())
    val vehicle: StateFlow<VehicleProfile> = _vehicle.asStateFlow()

    private val _adapter = MutableStateFlow(AdapterCapabilities())
    val adapter: StateFlow<AdapterCapabilities> = _adapter.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val pairedDevices: StateFlow<List<Pair<String, String>>> = _pairedDevices.asStateFlow()
    private val _usbDevices = MutableStateFlow<List<String>>(emptyList())
    val usbDevices: StateFlow<List<String>> = _usbDevices.asStateFlow()

    private val _connectionMessage = MutableStateFlow<String?>(null)
    val connectionMessage: StateFlow<String?> = _connectionMessage.asStateFlow()

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

    fun setLanguage(lang: AppLanguage) { _language.value = lang }


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
    fun setUnitSystem(units: UnitSystem) { _unitSystem.value = units }

    fun updateVehicle(
        year: Int = _vehicle.value.modelYear,
        engine: CamaroEngine = _vehicle.value.engine,
        transmission: TransmissionType = _vehicle.value.transmission,
        market: MarketRegion = _vehicle.value.market
    ) {
        val compatibleEngines = CamaroEngine.entries.filter { year in it.years }
        val safeEngine = if (engine in compatibleEngines) engine else compatibleEngines.first()
        _vehicle.value = _vehicle.value.copy(
            modelYear = year,
            engine = safeEngine,
            transmission = transmission,
            market = market
        )
        clearVehicleEvidence()
        refreshTopologyAndDefaults()
        refreshObdbSignalYear()
        refreshNativeSignalYear()
    }

    fun updateVin(vin: String) {
        _vehicle.value = _vehicle.value.copy(vin = vin.uppercase().filter { it.isLetterOrDigit() }.take(17))
        clearVehicleEvidence()
    }

    private fun clearVehicleEvidence() {
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
            calibrationIdResponse = "",
            cvnResponse = "",
            udsDidResponse = "",
            udsDtcResponse = "",
            udsMessage = ""
        )
    }

    private val _calibrationWorkspace = MutableStateFlow(CalibrationWorkspaceState())
    val calibrationWorkspace: StateFlow<CalibrationWorkspaceState> = _calibrationWorkspace.asStateFlow()

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
                        nativeSignalMessage = getApplication<Application>().getString(R.string.vm_native_catalog_loaded, signals.size, _vehicle.value.modelYear)
                    )
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                        nativeSignalMessage = error.localizedMessage ?: "Native Camaro signal catalogue could not be loaded."
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
                            obdbSignalMessage = getApplication<Application>().getString(R.string.vm_community_catalog_loaded, signals.size, _vehicle.value.modelYear)
                        )
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                            obdbSignalMessage = error.localizedMessage ?: "OBDb Camaro catalog could not be loaded."
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
            nativeSignalMessage = getApplication<Application>().getString(R.string.vm_native_catalog_loaded)
        )
    }

    private fun refreshObdbSignalYear() {
        val catalog = obdbCamaroSignalSet ?: return
        val signals = catalog.forYear(_vehicle.value.modelYear)
        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
            obdbSignals = signals,
            obdbSignalValue = "",
            obdbSignalMessage = getApplication<Application>().getString(R.string.vm_community_catalog_loaded)
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
                    obdbSignalMessage = error.localizedMessage ?: "OBDb signal read failed."
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
                    j2534BridgeMessage = error.localizedMessage ?: "Connexion au pont J2534 impossible."
                )
            }
        }
    }

    fun disconnectJ2534Bridge() {
        obdManager.disconnect()
        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
            isJ2534BridgeConnecting = false,
            isJ2534BridgeConnected = false,
            j2534BridgeMessage = getApplication<Application>().getString(R.string.vm_pc_bridge_disconnected)
        )
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
                    udsMessage = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_uds_read_failed)
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
                val dtcs = client.readDtcByStatusMask()
                val rendered = if (dtcs.isEmpty()) {
                    getApplication<Application>().getString(R.string.vm_no_dtcs_returned, responseCanIdHex.uppercase())
                } else {
                    dtcs.joinToString("\\n") { record ->
                        "0x${record.dtc.toString(16).uppercase().padStart(6, '0')}  status=0x${record.status.toString(16).uppercase().padStart(2, '0')}"
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
                    udsMessage = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_uds_dtc_read_failed)
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
                calibrationIdResponse = "",
                cvnResponse = "",
                message = ""
            )
            try {
                val (ecuVin, calIds, cvns) = obdManager.withObdCommandSession { engine ->
                    Triple(
                        engine.readVin(),
                        engine.readCalibrationIds(),
                        engine.readCalibrationVerificationNumbers()
                    )
                }
                val validCal = calIds.isNotEmpty()
                val validCvn = cvns.isNotEmpty()
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isReading = false,
                    ecuVinResponse = ecuVin ?: getApplication<Application>().getString(R.string.obd_not_available),
                    calibrationIdResponse = calIds.joinToString("\n").ifBlank { getApplication<Application>().getString(R.string.obd_not_available) },
                    cvnResponse = cvns.joinToString("\n").ifBlank { getApplication<Application>().getString(R.string.obd_not_available) },
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
                    message = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_cal_identifiers_failed)
                )
            }
        }
    }

    /** Imports a local calibration file for inventory and integrity checks only. */
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
                    inspectionFormat = inspection.format.label,
                    inspectionSummary = inspection.summary,
                    inspectionPayloadBytes = inspection.payloadBytes,
                    inspectionRecordCount = inspection.recordCount,
                    inspectionChecksumStatus = inspection.checksumValidation,
                    inspectionAddressRanges = inspection.addressRanges.map { "0x${it.start.toString(16).uppercase()}–0x${it.endInclusive.toString(16).uppercase()} (${it.length} octets)" },
                    inspectionWarnings = inspection.warnings,
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
                    message = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_cal_file_import_failed)
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
                require(current.importedFilePath != null && current.inspectionFormat == "BIN / raw binary") {
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
                    comparison.identical -> "Les deux images BIN sont identiques octet par octet."
                    comparison.referenceSize != comparison.modifiedSize -> "Comparaison de ${comparison.comparedBytes} octets communs ; les tailles des fichiers diffèrent."
                    else -> "${comparison.changedBytes} octets différents répartis dans ${comparison.changedRanges.size + comparison.omittedRangeCount} zone(s)."
                }
                val ranges = comparison.changedRanges.take(25).map {
                    "0x${it.startOffset.toString(16).uppercase().padStart(8, '0')}–0x${it.endOffsetInclusive.toString(16).uppercase().padStart(8, '0')} (${it.changedBytes} octets)"
                } + if (comparison.omittedRangeCount > 0) listOf("${comparison.omittedRangeCount} zone(s) supplémentaire(s) non affichées") else emptyList()
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
                    message = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_cal_comparison_failed)
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
                    mapError = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_cal_template_export_failed)
                )
            }
        }
    }

    /** Imports a semicolon-separated table-definition file supplied for the exact calibration image. */
    fun importCalibrationDefinitions(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = _calibrationWorkspace.value
                require(current.importedFilePath != null && current.inspectionFormat == "BIN / raw binary") {
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
                    mapDefinitionsWarnings = parsed.warnings + "OS et référence matérielle non lus par Mode 09 standard : vérification documentaire indépendante obligatoire.",
                    mapDirty = false,
                    mapError = null,
                    message = getApplication<Application>().getString(R.string.msg_cal_definitions_loaded, parsed.definitions.size)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    mapError = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_cal_definitions_import_failed),
                    message = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_cal_definitions_import_failed)
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
                    mapError = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_cal_map_read_failed)
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
                    inspectionSummary = inspection.summary,
                    inspectionPayloadBytes = inspection.payloadBytes,
                    inspectionRecordCount = inspection.recordCount,
                    inspectionChecksumStatus = inspection.checksumValidation,
                    inspectionAddressRanges = inspection.addressRanges.map {
                        "0x${it.start.toString(16).uppercase()}–0x${it.endInclusive.toString(16).uppercase()} (${it.length} octets)"
                    },
                    inspectionWarnings = inspection.warnings,
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
                    mapError = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_cal_table_edit_failed),
                    message = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_cal_table_edit_failed)
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
                    message = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_cal_file_export_failed)
                )
            }
        }
    }

    private fun markAdapterDisconnected() {
        _adapter.value = AdapterCapabilities()
        _modules.value = CamaroUdsProtocol.buildTopology(_vehicle.value, _adapter.value)
        clearVehicleEvidence()
        _isObdScanning.value = false
    }

    private fun connectWithResult(
        connect: suspend () -> Result<AdapterCapabilities>,
        fallbackMessage: String
    ) {
        viewModelScope.launch {
            beginBusyOperation()
            _connectionMessage.value = null
            markAdapterDisconnected()
            try {
                connect()
                    .onSuccess { capabilities ->
                        _adapter.value = capabilities
                        refreshTopologyAndDefaults(runInitialScans = true)
                    }
                    .onFailure { error ->
                        _connectionMessage.value = error.localizedMessage ?: fallbackMessage
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _connectionMessage.value = error.localizedMessage ?: fallbackMessage
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
                _connectionMessage.value = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_refresh_adapters_failed)
            }
        }
    }

    fun connectWifi(host: String, port: Int) {
        connectWithResult(
            connect = { obdManager.connectWifi(host.trim(), port) },
            fallbackMessage = getApplication<Application>().getString(R.string.msg_wifi_connection_failed)
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
            fallbackMessage = getApplication<Application>().getString(R.string.msg_usb_connection_failed)
        )
    }

    fun selectSimulatedAdapter(preset: String) {
        viewModelScope.launch {
            beginBusyOperation()
            markAdapterDisconnected()
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
                _connectionMessage.value = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_simulation_activation_failed)
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
            fallbackMessage = getApplication<Application>().getString(R.string.msg_bluetooth_connection_failed)
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
                    message = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_live_csv_export_failed)
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
                    _dtcs.value = emptyList()
                    _connectionMessage.value = if (obdManager.isJ2534BridgeConnected())
                        getApplication<Application>().getString(R.string.msg_dtc_j2534_unavailable)
                    else getApplication<Application>().getString(R.string.msg_dtc_adapter_required)
                } else {
                    val (confirmed, pending, permanent) = obdManager.withObdCommandSession { engine ->
                        engine.readDtcs()
                    }
                    _dtcs.value = mapObdDtcs(confirmed + pending + permanent)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _connectionMessage.value = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_dtc_scan_failed)
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

    private val standardDtcDescriptions = mapOf(
            "P0101" to ("Mass or Volume Air Flow Circuit Range/Performance" to "Débitmètre d'air : plage / performance"),
            "P0102" to ("Mass or Volume Air Flow Circuit Low Input" to "Débitmètre d'air : signal trop faible"),
            "P0103" to ("Mass or Volume Air Flow Circuit High Input" to "Débitmètre d'air : signal trop élevé"),
            "P0113" to ("Intake Air Temperature Sensor 1 Circuit High" to "Sonde de température d'air admission : signal élevé"),
            "P0128" to ("Coolant Thermostat (Coolant Temperature Below Thermostat Regulating Temperature)" to "Thermostat moteur : température sous le seuil de régulation"),
            "P0171" to ("System Too Lean (Bank 1)" to "Mélange trop pauvre (banc 1)"),
            "P0172" to ("System Too Rich (Bank 1)" to "Mélange trop riche (banc 1)"),
            "P0174" to ("System Too Lean (Bank 2)" to "Mélange trop pauvre (banc 2)"),
            "P0175" to ("System Too Rich (Bank 2)" to "Mélange trop riche (banc 2)"),
            "P0300" to ("Random/Multiple Cylinder Misfire Detected" to "Ratés d'allumage aléatoires / multiples"),
            "P0301" to ("Cylinder 1 Misfire Detected" to "Ratés d'allumage cylindre 1"),
            "P0302" to ("Cylinder 2 Misfire Detected" to "Ratés d'allumage cylindre 2"),
            "P0303" to ("Cylinder 3 Misfire Detected" to "Ratés d'allumage cylindre 3"),
            "P0304" to ("Cylinder 4 Misfire Detected" to "Ratés d'allumage cylindre 4"),
            "P0305" to ("Cylinder 5 Misfire Detected" to "Ratés d'allumage cylindre 5"),
            "P0306" to ("Cylinder 6 Misfire Detected" to "Ratés d'allumage cylindre 6"),
            "P0307" to ("Cylinder 7 Misfire Detected" to "Ratés d'allumage cylindre 7"),
            "P0308" to ("Cylinder 8 Misfire Detected" to "Ratés d'allumage cylindre 8"),
            "P0420" to ("Catalyst System Efficiency Below Threshold (Bank 1)" to "Efficacité du catalyseur sous le seuil (banc 1)"),
            "P0430" to ("Catalyst System Efficiency Below Threshold (Bank 2)" to "Efficacité du catalyseur sous le seuil (banc 2)"),
            "P0442" to ("EVAP System Small Leak Detected" to "Circuit EVAP : petite fuite détectée"),
            "P0455" to ("EVAP System Large Leak Detected" to "Circuit EVAP : fuite importante détectée"),
            "P0496" to ("EVAP Flow During Non-Purge Condition" to "Débit EVAP détecté hors phase de purge"),
            "P0507" to ("Idle Control System RPM Higher Than Expected" to "Régime de ralenti supérieur à la consigne"),
            "P06DD" to ("Engine Oil Pressure Control Solenoid Performance" to "Commande de pression d'huile moteur : performance"),
            "P1101" to ("Intake Air Flow System Performance" to "Système de débit d'air admission : performance"),
            "P0010" to ("Intake Camshaft Position Actuator Circuit (Bank 1)" to "Commande d'actionneur d'arbre à cames admission (banc 1)"),
            "P0011" to ("Intake Camshaft Position Timing Over-Advanced (Bank 1)" to "Calage arbre à cames admission trop avancé (banc 1)"),
            "P0014" to ("Exhaust Camshaft Position Timing Over-Advanced (Bank 1)" to "Calage arbre à cames échappement trop avancé (banc 1)"),
            "P0087" to ("Fuel Rail/System Pressure Too Low" to "Pression de rampe / circuit carburant trop faible"),
            "P0089" to ("Fuel Pressure Regulator Performance" to "Régulateur de pression carburant : performance"),
            "P0191" to ("Fuel Rail Pressure Sensor Circuit Range/Performance" to "Capteur de pression de rampe : plage / performance"),
            "P0299" to ("Turbocharger/Supercharger Underboost" to "Sous-pression de suralimentation"),
        )

    private fun standardDtcDescription(code: String, isFr: Boolean): String {
        val entry = standardDtcDescriptions[code]
            ?: return if (isFr) {
                "Description non disponible dans le dictionnaire local ; vérifier la documentation GM correspondant au VIN."
            } else {
                "Description unavailable in the local dictionary; check GM service information for this VIN."
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
                        _dtcs.value = emptyList()
                        _connectionMessage.value = null
                    } else {
                        _connectionMessage.value = getApplication<Application>().getString(R.string.msg_dtc_clear_not_confirmed, response)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _connectionMessage.value = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_dtc_clear_failed)
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
                _connectionMessage.value = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_mileage_audit_failed)
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
                _dtcs.value = mapObdDtcs(allDtcs)
                _obdScanMessage.value = if (obdManager.isSimulationActive) {
                    getApplication<Application>().getString(R.string.msg_full_scan_simulation_complete, report.supportedPids.size, allDtcs.size)
                } else {
                    getApplication<Application>().getString(R.string.msg_full_scan_complete, report.supportedPids.size, allDtcs.size)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _obdScanMessage.value = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_full_scan_failed)
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
                _connectionMessage.value = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_session_save_failed)
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
                _connectionMessage.value = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_session_delete_failed)
            }
        }
    }

    private fun refreshTopologyAndDefaults(runInitialScans: Boolean = false) {
        _modules.value = CamaroUdsProtocol.buildTopology(_vehicle.value, _adapter.value)
        if (runInitialScans && obdManager.isObdCommandChannelAvailable) {
            scanDtcs()
            runMileageAudit()
        }
    }

    private fun startLiveTelemetryLoop() {
        viewModelScope.launch {
            var tick = 0.0
            var consecutiveFailures = 0
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
                val response = obdManager.readMode01PidIfSupported(pidValue) ?: return null
                if (response.equals("NO_SOCKET", ignoreCase = true) ||
                    response.contains("UNABLE TO CONNECT", ignoreCase = true)) {
                    throw IllegalStateException("La connexion OBD a été interrompue.")
                }
                return CamaroUdsProtocol.parsePidBytes(response, pid)
            }

            while (currentCoroutineContext().isActive) {
                if (_isStreamingLive.value) {
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
                                read("42")?.takeIf { it.size >= 2 }?.let { b ->
                                    val v = (b[0] * 256 + b[1]) / 1000.0
                                    values += LivePidValue("BAT", "Control Module Voltage", "Tension calculateur", v, "V", v, "V", 9.0, 16.0, push("BAT", v))
                                }
                                _livePids.value = values
                                _liveTelemetryMessage.value = if (values.isEmpty()) {
                                    getApplication<Application>().getString(R.string.msg_live_no_pid_response)
                                } else {
                                    null
                                }
                            }
                        }
                        captureLiveTelemetrySample(_livePids.value)
                        consecutiveFailures = 0
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        consecutiveFailures++
                        _liveTelemetryMessage.value = error.localizedMessage ?: getApplication<Application>().getString(R.string.msg_live_read_failed)
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
        super.onCleared()
        obdManager.disconnect()
    }
}
