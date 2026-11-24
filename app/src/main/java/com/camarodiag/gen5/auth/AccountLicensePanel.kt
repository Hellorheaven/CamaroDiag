package com.camarodiag.gen5.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.camarodiag.gen5.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.auth.FirebaseAuth

/**
 * Read-only license status is independent of the disabled paywall.
 * Never interpret an unavailable backend as an unlicensed account.
 */
/**
 * The account dialog only displays the license result already checked when
 * the app started. Opening the dialog never triggers another Firebase call.
 */
@Composable
internal fun AccountLicenseStatus(
    french: Boolean,
    level: LicenseLevel,
    confirmed: Boolean?,
    source: String?
) {
    val value = when {
        !LicenseRolloutConfig.ENABLED ->
            if (french) "Contrôle des licences non activé" else "License checks not enabled"
        level == LicenseLevel.OWNER ->
            if (french) "Oui (propriétaire)" else "Yes (owner)"
        confirmed == null ->
            if (french) "Vérification en cours…" else "Checking…"
        confirmed == false ->
            if (french) "Indisponible" else "Unavailable"
        level == LicenseLevel.FREE ->
            if (french) "Non" else "No"
        source == "purchase" ->
            if (french) "Oui (achat)" else "Yes (purchase)"
        source == "promotion" ->
            if (french) "Oui (offerte)" else "Yes (complimentary)"
        source == "administrative" ->
            if (french) "Oui (attribution administrative)" else "Yes (admin grant)"
        else ->
            if (french) "Oui" else "Yes"
    }
    Text(
        (if (french) "Licence : " else "License: ") + value,
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        if (french) "Statut contrôlé au démarrage ou après une reconnexion."
        else "Status checked at startup or after signing in again.",
        style = MaterialTheme.typography.bodySmall
    )
}

private data class AdminAccountRow(
    val uid: String, val email: String, val admin: Boolean, val owner: Boolean,
    val disabled: Boolean, val verified: Boolean, val licensed: Boolean?,
    val passwordLinked: Boolean
)

