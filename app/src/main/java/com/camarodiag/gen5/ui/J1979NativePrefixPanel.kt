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
import com.camarodiag.gen5.R
import com.camarodiag.gen5.domain.model.VehicleProfile

/** An explicit offline operation, separate from the live recorder and
 * independent of whether a USB adapter is currently connected. */
@Composable
internal fun J1979NativePrefixPanel(enabled: Boolean,vehicle: VehicleProfile,generation: Long,
                                    inspect:(Uri,Long,VehicleProfile)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember(vehicle,generation) { mutableStateOf<Uri?>(null) }
    var confirmed by remember(vehicle,generation) { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<VehicleProfile,Long>?>(null) }
    val current by rememberUpdatedState(vehicle to generation)
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(pending==current&&uri!=null&&uri.scheme=="content") {
            selected=uri
            confirmed=false
        }
        pending=null
    }
    OutlinedButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.j1979_native_prefix_title))
    }
    if(!expanded)return
    Column(verticalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.fillMaxWidth()) {
        OutlinedButton(onClick={
            pending=current
            picker.launch(arrayOf("*/*"))
        },enabled=enabled&&pending==null,modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(if(selected==null) R.string.j1979_native_prefix_select
                else R.string.j1979_catalogue_selected))
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Checkbox(confirmed,{confirmed=it},enabled=enabled&&pending==null)
            Text(stringResource(R.string.j1979_native_prefix_confirm,vehicle.vin))
        }
        OutlinedButton(onClick={
            val source=selected
            if(source!=null&&confirmed) {
                confirmed=false
                inspect(source,generation,vehicle)
            }
        },enabled=enabled&&pending==null&&selected!=null&&confirmed&&
            J1979CatalogueProjection.validVin(vehicle.vin),modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.j1979_native_prefix_run))
        }
        Text(stringResource(R.string.j1979_native_prefix_scope))
    }
}
