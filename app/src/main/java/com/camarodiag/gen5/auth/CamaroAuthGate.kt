package com.camarodiag.gen5.auth

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.camarodiag.gen5.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Denies access to diagnostic screens until a Firebase session exists.
 * The authenticated user's session is persisted by the Firebase SDK.
 */
@Composable
fun CamaroAuthGate(content: @Composable () -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }
    var currentUser by remember { mutableStateOf(auth.currentUser) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { currentUser = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    if (currentUser == null) {
        CamaroLoginScreen(auth)
    } else {
        Box {
            content()
        }
    }
}

@Composable
private fun CamaroLoginScreen(auth: FirebaseAuth) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val french = remember { Locale.getDefault().language == "fr" }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }

    fun finishError(error: Exception?) {
        busy = false
        message = if (french) "Échec de l'authentification. Vérifiez vos informations et votre connexion."
        else "Authentication failed. Check your details and your connection."
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("CamaroDiag", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text(if (french) "Connectez-vous pour accéder au diagnostic" else "Sign in to access diagnostics")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(value = email, onValueChange = { email = it }, singleLine = true,
            label = { Text(if (french) "Adresse e-mail" else "Email address") },
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text(if (french) "Mot de passe" else "Password") },
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            onClick = {
                busy = true
                message = ""
                val normalizedEmail = email.trim()
                if (register) {
                    auth.createUserWithEmailAndPassword(normalizedEmail, password)
                        .addOnCompleteListener { task ->
                            busy = false
                            if (task.isSuccessful) {
                                auth.currentUser?.sendEmailVerification()
                                message = if (french) "Compte créé. Vérifiez votre boîte e-mail."
                                else "Account created. Check your email for verification."
                            } else finishError(task.exception)
                        }
                } else {
                    auth.signInWithEmailAndPassword(normalizedEmail, password)
                        .addOnCompleteListener { task ->
                            busy = false
                            if (!task.isSuccessful) finishError(task.exception)
                        }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (register) { if (french) "Créer mon compte" else "Create account" }
                 else { if (french) "Se connecter" else "Sign in" }) }
        TextButton(enabled = !busy, onClick = { register = !register; message = "" }) {
            Text(if (register) { if (french) "J'ai déjà un compte" else "I already have an account" }
                 else { if (french) "Créer un compte e-mail" else "Create an email account" })
        }
        TextButton(enabled = !busy && email.isNotBlank(), onClick = {
            busy = true
            auth.sendPasswordResetEmail(email.trim()).addOnCompleteListener { task ->
                busy = false
                message = if (task.isSuccessful) {
                    if (french) "Si ce compte existe, un e-mail de réinitialisation a été envoyé."
                    else "If this account exists, a reset email was sent."
                } else {
                    if (french) "Impossible d'envoyer l'e-mail pour le moment."
                    else "Unable to send the email right now."
                }
            }
        }) { Text(if (french) "Mot de passe oublié ?" else "Forgot password?") }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true
                message = ""
                scope.launch {
                    try {
                        val googleIdOption = GetGoogleIdOption.Builder()
                            .setServerClientId(context.getString(R.string.default_web_client_id))
                            .setFilterByAuthorizedAccounts(false)
                            .build()
                        val request = GetCredentialRequest.Builder()
                            .addCredentialOption(googleIdOption)
                            .build()
                        val credential = CredentialManager.create(context)
                            .getCredential(context, request).credential
                        if (credential is CustomCredential &&
                            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                                .addOnCompleteListener { task ->
                                    busy = false
                                    if (!task.isSuccessful) finishError(task.exception)
                                }
                        } else {
                            busy = false
                            message = if (french) "Compte Google non reconnu." else "Google account not recognized."
                        }
                    } catch (_: Exception) {
                        busy = false
                        message = if (french) "Connexion Google annulée ou indisponible."
                                  else "Google sign-in cancelled or unavailable."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (french) "Continuer avec Google" else "Continue with Google") }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
