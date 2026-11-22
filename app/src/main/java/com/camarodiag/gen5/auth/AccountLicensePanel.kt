package com.camarodiag.gen5.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.functions.FirebaseFunctions

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

@Composable
internal fun AccountAdministrationDialog(french: Boolean, onClose: () -> Unit) {
    val functions = remember { FirebaseFunctions.getInstance("europe-west1") }
    var email by remember { mutableStateOf("") }
    var selectedUid by remember { mutableStateOf<String?>(null) }
    var selectedEmail by remember { mutableStateOf("") }
    var licensed by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun readAccount(value: String) {
        busy = true
        message = ""
        selectedUid = null
        licensed = null
        functions.getHttpsCallable("adminGetAccount")
            .call(mapOf("email" to value.trim()))
            .addOnCompleteListener { task ->
                busy = false
                if (!task.isSuccessful) {
                    message = if (french) "Consultation refusée ou indisponible. Reconnectez-vous si nécessaire."
                    else "Lookup denied or unavailable. Sign in again if needed."
                    return@addOnCompleteListener
                }
                val data = (task.result?.data as? Map<*, *>).orEmpty()
                val account = data["account"] as? Map<*, *>
                selectedUid = account?.get("uid") as? String
                selectedEmail = account?.get("email") as? String ?: ""
                val status = account?.get("license") as? Map<*, *>
                licensed = status?.get("licensed") as? Boolean
                if (selectedUid == null || licensed == null) {
                    message = if (french) "Réponse incomplète" else "Incomplete response"
                }
            }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (french) "Administration des comptes" else "Account administration") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(if (french) "Rechercher un utilisateur par adresse e-mail"
                     else "Find a user by email address")
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        selectedUid = null
                        licensed = null
                    },
                    label = { Text(if (french) "Adresse e-mail" else "Email address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = !busy && email.contains("@"),
                    onClick = { readAccount(email) }
                ) { Text(if (french) "Rechercher" else "Search") }

                if (selectedUid != null && licensed != null) {
                    Text(selectedEmail)
                    Text((if (french) "Licence : " else "License: ") +
                        (if (licensed == true) (if (french) "Oui" else "Yes")
                         else (if (french) "Non" else "No")))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && licensed != true, onClick = {
                            busy = true
                            functions.getHttpsCallable("adminUpdateAccount")
                                .call(mapOf("uid" to selectedUid, "action" to "license", "value" to true))
                                .addOnCompleteListener { task ->
                                    busy = false
                                    if (task.isSuccessful) {
                                        licensed = true
                                        message = if (french) "Licence activée" else "License enabled"
                                    } else message = if (french) "Modification refusée" else "Update denied"
                                }
                        }) { Text(if (french) "Oui" else "Yes") }
                        OutlinedButton(enabled = !busy && licensed != false, onClick = {
                            busy = true
                            functions.getHttpsCallable("adminUpdateAccount")
                                .call(mapOf("uid" to selectedUid, "action" to "license", "value" to false))
                                .addOnCompleteListener { task ->
                                    busy = false
                                    if (task.isSuccessful) {
                                        licensed = false
                                        message = if (french) "Licence désactivée" else "License disabled"
                                    } else message = if (french) "Modification refusée" else "Update denied"
                                }
                        }) { Text(if (french) "Non" else "No") }
                    }
                }
                if (message.isNotEmpty()) Text(message)
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onClose) {
                Text(if (french) "Fermer" else "Close")
            }
        }
    )
}
