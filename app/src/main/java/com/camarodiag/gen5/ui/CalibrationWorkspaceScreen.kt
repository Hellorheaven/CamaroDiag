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
import com.camarodiag.gen5.data.obd.CanTraceAnalysisReport
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
    val isTraceAnalyzing: Boolean = false,
    val traceMessage: String = "",
    val canTraceReport: CanTraceAnalysisReport? = null,
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
    onImportTrace: () -> Unit,
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
                Text(if (isFr) "ATELIER ECU / CALIBRATION" else "ECU / CALIBRATION WORKSPACE",
                    color = CamaroAmber, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Text(vehicleLabel, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(if (isFr) "Famille calculateur indicative : ${ecmType}" else "Indicative controller family: ${ecmType}",
                    color = TextMuted, fontSize = 12.sp)
                Text(
                    if (isFr) currentProfile?.let { "Profil moteur : ${it.engine.rpo} • ${it.generation.label} • ${it.modelYears.first}–${it.modelYears.last}" } ?: "Profil moteur non répertorié pour cette année"
                    else currentProfile?.let { "Engine profile: ${it.engine.rpo} • ${it.generation.label} • ${it.modelYears.first}–${it.modelYears.last}" } ?: "No engine profile for this model year",
                    color = CamaroAmber, fontSize = 12.sp
                )
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (isFr) "Couverture moteurs Camaro Gen 5 / Gen 6" else "Camaro Gen 5 / Gen 6 engine coverage",
                        color = CamaroAmber, fontWeight = FontWeight.Bold)
                    Text(
                        if (isFr) "Tous les profils moteur répertoriés sont présents. La famille ECM affichée est indicative : le CAL ID, le CVN, le numéro matériel et l'OS doivent identifier l'ECU réellement installé."
                        else "All catalogued engine profiles are listed. The displayed ECM family is indicative: CAL ID, CVN, hardware number and OS must identify the ECU actually installed.",
                        color = TextMuted, fontSize = 12.sp
                    )
                    Text(
                        if (isFr) "Motorisations répertoriées pour ${vehicle.modelYear} : " + yearProfiles.joinToString(", ") { it.engine.rpo }.ifBlank { "aucune" }
                        else "Catalogued engines for ${vehicle.modelYear}: " + yearProfiles.joinToString(", ") { it.engine.rpo }.ifBlank { "none" },
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
                            Text(if (isFr) profile.engine.nameFr else profile.engine.nameEn, color = TextPrimary, fontSize = 12.sp)
                            Text("ECM : ${profile.nominalControllerFamily}", color = TextMuted, fontSize = 11.sp)
                            Text(
                                if (isFr) "CAL ID/CVN : lecture standard ${if (profile.supportsStandardCalIdRead && profile.supportsStandardCvnRead) "prévue" else "non validée"} • Lecture binaire : ${if (profile.supportsBinaryRead) "validée" else "non disponible"} • Tables moteur validées : ${if (profile.supportsTableEditing) "oui" else "non fournies"} • Flash ECU : ${if (profile.supportsEcuWrite) "validé" else "désactivé"}"
                                else "CAL ID/CVN: standard read ${if (profile.supportsStandardCalIdRead && profile.supportsStandardCvnRead) "profiled" else "not verified"} • Binary read: ${if (profile.supportsBinaryRead) "validated" else "unavailable"} • Validated engine tables: ${if (profile.supportsTableEditing) "available" else "not supplied"} • ECU flash: ${if (profile.supportsEcuWrite) "validated" else "disabled"}",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                            Text(if (isFr) profile.notesFr else profile.notesEn, color = TextMuted, fontSize = 10.sp)
                            Text(
                                if (isFr) "CAL ID/CVN : lecture standard • Dump binaire, édition et écriture : procédure ECU à valider"
                                else "CAL ID/CVN: standard read • Binary dump, editing and write: ECU procedure must be validated",
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
                        Text(if (isFr) "Identification du calculateur" else "ECU identification",
                            color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (isFr) "Lecture des identifiants OBD Mode 09 : VIN (PID 02), CAL ID (PID 04) et CVN (PID 06). Ces identifiants ne constituent pas une lecture de la cartographie binaire."
                        else "Read OBD Mode 09 identifiers: VIN (PID 02), CAL ID (PID 04) and CVN (PID 06). These identifiers do not read the binary calibration map.",
                        color = TextMuted, fontSize = 12.sp
                    )
                    Button(
                        onClick = onReadIdentification,
                        enabled = isConnected && !state.isReading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isReading) CircularProgressIndicator()
                        else Text(if (isFr) "Lire VIN / CAL ID / CVN sur l'ECU" else "Read ECU VIN / CAL ID / CVN")
                    }
                    if (!isConnected) Text(
                        if (isFr) "Connecte une interface OBD avant la lecture réelle." else "Connect an OBD adapter before reading the real ECU.",
                        color = CamaroAmber, fontSize = 12.sp
                    )
                    HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                    Text(if (isFr) "Diagnostic UDS sur CAN" else "UDS over CAN",
                        color = CamaroAmber, fontWeight = FontWeight.Bold)
                    Text(
                        if (isFr) "Transport STPX pour OBDLink MX+ ou pont PC J2534 pour VXDIAG. Saisis les identifiants CAN physiques confirmés : aucune adresse GM n'est supposée."
                        else "STPX transport for OBDLink MX+ or a PC J2534 bridge for VXDIAG. Enter confirmed physical CAN IDs: no GM address is assumed.",
                        color = TextMuted, fontSize = 11.sp
                    )
                    OutlinedTextField(
                        value = udsRequestCanId,
                        onValueChange = { udsRequestCanId = it.uppercase().filter { ch -> ch.isDigit() || ch in 'A'..'F' }.take(8) },
                        label = { Text(if (isFr) "CAN ID requête (ex. 7E0)" else "Request CAN ID (e.g. 7E0)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                    OutlinedTextField(
                        value = udsResponseCanId,
                        onValueChange = { udsResponseCanId = it.uppercase().filter { ch -> ch.isDigit() || ch in 'A'..'F' }.take(8) },
                        label = { Text(if (isFr) "CAN ID réponse (ex. 7E8)" else "Response CAN ID (e.g. 7E8)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                    OutlinedTextField(
                        value = udsCanProtocol,
                        onValueChange = { udsCanProtocol = it.filter(Char::isDigit).take(2) },
                        label = { Text(if (isFr) "Profil CAN : 33/34 HS-CAN 500k • 35/36 HS-CAN 250k • 63/64 SW-CAN" else "CAN profile: 33/34 HS-CAN 500k • 35/36 HS-CAN 250k • 63/64 SW-CAN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = udsDid,
                        onValueChange = { udsDid = it.uppercase().filter { ch -> ch.isDigit() || ch in 'A'..'F' }.take(4) },
                        label = { Text(if (isFr) "DID UDS (F190 = VIN)" else "UDS DID (F190 = VIN)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = .72f))) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(if (isFr) "PONT PC J2534 • VXDIAG VCX SE / NANO" else "J2534 PC BRIDGE • VXDIAG VCX SE / NANO",
                                color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                if (isFr) "Lance CamaroDiag.J2534Bridge.exe sur le PC Windows, puis saisis son IP et le jeton affiché dans la console. Le PC doit être sur le même réseau local."
                                else "Run CamaroDiag.J2534Bridge.exe on the Windows PC, then enter its IP and the token shown in the console. Both devices must be on the same local network.",
                                color = TextMuted, fontSize = 10.sp
                            )
                            Text(
                                if (isFr) "Sécurité : jeton non chiffré par TLS. Utilise un réseau privé de confiance et n'expose jamais ce port à Internet."
                                else "Security: the token is not TLS-encrypted. Use a trusted private network and never expose this port to the Internet.",
                                color = CamaroAmber, fontSize = 10.sp
                            )
                            OutlinedTextField(
                                value = j2534Host,
                                onValueChange = { j2534Host = it.trim().take(64) },
                                label = { Text(if (isFr) "Adresse IP du PC" else "PC IP address") },
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
                                label = { Text(if (isFr) "Jeton d'appairage du PC" else "PC pairing token") },
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
                                    else Text(if (isFr) "Connecter le pont J2534" else "Connect J2534 bridge")
                                }
                            } else {
                                OutlinedButton(onClick = onDisconnectJ2534Bridge, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (isFr) "Déconnecter le pont PC" else "Disconnect PC bridge")
                                }
                            }
                            if (state.j2534BridgeMessage.isNotBlank()) {
                                Text(state.j2534BridgeMessage,
                                    color = if (state.isJ2534BridgeConnected) OkGreen else CamaroAmber,
                                    fontSize = 11.sp)
                            }
                            Text(
                                if (isFr) "Pont actuel : HS-CAN ISO15765 250/500 kbit/s et SW-ISO15765 J2534-2 si le pilote le prend en charge. CAN FD reste désactivé."
                                else "Bridge: HS-CAN ISO15765 250/500 kbit/s and J2534-2 SW-ISO15765 when supported by the driver. CAN FD remains disabled.",
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
                        else Text(if (isFr) "Lire le DID UDS" else "Read UDS DID")
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
                        Text(if (isFr) "Lire les DTC UDS" else "Read UDS DTCs")
                    }
                    if (state.udsDtcResponse.isNotBlank()) {
                        Text(if (isFr) "Défauts UDS (0x19)" else "UDS trouble codes (0x19)",
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
                    Text(if (isFr) "CATALOGUE NATIF CAMARODIAG" else "CAMARODIAG FIRST-PARTY CATALOG",
                        color = CamaroAmber, fontWeight = FontWeight.Bold)
                    Text(
                        if (isFr) "Définitions maintenues par CamaroDiag, indépendantes des catalogues communautaires. Une définition proposée ou documentée n'est jamais envoyée à un calculateur."
                        else "Definitions maintained by CamaroDiag, independent from community catalogues. Proposed or documented definitions are never sent to an ECU.",
                        color = TextMuted, fontSize = 11.sp
                    )
                    Text(state.nativeSignalMessage.ifBlank {
                        if (isFr) "Chargement du catalogue natif…" else "Loading first-party catalogue…"
                    }, color = TextMuted, fontSize = 10.sp)
                    if (state.nativeSignals.isEmpty()) {
                        Text(
                            if (isFr) "Aucun DID propriétaire Camaro n'est actuellement déclaré comme exploitable. Les adresses et facteurs inconnus ne sont pas inventés."
                            else "No proprietary Camaro DID is currently declared as usable. Unknown addresses and scaling are not fabricated.",
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
                                    if (isFr) "Lecture véhicule désactivée tant que l'adresse et le décodage ne sont pas vérifiés sur une configuration identique."
                                    else "Vehicle read disabled until addressing and decoding are verified on an identical configuration.",
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
                    Text(if (isFr) "SIGNAUX SPÉCIFIQUES CAMARO • OBDb" else "CAMARO-SPECIFIC SIGNALS • OBDb",
                        color = CamaroAmber, fontWeight = FontWeight.Bold)
                    Text(
                        if (isFr) "Catalogue communautaire importé avec ses DID, adresses CAN, facteurs et filtres d'année. Ces définitions ne sont pas des validations GM : vérifie toujours la réponse du calculateur."
                        else "Community catalogue imported with DIDs, CAN addresses, scaling factors and model-year filters. These definitions are not GM validation; always verify the ECU response.",
                        color = TextMuted, fontSize = 11.sp
                    )
                    Text(state.obdbSignalMessage.ifBlank {
                        if (isFr) "Chargement du catalogue Camaro…" else "Loading Camaro signal catalog…"
                    }, color = TextMuted, fontSize = 10.sp)
                    OutlinedTextField(
                        value = obdbSignalSearch,
                        onValueChange = { obdbSignalSearch = it.take(60) },
                        label = { Text(if (isFr) "Rechercher un signal (nom, DID, système)" else "Search signal (name, DID, system)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box {
                        TextButton(
                            onClick = { obdbSignalMenuExpanded = true },
                            enabled = state.obdbSignals.isNotEmpty()
                        ) {
                            Text(selectedObdbSignal?.let { "${it.name} • ${it.id}" }
                                ?: if (isFr) "Aucun signal disponible" else "No signal available")
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
                            if (isFr) "DID 0x${signal.identifierHex} • Requête ${signal.requestHeaderHex} / réponse ${signal.responseHeaderHex ?: "non résolue"} • période ${signal.frequencySeconds}s"
                            else "DID 0x${signal.identifierHex} • request ${signal.requestHeaderHex} / response ${signal.responseHeaderHex ?: "unresolved"} • interval ${signal.frequencySeconds}s",
                            color = TextMuted, fontSize = 10.sp
                        )
                        if (signal.debugSignal) {
                            Text(if (isFr) "Signal marqué DEBUG dans la source OBDb." else "Signal is marked DEBUG in the OBDb source.",
                                color = CamaroAmber, fontSize = 10.sp)
                        }
                        if (signal.flowControlRequired) {
                            Text(if (isFr) "Flow control requis par la définition source." else "Flow control is required by the source definition.",
                                color = TextMuted, fontSize = 10.sp)
                        }
                        signal.description?.let { Text(it, color = TextMuted, fontSize = 10.sp) }
                        if (!signal.hasSupportedPhysicalCanAddressing) {
                            Text(
                                if (isFr) "Adresse CAN non résolue : la lecture reste désactivée pour ce signal."
                                else "CAN address is unresolved: reading is disabled for this signal.",
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
                            else Text(if (isFr) "Lire ce signal sur l'ECU" else "Read this ECU signal")
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
                        if (isFr) "Analyseur de traces CAN / UDS hors ligne" else "Offline CAN / UDS trace analyzer",
                        color = TextPrimary, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isFr) "Importe une capture Vector ASC ou un CSV CAN. CamaroDiag reconstruit les messages ISO-TP et relève les services UDS et DID observés, sans envoyer de commande au véhicule."
                        else "Import a Vector ASC capture or CAN CSV. CamaroDiag reassembles ISO-TP messages and lists observed UDS services and DIDs without sending commands to a vehicle.",
                        color = TextMuted, fontSize = 12.sp
                    )
                    OutlinedButton(
                        onClick = onImportTrace,
                        enabled = !state.isTraceAnalyzing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isTraceAnalyzing) CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
                        else androidx.compose.material3.Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Text(if (isFr) "Importer une trace CAN (.asc / .csv)" else "Import CAN trace (.asc / .csv)")
                    }
                    if (state.traceMessage.isNotBlank()) {
                        Text(state.traceMessage, color = CamaroAmber, fontSize = 12.sp)
                    }
                    state.canTraceReport?.let { report ->
                        HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                        Text(report.sourceName, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            "CAN IDs: ${report.observedCanIds.joinToString { "0x" + it.toString(16).uppercase() }}",
                            color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp
                        )
                        Text(
                            if (isFr) "DID candidats : ${report.readDidCandidates.joinToString { "0x" + it.toString(16).uppercase().padStart(4, '0') }.ifBlank { "aucun" }}"
                            else "DID candidates: ${report.readDidCandidates.joinToString { "0x" + it.toString(16).uppercase().padStart(4, '0') }.ifBlank { "none" }}",
                            color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                        )
                        report.didObservations.take(20).forEach { observation ->
                            val direction = when (observation.direction) {
                                com.camarodiag.gen5.data.obd.CanTraceDirection.TX -> "TX"
                                com.camarodiag.gen5.data.obd.CanTraceDirection.RX -> "RX"
                                com.camarodiag.gen5.data.obd.CanTraceDirection.UNKNOWN -> "?"
                            }
                            val result = if (observation.positiveResponse) "POS" else
                                observation.negativeResponseCode?.let { "NRC %02X".format(it) } ?: "REQ/OBS"
                            Text(
                                "$direction • CAN ${observation.canId.toString(16).uppercase()} • DID ${observation.did.toString(16).uppercase().padStart(4, '0')} • ${result}",
                                color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp
                            )
                        }
                        if (report.didObservations.size > 20) {
                            Text(
                                if (isFr) "… et ${report.didObservations.size - 20} observation(s) supplémentaire(s)."
                                else "… and ${report.didObservations.size - 20} more observation(s).",
                                color = TextMuted, fontSize = 10.sp
                            )
                        }
                        report.errors.take(10).forEach { error ->
                            Text(error, color = CamaroAmber, fontSize = 10.sp)
                        }
                        Text(
                            if (isFr) "Les DID détectés sont des observations de trace uniquement. Ils ne sont ni décodés ni promus comme signaux vérifiés."
                            else "Detected DIDs are trace observations only. They are not decoded or promoted as verified signals.",
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
                        Text(if (isFr) "Fichiers de calibration" else "Calibration files",
                            color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (isFr) "Importe un fichier BIN, HEX ou autre dump pour en vérifier la taille et l'empreinte SHA-256. Le fichier reste local : aucune écriture ECU n'est déclenchée."
                        else "Import a BIN, HEX or other dump to inspect its size and SHA-256 fingerprint. The file stays local; no ECU write is triggered.",
                        color = TextMuted, fontSize = 12.sp
                    )
                    OutlinedButton(onClick = onImportFile, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Text(if (isFr) "Importer un fichier de cartographie" else "Import a calibration file")
                    }
                    Text(
                        if (isFr) "Importe les BIN, HEX, S-record, archives ZIP et fichiers de projet/définition externes. Les formats propriétaires sont conservés tels quels pour sauvegarde/export ; seuls les BIN, HEX et S-record sont inspectés ici. Les définitions XDF/A2L doivent encore être converties en TSV avant édition des tables."
                        else "Import BIN, HEX, S-record, ZIP archives and external project/definition files. Proprietary formats are preserved unchanged for backup/export; only BIN, HEX and S-record are inspected here. XDF/A2L definitions must still be converted to TSV before table editing.",
                        color = TextMuted, fontSize = 11.sp
                    )
                    state.importedFileName?.let { name ->
                        HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                        Text(name, color = CamaroAmber, fontWeight = FontWeight.Bold)
                        Text("${state.importedFileSize ?: 0L} bytes", color = TextPrimary, fontSize = 12.sp)
                        Text("SHA-256", color = TextMuted, fontSize = 11.sp)
                        Text(state.importedFileSha256.orEmpty(), color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        Text(if (isFr) "Aperçu hexadécimal (64 premiers octets)" else "Hex preview (first 64 bytes)", color = TextMuted, fontSize = 11.sp)
                        Text(state.importedFilePreview.orEmpty(), color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        state.inspectionFormat?.let { format ->
                            HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                            Text(if (isFr) "Analyse du fichier : $format" else "File inspection: $format", color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(state.inspectionSummary.orEmpty(), color = TextPrimary, fontSize = 12.sp)
                            state.inspectionPayloadBytes?.let { Text(if (isFr) "Données utiles : $it octets" else "Payload: $it bytes", color = TextMuted, fontSize = 11.sp) }
                            state.inspectionRecordCount?.takeIf { it > 0 }?.let { Text(if (isFr) "Enregistrements : $it" else "Records: $it", color = TextMuted, fontSize = 11.sp) }
                            Text(if (isFr) "Contrôle d'intégrité du format : ${state.inspectionChecksumStatus}" else "Format checksum validation: ${state.inspectionChecksumStatus}", color = TextMuted, fontSize = 11.sp)
                            state.inspectionAddressRanges.forEach { range -> Text(range, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                            state.inspectionWarnings.forEach { warning -> Text("• $warning", color = CamaroAmber, fontSize = 10.sp) }
                        }
                        OutlinedButton(onClick = onExportFile, modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.Icon(Icons.Default.CloudUpload, contentDescription = null)
                            Text(if (isFr) "Exporter / télécharger le fichier" else "Export / download file")
                        }
                        if (state.inspectionFormat == "BIN / raw binary") {
                            OutlinedButton(onClick = onImportComparisonFile, modifier = Modifier.fillMaxWidth()) {
                                Text(if (isFr) "Importer un second BIN pour comparer" else "Import a second BIN to compare")
                            }
                        } else {
                            Text(
                                if (isFr) "La comparaison octet par octet est activée pour les images BIN brutes uniquement."
                                else "Byte-for-byte comparison is enabled for raw BIN images only.",
                                color = TextMuted, fontSize = 10.sp
                            )
                        }
                        state.comparisonFileName?.let { comparisonName ->
                            HorizontalDivider(color = TextMuted.copy(alpha = .25f))
                            Text(if (isFr) "Comparaison : $comparisonName" else "Comparison: $comparisonName", color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(state.comparisonSummary.orEmpty(), color = TextPrimary, fontSize = 12.sp)
                            Text(
                                if (isFr) "Octets modifiés : ${state.comparisonChangedBytes ?: 0L} • Ajoutés : ${state.comparisonAppendedBytes ?: 0L} • Manquants : ${state.comparisonMissingBytes ?: 0L}"
                                else "Changed bytes: ${state.comparisonChangedBytes ?: 0L} • Appended: ${state.comparisonAppendedBytes ?: 0L} • Missing: ${state.comparisonMissingBytes ?: 0L}",
                                color = TextMuted, fontSize = 11.sp
                            )
                            state.comparisonRanges.forEach { range ->
                                Text(range, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }
                            if (state.comparisonIdentical == true) Text(if (isFr) "Images identiques" else "Images are identical", color = OkGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        TextButton(onClick = onClearFile) { Text(if (isFr) "Retirer les fichiers" else "Remove files") }
                    }
                }
            }
        }
        item {
            var mapMenuExpanded by remember { mutableStateOf(false) }
            val selectedMap = state.mapDefinitions.firstOrNull { it.id == state.selectedMapId }
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(if (isFr) "Éditeur de tables de calibration" else "Calibration table editor",
                        color = CamaroAmber, fontWeight = FontWeight.Bold)
                    Text(
                        if (isFr) "Charge un fichier de définitions TSV contenant les offsets, dimensions, type numérique, ordre des octets et facteur d'échelle exacts de ton fichier BIN. CamaroDiag ne fournit pas d'adresses de tables supposées."
                        else "Import a TSV definition file containing exact offsets, dimensions, numeric type, byte order and scaling for your BIN image. CamaroDiag does not guess table addresses.",
                        color = TextMuted, fontSize = 11.sp
                    )
                    OutlinedButton(
                        onClick = onImportDefinitions,
                        enabled = state.importedFilePath != null && state.inspectionFormat == "BIN / raw binary",
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (isFr) "Importer les définitions de tables (.csv / .tsv)" else "Import table definitions (.csv / .tsv)") }
                    TextButton(onClick = onExportDefinitionTemplate, modifier = Modifier.fillMaxWidth()) {
                        Text(if (isFr) "Exporter le modèle de définitions TSV" else "Export TSV definition template")
                    }
                    if (state.mapDefinitions.isNotEmpty()) {
                        OutlinedButton(onClick = { mapMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selectedMap?.let { "${it.name} • ${it.rows} × ${it.columns}" }
                                ?: if (isFr) "Choisir une table" else "Select a table")
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
                                if (isFr) "Axe X : ${definition.xAxis?.let { "0x" + it.fileOffset.toString(16).uppercase() + " • " + it.count + " valeurs • " + it.unit } ?: "non défini"}"
                                else "X axis: ${definition.xAxis?.let { "0x" + it.fileOffset.toString(16).uppercase() + " • " + it.count + " values • " + it.unit } ?: "not defined"}",
                                color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                            )
                            Text(
                                if (isFr) "Axe Y : ${definition.yAxis?.let { "0x" + it.fileOffset.toString(16).uppercase() + " • " + it.count + " valeurs • " + it.unit } ?: "non défini"}"
                                else "Y axis: ${definition.yAxis?.let { "0x" + it.fileOffset.toString(16).uppercase() + " • " + it.count + " values • " + it.unit } ?: "not defined"}",
                                color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                        state.mapDefinitionsWarnings.forEach { warning ->
                            Text(warning, color = CamaroAmber, fontSize = 10.sp)
                        }
                        state.mapError?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFFF6B6B), fontSize = 11.sp) }
                        if (state.mapValues.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(if (isFr) "Valeurs de table" else "Table values", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (state.mapDirty) (if (isFr) "Modifications en attente" else "Unsaved edits")
                                    else (if (isFr) "Chargée" else "Loaded"),
                                    color = if (state.mapDirty) CamaroAmber else OkGreen, fontSize = 10.sp
                                )
                            }
                            if (state.mapXAxisValues.isNotEmpty()) {
                                Text(
                                    if (isFr) "Axe X : ${selectedMap?.xAxis?.unit.orEmpty()}" else "X axis: ${selectedMap?.xAxis?.unit.orEmpty()}",
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
                                ) { Text(if (isFr) "Annuler" else "Discard") }
                                Button(
                                    onClick = onApplyMap,
                                    enabled = state.mapDirty,
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (isFr) "Appliquer au fichier" else "Apply to file") }
                            }
                        }
                    }
                    Text(
                        if (isFr) "L'application des cellules crée une nouvelle image BIN locale et conserve l'original. Elle n'envoie aucune modification à l'ECU. Les définitions et les facteurs doivent être vérifiés pour le CAL ID et l'OS exacts."
                        else "Applying cell edits creates a new local BIN image and preserves the original. It sends no changes to the ECU. Definitions and scaling must be verified for the exact CAL ID and OS.",
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
                        Text(if (isFr) "Lecture / écriture de la mémoire ECU" else "ECU memory read / write",
                            color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (isFr) "Les 10 profils moteur Gen 5/Gen 6 sont catalogués. L'éditeur hors ligne de tables fonctionne sur un fichier BIN importé lorsque tu fournis une définition exacte (offset, dimensions, type, ordre des octets, échelle et biais) correspondant au CAL ID et à l'OS relevés. CamaroDiag ne fournit pas encore de définitions moteur validées, ne lit pas directement la mémoire de l'ECM et ne programme pas l'ECU."
                        else "All 10 Gen 5 / Gen 6 engine profiles are catalogued. The offline table editor works on an imported BIN when you provide an exact definition (offset, dimensions, data type, byte order, scaling and bias) for the identified CAL ID and OS. CamaroDiag does not yet ship validated engine table definitions, read ECM memory directly or program the ECU.",
                        color = TextMuted, fontSize = 12.sp
                    )
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text(if (isFr) "Écriture ECU indisponible – profil constructeur requis" else "ECU write unavailable – verified OEM profile required")
                    }
                    Text(
                        if (isFr) "Aucune cartographie ne sera envoyée tant que le profil ECU, la compatibilité du fichier et la procédure de récupération ne seront pas validés."
                        else "No calibration will be written until the ECU profile, file compatibility and recovery procedure have been validated.",
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
