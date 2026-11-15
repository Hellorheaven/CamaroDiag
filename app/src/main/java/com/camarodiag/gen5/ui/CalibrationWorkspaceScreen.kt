package com.camarodiag.gen5.ui

import androidx.compose.foundation.layout.Arrangement
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
import com.camarodiag.gen5.domain.model.VehicleProfile

data class CalibrationWorkspaceState(
    val isReading: Boolean = false,
    val calibrationIdResponse: String = "",
    val cvnResponse: String = "",
    val message: String = "",
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
    vehicle: VehicleProfile,
    onImportFile: () -> Unit,
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
    val currentProfile = remember(vehicle) { CamaroCalibrationCatalog.forVehicle(vehicle) }
    val allProfiles = remember { CamaroCalibrationCatalog.profiles }

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
                        if (isFr) "Lecture des identifiants OBD normalisés Mode 09 : CAL ID (PID 04) et CVN (PID 06). Ces données identifient la calibration mais ne constituent pas une lecture de la cartographie binaire."
                        else "Read standard OBD Mode 09 identifiers: CAL ID (PID 04) and CVN (PID 06). These identify the calibration but do not read the binary calibration map.",
                        color = TextMuted, fontSize = 12.sp
                    )
                    Button(
                        onClick = onReadIdentification,
                        enabled = isConnected && !state.isReading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isReading) CircularProgressIndicator()
                        else Text(if (isFr) "Lire CAL ID / CVN sur l'ECU" else "Read ECU CAL ID / CVN")
                    }
                    if (!isConnected) Text(
                        if (isFr) "Connecte une interface OBD avant la lecture réelle." else "Connect an OBD adapter before reading the real ECU.",
                        color = CamaroAmber, fontSize = 12.sp
                    )
                    if (state.calibrationIdResponse.isNotBlank() || state.cvnResponse.isNotBlank()) {
                        HorizontalDivider(color = TextMuted.copy(alpha = .25f))
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
                                            "%02d".format(rowIndex),
                                            modifier = Modifier.width(28.dp),
                                            color = TextMuted, fontSize = 10.sp
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
                        if (isFr) "Les 10 profils moteur Gen 5/Gen 6 sont catalogués, mais la lecture mémoire binaire, l'édition des tables et le flashage ne sont pas encore implémentés. Il faut identifier le contrôleur exact, le CAL ID/CVN, le matériel, l'OS, le protocole de programmation constructeur, les checksums et la récupération."
                        else "All 10 Gen 5 / Gen 6 engine profiles are catalogued, but binary memory reads, table editing and flashing are not implemented. The exact controller, CAL ID/CVN, hardware, OS, OEM programming protocol, checksums and recovery path must be identified.",
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