private fun parseAdminAccount(raw: Any?): AdminAccountRow? {
    val m = raw as? Map<*, *> ?: return null
    val uid = (m["uid"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    return AdminAccountRow(uid, m["email"] as? String ?: "", m["administrator"] == true,
        m["functionalTests"] == true, m["disabled"] == true, m["emailVerified"] == true,
        (m["license"] as? Map<*, *>)?.get("licensed") as? Boolean,
        (m["providers"] as? List<*>)?.contains("password") == true)
}

private data class AdminChange(val row: AdminAccountRow, val action: String, val value: Boolean?)

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AccountAdministrationDialog(french: Boolean, onClose: () -> Unit) {
    val functions = remember { FirebaseFunctions.getInstance("europe-west1") }
    val actor = FirebaseAuth.getInstance().currentUser?.uid
    var rows by remember(actor) { mutableStateOf<List<AdminAccountRow>>(emptyList()) }
    var next by remember(actor) { mutableStateOf<String?>(null) }
    var busy by remember(actor) { mutableStateOf(false) }
    var error by remember(actor) { mutableStateOf(false) }
    var pending by remember(actor) { mutableStateOf<AdminChange?>(null) }
    var selected by remember(actor) { mutableStateOf<AdminAccountRow?>(null) }
    var active by remember(actor) { mutableStateOf(true) }
    DisposableEffect(actor) { onDispose { active = false } }
    fun current() = active && actor != null && FirebaseAuth.getInstance().currentUser?.uid == actor
    fun load(reset: Boolean) {
        if (busy || !current()) return
        val token = if (reset) null else next ?: return
        busy = true
        error = false
        if (reset) { rows = emptyList(); next = null; selected = null }
        val payload = if (token == null) emptyMap<String, String>() else mapOf("pageToken" to token)
        functions.getHttpsCallable("adminListAccounts").call(payload).addOnCompleteListener { task ->
            if (!current()) return@addOnCompleteListener
            busy = false
            val data = if (task.isSuccessful) task.result?.data as? Map<*, *> else null
            val raw = data?.get("accounts") as? List<*>
            val page = raw?.mapNotNull(::parseAdminAccount)
            if (raw == null || page == null || page.size != raw.size) { error = true; return@addOnCompleteListener }
            rows = (rows + page).distinctBy { it.uid }
            next = data?.get("nextPageToken") as? String
        }
    }
    fun editable(row: AdminAccountRow) = row.uid != actor && !row.owner
    fun apply(change: AdminChange) {
        if (busy || !current() || !editable(change.row) || (change.action == "delete" && change.row.admin)) return
        busy = true
        error = false
        val data = mutableMapOf<String, Any>("uid" to change.row.uid, "action" to change.action)
        change.value?.let { data["value"] = it }
        functions.getHttpsCallable("adminUpdateAccount").call(data).addOnCompleteListener { task ->
            if (!current()) return@addOnCompleteListener
            busy = false
            val envelope = if (task.isSuccessful) task.result?.data as? Map<*, *> else null
            if (change.action == "delete") {
                if (envelope?.get("deletedUid") != change.row.uid) { error = true; return@addOnCompleteListener }
                rows = rows.filterNot { it.uid == change.row.uid }
                if (selected?.uid == change.row.uid) selected = null
                return@addOnCompleteListener
            }
            val result = parseAdminAccount(envelope?.get("account"))
            if (result == null || result.uid != change.row.uid) { error = true; return@addOnCompleteListener }
            rows = rows.map { if (it.uid == result.uid) result else it }
            if (selected?.uid == result.uid) selected = result
        }
    }
    LaunchedEffect(actor) { load(true) }
    val yes = stringResource(R.string.admin_table_yes)
    val no = stringResource(R.string.admin_table_no)
    val unknown = stringResource(R.string.admin_table_unknown)
    fun status(v: Boolean?) = when(v) { true -> yes; false -> no; null -> unknown }
    Dialog(onDismissRequest = { if (!busy) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.admin_table_title), style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { load(true) }, enabled = !busy) { Text(stringResource(R.string.admin_table_refresh)) }
                    TextButton(onClick = onClose, enabled = !busy) { Text(stringResource(R.string.admin_table_close)) }
                }
                if (error) Text(stringResource(R.string.admin_table_error), color = MaterialTheme.colorScheme.error)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row {
                        Text(stringResource(R.string.admin_table_email), Modifier.width(240.dp))
                        Text(stringResource(R.string.admin_table_license), Modifier.width(100.dp))
                        Text(stringResource(R.string.admin_table_admin), Modifier.width(130.dp))
                        Text(stringResource(R.string.admin_table_active), Modifier.width(100.dp))
                        Text(stringResource(R.string.admin_table_verified), Modifier.width(100.dp))
                        Text(stringResource(R.string.admin_table_actions), Modifier.width(130.dp))
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.width(800.dp).heightIn(max = 440.dp)) {
                        items(rows, key = { it.uid }) { row ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { selected = row }, modifier = Modifier.width(240.dp)) {
                                    Text(row.email.ifBlank { row.uid })
                                }
                                TextButton(onClick = { pending = AdminChange(row, "license", row.licensed != true) },
                                    enabled = !busy && editable(row) && row.licensed != null && !row.disabled &&
                                        (row.licensed == true || row.verified), modifier = Modifier.width(100.dp)) { Text(status(row.licensed)) }
                                TextButton(onClick = { pending = AdminChange(row, "administrator", !row.admin) },
                                    enabled = !busy && editable(row), modifier = Modifier.width(130.dp)) {
                                    Text(if (row.owner) stringResource(R.string.admin_table_owner) else status(row.admin))
                                }
                                TextButton(onClick = { pending = AdminChange(row, "disable", !row.disabled) },
                                    enabled = !busy && editable(row) && (!row.admin || row.disabled), modifier = Modifier.width(100.dp)) { Text(status(!row.disabled)) }
                                Text(status(row.verified), Modifier.width(100.dp))
                                TextButton(enabled = !busy && editable(row) && !row.admin,
                                    onClick = { pending = AdminChange(row, "delete", true) },
                                    modifier = Modifier.width(130.dp)) {
                                    Text(stringResource(R.string.admin_table_delete), color = if (!busy && editable(row) && !row.admin) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                if (next != null) OutlinedButton(enabled = !busy, onClick = { load(false) }) { Text(stringResource(R.string.admin_table_more)) }
                if (!busy && !error && rows.isEmpty()) Text(stringResource(R.string.admin_table_empty))
            }
        }
    }
    selected?.let { row ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(row.email.ifBlank { row.uid }) },
            text = { Column {
                Text(status(row.verified))
                OutlinedButton(enabled = !busy && editable(row), onClick = { selected = null; pending = AdminChange(row, "revoke_sessions", null) }) {
                    Text(stringResource(R.string.admin_table_revoke))
                }
                if (row.passwordLinked && !row.disabled && row.email.isNotBlank()) {
                    OutlinedButton(enabled = !busy && editable(row), onClick = {
                        busy = true; error = false; selected = null
                        FirebaseAuth.getInstance().sendPasswordResetEmail(row.email).addOnCompleteListener {
                            if (current()) { busy = false; error = !it.isSuccessful }
                        }
                    }) { Text(stringResource(R.string.admin_table_reset)) }
                }
            } }, confirmButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.admin_table_close)) } })
    }
    pending?.let { change ->
        val permission = stringResource(when(change.action) {
            "delete" -> R.string.admin_table_delete
            "license" -> R.string.admin_table_license
            "administrator" -> R.string.admin_table_admin
            "disable" -> R.string.admin_table_active
            else -> R.string.admin_table_revoke
        })
        AlertDialog(onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.admin_table_confirm)) },
            text = { if (change.action == "delete") Text(stringResource(R.string.admin_table_delete_warning, change.row.email.ifBlank { change.row.uid })) else Text(change.row.email.ifBlank { change.row.uid } + "\n" + permission +
                (change.value?.let { ": " + status(if (change.action == "disable") !it else it) } ?: "")) },
            confirmButton = { TextButton(onClick = { pending = null; apply(change) }) { Text(stringResource(R.string.admin_table_confirm)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.admin_table_cancel)) } })
    }
}
