package com.camarodiag.gen5.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camarodiag.gen5.R
import com.camarodiag.gen5.domain.model.VehicleProfile

/** The owner screen provides the authorization; the view model rechecks
 * account, vehicle and storage permissions before publication. */
@Composable
internal fun J1979CataloguePanel(enabled: Boolean, vehicle: VehicleProfile, epoch: Long,
    project: (Uri, Uri, Uri, String, Long, VehicleProfile) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var catalogue by remember(vehicle, epoch) { mutableStateOf<Uri?>(null) }
    var reference by remember(vehicle, epoch) { mutableStateOf<Uri?>(null) }
    var report by remember(vehicle, epoch) { mutableStateOf<Uri?>(null) }
    var pin by remember(vehicle, epoch) { mutableStateOf("") }
    var confirmed by remember(vehicle, epoch) { mutableStateOf(false) }
    val current by rememberUpdatedState(vehicle to epoch)
    var pendingCatalogue by remember { mutableStateOf<Pair<VehicleProfile, Long>?>(null) }
    var pendingReference by remember { mutableStateOf<Pair<VehicleProfile, Long>?>(null) }
    var pendingReport by remember { mutableStateOf<Pair<VehicleProfile, Long>?>(null) }
    val selectCatalogue = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pendingCatalogue == current) { catalogue = uri; confirmed = false }
        pendingCatalogue = null
    }
    val selectReference = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pendingReference == current) { reference = uri; confirmed = false }
        pendingReference = null
    }
    val selectReport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pendingReport == current) { report = uri; confirmed = false }
        pendingReport = null
    }
    OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.j1979_catalogue_title))
    }
    if (!expanded) return
    val picking = pendingCatalogue != null || pendingReference != null || pendingReport != null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        @Composable fun Picker(label: Int, selected: Uri?, select: () -> Unit) {
            OutlinedButton(onClick = select, enabled = enabled && !picking, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(label) + " — " + stringResource(
                    if (selected == null) R.string.j1979_catalogue_not_selected else R.string.j1979_catalogue_selected))
            }
        }
        Picker(R.string.j1979_catalogue_file, catalogue) {
            pendingCatalogue = current
            selectCatalogue.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        Picker(R.string.j1979_catalogue_reference, reference) {
            pendingReference = current
            selectReference.launch(arrayOf("*/*"))
        }
        Picker(R.string.j1979_catalogue_report, report) {
            pendingReport = current
            selectReport.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        OutlinedTextField(value = pin, onValueChange = {
            pin = it.take(64); confirmed = false
        }, label = { Text(stringResource(R.string.j1979_catalogue_pin)) }, enabled = enabled && !picking,
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.j1979_catalogue_scope), fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }, enabled = enabled && !picking)
            Text(stringResource(R.string.j1979_catalogue_confirm, vehicle.vin))
        }
        val inputs = listOfNotNull(catalogue, reference, report)
        val valid = inputs.size == 3 && inputs.distinct().size == 3 &&
            inputs.all { it.scheme == "content" } && pin.matches(Regex("[0-9a-f]{64}")) &&
            J1979CatalogueProjection.validVin(vehicle.vin)
        OutlinedButton(onClick = {
            val cat = catalogue; val ref = reference; val src = report
            if (cat != null && ref != null && src != null && valid && confirmed) {
                project(cat, ref, src, pin, epoch, vehicle)
                confirmed = false
            }
        }, enabled = enabled && !picking && valid && confirmed, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.j1979_catalogue_project))
        }
    }
}
