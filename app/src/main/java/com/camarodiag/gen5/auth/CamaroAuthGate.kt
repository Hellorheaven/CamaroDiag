package com.camarodiag.gen5.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Denies access to diagnostic screens until a Firebase session exists.
 * The authenticated user's session is persisted by the Firebase SDK.
 */
@Composable
fun CamaroAuthGate(
    onAccountChanged: (String?) -> Unit,
    onAccountDeleted: (String) -> Unit,
    content: @Composable (onOpenAccount: () -> Unit, accessLevel: LicenseLevel, onRefreshLicense: () -> Unit) -> Unit
) {
    val auth = remember { FirebaseAuth.getInstance() }
    val context = LocalContext.current
    val validationPrefs = remember(context) {
        context.getSharedPreferences("camarodiag_session_validation", Context.MODE_PRIVATE)
    }
    val signOut = rememberFirebaseSignOut(auth)
    var currentUser by remember { mutableStateOf(auth.currentUser, referentialEqualityPolicy()) }
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
    var verificationRequired by remember { mutableStateOf(false) }
    var accessLevel by remember { mutableStateOf(LicenseLevel.FREE) }
    var accountAdminAllowed by remember { mutableStateOf(false) }
    var licenseChecked by remember { mutableStateOf<Boolean?>(null) }
    var licenseSource by remember { mutableStateOf<String?>(null) }
    var retryValidation by remember { mutableIntStateOf(0) }
    var validationGeneration by remember { mutableIntStateOf(0) }
    val latestOnAccountChanged by rememberUpdatedState(onAccountChanged)
    // Check Firebase only on app startup, account changes or manual retry.
    LaunchedEffect(user, retryValidation) {
        // A late callback from the previous FirebaseUser instance or retry may
        // never authenticate a different validation attempt with the same UID.
        val attempt = ++validationGeneration
        val identityChanged = readyAccountUid != user?.uid ||
            (validatedUser != null && validatedUser !== user)
        if (identityChanged) {
            accountAdminAllowed = false
            licenseChecked = null
            licenseSource = null
            accessLevel = LicenseLevel.FREE
            readyAccountUid = null
            validatedAccountUid = null
            validatedUser = null
            verificationRequired = false
            latestOnAccountChanged(null)
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
                    val verifiedClaims = task.result?.claims.orEmpty()
                    val providerIds = user.providerData.map { it.providerId }
                    val verifiedEmail = EmailVerificationPolicy.isVerified(verifiedClaims)
                    verificationRequired = EmailVerificationPolicy.requiresVerification(providerIds, verifiedClaims)
                    if (verificationRequired) {
                        // Keep the signed-in Firebase session for verification only. Do not expose
                        // diagnostics, previous account state, owner access or offline grace.
                        accountAdminAllowed = false
                        licenseChecked = null
                        licenseSource = null
                        accessLevel = LicenseLevel.FREE
                        validationPrefs.edit { remove("verified_$targetUid"); remove("verified_email_$targetUid") }
                        validatedAccountUid = targetUid
                        validatedUser = user
                        latestOnAccountChanged(null)
                        return@addOnCompleteListener
                    }
                    accountAdminAllowed = AccountAdminAccessPolicy.isAllowed(verifiedClaims)
                    // Owner claim is signed by Firebase Admin. Paying users
                    // require a SECOND proof from Firestore's SERVER, never cache.
                    accessLevel = if (!LicenseRolloutConfig.ENABLED) {
                        // Licensing is staged but disconnected: normal functions
                        // remain available without license lookup or payment.
                        if (OwnerFunctionalTestPolicy.isAllowed(providerIds, verifiedClaims))
                            LicenseLevel.OWNER else LicenseLevel.PREMIUM
                    } else LicenseAccessPolicy.resolve(
                        uid = targetUid,
                        email = user.email,
                        emailVerified = verifiedEmail,
                        providerIds = providerIds,
                        signedClaims = verifiedClaims,
                        license = null
                    )
                    if (accessLevel == LicenseLevel.OWNER) {
                        licenseChecked = true
                        licenseSource = "owner"
                    } else if (LicenseRolloutConfig.ENABLED) {
                        // One server entitlement read per startup, account change,
                        // or explicit refresh. Never re-query just to show My Account.
                        licenseChecked = null
                        licenseSource = null
                        FirebaseFunctions.getInstance("europe-west1")
                            .getHttpsCallable("getMyLicenseStatus").call()
                            .addOnCompleteListener { licenseTask ->
                                if (auth.currentUser?.uid != targetUid ||
                                    validationGeneration != attempt
                                ) return@addOnCompleteListener
                                val data = if (licenseTask.isSuccessful)
                                    licenseTask.result?.data as? Map<*, *> else null
                                val confirmed = data?.get("verified") == true &&
                                    data["licensed"] is Boolean
                                licenseChecked = confirmed
                                licenseSource = if (confirmed)
                                    data?.get("source") as? String else null
                                accessLevel = if (confirmed && data?.get("licensed") == true)
                                    LicenseLevel.PREMIUM else LicenseLevel.FREE
                            }
                    }
                    validationPrefs.edit {
                        putLong("verified_$targetUid", System.currentTimeMillis())
                        if (verifiedEmail) putString("verified_email_$targetUid", user.email.orEmpty())
                        else remove("verified_email_$targetUid")
                    }
                    validatedAccountUid = targetUid
                    validatedUser = user
                    latestOnAccountChanged(targetUid)
                } else {
                    // Failed validation: never retain a previously granted Premium entitlement.
                    accountAdminAllowed = false
                    licenseChecked = false
                    licenseSource = null
                    accessLevel = LicenseLevel.FREE
                    val lastValidated = validationPrefs.getLong("verified_$targetUid", 0L)
                    val previousEmail = validationPrefs.getString("verified_email_$targetUid", null)
                    val offlineGrace = task.exception is FirebaseNetworkException &&
                        !verificationRequired && EmailVerificationPolicy.allowsOfflineEmail(
                            user.providerData.map { it.providerId }, user.email, user.isEmailVerified, previousEmail
                        ) && OfflineSessionPolicy.allowsOfflineAccess(
                            lastValidated, System.currentTimeMillis()
                        )
                    if (offlineGrace) {
                        // Ordinary diagnostics remain available; private tools
                        // stay locked until a fresh signed Google token is checked.
                        accessLevel = if (LicenseRolloutConfig.ENABLED)
                            LicenseLevel.FREE else LicenseLevel.PREMIUM
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
    } else if (verificationRequired && readyAccountUid == user.uid &&
        validatedAccountUid == user.uid && validatedUser === user
    ) {
        key(user.uid) { EmailVerificationScreen(auth, onRefresh = { retryValidation++ }) }
    } else if (readyAccountUid != user.uid || validatedAccountUid != user.uid ||
        validatedUser !== user
    ) {
        val french = LocalConfiguration.current.locales[0].language == "fr"
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
        var administrationOpen by remember(user.uid) { mutableStateOf(false) }
        var deleteConfirm by remember(user.uid) { mutableStateOf(false) }
        var deleting by remember(user.uid) { mutableStateOf(false) }
        var deleteError by remember(user.uid) { mutableStateOf(false) }
        var linking by remember(user.uid) { mutableStateOf(false) }
        var changePasswordOpen by remember(user.uid) { mutableStateOf(false) }
        var linkMessage by remember(user.uid) { mutableStateOf("") }
        var accountRevision by remember(user.uid) { mutableIntStateOf(0) }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val french = LocalConfiguration.current.locales[0].language == "fr"
        // Account action belongs to the app's TopAppBar; an overlay here would
        // overlap system bars and the title on narrow Android 15/16 devices.
        content({ accountOpen = true }, accessLevel, { retryValidation++ })
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
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(user.email.orEmpty())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (french) "Connexion : $provider" else "Signed in with: $provider",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        AccountLicenseStatus(french, accessLevel, licenseChecked, licenseSource)
                        if (accountAdminAllowed) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                accountOpen = false
                                administrationOpen = true
                            }) {
                                Text(if (french) "Administration des comptes" else "Account administration")
                            }
                        }
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
                                                    if (result.isSuccessful) { accountRevision++; retryValidation++ }
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
                        if (linkMessage.isNotEmpty()) Text(linkMessage)
                        if (PasswordAccountPolicy.isEmailPasswordAccount(user.providerData.map { it.providerId })) {
                            TextButton(enabled = !deleting && !linking, onClick = {
                                accountOpen = false
                                changePasswordOpen = true
                            }) {
                                Text(if (french) "Changer mon mot de passe" else "Change my password")
                            }
                        }
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
        if (administrationOpen && accountAdminAllowed) {
            AccountAdministrationDialog(french) { administrationOpen = false }
        }
        if (changePasswordOpen &&
            PasswordAccountPolicy.isEmailPasswordAccount(user.providerData.map { it.providerId })
        ) {
            // Password entry exists only in the e-mail account flow. Google-backed
            // accounts (including mixed providers) never expose password controls.
            var oldPassword by remember(user.uid) { mutableStateOf("") }
            var newPassword by remember(user.uid) { mutableStateOf("") }
            var confirmPassword by remember(user.uid) { mutableStateOf("") }
            var changing by remember(user.uid) { mutableStateOf(false) }
            var changed by remember(user.uid) { mutableStateOf(false) }
            var error by remember(user.uid) { mutableStateOf(false) }
            val eligible = PasswordAccountPolicy.canChange(oldPassword, newPassword, confirmPassword)
            AlertDialog(
                onDismissRequest = { if (!changing) changePasswordOpen = false },
                title = { Text(if (french) "Changer mon mot de passe" else "Change my password") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (changed) {
                            Text(if (french) "Mot de passe modifié. Votre compte est conservé."
                                else "Password updated. Your account has been preserved.")
                        } else {
                            OutlinedTextField(
                                value = oldPassword, onValueChange = { oldPassword = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true, enabled = !changing,
                                label = { Text(if (french) "Mot de passe actuel" else "Current password") },
                                visualTransformation = PasswordVisualTransformation()
                            )
                            OutlinedTextField(
                                value = newPassword, onValueChange = { newPassword = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true, enabled = !changing,
                                label = { Text(if (french) "Nouveau mot de passe" else "New password") },
                                visualTransformation = PasswordVisualTransformation()
                            )
                            OutlinedTextField(
                                value = confirmPassword, onValueChange = { confirmPassword = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true, enabled = !changing,
                                label = { Text(if (french) "Confirmer le nouveau mot de passe"
                                    else "Confirm new password") },
                                visualTransformation = PasswordVisualTransformation()
                            )
                            if (newPassword.isNotEmpty() && newPassword.length < 8) {
                                Text(if (french) "Au moins 8 caractères requis."
                                    else "At least 8 characters are required.")
                            }
                            if (confirmPassword.isNotEmpty() && newPassword != confirmPassword) {
                                Text(if (french) "Les deux saisies sont différentes."
                                    else "The new passwords do not match.",
                                    color = MaterialTheme.colorScheme.error)
                            }
                            if (error) {
                                Text(if (french) "Modification refusée. Vérifiez l'ancien mot de passe et la connexion."
                                    else "Change failed. Check the current password and connection.",
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                },
                confirmButton = {
                    if (!changed) {
                        TextButton(enabled = !changing && eligible, onClick = {
                            val pendingUser = auth.currentUser
                            val address = user.email
                            if (pendingUser !== user || address.isNullOrBlank()) {
                                error = true
                                return@TextButton
                            }
                            changing = true
                            error = false
                            val current = oldPassword
                            val replacement = newPassword
                            // Firebase requires recent proof of the password before changing it.
                            // No credential, password or security token is persisted locally.
                            pendingUser.reauthenticate(
                                EmailAuthProvider.getCredential(address, current)
                            ).addOnCompleteListener { reauthenticated ->
                                if (auth.currentUser !== pendingUser) {
                                    changing = false
                                    return@addOnCompleteListener
                                }
                                if (!reauthenticated.isSuccessful) {
                                    changing = false
                                    error = true
                                    return@addOnCompleteListener
                                }
                                pendingUser.updatePassword(replacement).addOnCompleteListener { updated ->
                                    changing = false
                                    if (auth.currentUser !== pendingUser) return@addOnCompleteListener
                                    if (updated.isSuccessful) {
                                        oldPassword = ""
                                        newPassword = ""
                                        confirmPassword = ""
                                        changed = true
                                        retryValidation++
                                    } else error = true
                                }
                            }
                        }) { Text(if (french) "Enregistrer" else "Save") }
                    }
                },
                dismissButton = {
                    TextButton(enabled = !changing, onClick = {
                        changePasswordOpen = false
                    }) { Text(if (french) "Fermer" else "Close") }
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
                                validationPrefs.edit { remove("verified_$accountToDelete"); remove("verified_email_$accountToDelete") }
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
    val french = LocalConfiguration.current.locales[0].language == "fr"
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    // Google is the default sign-in route. Password fields require an explicit
    // choice of the independent e-mail/password route.
    var emailMode by remember { mutableStateOf(false) }

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
        TextButton(enabled = !busy, onClick = {
            emailMode = !emailMode
            email = ""
            password = ""
            register = false
            message = ""
        }) {
            Text(if (emailMode) {
                if (french) "Utiliser Google plutôt" else "Use Google instead"
            } else {
                if (french) "Connexion avec e-mail et mot de passe" else "Sign in with email and password"
            })
        }
        if (emailMode) {
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
                                        auth.setLanguageCode(if (french) "fr" else "en")
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
                auth.setLanguageCode(if (french) "fr" else "en")
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
        }
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
        ) {
            Image(painter = painterResource(R.drawable.ic_google_sign_in), contentDescription = null,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(if (french) "Continuer avec Google" else "Continue with Google")
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmailVerificationScreen(auth: FirebaseAuth, onRefresh: () -> Unit) {
    val signOut = rememberFirebaseSignOut(auth)
    val french = LocalConfiguration.current.locales[0].language == "fr"
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestRefresh by rememberUpdatedState(onRefresh)
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    fun refreshVerification() {
        val pendingUser = auth.currentUser ?: return
        if (busy) return
        busy = true
        message = ""
        pendingUser.reload().addOnCompleteListener { task ->
            if (auth.currentUser !== pendingUser) {
                busy = false
                return@addOnCompleteListener
            }
            if (!task.isSuccessful) {
                busy = false
                message = if (french) "Actualisation impossible. Vérifiez votre connexion puis réessayez."
                    else "Unable to refresh. Check your connection and try again."
                return@addOnCompleteListener
            }
            // reload() updates the mutable user object; get a fresh signed token
            // too, so linked providers and the email_verified claim agree.
            pendingUser.getIdToken(true).addOnCompleteListener { refreshed ->
                busy = false
                if (auth.currentUser !== pendingUser) return@addOnCompleteListener
                val confirmed = refreshed.isSuccessful &&
                    EmailVerificationPolicy.isVerified(refreshed.result?.claims.orEmpty())
                message = when {
                    confirmed -> if (french) "Adresse vérifiée." else "Email verified."
                    !refreshed.isSuccessful -> if (french) "Impossible de confirmer la session. Réessayez."
                        else "Unable to confirm the session. Try again."
                    else -> if (french) "Vérification non confirmée. Ouvrez le lien reçu puis réessayez."
                        else "Verification not confirmed. Open the email link and try again."
                }
                if (confirmed) latestRefresh()
            }
        }
    }

    DisposableEffect(lifecycleOwner, auth) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshVerification()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(if (french) "Vérifiez votre adresse e-mail" else "Verify your email address",
            style = MaterialTheme.typography.headlineSmall)
        Text(auth.currentUser?.email.orEmpty())
        Spacer(Modifier.height(16.dp))
        Text(if (french) "Ouvrez le lien dans votre e-mail, puis revenez ici. La vérification sera actualisée automatiquement."
            else "Open the link in your email, then return here. Verification will refresh automatically.")
        Spacer(Modifier.height(16.dp))
        Button(enabled = !busy && !sending, onClick = { refreshVerification() }) {
            Text(if (french) "J'ai vérifié mon adresse" else "I've verified my email")
        }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp))
        TextButton(enabled = !busy && !sending, onClick = {
            val pendingUser = auth.currentUser ?: return@TextButton
            sending = true
            auth.setLanguageCode(if (french) "fr" else "en")
            pendingUser.sendEmailVerification().addOnCompleteListener { task ->
                sending = false
                if (auth.currentUser !== pendingUser) return@addOnCompleteListener
                message = if (task.isSuccessful) {
                    if (french) "E-mail de vérification envoyé." else "Verification email sent."
                } else {
                    if (french) "Envoi impossible. Réessayez plus tard." else "Unable to send email. Try again later."
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
