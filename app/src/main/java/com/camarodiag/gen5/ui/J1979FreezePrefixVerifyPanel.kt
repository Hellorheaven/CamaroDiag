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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camarodiag.gen5.R
import com.camarodiag.gen5.domain.model.VehicleProfile

/** Independent, user-selected read-only verification. Never auto-opens an ECU
 * or promotes a partial CF/1 capture to a completed diagnostic campaign. */
@Composable
internal fun J1979FreezePrefixVerifyPanel(enabled:Boolean,vehicle:VehicleProfile,generation:Long,
    verify:(Uri,Uri,Long,VehicleProfile)->Unit,scalar:Boolean=false) {
    var expanded by remember { mutableStateOf(false) }
    var capture by remember(vehicle,generation) { mutableStateOf<Uri?>(null) }
    var report by remember(vehicle,generation) { mutableStateOf<Uri?>(null) }
    var confirmed by remember(vehicle,generation) { mutableStateOf(false) }
    var capturePending by remember { mutableStateOf<Pair<VehicleProfile,Long>?>(null) }
    var reportPending by remember { mutableStateOf<Pair<VehicleProfile,Long>?>(null) }
    val current by rememberUpdatedState(vehicle to generation)
    val capturePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null && uri.scheme=="content" && capturePending==current) {
            capture=uri
            confirmed=false
        }
        capturePending=null
    }
    val reportPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null && uri.scheme=="content" && reportPending==current) {
            report=uri
            confirmed=false
        }
        reportPending=null
    }
    val picking=capturePending!=null||reportPending!=null
    val valid=capture!=null && report!=null && capture!=report &&
        capture?.scheme=="content" && report?.scheme=="content" &&
        J1979CatalogueProjection.validVin(vehicle.vin)
    OutlinedButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()) {
        Text(stringResource(if(scalar) R.string.j1979_scalar_verify_title else R.string.j1979_freeze_prefix_verify_title))
    }
    if(!expanded)return
    Column(verticalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.fillMaxWidth()) {
        OutlinedButton(onClick={
            capturePending=current
            capturePicker.launch(arrayOf("*/*"))
        },enabled=enabled&&!picking,modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(if(scalar) R.string.j1979_scalar_verify_capture else R.string.j1979_freeze_prefix_verify_capture)+" — "+
                stringResource(if(capture==null) R.string.j1979_catalogue_not_selected
                    else R.string.j1979_catalogue_selected))
        }
        OutlinedButton(onClick={
            reportPending=current
            reportPicker.launch(arrayOf("application/json","text/plain","application/octet-stream"))
        },enabled=enabled&&!picking,modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(if(scalar) R.string.j1979_scalar_verify_report else R.string.j1979_freeze_prefix_verify_report)+" — "+
                stringResource(if(report==null) R.string.j1979_catalogue_not_selected
                    else R.string.j1979_catalogue_selected))
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(checked=confirmed,onCheckedChange={confirmed=it},enabled=enabled&&!picking)
            Text(stringResource(if(scalar) R.string.j1979_scalar_verify_confirm else R.string.j1979_freeze_prefix_verify_confirm,vehicle.vin))
        }
        OutlinedButton(onClick={
            val raw=capture
            val json=report
            if(raw!=null&&json!=null&&confirmed&&valid) {
                confirmed=false
                verify(raw,json,generation,vehicle)
            }
        },enabled=enabled&&!picking&&confirmed&&valid,modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(if(scalar) R.string.j1979_scalar_verify_run else R.string.j1979_freeze_prefix_verify_run))
        }
        Text(stringResource(if(scalar) R.string.j1979_scalar_verify_scope else R.string.j1979_freeze_prefix_verify_scope),fontSize=11.sp)
    }
}
