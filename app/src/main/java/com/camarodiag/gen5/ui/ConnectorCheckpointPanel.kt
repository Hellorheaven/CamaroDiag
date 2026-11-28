package com.camarodiag.gen5.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.camarodiag.gen5.R
import com.camarodiag.gen5.data.obd.CamaroDiagConnectorCheckpoint
import com.camarodiag.gen5.ui.theme.TextMuted
import com.camarodiag.gen5.ui.theme.WarnRed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local imported evidence and explicit offline report export only.
 * Neither matching identity nor CRC can enable a transport or restoration. */
@Composable
internal fun ConnectorCheckpointPanel() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Uri?>(null) }
    var transaction by remember { mutableStateOf("") }
    var manifest by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<CamaroDiagConnectorCheckpoint.Snapshot?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    var exportPath by remember { mutableStateOf<String?>(null) }
    val valid = (transaction.isEmpty() || transaction.length == 32) &&
        (manifest.isEmpty() || manifest.length == 64)
    fun invalidate() {
        result = null
        exportPath = null
        message = null
    }

    suspend fun capture(uri: Uri, tx: String?, digest: String?) = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        CamaroDiagConnectorCheckpoint.capture(
            open = { context.contentResolver.openInputStream(uri) },
            expectedTransaction = tx, expectedManifest = digest,
            checkActive = { coroutine.ensureActive() })
    }

    fun inspect(uri: Uri) {
        val tx = transaction.ifEmpty { null }
        val hash = manifest.ifEmpty { null }
        invalidate()
        busy = true
        scope.launch {
            try {
                result = capture(uri, tx, hash)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = R.string.cdj_inspection_failed
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy && valid) {
            selected = uri
            inspect(uri)
        }
    }
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            message = if (CamaroDiagExportStorage.authorize(context, uri))
                R.string.cdj_folder_authorized else R.string.cdj_folder_required
        }
    }

    OutlinedTextField(
        value = transaction,
        onValueChange = {
            transaction = it.uppercase(java.util.Locale.ROOT).filter { c -> c in "0123456789ABCDEF" }.take(32)
            invalidate()
        },
        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.cdj_expected_transaction)) }
    )
    OutlinedTextField(
        value = manifest,
        onValueChange = {
            manifest = it.uppercase(java.util.Locale.ROOT).filter { c -> c in "0123456789ABCDEF" }.take(64)
            invalidate()
        },
        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.cdj_expected_manifest)) }
    )
    OutlinedButton(
        onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
        enabled = !busy && valid,
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(R.string.cdj_open)) }
    selected?.let { uri ->
        OutlinedButton(onClick = { inspect(uri) }, enabled = !busy && valid,
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cdj_reinspect)) }
    }
    if (busy) Text(stringResource(R.string.cdj_inspecting), color = TextMuted)
    result?.let { snapshot ->
        val inspection = snapshot.inspection
        // Previous technical display:
        // Text("CDJ/1 · " + stringResource(R.string.status_offline), color = TextMuted, fontSize = 11.sp)
        // Text(inspection.disposition.name, color = WarnRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        val status = when (inspection.disposition) {
            CamaroDiagConnectorCheckpoint.Disposition.UNAVAILABLE_OR_CORRUPT -> R.string.cdj_corrupt
            CamaroDiagConnectorCheckpoint.Disposition.RECOVERY_REQUIRED -> R.string.cdj_recovery
            CamaroDiagConnectorCheckpoint.Disposition.SAFE_BUT_BLOCKED -> R.string.cdj_blocked
            CamaroDiagConnectorCheckpoint.Disposition.IDENTITY_MISMATCH -> R.string.cdj_identity_mismatch
        }
        Text(stringResource(status), color = WarnRed)
        inspection.record?.let { record ->
            Text("${record.stage.name} · #${record.sequence}", color = TextMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.cdj_transaction, record.transactionHex), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.cdj_manifest, record.manifestSha256), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
        }
        snapshot.snapshotSha256?.let { hash ->
            Text(stringResource(R.string.cal_recovery_snapshot_sha256), fontSize = 11.sp)
            Text(hash, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        snapshot.problem?.let { problem ->
            Text(stringResource(if (problem == "CHANGED_DURING_INSPECTION") R.string.cdj_changed
                else R.string.cdj_inspection_failed), color = WarnRed)
        }
        Text(stringResource(R.string.cdj_unauthenticated), color = WarnRed, fontSize = 11.sp)
        Text(stringResource(R.string.cal_expert_block_recovery), color = WarnRed, fontSize = 11.sp)
        Text(stringResource(R.string.cal_no_calibration_write), color = WarnRed, fontSize = 11.sp)
        OutlinedButton(
            onClick = {
                val uri = selected
                if (uri != null) {
                    busy = true
                    message = null
                    exportPath = null
                    scope.launch {
                        try {
                            if (CamaroDiagExportStorage.availableTree(context) == null) {
                                message = R.string.cdj_folder_required
                                grant.launch(CamaroDiagExportStorage.initialPickerUri())
                            } else {
                                val current = capture(uri, snapshot.expectedTransaction, snapshot.expectedManifest)
                                CamaroDiagConnectorCheckpoint.requireSameEvidence(snapshot, current)
                                val bytes = ConnectorCheckpointReportJson.encode(current).toByteArray(Charsets.UTF_8)
                                val name = CamaroDiagExportStorage.write(context,
                                    "CamaroDiag_CDJ_Checkpoint.json", "application/json",
                                    CamaroDiagExportStorage.Category.RECOVERY,
                                    verifyStaged = { input ->
                                        // The export storage verifies staged bytes independently.
                                        // Re-acquire source evidence before final publication too.
                                        val latest = capture(uri, snapshot.expectedTransaction, snapshot.expectedManifest)
                                        CamaroDiagConnectorCheckpoint.requireSameEvidence(snapshot, latest)
                                    }) { output -> output.write(bytes) }
                                exportPath = name
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: IllegalStateException) {
                            result = null
                            message = R.string.cdj_changed
                        } catch (_: Exception) {
                            message = R.string.cdj_export_failed
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            enabled = !busy && snapshot.exportable && selected != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.cdj_export)) }
    }
    message?.let { Text(stringResource(it), color = WarnRed, fontSize = 11.sp) }
    exportPath?.let { Text(it, fontSize = 11.sp) }
}
