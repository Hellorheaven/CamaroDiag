package com.camarodiag.gen5.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.camarodiag.gen5.data.db.DiagnosticDatabase
import com.camarodiag.gen5.data.db.SavedSessionEntity
import com.camarodiag.gen5.data.obd.BluetoothObdManager
import com.camarodiag.gen5.data.obd.CamaroUdsProtocol
import com.camarodiag.gen5.mileageaudit.MileageAuditEngine
import com.camarodiag.gen5.domain.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random
import java.security.MessageDigest
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val obdManager = BluetoothObdManager(application)
    private val sessionDao = DiagnosticDatabase.getInstance(application).sessionDao()

    private val _language = MutableStateFlow(AppLanguage.FR)
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

    private val _modules = MutableStateFlow<List<EcuModule>>(emptyList())
    val modules: StateFlow<List<EcuModule>> = _modules.asStateFlow()

    private val _dtcs = MutableStateFlow<List<DiagnosticDtc>>(emptyList())
    val dtcs: StateFlow<List<DiagnosticDtc>> = _dtcs.asStateFlow()

    private val _livePids = MutableStateFlow<List<LivePidValue>>(emptyList())
    val livePids: StateFlow<List<LivePidValue>> = _livePids.asStateFlow()

    private val _isStreamingLive = MutableStateFlow(true)
    val isStreamingLive: StateFlow<Boolean> = _isStreamingLive.asStateFlow()

    private val _mileageReport = MutableStateFlow<MileageAuditReport?>(null)
    val mileageReport: StateFlow<MileageAuditReport?> = _mileageReport.asStateFlow()

    val savedSessions: StateFlow<List<SavedSessionEntity>> = sessionDao.observeAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshTopologyAndDefaults()
        startLiveTelemetryLoop()
    }

    fun setLanguage(lang: AppLanguage) { _language.value = lang }
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
        refreshTopologyAndDefaults()
    }

    fun updateVin(vin: String) {
        _vehicle.value = _vehicle.value.copy(vin = vin.uppercase().filter { it.isLetterOrDigit() }.take(17))
    }

    private val _calibrationWorkspace = MutableStateFlow(CalibrationWorkspaceState())
    val calibrationWorkspace: StateFlow<CalibrationWorkspaceState> = _calibrationWorkspace.asStateFlow()

    /** Reads only standardized OBD Mode 09 calibration identifiers; this is not a binary map read. */
    fun readCalibrationIdentification() {
        viewModelScope.launch {
            if (obdManager.isDemoMode) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    message = "Mode démo ou interface non connectée : aucune donnée ECU réelle n'a été lue."
                )
                return@launch
            }
            _calibrationWorkspace.value = _calibrationWorkspace.value.copy(isReading = true, message = "")
            try {
                val calId = obdManager.sendAtCommand("0904", 3500L)
                val cvn = obdManager.sendAtCommand("0906", 3500L)
                val validCal = calId.isNotBlank() && !calId.contains("NO_SOCKET") && !calId.contains("NO DATA", true)
                val validCvn = cvn.isNotBlank() && !cvn.contains("NO_SOCKET") && !cvn.contains("NO DATA", true)
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isReading = false,
                    calibrationIdResponse = if (validCal) calId else "",
                    cvnResponse = if (validCvn) cvn else "",
                    message = if (validCal || validCvn) {
                        "Identifiants Mode 09 lus. Ils ne représentent pas le contenu binaire de la cartographie."
                    } else {
                        "Aucun CAL ID/CVN exploitable retourné. Le calculateur ou l'interface peut ne pas prendre ces PID en charge."
                    }
                )
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isReading = false,
                    message = error.localizedMessage ?: "Échec de lecture des identifiants ECU."
                )
            }
        }
    }

    /** Imports a local calibration file for inventory and integrity checks only. */
    fun importCalibrationFile(uri: Uri) {
        viewModelScope.launch {
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
                val storageDir = File(getApplication<Application>().filesDir, "calibrations")
                check(storageDir.exists() || storageDir.mkdirs()) { "Impossible de créer le dossier local des cartographies." }
                val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "calibration.bin" }
                val storedFile = File(storageDir, "${System.currentTimeMillis()}_${safeName}")
                storedFile.writeBytes(bytes)
                val preview = bytes.take(64).joinToString(" ") { "%02X".format(it) }
                _calibrationWorkspace.value.importedFilePath?.let { previousPath ->
                    if (previousPath != storedFile.absolutePath) runCatching { File(previousPath).delete() }
                }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    importedFileName = displayName,
                    importedFileSize = bytes.size.toLong(),
                    importedFileSha256 = sha256,
                    importedFilePreview = preview,
                    importedFilePath = storedFile.absolutePath,
                    message = "Fichier copié dans le stockage privé de l'application. Compatibilité ECU et checksum constructeur non vérifiés."
                )
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    message = error.localizedMessage ?: "Import du fichier impossible."
                )
            }
        }
    }

    fun clearCalibrationFile() {
        _calibrationWorkspace.value.importedFilePath?.let { path -> runCatching { File(path).delete() } }
        _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
            importedFileName = null,
            importedFileSize = null,
            importedFileSha256 = null,
            importedFilePreview = null,
            importedFilePath = null,
            message = ""
        )
    }

    /** Exports the selected local calibration file through Android's Storage Access Framework. */
    fun exportCalibrationFile(destinationUri: Uri) {
        viewModelScope.launch {
            try {
                val sourcePath = _calibrationWorkspace.value.importedFilePath
                    ?: error("Aucun fichier de calibration n'est chargé.")
                val source = File(sourcePath)
                check(source.isFile && source.exists()) { "Le fichier local de calibration est introuvable." }
                val output = getApplication<Application>().contentResolver.openOutputStream(destinationUri, "w")
                    ?: error("Impossible de créer le fichier de destination.")
                source.inputStream().use { input ->
                    output.use { target -> input.copyTo(target) }
                }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    message = "Fichier exporté. Cette opération sauvegarde le fichier local et ne programme pas l'ECU."
                )
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    message = error.localizedMessage ?: "Export du fichier impossible."
                )
            }
        }
    }

    fun refreshPairedDevices() {
        viewModelScope.launch {
            _pairedDevices.value = obdManager.getPairedDevices()
            _usbDevices.value = obdManager.getUsbDevices()
            val nearbyBle = obdManager.scanNearbyBleDevices()
            _pairedDevices.value = (_pairedDevices.value + nearbyBle)
                .distinctBy { it.second }
                .sortedByDescending { it.first.contains("OBD", true) || it.first.contains("ELM", true) || it.first.contains("vLinker", true) }
        }
    }

    fun connectWifi(host: String, port: Int) {
        viewModelScope.launch {
            _isBusy.value = true
            _connectionMessage.value = null
            obdManager.connectWifi(host.trim(), port).onSuccess { caps -> _adapter.value = caps; refreshTopologyAndDefaults() }
                .onFailure { _connectionMessage.value = it.localizedMessage ?: "Wi-Fi OBD connection failed" }
            _isBusy.value = false
        }
    }

    fun connectUsb(deviceLabel: String) {
        val id = Regex("#(\\d+)").find(deviceLabel)?.groupValues?.get(1)?.toIntOrNull()
        if (id == null) { _connectionMessage.value = "USB device identifier unavailable"; return }
        viewModelScope.launch {
            _isBusy.value = true
            _connectionMessage.value = null
            val baudRate = if (deviceLabel.contains("OBDLink", true)) 115200 else 38400
            obdManager.connectUsb(id, baudRate).onSuccess { caps -> _adapter.value = caps; refreshTopologyAndDefaults() }
                .onFailure { _connectionMessage.value = it.localizedMessage ?: "USB OBD connection failed" }
            _isBusy.value = false
        }
    }

    fun selectSimulatedAdapter(preset: String) {
        viewModelScope.launch {
            _isBusy.value = true
            val caps = obdManager.activateSimulatedAdapter(preset)
            _adapter.value = caps
            _connectionMessage.value = null
            refreshTopologyAndDefaults()
            _isBusy.value = false
        }
    }

    fun connectRealBluetooth(macAddress: String) {
        val selectedName = _pairedDevices.value.firstOrNull { it.second == macAddress }?.first.orEmpty()
        if (selectedName.contains("OBDLink CX", ignoreCase = true)) {
            _connectionMessage.value = "OBDLink CX détecté en BLE, mais le fabricant indique qu’il n’est pas compatible avec les véhicules GM. Utilisez MX+, MX, LX ou une interface GM compatible."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            _connectionMessage.value = null
            val res = obdManager.connectAndProbe(macAddress)
            res.onSuccess { caps ->
                _adapter.value = caps
                refreshTopologyAndDefaults()
            }.onFailure { err ->
                _connectionMessage.value = err.localizedMessage ?: "Bluetooth SPP connection failed"
            }
            _isBusy.value = false
        }
    }

    fun toggleLiveStream() {
        _isStreamingLive.value = !_isStreamingLive.value
    }

    fun scanDtcs() {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                if (obdManager.isDemoMode) {
                    _dtcs.value = listOf(
                        DiagnosticDtc("P0171", "ECM", "Sample / Confirmed", "Exemple / Confirmé", "System Too Lean (Bank 1)", "Mélange trop pauvre (Banc 1)"),
                        DiagnosticDtc("P0014", "ECM", "Sample / Pending", "Exemple / En attente", "Exhaust Camshaft Position Timing Over-Advanced (Bank 1)", "Calage arbre à cames échappement trop avancé (Banc 1)", true)
                    )
                } else {
                    val confirmed = obdManager.sendAtCommand("03", 2500L)
                    val pending = obdManager.sendAtCommand("07", 2500L)
                    _dtcs.value = CamaroUdsProtocol.parseDtcResponses(confirmed, pending)
                }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun clearDtcs() {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                if (obdManager.isDemoMode) {
                    _dtcs.value = emptyList()
                } else {
                    val response = obdManager.sendAtCommand("04", 2500L)
                    if (response.contains("44", ignoreCase = true) || response.contains("OK", ignoreCase = true)) {
                        _dtcs.value = emptyList()
                        _connectionMessage.value = null
                    } else {
                        _connectionMessage.value = "Effacement non confirmé par le calculateur : $response"
                    }
                }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun runMileageAudit() {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val readings = mutableListOf<MileageSourceReading>()
                if (obdManager.isDemoMode) {
                    readings += MileageSourceReading(
                        "DEMO", "Demo data only", "Données de démonstration uniquement",
                        "No vehicle ECU queried", null, ConfidenceLevel.UNAVAILABLE,
                        "Demo mode never represents the actual vehicle mileage.",
                        "Le mode démo ne représente jamais le kilométrage réel du véhicule."
                    )
                } else {
                    val a6Response = obdManager.sendAtCommand("01A6", 1800L)
                    val a6Km = CamaroUdsProtocol.parseOdometerPidA6(a6Response)
                    readings += MileageSourceReading(
                        "ECM_A6", "OBD odometer (PID A6)", "Odomètre OBD (PID A6)",
                        "Mode 01 PID A6 • $a6Response", a6Km,
                        if (a6Km != null) ConfidenceLevel.MODERATE else ConfidenceLevel.UNAVAILABLE,
                        if (a6Km != null) "ECU-reported OBD odometer value. It is one ECU datum, not an independent cross-module check." else "PID A6 not supported or no valid response.",
                        if (a6Km != null) "Valeur odométrique déclarée par l'ECU. Il s'agit d'une donnée d'un calculateur, pas d'une comparaison intermodules indépendante." else "PID A6 non pris en charge ou réponse invalide."
                    )
                    val d3Response = obdManager.sendAtCommand("01D3", 1800L)
                    val d3Km = CamaroUdsProtocol.parseOdometerPidD3(d3Response)
                    readings += MileageSourceReading(
                        "ECM_D3", "Engine odometer (PID D3)", "Odomètre moteur (PID D3)",
                        "Mode 01 PID D3 • $d3Response", d3Km,
                        if (d3Km != null) ConfidenceLevel.MODERATE else ConfidenceLevel.UNAVAILABLE,
                        if (d3Km != null) "Engine ECU value only; not an independent BCM/IPC confirmation." else "PID D3 not supported or no valid response.",
                        if (d3Km != null) "Valeur du calculateur moteur uniquement ; ce n'est pas une confirmation indépendante BCM/IPC." else "PID D3 non pris en charge ou réponse invalide."
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
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun saveCurrentSession() {
        viewModelScope.launch {
            val v = _vehicle.value
            val report = _mileageReport.value
            val entity = SavedSessionEntity(
                timestamp = System.currentTimeMillis(),
                vehicleLabel = "${v.modelYear} Camaro ${v.engine.rpo} (${v.market.code})",
                vin = v.vin,
                adapterName = _adapter.value.deviceName,
                dtcSummary = if (_dtcs.value.isEmpty()) "0 DTC" else _dtcs.value.joinToString(", ") { it.code },
                mileageSummary = report?.let {
                    if (_language.value == AppLanguage.FR) it.summaryFr else it.summaryEn
                } ?: "Audit non exécuté",
                hasMileageDiscrepancy = report?.hasDiscrepancy ?: false,
                maxDeltaKm = report?.maxDeltaKm ?: 0.0
            )
            sessionDao.insertSession(entity)
        }
    }

    fun deleteSavedSession(session: SavedSessionEntity) {
        viewModelScope.launch {
            sessionDao.deleteSession(session)
        }
    }

    private fun refreshTopologyAndDefaults() {
        _modules.value = CamaroUdsProtocol.buildTopology(_vehicle.value, _adapter.value)
        viewModelScope.launch {
            scanDtcs()
            runMileageAudit()
        }
    }

    private fun startLiveTelemetryLoop() {
        viewModelScope.launch {
            var tick = 0.0
            val histories = mutableMapOf<String, ArrayDeque<Double>>()
            fun push(id: String, value: Double): List<Double> {
                val queue = histories.getOrPut(id) { ArrayDeque() }
                if (queue.size >= 24) queue.removeFirst()
                queue.addLast(value)
                return queue.toList()
            }

            while (true) {
                if (_isStreamingLive.value) {
                    if (obdManager.isDemoMode) {
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
                            LivePidValue("STFT", "Short Term Fuel Trim B1 (DEMO)", "Correction carburant B1 (DÉMO)", stft, "%", stft, "%", -15.0, 15.0, push("STFT", stft)),
                            LivePidValue("BAT", "Control Module Voltage (DEMO)", "Tension calculateur (DÉMO)", voltage, "V", voltage, "V", 11.5, 15.2, push("BAT", voltage))
                        )
                    } else {
                        val values = mutableListOf<LivePidValue>()
                        suspend fun read(pid: String): IntArray? = CamaroUdsProtocol.parsePidBytes(obdManager.sendAtCommand("01$pid", 700L), pid)
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
                    }
                }
                delay(if (obdManager.isDemoMode) 450 else 900)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        obdManager.disconnect()
    }
}
