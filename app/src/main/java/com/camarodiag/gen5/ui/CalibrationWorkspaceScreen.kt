package com.camarodiag.gen5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camarodiag.gen5.ui.theme.CamaroAmber
import com.camarodiag.gen5.ui.theme.SurfaceDark
import com.camarodiag.gen5.ui.theme.TextMuted
import com.camarodiag.gen5.ui.theme.TextPrimary
import com.camarodiag.gen5.data.obd.CamaroCalibrationCatalog
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
    val importedFilePath: String? = null
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
                        OutlinedButton(onClick = onExportFile, modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.Icon(Icons.Default.CloudUpload, contentDescription = null)
                            Text(if (isFr) "Exporter / télécharger le fichier" else "Export / download file")
                        }
                        TextButton(onClick = onClearFile) { Text(if (isFr) "Retirer le fichier" else "Remove file") }
                    }
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
