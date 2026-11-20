package com.camarodiag.gen5.auth

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
    // Recompose after an explicit Firebase email verification reload.
    var verificationRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { currentUser = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    val user = currentUser
    if (user == null) {
        CamaroLoginScreen(auth)
    } else if (user.providerData.any { it.providerId == "password" } &&
        !remember(user, verificationRevision) { user.isEmailVerified }
    ) {
        EmailVerificationScreen(auth, onVerified = { verificationRevision++ })
    } else {
        var accountOpen by remember(user.uid) { mutableStateOf(false) }
        val french = remember { Locale.getDefault().language == "fr" }
        Box {
            content()
            TextButton(
                onClick = { accountOpen = true },
                modifier = Modifier.align(Alignment.TopEnd)
            ) { Text(if (french) "Mon compte" else "My account") }
        }
        if (accountOpen) {
            val provider = when {
                user.providerData.any { it.providerId == "google.com" } -> "Google"
                user.providerData.any { it.providerId == "password" } ->
                    if (french) "E-mail / mot de passe" else "Email / password"
                else -> if (french) "Autre fournisseur" else "Other provider"
            }
            AlertDialog(
                onDismissRequest = { accountOpen = false },
                title = { Text(if (french) "Mon compte" else "My account") },
                text = {
                    Column {
                        Text(user.email.orEmpty())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (french) "Connexion : $provider" else "Signed in with: $provider",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        accountOpen = false
                        auth.signOut()
                    }) { Text(if (french) "Se déconnecter" else "Sign out") }
                },
                dismissButton = {
                    TextButton(onClick = { accountOpen = false }) {
                        Text(if (french) "Fermer" else "Close")
                    }
                }
            )
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

    fun finishError() {
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
                                val registeredUser = auth.currentUser
                                if (registeredUser == null) {
                                    finishError()
                                } else {
                                    // Do not claim the verification email was sent before
                                    // Firebase confirms the request actually succeeded.
                                    registeredUser.sendEmailVerification().addOnCompleteListener { sent ->
                                        if (auth.currentUser?.uid != registeredUser.uid) return@addOnCompleteListener
                                        message = if (sent.isSuccessful) {
                                            if (french) "Compte créé. Vérifiez votre boîte e-mail."
                                            else "Account created. Check your email for verification."
                                        } else {
                                            if (french) "Compte créé, mais l'envoi de l'e-mail a échoué. Réessayez depuis l'écran de vérification."
                                            else "Account created, but the verification email could not be sent. Retry from the verification screen."
                                        }
                                    }
                                }
                            } else finishError()
                        }
                } else {
                    auth.signInWithEmailAndPassword(normalizedEmail, password)
                        .addOnCompleteListener { task ->
                            busy = false
                            if (!task.isSuccessful) finishError()
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
                                    if (!task.isSuccessful) finishError()
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

@Composable
private fun EmailVerificationScreen(auth: FirebaseAuth, onVerified: () -> Unit) {
    val french = remember { Locale.getDefault().language == "fr" }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(if (french) "Vérifiez votre adresse e-mail" else "Verify your email address",
            style = MaterialTheme.typography.headlineSmall)
        Text(auth.currentUser?.email.orEmpty())
        Spacer(Modifier.height(16.dp))
        Button(enabled = !busy, onClick = {
            val pendingUser = auth.currentUser ?: return@Button
            busy = true
            pendingUser.reload().addOnCompleteListener { task ->
                busy = false
                val verified = task.isSuccessful &&
                    auth.currentUser?.uid == pendingUser.uid &&
                    auth.currentUser?.isEmailVerified == true
                message = if (verified) {
                    if (french) "Adresse vérifiée." else "Email verified."
                } else {
                    if (french) "Vérification non confirmée." else "Verification not confirmed."
                }
                if (verified) onVerified()
            }
        }) { Text(if (french) "J'ai vérifié mon adresse" else "I've verified my email") }
        TextButton(enabled = !busy, onClick = {
            auth.currentUser?.sendEmailVerification()?.addOnCompleteListener { task ->
                message = if (task.isSuccessful) {
                    if (french) "E-mail de vérification envoyé." else "Verification email sent."
                } else {
                    if (french) "Envoi impossible." else "Unable to send email."
                }
            }
        }) { Text(if (french) "Renvoyer l'e-mail" else "Resend email") }
        TextButton(onClick = { auth.signOut() }) {
            Text(if (french) "Se déconnecter" else "Sign out")
        }
        if (message.isNotBlank()) Text(message)
    }
}
