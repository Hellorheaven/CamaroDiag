package com.camarodiag.gen5.ui

import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.camarodiag.gen5.R
import com.camarodiag.gen5.data.obd.CamaroDiagConnectorNativeCampaign
import com.camarodiag.gen5.data.obd.CamaroDiagConnectorFreezeCampaign
import com.camarodiag.gen5.data.obd.UsbPermissionResultBus
import com.camarodiag.gen5.domain.model.VehicleProfile

/** Explicit CU/1 recipe: no automatic device or ECU selection. */
@Composable
internal fun NativeUsbFunctionalPanel(
    enabled: Boolean, generation: Long, vehicle: VehicleProfile,
    onRun: (UsbDevice, CamaroDiagConnectorNativeCampaign.Target, Uri, Long, VehicleProfile) -> Unit,
    asScan:Boolean=false,
    onRunFreeze: ((UsbDevice, CamaroDiagConnectorFreezeCampaign.Target, Uri, Long, VehicleProfile) -> Unit)? = null
) {
    val context = LocalContext.current
    val manager = context.getSystemService(UsbManager::class.java)
    var devices by remember { mutableStateOf(emptyList<UsbDevice>()) }
    var selected by remember(generation, vehicle) { mutableStateOf<UsbDevice?>(null) }
    var menu by remember { mutableStateOf(false) }
    var tx by remember(generation, vehicle) { mutableStateOf("") }
    var rx by remember(generation, vehicle) { mutableStateOf("") }
    var extended by remember(generation, vehicle) { mutableStateOf(false) }
    var confirmed by remember(generation, vehicle) { mutableStateOf(false) }
    var credential by remember(generation, vehicle) { mutableStateOf<Uri?>(null) }
    var granted by remember(generation, vehicle) { mutableStateOf(false) }
    var freeze by remember(generation,vehicle) { mutableStateOf(false) }
    var frames by remember(generation,vehicle) { mutableStateOf("00") }
    var pids by remember(generation,vehicle) { mutableStateOf("") }
    var budget by remember(generation,vehicle) { mutableStateOf("180") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        credential = uri; confirmed = false
    }
    fun refresh() {
        devices = manager?.deviceList?.values?.sortedBy { it.deviceName }.orEmpty()
        if (selected?.let { item -> devices.none { it == item } } == true) {
            selected = null; credential = null; confirmed = false
        }
        granted = selected?.let { manager?.hasPermission(it) == true } == true
    }
    LaunchedEffect(manager, generation) {
        refresh()
        UsbPermissionResultBus.results.collect { refresh() }
    }
    val device = selected
    val control = device?.let { item -> (0 until item.interfaceCount).map(item::getInterface)
        .filter { it.interfaceClass == UsbConstants.USB_CLASS_COMM }.singleOrNull() }
    val data = device?.let { item -> (0 until item.interfaceCount).map(item::getInterface)
        .filter { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }.singleOrNull() }
    fun address(s: String) = s.trim().removePrefix("0x").removePrefix("0X")
        .takeIf { it.matches(Regex("[0-9a-fA-F]{1,8}")) }?.toIntOrNull(16)
    val request = address(tx)
    val response = address(rx)
    val target = if (device != null && control != null && data != null && request != null && response != null)
        CamaroDiagConnectorNativeCampaign.Target(control.id, data.id, request, response,
            extended, if(freeze) (budget.toIntOrNull() ?: 0)*1000 else 60000, vehicle.vin, "ECM", device.deviceName, maxOf(1L,generation)) else null
    val freezeTarget = if (freeze && target != null) {
        val selectedFrames=parseFreezeIds(frames,8,false)
        val selectedPids=parseFreezeIds(pids,64,true)
        if(selectedFrames != null && selectedPids != null)
            CamaroDiagConnectorFreezeCampaign.Target(target,selectedFrames,selectedPids) else null
    } else null
    val scopeValid = if(freeze) freezeTarget?.valid == true else target?.valid == true
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(if(asScan) R.string.functional_native_usb_scan_title else R.string.functional_native_usb_title))
        Box {
            OutlinedButton(onClick = { refresh(); menu = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth()) {
                Text(device?.deviceName ?: stringResource(R.string.functional_native_usb_select))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                devices.forEach { item ->
                    DropdownMenuItem(text = { Text(item.deviceName + " • " +
                        "%04X:%04X".format(java.util.Locale.ROOT, item.vendorId, item.productId)) },
                        onClick = { selected = item; granted = manager?.hasPermission(item) == true
                            confirmed = false; credential = null; menu = false })
                }
            }
        }
        if (device != null && !granted) {
            OutlinedButton(onClick = {
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val intent = Intent("com.camarodiag.gen5.USB_PERMISSION").setPackage(context.packageName)
                manager?.requestPermission(device, PendingIntent.getBroadcast(context, device.deviceId, intent, flags))
            }, enabled = enabled) { Text(stringResource(R.string.functional_native_usb_permission)) }
        }
        if (device != null && (control == null || data == null))
            Text(stringResource(R.string.functional_native_usb_interfaces))
        OutlinedTextField(tx, { tx = it; confirmed = false }, singleLine = true,
            label = { Text(stringResource(R.string.functional_native_usb_tx)) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(rx, { rx = it; confirmed = false }, singleLine = true,
            label = { Text(stringResource(R.string.functional_native_usb_rx)) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(extended, { extended = it; confirmed = false }, enabled = enabled)
            Text(stringResource(R.string.functional_native_usb_extended))
        }
        if(onRunFreeze != null && !asScan) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(freeze,{ freeze=it; confirmed=false },enabled=enabled)
                Text(stringResource(R.string.j1979_freeze_native_title))
            }
            if(freeze) {
                OutlinedTextField(frames,{frames=it.take(23).uppercase(java.util.Locale.ROOT);confirmed=false},
                    singleLine=true,label={Text(stringResource(R.string.j1979_freeze_frames))},
                    enabled=enabled,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(pids,{pids=it.take(191).uppercase(java.util.Locale.ROOT);confirmed=false},
                    singleLine=true,label={Text(stringResource(R.string.j1979_freeze_pids))},
                    enabled=enabled,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(budget,{budget=it.take(3);confirmed=false},singleLine=true,
                    label={Text(stringResource(R.string.j1979_live_budget))},
                    enabled=enabled,modifier=Modifier.fillMaxWidth())
            }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = enabled) {
            Text(stringResource(if (credential == null) R.string.functional_native_usb_key else R.string.functional_native_usb_key_selected))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(confirmed, { confirmed = it }, enabled = enabled)
            Text(if(freeze) stringResource(R.string.j1979_freeze_confirm,vehicle.vin)
                else stringResource(R.string.functional_native_usb_confirm))
        }
        Button(onClick = {
            val uri = credential
            if (device != null && target != null && scopeValid && uri != null && confirmed &&
                manager?.hasPermission(device) == true) {
                confirmed = false; credential = null
                if(freeze) freezeTarget?.let { onRunFreeze?.invoke(device,it,uri,generation,vehicle) }
                else onRun(device, target, uri, generation, vehicle)
            }
        }, enabled = enabled && granted && confirmed && credential != null && scopeValid,
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(if(freeze) R.string.j1979_freeze_run else if(asScan) R.string.functional_native_usb_scan_run else R.string.functional_native_usb_run)) }
    }
}
