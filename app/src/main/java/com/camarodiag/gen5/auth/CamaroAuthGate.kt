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
import androidx.credentials.exceptions.NoCredentialException
import android.content.Context
import androidx.core.content.edit
import com.google.firebase.FirebaseNetworkException
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.camarodiag.gen5.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Denies access to diagnostic screens until a Firebase session exists.
 * The authenticated user's session is persisted by the Firebase SDK.
 */
@Composable
fun CamaroAuthGate(
    onAccountChanged: (String?) -> Unit,
    onAccountDeleted: (String) -> Unit,
    content: @Composable (onOpenAccount: () -> Unit) -> Unit
) {
    val auth = remember { FirebaseAuth.getInstance() }
    val context = LocalContext.current
    val validationPrefs = remember(context) {
        context.getSharedPreferences("camarodiag_session_validation", Context.MODE_PRIVATE)
    }
    val signOut = rememberFirebaseSignOut(auth)
    var currentUser by remember { mutableStateOf(auth.currentUser) }
    // Recompose after an explicit Firebase email verification reload.
    var verificationRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { currentUser = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    val user = currentUser
    var readyAccountUid by remember { mutableStateOf<String?>(null) }
    var validatedAccountUid by remember { mutableStateOf<String?>(null) }
    var validatedUser by remember { mutableStateOf<com.google.firebase.auth.FirebaseUser?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var retryValidation by remember { mutableIntStateOf(0) }
    var validationGeneration by remember { mutableIntStateOf(0) }
    val latestOnAccountChanged by rememberUpdatedState(onAccountChanged)
    // Delay diagnostic UI until the old user's active connection and transient
    // diagnostic results have been cleared by the ViewModel.
    // Reconfirm authorization periodically, even when the app remains open.
    // This also enforces the bounded offline grace without needing a restart.
    LaunchedEffect(user?.uid) {
        if (user == null) return@LaunchedEffect
        while (true) {
            delay(5L * 60L * 1000L)
            retryValidation++
        }
    }
    LaunchedEffect(user, retryValidation) {
        // A late callback from the previous FirebaseUser instance or retry may
        // never authenticate a different validation attempt with the same UID.
        val attempt = ++validationGeneration
        // Refreshing the current user's token must not unmount the diagnostic
        // screen every five minutes. Only an identity change hides old data.
        val identityChanged = readyAccountUid != user?.uid ||
            (validatedUser != null && validatedUser !== user)
        if (identityChanged) {
            readyAccountUid = null
            validatedAccountUid = null
            validatedUser = null
            latestOnAccountChanged(user?.uid)
            readyAccountUid = user?.uid
        }
        validationError = null
        if (user != null) {
            val targetUid = user.uid
            user.getIdToken(true).addOnCompleteListener { task ->
                if (auth.currentUser?.uid != targetUid || validationGeneration != attempt) {
                    return@addOnCompleteListener
                }
                if (task.isSuccessful) {
                    validationPrefs.edit {
                        putLong("verified_$targetUid", System.currentTimeMillis())
                    }
                    validatedAccountUid = targetUid
                    validatedUser = user
                    latestOnAccountChanged(targetUid)
                } else {
                    val lastValidated = validationPrefs.getLong("verified_$targetUid", 0L)
                    val offlineGrace = task.exception is FirebaseNetworkException &&
                        OfflineSessionPolicy.allowsOfflineAccess(
                            lastValidated, System.currentTimeMillis()
                        )
                    if (offlineGrace) {
                        validatedAccountUid = targetUid
                        validatedUser = user
                        latestOnAccountChanged(targetUid)
                    } else {
                        // Expired offline validation or a rejected Firebase token:
                        // revoke in-app access immediately and end OBD operations.
                        validatedAccountUid = null
                        validatedUser = null
                        latestOnAccountChanged(null)
                        validationError = if (task.exception is FirebaseNetworkException) {
                            "offline"
                        } else {
                            "invalid"
                        }
                    }
                }
            }
        }
    }
    if (user == null) {
        CamaroLoginScreen(auth)
    } else if (user.providerData.any { it.providerId == "password" } &&
        !remember(user, verificationRevision) { user.isEmailVerified }
    ) {
        EmailVerificationScreen(auth, onVerified = { verificationRevision++ })
    } else if (readyAccountUid != user.uid || validatedAccountUid != user.uid ||
        validatedUser !== user
    ) {
        val french = remember { Locale.getDefault().language == "fr" }
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (validationError == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    if (validationError == "offline") {
                        if (french) "Connexion requise : la validation hors ligne a expiré."
                        else "Connect to the internet: offline validation has expired."
                    } else {
                        if (french) "Impossible de confirmer la session. Reconnectez-vous."
                        else "Session could not be verified. Sign in again."
                    }
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { retryValidation++ }) {
                    Text(if (french) "Réessayer" else "Retry")
                }
                TextButton(onClick = { signOut() }) {
                    Text(if (french) "Se déconnecter" else "Sign out")
                }
            }
        }
    } else {
        var accountOpen by remember(user.uid) { mutableStateOf(false) }
        var deleteConfirm by remember(user.uid) { mutableStateOf(false) }
        var deleting by remember(user.uid) { mutableStateOf(false) }
        var deleteError by remember(user.uid) { mutableStateOf(false) }
        var linking by remember(user.uid) { mutableStateOf(false) }
        var linkPassword by remember(user.uid) { mutableStateOf("") }
        var linkMessage by remember(user.uid) { mutableStateOf("") }
        var accountRevision by remember(user.uid) { mutableIntStateOf(0) }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val french = remember { Locale.getDefault().language == "fr" }
        // Account action belongs to the app's TopAppBar; an overlay here would
        // overlap system bars and the title on narrow Android 15/16 devices.
        content { accountOpen = true }
        if (accountOpen) {
            val googleLinked = remember(user, accountRevision) {
                user.providerData.any { it.providerId == "google.com" }
            }
            val passwordLinked = remember(user, accountRevision) {
                user.providerData.any { it.providerId == "password" }
            }
            val provider = when {
                googleLinked && passwordLinked -> if (french) "Google et e-mail" else "Google and email"
                googleLinked -> "Google"
                passwordLinked -> if (french) "E-mail / mot de passe" else "Email / password"
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
                        Spacer(Modifier.height(12.dp))
                        if (passwordLinked && !googleLinked && user.isEmailVerified) {
                            TextButton(enabled = !linking, onClick = {
                                linking = true
                                linkMessage = ""
                                scope.launch {
                                    try {
                                        val option = GetGoogleIdOption.Builder()
                                            .setServerClientId(context.getString(R.string.default_web_client_id))
                                            .setFilterByAuthorizedAccounts(false)
                                            .build()
                                        val request = GetCredentialRequest.Builder()
                                            .addCredentialOption(option).build()
                                        val credential = CredentialManager.create(context)
                                            .getCredential(context, request).credential
                                        if (credential !is CustomCredential ||
                                            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                        ) {
                                            linking = false
                                            linkMessage = if (french) "Compte Google non reconnu." else "Google account not recognized."
                                        } else {
                                            val google = GoogleIdTokenCredential.createFrom(credential.data)
                                            if (!google.id.equals(user.email, ignoreCase = true)) {
                                                linking = false
                                                linkMessage = if (french) "L'adresse Google doit correspondre au compte existant."
                                                else "Google email must match the existing account."
                                            } else {
                                                user.linkWithCredential(
                                                    GoogleAuthProvider.getCredential(google.idToken, null)
                                                ).addOnCompleteListener { result ->
                                                    linking = false
                                                    if (result.isSuccessful) accountRevision++
                                                    linkMessage = if (result.isSuccessful) {
                                                        if (french) "Compte Google lié." else "Google account linked."
                                                    } else {
                                                        if (french) "Liaison refusée. Ce compte Google peut déjà être utilisé."
                                                        else "Could not link Google. This Google account may already be in use."
                                                    }
                                                }
                                            }
                                        }
                                    } catch (_: NoCredentialException) {
                                        linking = false
                                        linkMessage = if (french) "Aucun compte Google disponible pour la liaison."
                                        else "No Google account available to link."
                                    } catch (_: Exception) {
                                        linking = false
                                        linkMessage = if (french) "Liaison Google annulée ou indisponible."
                                        else "Google account linking cancelled or unavailable."
                                    }
                                }
                            }) { Text(if (french) "Lier mon compte Google" else "Link my Google account") }
                        }
                        if (googleLinked && !passwordLinked && !user.email.isNullOrBlank()) {
                            OutlinedTextField(
                                value = linkPassword,
                                onValueChange = { linkPassword = it },
                                singleLine = true,
                                label = { Text(if (french) "Créer un mot de passe" else "Create a password") },
                                visualTransformation = PasswordVisualTransformation()
                            )
                            TextButton(enabled = !linking && linkPassword.length >= 6, onClick = {
                                linking = true
                                linkMessage = ""
                                val credential = EmailAuthProvider.getCredential(user.email!!, linkPassword)
                                user.linkWithCredential(credential).addOnCompleteListener { result ->
                                    linking = false
                                    linkPassword = ""
                                    if (result.isSuccessful) accountRevision++
                                    linkMessage = if (result.isSuccessful) {
                                        if (french) "Connexion e-mail ajoutée." else "Email sign-in added."
                                    } else {
                                        if (french) "Impossible d'ajouter ce mot de passe."
                                        else "Unable to add email password sign-in."
                                    }
                                }
                            }) { Text(if (french) "Ajouter la connexion e-mail" else "Add email sign-in") }
                        }
                        if (linkMessage.isNotEmpty()) Text(linkMessage)
                        Spacer(Modifier.height(12.dp))
                        TextButton(enabled = !deleting && !linking, onClick = {
                            accountOpen = false
                            deleteConfirm = true
                        }) {
                            Text(if (french) "Supprimer mon compte" else "Delete my account")
                        }
                        if (deleteError) Text(
                            if (french) "Suppression impossible. Reconnectez-vous récemment, puis réessayez."
                            else "Account deletion failed. Sign in again recently, then retry.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        accountOpen = false
                        signOut()
                    }) { Text(if (french) "Se déconnecter" else "Sign out") }
                },
                dismissButton = {
                    TextButton(onClick = { accountOpen = false }) {
                        Text(if (french) "Fermer" else "Close")
                    }
                }
            )
        }
        if (deleteConfirm) {
            AlertDialog(
                onDismissRequest = { if (!deleting) { deleteConfirm = false; accountOpen = true } },
                title = { Text(if (french) "Supprimer définitivement le compte ?" else "Permanently delete account?") },
                text = {
                    Text(
                        if (french)
                            "Le compte Firebase et les véhicules et diagnostics locaux associés seront supprimés. Exportez vos données avant de continuer."
                        else
                            "Your Firebase account and its local saved vehicles and diagnostic history will be deleted. Export important data before continuing."
                    )
                },
                confirmButton = {
                    TextButton(enabled = !deleting, onClick = {
                        deleting = true
                        val accountToDelete = user.uid
                        user.delete().addOnCompleteListener { result ->
                            deleting = false
                            if (result.isSuccessful) {
                                // The deleted account must not retain an offline
                                // authentication grace if it is later recreated.
                                validationPrefs.edit { remove("verified_$accountToDelete") }
                                onAccountDeleted(accountToDelete)
                                deleteConfirm = false
                                accountOpen = false
                                signOut()
                            } else {
                                deleteConfirm = false
                                accountOpen = true
                                deleteError = true
                            }
                        }
                    }) { Text(if (french) "Supprimer" else "Delete") }
                },
                dismissButton = {
                    TextButton(enabled = !deleting, onClick = {
                        deleteConfirm = false
                        accountOpen = true
                    }) {
                        Text(if (french) "Annuler" else "Cancel")
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
                    } catch (_: NoCredentialException) {
                        busy = false
                        message = if (french) "Aucun compte Google disponible."
                        else "No Google account is available."
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
    val signOut = rememberFirebaseSignOut(auth)
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
        TextButton(onClick = { signOut() }) {
            Text(if (french) "Se déconnecter" else "Sign out")
        }
        if (message.isNotBlank()) Text(message)
    }
}

/** Clears the Google Credential Manager selection after ending a Firebase session. */
@Composable
private fun rememberFirebaseSignOut(auth: FirebaseAuth): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(auth, context, scope) {
        {
            auth.signOut()
            scope.launch {
                runCatching {
                    CredentialManager.create(context)
                        .clearCredentialState(ClearCredentialStateRequest())
                }
            }
        }
    }
}
