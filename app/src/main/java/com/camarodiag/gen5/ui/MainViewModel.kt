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
import com.camarodiag.gen5.data.obd.CalibrationFileInspector
import com.camarodiag.gen5.data.obd.CalibrationBinaryComparer
import com.camarodiag.gen5.data.obd.CalibrationTableCodec
import com.camarodiag.gen5.data.obd.ObdScanReport
import com.camarodiag.gen5.mileageaudit.MileageAuditEngine
import com.camarodiag.gen5.domain.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                val engine = obdManager.createObd2FeatureEngine()
                val calIds = engine.readCalibrationIds()
                val cvns = engine.readCalibrationVerificationNumbers()
                val validCal = calIds.isNotEmpty()
                val validCvn = cvns.isNotEmpty()
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    isReading = false,
                    calibrationIdResponse = calIds.joinToString("\\n").ifBlank { "Non disponible" },
                    cvnResponse = cvns.joinToString("\\n").ifBlank { "Non disponible" },
                    message = if (validCal || validCvn) {
                        "CAL ID / CVN décodés depuis Mode 09. Ils identifient le logiciel mais ne constituent pas une lecture de cartographie binaire."
                    } else {
                        "Aucun CAL ID/CVN exploitable retourné. Le calculateur ou l'interface peut ne pas prendre ces informations en charge."
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
                    message = "Fichier copié localement. L'analyse de format ne prouve pas sa compatibilité ECU ni la validité d'un checksum GM."
                )
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    message = error.localizedMessage ?: "Import du fichier impossible."
                )
            }
        }
    }

    /** Imports a second raw BIN image and compares it byte-for-byte with the loaded reference. */
    fun importComparisonCalibrationFile(uri: Uri) {
        viewModelScope.launch {
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
                    message = "Comparaison locale terminée. Les écarts ne sont pas interprétés comme des tables moteur et ne valident pas la compatibilité d'un flash."
                )
            } catch (error: Exception) {
                storedFile?.let { runCatching { it.delete() } }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    message = error.localizedMessage ?: "Comparaison des fichiers impossible."
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
                    message = "Modèle TSV exporté. Remplace les exemples par les offsets et facteurs vérifiés de la calibration exacte."
                )
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    mapError = error.localizedMessage ?: "Export du modèle TSV impossible."
                )
            }
        }
    }

    /** Imports a semicolon-separated table-definition file supplied for the exact calibration image. */
    fun importCalibrationDefinitions(uri: Uri) {
        viewModelScope.launch {
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
                val workingFile = File(current.activeFilePath ?: current.importedFilePath!!)
                check(workingFile.isFile && workingFile.exists()) { "L'image BIN active est introuvable." }
                val first = parsed.definitions.first()
                val decoded = CalibrationTableCodec.decode(workingFile.readBytes(), first)
                _calibrationWorkspace.value = current.copy(
                    mapDefinitions = parsed.definitions,
                    selectedMapId = first.id,
                    mapValues = decoded.values.map { row -> row.map(CalibrationTableCodec::formatEngineeringValue) },
                    mapDefinitionsWarnings = parsed.warnings,
                    mapDirty = false,
                    mapError = null,
                    message = "${parsed.definitions.size} table(s) chargée(s). Les adresses proviennent de ton fichier de définitions et ne sont pas certifiées par CamaroDiag."
                )
            } catch (error: Exception) {
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    mapError = error.localizedMessage ?: "Import des définitions impossible.",
                    message = error.localizedMessage ?: "Import des définitions impossible."
                )
            }
        }
    }

    fun selectCalibrationMap(mapId: String) {
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
                mapDirty = false,
                mapError = null
            )
        } catch (error: Exception) {
            _calibrationWorkspace.value = current.copy(mapError = error.localizedMessage ?: "Lecture de la table impossible.")
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
        viewModelScope.launch {
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
                    mapDirty = false,
                    mapError = null,
                    message = "Table enregistrée dans une nouvelle image locale. L'original importé reste conservé. Aucun octet n'a été envoyé à l'ECU."
                )
            } catch (error: Exception) {
                createdFile?.let { runCatching { it.delete() } }
                _calibrationWorkspace.value = _calibrationWorkspace.value.copy(
                    mapError = error.localizedMessage ?: "Modification de la table impossible.",
                    message = error.localizedMessage ?: "Modification de la table impossible."
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
        viewModelScope.launch {
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

    /** Records actual live OBD samples; demo values are deliberately excluded. */
    fun toggleLiveRecording() {
        if (_liveRecordingState.value.isRecording) {
            stopLiveRecording()
            return
        }
        if (obdManager.isDemoMode) {
            _liveRecordingState.value = _liveRecordingState.value.copy(
                message = "Enregistrement réel indisponible en mode démo ou sans interface connectée."
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
            message = "Enregistrement des données OBD réelles démarré."
        )
    }

    fun stopLiveRecording() {
        if (!_liveRecordingState.value.isRecording) return
        _liveRecordingState.value = _liveRecordingState.value.copy(
            isRecording = false,
            stoppedAtMillis = System.currentTimeMillis(),
            message = "Enregistrement arrêté : ${recordedLiveSamples.size} échantillon(s) capturé(s)."
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
                    message = "Journal CSV exporté : ${snapshot.size} échantillon(s)."
                )
            } catch (error: Exception) {
                _liveRecordingState.value = _liveRecordingState.value.copy(
                    message = error.localizedMessage ?: "Export du journal CSV impossible."
                )
            }
        }
    }

    private fun captureLiveTelemetrySample(values: List<LivePidValue>) {
        if (!_liveRecordingState.value.isRecording) return
        if (obdManager.isDemoMode) {
            stopLiveRecording()
            _liveRecordingState.value = _liveRecordingState.value.copy(
                message = "Connexion réelle perdue : journal arrêté pour ne pas mélanger des données de démonstration."
            )
            return
        }
        if (values.isEmpty()) return
        if (recordedLiveSamples.size >= maxLiveRecordingSamples) {
            _liveRecordingState.value = _liveRecordingState.value.copy(
                isRecording = false,
                stoppedAtMillis = System.currentTimeMillis(),
                sampleCount = recordedLiveSamples.size,
                message = "Limite de sécurité atteinte ($maxLiveRecordingSamples échantillons) : enregistrement arrêté automatiquement."
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
            message = "Enregistrement en cours : ${recordedLiveSamples.size} échantillon(s)."
        )
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
                    val (confirmed, pending, permanent) = obdManager.createObd2FeatureEngine().readDtcs()
                    _dtcs.value = (confirmed + pending + permanent).distinctBy { it.code to it.status }.map { dtc ->
                        val pendingCode = dtc.status == "pending"
                        DiagnosticDtc(
                            code = dtc.code,
                            module = "ECM",
                            statusEn = when (dtc.status) { "pending" -> "Pending"; "permanent" -> "Permanent"; else -> "Confirmed" },
                            statusFr = when (dtc.status) { "pending" -> "En attente"; "permanent" -> "Permanent"; else -> "Confirmé" },
                            descEn = standardDtcDescription(dtc.code, false),
                            descFr = standardDtcDescription(dtc.code, true),
                            isPending = pendingCode
                        )
                    }
                }
            } catch (error: Exception) {
                _connectionMessage.value = error.localizedMessage ?: "Échec du scan des défauts."
            } finally {
                _isBusy.value = false
            }
        }
    }

    private fun standardDtcDescription(code: String, isFr: Boolean): String {
        val descriptions = mapOf(
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
        val entry = descriptions[code] ?: return if (isFr) "Description non disponible dans le dictionnaire local ; vérifier la documentation GM correspondant au VIN." else "Description unavailable in the local dictionary; check GM service information for this VIN."
        return if (isFr) entry.second else entry.first
    }

    fun clearDtcs() {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                if (obdManager.isDemoMode) {
                    _dtcs.value = emptyList()
                } else {
                    val response = obdManager.sendAtCommand("04", 2500L)
                    if (Regex("\\b44\\b").containsMatchIn(response.uppercase())) {
                        _dtcs.value = emptyList()
                        _connectionMessage.value = null
                    } else {
                        _connectionMessage.value = "Effacement non confirmé par une réponse positive Mode 04 : $response"
                    }
                }
            } catch (error: Exception) {
                _connectionMessage.value = error.localizedMessage ?: "Échec de l'effacement des défauts."
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

    /** Executes a complete, standards-based read-only OBD-II scan on the connected Camaro. */
    fun runFullObdScan() {
        viewModelScope.launch {
            if (obdManager.isDemoMode) {
                _obdScanReport.value = null
                _obdScanMessage.value = "Mode démo : aucun calculateur réel n'est interrogé."
                return@launch
            }
            _isObdScanning.value = true
            _obdScanMessage.value = null
            try {
                val report = obdManager.createObd2FeatureEngine().fullReadOnlyScan()
                _obdScanReport.value = report
                val allDtcs = report.confirmedDtcs + report.pendingDtcs + report.permanentDtcs
                _dtcs.value = allDtcs.distinctBy { it.code to it.status }.map { dtc ->
                    DiagnosticDtc(
                        code = dtc.code,
                        module = "ECM",
                        statusEn = when (dtc.status) { "pending" -> "Pending"; "permanent" -> "Permanent"; else -> "Confirmed" },
                        statusFr = when (dtc.status) { "pending" -> "En attente"; "permanent" -> "Permanent"; else -> "Confirmé" },
                        descEn = standardDtcDescription(dtc.code, false),
                        descFr = standardDtcDescription(dtc.code, true),
                        isPending = dtc.status == "pending"
                    )
                }
                _obdScanMessage.value = "Scan OBD-II terminé : ${report.supportedPids.size} PIDs supportés, ${allDtcs.size} défaut(s) retourné(s)."
            } catch (error: Exception) {
                _obdScanMessage.value = error.localizedMessage ?: "Échec du scan OBD-II."
            } finally {
                _isObdScanning.value = false
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
                    captureLiveTelemetrySample(_livePids.value)
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
