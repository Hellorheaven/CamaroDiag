package com.camarodiag.gen5.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

@Composable
internal fun AccountAdministrationDialog(french: Boolean, onClose: () -> Unit) {
    val functions = remember { FirebaseFunctions.getInstance("europe-west1") }
    var email by remember { mutableStateOf("") }
    var uid by remember { mutableStateOf<String?>(null) }
    var selectedEmail by remember { mutableStateOf("") }
    var licensed by remember { mutableStateOf<Boolean?>(null) }
    var disabled by remember { mutableStateOf(false) }
    var isAdmin by remember { mutableStateOf(false) }
    var isOwner by remember { mutableStateOf(false) }
    var emailVerified by remember { mutableStateOf(false) }
    var passwordLinked by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, Boolean?>?>(null) }

    fun updateFromServer(raw: Any?) {
        val envelope = raw as? Map<*, *> ?: return
        val account = envelope["account"] as? Map<*, *> ?: return
        uid = account["uid"] as? String
        selectedEmail = account["email"] as? String ?: ""
        disabled = account["disabled"] == true
        emailVerified = account["emailVerified"] == true
        isOwner = account["functionalTests"] == true
        isAdmin = account["administrator"] == true
        val providers = account["providers"] as? List<*>
        passwordLinked = providers?.contains("password") == true
        val status = account["license"] as? Map<*, *>
        licensed = status?.get("licensed") as? Boolean
        source = status?.get("source") as? String ?: ""
    }

    fun fetchAccount() {
        if (email.isBlank()) return
        busy = true
        message = ""
        uid = null
        licensed = null
        functions.getHttpsCallable("adminGetAccount")
            .call(mapOf("email" to email.trim()))
            .addOnCompleteListener { task ->
                busy = false
                if (task.isSuccessful) {
                    updateFromServer(task.result?.data)
                    if (uid == null) message = if (french) "Réponse incomplète" else "Incomplete response"
                } else {
                    message = if (french) "Consultation indisponible. Reconnectez-vous si nécessaire."
                    else "Lookup unavailable. Sign in again if necessary."
                }
            }
    }

    fun changeAccount(action: String, value: Boolean? = null) {
        val targetUid = uid ?: return
        if (isOwner || busy) return
        busy = true
        message = ""
        val payload = mutableMapOf<String, Any>("uid" to targetUid, "action" to action)
        if (value != null) payload["value"] = value
        functions.getHttpsCallable("adminUpdateAccount")
            .call(payload)
            .addOnCompleteListener { task ->
                busy = false
                if (task.isSuccessful) {
                    updateFromServer(task.result?.data)
                    message = if (french) "Modification enregistrée." else "Change saved."
                } else {
                    message = if (french) "Modification refusée. Vérifiez vos droits ou reconnectez-vous."
                    else "Change refused. Check your permissions or sign in again."
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
                Text(if (french) "Recherche par adresse e-mail" else "Search by email address")
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        uid = null
                        licensed = null
                        message = ""
                    },
                    label = { Text(if (french) "E-mail utilisateur" else "User email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(enabled = !busy && email.contains("@"), onClick = { fetchAccount() }) {
                    Text(if (french) "Rechercher" else "Search")
                }
                if (uid != null) {
                    HorizontalDivider()
                    Text(selectedEmail, style = MaterialTheme.typography.titleSmall)
                    Text(if (french) "E-mail vérifié : " + if (emailVerified) "Oui" else "Non"
                         else "Email verified: " + if (emailVerified) "Yes" else "No")
                    Text(if (french) "Compte : " + if (disabled) "Désactivé" else "Actif"
                         else "Account: " + if (disabled) "Disabled" else "Active")
                    Text(if (french) "Administrateur : " + if (isAdmin) "Oui" else "Non"
                         else "Administrator: " + if (isAdmin) "Yes" else "No")
                    Text(if (french) "Licence : " + if (licensed == true) "Oui" else "Non"
                         else "License: " + if (licensed == true) "Yes" else "No")
                    if (source.isNotBlank()) Text(
                        (if (french) "Origine : " else "Source: ") + source,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (isOwner) {
                        Text(if (french) "Compte propriétaire protégé : modification interdite."
                             else "Protected owner account: changes are not permitted.")
                    } else {
                        Text(if (french) "Attribuer ou retirer une licence" else "Grant or revoke license")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy && licensed != true, onClick = {
                                changeAccount("license", true)
                            }) { Text(if (french) "Licence Oui" else "License Yes") }
                            OutlinedButton(enabled = !busy && licensed != false, onClick = {
                                confirm = "license" to false
                            }) { Text(if (french) "Licence Non" else "License No") }
                        }
                        HorizontalDivider()
                        Text(if (french) "Gestion des droits" else "Permission management")
                        OutlinedButton(enabled = !busy, onClick = {
                            confirm = "administrator" to !isAdmin
                        }) {
                            Text(if (isAdmin) {
                                if (french) "Retirer le droit administrateur" else "Revoke administrator"
                            } else {
                                if (french) "Donner le droit administrateur" else "Grant administrator"
                            })
                        }
                        OutlinedButton(enabled = !busy, onClick = {
                            confirm = "disable" to !disabled
                        }) {
                            Text(if (disabled) {
                                if (french) "Réactiver le compte" else "Enable account"
                            } else {
                                if (french) "Désactiver le compte" else "Disable account"
                            })
                        }
                        OutlinedButton(enabled = !busy, onClick = {
                            confirm = "revoke_sessions" to null
                        }) { Text(if (french) "Révoquer les sessions" else "Revoke sessions") }
                        if (passwordLinked && !disabled) {
                            OutlinedButton(enabled = !busy, onClick = {
                                busy = true
                                FirebaseAuth.getInstance().sendPasswordResetEmail(selectedEmail)
                                    .addOnCompleteListener { result ->
                                        busy = false
                                        message = if (result.isSuccessful) {
                                            if (french) "E-mail de réinitialisation demandé."
                                            else "Password reset email requested."
                                        } else {
                                            if (french) "Envoi du courriel impossible."
                                            else "Could not send reset email."
                                        }
                                    }
                            }) {
                                Text(if (french) "Envoyer une réinitialisation de mot de passe"
                                     else "Send password reset email")
                            }
                        }
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

    if (confirm != null) {
        val pending = confirm!!
        val label = when (pending.first) {
            "disable" -> if (french) "Modifier l'état du compte et éventuellement déconnecter l'utilisateur ?"
                         else "Change account status and possibly sign the user out?"
            "administrator" -> if (french) "Modifier les droits d'administration de cet utilisateur ?"
                               else "Change this user's administration permissions?"
            "revoke_sessions" -> if (french) "Révoquer toutes les sessions de ce compte ?"
                                 else "Revoke all sessions for this account?"
            else -> if (french) "Retirer l'accès aux fonctions Premium ?" else "Remove Premium access?"
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (french) "Confirmation" else "Confirm change") },
            text = { Text(label) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    changeAccount(pending.first, pending.second)
                }) { Text(if (french) "Confirmer" else "Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(if (french) "Annuler" else "Cancel")
                }
            }
        )
    }
}
