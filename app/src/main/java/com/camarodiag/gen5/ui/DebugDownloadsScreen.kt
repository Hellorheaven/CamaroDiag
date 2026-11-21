package com.camarodiag.gen5.ui

import android.content.Intent
import android.net.Uri
import com.camarodiag.gen5.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Owner-only entry point. The parent navigates here only after the Firebase
 * ID-token claim "owner_functional_tests" has been verified by CamaroAuthGate.
 * GitHub Actions requires its OWN GitHub account session to download ZIP files.
 */
@Composable
internal fun DebugDownloadsScreen() {
    val french = LocalConfiguration.current.locales[0].language == "fr"
    val errorText = stringResource(R.string.debug_builds_error)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var listing by remember { mutableStateOf(DebugBuildListing()) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        loading = true
        error = null
        try {
            listing = DebugBuildCatalog.fetchLatest()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            listing = DebugBuildListing()
            error = errorText
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun openDownload(artifact: DebugBuildArtifact) {
        // The selected link is constructed from validated numeric GitHub IDs;
        // never accept an arbitrary URL from remote JSON.
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(artifact.downloadPage()))
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.debug_builds_title),
                    style = MaterialTheme.typography.titleLarge)
                Text(
                    if (french)
                        "Télécharge les derniers artefacts Debug de la PR #70. " +
                            "GitHub demande une connexion à un compte GitHub autorisé. " +
                            "Le fichier téléchargé est une archive ZIP à extraire."
                    else
                        "Download the latest PR #70 Debug artifacts. " +
                            "GitHub requires sign-in with a permitted GitHub account. " +
                            "The download is a ZIP archive to extract.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(onClick = { scope.launch { refresh() } }, enabled = !loading) {
                    Text(stringResource(R.string.debug_builds_refresh))
                }
                if (loading) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            DebugArtifactCard(
                title = "Android Debug",
                description = if (french) "Archive contenant l’APK Android Debug."
                else "Archive containing the Android Debug APK.",
                artifact = listing.android,
                french = french,
                enabled = !loading,
                onOpen = ::openDownload
            )
        }
        item {
            DebugArtifactCard(
                title = "Windows J2534 Debug",
                description = if (french) "Archive contenant les installateurs Windows x64 et x86."
                else "Archive containing the Windows x64 and x86 installers.",
                artifact = listing.windows,
                french = french,
                enabled = !loading,
                onOpen = ::openDownload
            )
        }
        item {
            Text(
                if (french)
                    "Les artefacts expirent après 30 jours. Ils peuvent provenir " +
                        "d’une compilation non validée : leur disponibilité ne garantit " +
                        "ni une CI verte ni des tests sur véhicule. Les APK Debug ne sont " +
                        "pas forcément compatibles avec la signature de la version publique."
                else
                    "Artifacts expire after 30 days. Availability does not certify " +
                        "a passing CI run or physical vehicle testing. Debug APKs " +
                        "may have a different package/signature than production builds.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun DebugArtifactCard(
    title: String,
    description: String,
    artifact: DebugBuildArtifact?,
    french: Boolean,
    enabled: Boolean,
    onOpen: (DebugBuildArtifact) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
            if (artifact == null) {
                Text(
                    stringResource(R.string.debug_builds_unavailable),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    "Commit : " + artifact.headSha.take(12) +
                        " · " + (if (french) "Créé" else "Created") + " : " + artifact.createdAt,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    (if (french) "Expiration : " else "Expires: ") + artifact.expiresAt,
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = { onOpen(artifact) }, enabled = enabled) {
                    Text(
                        stringResource(R.string.debug_builds_download)
                    )
                }
            }
        }
    }
}
