package com.camarodiag.gen5.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local imported evidence only. No transport, file export, reset or recovery action. */
@Composable
internal fun ConnectorCheckpointPanel() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CamaroDiagConnectorCheckpoint.Inspection?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        result = null
        if (uri != null) {
            reading = true
            scope.launch {
                try {
                    result = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            CamaroDiagConnectorCheckpoint.inspectStream(it)
                        } ?: CamaroDiagConnectorCheckpoint.inspect(null)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    result = CamaroDiagConnectorCheckpoint.inspect(null)
                } finally {
                    reading = false
                }
            }
        }
    }
    OutlinedButton(
        onClick = { result = null; picker.launch(arrayOf("application/octet-stream", "*/*")) },
        enabled = !reading,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.cal_import_file) + " (CDJ/1)")
    }
    result?.let { inspection ->
        // Wire-level tokens are technical identifiers, not localized status text.
        Text("CDJ/1 · " + stringResource(R.string.status_offline), color = TextMuted, fontSize = 11.sp)
        Text(inspection.disposition.name, color = WarnRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        inspection.record?.let { record ->
            Text("${record.stage.name} · #${record.sequence}", color = TextMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text("SHA-256: ${record.manifestSha256}", color = TextMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
        }
        Text(stringResource(R.string.status_not_verified), color = WarnRed, fontSize = 11.sp)
        Text(stringResource(R.string.cal_expert_block_recovery), color = WarnRed, fontSize = 11.sp)
        Text(stringResource(R.string.cal_no_calibration_write), color = WarnRed, fontSize = 11.sp)
    }
}
