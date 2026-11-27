package com.camarodiag.gen5.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camarodiag.gen5.R
import com.camarodiag.gen5.data.obd.PersistentEcuWriteJournal.RecoveryDisposition
import com.camarodiag.gen5.ui.theme.CamaroAmber
import com.camarodiag.gen5.ui.theme.OkGreen
import com.camarodiag.gen5.ui.theme.TextPrimary
import com.camarodiag.gen5.ui.theme.WarnRed

/** Selection order is explicit. Neither document names nor times imply chronology. */
@Composable
internal fun PhysicalRecoveryHistoryPanel(
    identity: PhysicalRecoveryIdentity,
    accountUid: String?,
    state: CalibrationWorkspaceState,
    onInspect: (PhysicalRecoveryIdentity, List<Uri>) -> Unit,
    onExport: (PhysicalRecoveryHistory.Report) -> Unit
) {
    var trees by remember(accountUid, identity) { mutableStateOf(emptyList<Uri>()) }
    var pickerOwner by remember(accountUid, identity) { mutableStateOf<String?>(null) }
    var duplicate by remember(accountUid, identity) { mutableStateOf(false) }
    val editable = accountUid != null && identity.valid &&
        !state.physicalRecoveryReading && !state.physicalRecoveryExporting
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val expected = pickerOwner
        pickerOwner = null
        if (uri != null && expected != null && expected == accountUid && editable &&
            trees.size < PhysicalRecoveryHistory.MAX_SNAPSHOTS) {
            val key = treeKey(uri)
            if (key == null || trees.any { treeKey(it) == key }) duplicate = true
            else {
                trees = trees + uri
                duplicate = false
            }
        }
    }
    HorizontalDivider()
    Text(stringResource(R.string.recovery_history_title),
        color = TextPrimary, fontWeight = FontWeight.Bold)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((index, uri) in trees.withIndex()) {
            Text(stringResource(R.string.recovery_history_folder, index + 1,
                treeKey(uri)?.second ?: ""), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { trees = moved(trees, index, -1); duplicate = false },
                    enabled = editable && index > 0) {
                    Text(stringResource(R.string.recovery_history_up))
                }
                TextButton(onClick = { trees = moved(trees, index, 1); duplicate = false },
                    enabled = editable && index < trees.lastIndex) {
                    Text(stringResource(R.string.recovery_history_down))
                }
                TextButton(onClick = { trees = trees.filterIndexed { i, _ -> i != index }; duplicate = false },
                    enabled = editable) { Text(stringResource(R.string.recovery_history_remove)) }
            }
        }
    }
    if (duplicate) Text(stringResource(R.string.recovery_history_duplicate), color = WarnRed)
    OutlinedButton(onClick = {
        pickerOwner = accountUid
        picker.launch(null)
    }, enabled = editable && pickerOwner == null && trees.size < PhysicalRecoveryHistory.MAX_SNAPSHOTS,
        modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.recovery_history_add))
    }
    Button(onClick = { onInspect(identity, trees.toList()) },
        enabled = editable && trees.size in 2..PhysicalRecoveryHistory.MAX_SNAPSHOTS,
        modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.recovery_history_inspect))
    }
    val report = state.physicalRecoveryHistory?.takeIf {
        it.identity == identity && state.physicalRecoveryHistoryUris == trees
    }
    if (report != null) {
        Text(stringResource(if (report.verified) R.string.recovery_history_verified else
            R.string.recovery_history_rejected), color = if (report.verified) OkGreen else WarnRed)
        report.problem?.let { Text(stringResource(problemResource(it)), color = WarnRed) }
        report.failedOrdinal?.let { Text(stringResource(R.string.recovery_history_failed_ordinal, it)) }
        report.snapshots.forEach { snapshot ->
            val audit = snapshot.evidence.journalAudit
            Text(stringResource(R.string.recovery_history_snapshot, snapshot.ordinal,
                snapshot.journalName, audit?.records ?: 0), fontSize = 12.sp)
            snapshot.evidence.snapshotSha256?.let {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            Text(stringResource(dispositionResource(snapshot.evidence.journalDisposition)), fontSize = 12.sp)
        }
        report.transitions.forEach { transition ->
            Text(stringResource(R.string.recovery_history_transition, transition.fromOrdinal,
                transition.toOrdinal, transition.addedRecords, transition.addedBytes), fontSize = 12.sp)
        }
        report.latestEvidence?.let { latest ->
            Text(stringResource(R.string.recovery_history_latest,
                stringResource(dispositionResource(latest.journalDisposition))), color = CamaroAmber)
            latest.uncertainAddress?.let { Text(stringResource(R.string.cal_recovery_address, it), color = WarnRed) }
            latest.uncertainRegion?.let { Text(stringResource(R.string.cal_recovery_uncertain_region, it.name), color = WarnRed) }
        }
        Text(stringResource(R.string.cal_recovery_no_restore), color = CamaroAmber, fontSize = 12.sp)
        OutlinedButton(onClick = { onExport(report) }, enabled = editable && report.verified,
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cal_recovery_export)) }
    }
    if (state.physicalRecoveryHistoryUris == trees && trees.isNotEmpty()) {
        state.physicalRecoveryHistoryMessage?.let { Text(it, fontSize = 12.sp) }
    }
}

private fun treeKey(uri: Uri): Pair<String, String>? = runCatching {
    require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
    requireNotNull(uri.authority) to DocumentsContract.getTreeDocumentId(uri)
}.getOrNull()

internal fun <T> moved(rows: List<T>, index: Int, offset: Int): List<T> {
    val destination = index + offset
    if (index !in rows.indices || destination !in rows.indices) return rows
    return rows.toMutableList().apply { add(destination, removeAt(index)) }.toList()
}

private fun dispositionResource(disposition: RecoveryDisposition?): Int = when (disposition) {
    RecoveryDisposition.NO_ERASE_RECORDED -> R.string.cal_recovery_no_erase
    RecoveryDisposition.ERASE_OR_WRITE_MAY_BE_INCOMPLETE -> R.string.cal_recovery_incomplete
    RecoveryDisposition.ADDITIONAL_BASELINES_NOT_WRITTEN -> R.string.cal_recovery_partial
    RecoveryDisposition.ALL_RECORDED_SECTORS_VERIFIED -> R.string.cal_recovery_sectors_verified
    RecoveryDisposition.FINAL_READBACK_INCOMPLETE -> R.string.cal_recovery_final_incomplete
    RecoveryDisposition.TRANSACTION_COMPLETION_RECORDED -> R.string.cal_recovery_completed
    else -> R.string.cal_recovery_unknown
}

private fun problemResource(problem: PhysicalRecoveryHistory.Problem): Int = when (problem) {
    PhysicalRecoveryHistory.Problem.INVALID_SELECTION -> R.string.recovery_history_invalid_selection
    PhysicalRecoveryHistory.Problem.UNVERIFIED_SNAPSHOT -> R.string.recovery_history_unverified_snapshot
    PhysicalRecoveryHistory.Problem.ORIGINAL_EVIDENCE_CHANGED -> R.string.recovery_history_original_changed
    PhysicalRecoveryHistory.Problem.NOT_STRICT_EXTENSION -> R.string.recovery_history_not_extension
    PhysicalRecoveryHistory.Problem.TERMINAL_EXTENSION -> R.string.recovery_history_terminal_extension
    PhysicalRecoveryHistory.Problem.EVIDENCE_CHANGED -> R.string.recovery_source_changed
    PhysicalRecoveryHistory.Problem.UNAVAILABLE -> R.string.cal_recovery_folder_failed
}
