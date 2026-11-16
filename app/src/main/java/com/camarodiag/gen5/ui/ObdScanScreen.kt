package com.camarodiag.gen5.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camarodiag.gen5.data.obd.ObdDtc
import com.camarodiag.gen5.data.obd.ObdScanReport
import com.camarodiag.gen5.data.obd.ObdValue
import com.camarodiag.gen5.ui.theme.CamaroAmber
import com.camarodiag.gen5.ui.theme.OkGreen
import com.camarodiag.gen5.ui.theme.SurfaceDark
import com.camarodiag.gen5.ui.theme.TextMuted
import com.camarodiag.gen5.ui.theme.TextPrimary
import com.camarodiag.gen5.ui.theme.WarnRed

@Composable
fun ObdScanScreen(
    report: ObdScanReport?,
    isScanning: Boolean,
    message: String?,
    isConnected: Boolean,
    isFr: Boolean,
    onRunScan: () -> Unit
) {
    val unavailable = if (isFr) "Non disponible" else "Not available"
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (isFr) "SCAN OBD-II COMPLET" else "FULL OBD-II SCAN",
                    color = CamaroAmber, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Text(
                    if (isFr) "Lecture normalisée SAE J1979 : données moteur, défauts, freeze frame et identifiants du calculateur."
                    else "SAE J1979 read-only inspection: powertrain data, trouble codes, freeze frame and ECU identification.",
                    color = TextMuted, fontSize = 12.sp
                )
                Button(
                    onClick = onRunScan,
                    enabled = isConnected && !isScanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isScanning) CircularProgressIndicator()
                    else Text(if (isFr) "Lancer le scan complet" else "Run full scan")
                }
                if (!isConnected) Text(
                    if (isFr) "Connecte une interface OBD réelle avant de lancer le scan. Le mode démo ne simule aucun résultat."
                    else "Connect a real OBD adapter before scanning. Demo mode does not fabricate scan results.",
                    color = CamaroAmber, fontSize = 12.sp
                )
                message?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = CamaroAmber, fontSize = 12.sp)
                }
            }
        }

        if (report != null) {
            val isSimulated = report.adapterInfo["IDENTIFICATION"].orEmpty()
                .contains("SIMULATED", ignoreCase = true)
            if (isSimulated) {
                item {
                    Text(
                        if (isFr) "SIMULATION HORS VÉHICULE — VIN, CAL ID, CVN, DTC et mesures ci-dessous sont synthétiques et ne proviennent d'aucune Camaro réelle."
                        else "OFFLINE SIMULATION — VIN, CAL ID, CVN, DTCs and measurements below are synthetic and do not come from a real Camaro.",
                        color = CamaroAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
            item {
                InfoCard(title = if (isFr) "Identification véhicule / ECU" else "Vehicle / ECU identification") {
                    InfoLine(if (isFr) "VIN" else "VIN", report.vin ?: unavailable)
                    InfoLine(if (isFr) "CAL ID" else "CAL ID", report.calibrationIds.joinToString(" | ").ifBlank { unavailable })
                    InfoLine(if (isFr) "CVN" else "CVN", report.calibrationVerificationNumbers.joinToString(" | ").ifBlank { unavailable })
                    InfoLine(if (isFr) "Nom ECU" else "ECU name", report.ecuNames.joinToString(" | ").ifBlank { unavailable })
                    InfoLine(if (isFr) "PIDs supportés" else "Supported PIDs", report.supportedPids.size.toString())
                    InfoLine(
                        if (isFr) "Adresses CAN répondantes" else "Responding CAN IDs",
                        report.respondingEcuAddresses.joinToString(", ").ifBlank { if (isFr) "Aucune identifiée" else "None identified" }
                    )
                    InfoLine(if (isFr) "Tension interface" else "Adapter voltage", report.adapterInfo["VOLTAGE"] ?: "—")
                    InfoLine(if (isFr) "Protocole" else "Protocol", report.adapterInfo["PROTOCOL"] ?: "—")
                }
            }

            item {
                Text(
                    if (isFr) "Les identifiants CAN sont les réponses observées à la requête OBD fonctionnelle 0100. Ils ne suffisent pas à nommer un calculateur (BCM, TCM, ABS, IPC, SDM) ni à autoriser des services GM étendus."
                    else "CAN identifiers are responses observed to the OBD functional request 0100. They are not sufficient to identify a module (BCM, TCM, ABS, IPC, SDM) or authorize GM enhanced services.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            item {
                InfoCard(title = if (isFr) "État OBD / readiness" else "OBD readiness") {
                    Text(report.readiness ?: if (isFr) "Donnée non disponible" else "Not available",
                        color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }

            item {
                SectionTitle(if (isFr) "Données moteur retournées" else "Returned live data")
            }
            if (report.liveData.isEmpty()) {
                item { Text(if (isFr) "Aucun PID de données exploitable n'a été retourné." else "No usable live-data PID was returned.", color = TextMuted) }
            } else {
                items(report.liveData, key = { it.pid }) { value ->
                    ObdValueCard(value, isFr)
                }
            }

            item {
                SectionTitle(if (isFr) "Codes défaut (DTC)" else "Diagnostic trouble codes")
            }
            val allCodes = report.confirmedDtcs + report.pendingDtcs + report.permanentDtcs
            if (allCodes.isEmpty()) {
                item { Text(if (isFr) "Aucun DTC retourné par les services standard 03/07/0A." else "No DTC returned by standard services 03/07/0A.", color = OkGreen) }
            } else {
                items(allCodes.distinctBy { it.code to it.status }, key = { "${it.code}-${it.status}" }) { dtc ->
                    DtcResultCard(dtc, isFr)
                }
            }

            item {
                InfoCard(title = if (isFr) "Freeze frame (Mode 02)" else "Freeze frame (Mode 02)") {
                    if (report.freezeFrame.isEmpty()) {
                        Text(if (isFr) "Aucune donnée figée exploitable." else "No usable freeze-frame data.", color = TextMuted, fontSize = 12.sp)
                    } else {
                        report.freezeFrame.forEach { (pid, bytes) ->
                            InfoLine("PID %02X".format(pid), bytes.joinToString(" ") { "%02X".format(it) })
                        }
                    }
                }
            }

            item {
                InfoCard(title = if (isFr) "Résultats Mode 06" else "Mode 06 monitor results") {
                    Text(report.monitorTestRaw ?: if (isFr) unavailable else "Not available",
                        color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }

            item {
                InfoCard(title = if (isFr) "Journal brut du scan" else "Raw scan log") {
                    report.rawLog.forEach { line ->
                        Text(line, color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    }
                }
            }

            item {
                Text(
                    if (isSimulated) {
                        if (isFr) "Portée : données de démonstration déterministes uniquement. Aucun adaptateur, calculateur ou véhicule réel n'est interrogé."
                        else "Scope: deterministic demonstration data only. No real adapter, ECU or vehicle is queried."
                    } else {
                        if (isFr) "Portée : les résultats proviennent uniquement des réponses réellement émises par l'ECU accessible via OBD-II. Ce scan ne balaie pas le BCM, l'IPC, l'ABS, le SDM ou les autres calculateurs GM et n'exécute aucune commande d'actionnement ou de programmation."
                        else "Scope: results come only from responses actually returned by the OBD-II-accessible ECU. This scan does not poll BCM, IPC, ABS, SDM or other GM modules and does not execute actuator or programming commands."
                    },
                    color = TextMuted, fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = CamaroAmber, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = TextMuted.copy(alpha = .25f))
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(0.32f))
        Text(value, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.68f))
    }
}

@Composable
private fun ObdValueCard(value: ObdValue, isFr: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("PID %02X".format(value.pid), color = CamaroAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(value.name, color = TextMuted, fontSize = 12.sp)
            }
            Text("${value.value} ${value.unit}", color = TextPrimary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DtcResultCard(dtc: ObdDtc, isFr: Boolean) {
    val status = when (dtc.status) {
        "pending" -> if (isFr) "En attente" else "Pending"
        "permanent" -> if (isFr) "Permanent" else "Permanent"
        else -> if (isFr) "Confirmé" else "Confirmed"
    }
    val statusColor = if (dtc.status == "confirmed") WarnRed else CamaroAmber
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(dtc.code, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(if (isFr) "Groupe motopropulseur / ECM" else "Powertrain / ECM", color = TextMuted, fontSize = 10.sp)
            }
            Text(status, color = statusColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        }
    }
}
