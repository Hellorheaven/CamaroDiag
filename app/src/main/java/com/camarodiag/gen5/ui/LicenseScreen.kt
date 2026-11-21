package com.camarodiag.gen5.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.camarodiag.gen5.R
import androidx.compose.ui.res.stringResource
import com.camarodiag.gen5.auth.LicenseLevel
import com.camarodiag.gen5.auth.LicenseRolloutConfig
import com.google.firebase.functions.FirebaseFunctions

/**
 * Payment can be initiated only by the authenticated Firebase account via a
 * server-side callable function. This screen never accepts a license key or
 * trusts a browser success redirect as proof of payment.
 */
@Composable
fun LicenseScreen(level: LicenseLevel, onRefresh: () -> Unit) {
    val french = LocalConfiguration.current.locales[0].language == "fr"
    if (!LicenseRolloutConfig.ENABLED) {
        Text(stringResource(R.string.license_disabled))
        return
    }
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                stringResource(R.string.license_title),
                style = MaterialTheme.typography.titleLarge
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val status = when (level) {
                        LicenseLevel.FREE -> stringResource(R.string.license_free)
                        LicenseLevel.PREMIUM -> stringResource(R.string.license_premium)
                        LicenseLevel.OWNER -> stringResource(R.string.license_owner)
                    }
                    Text(status, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (french)
                            "Sans licence : données temps réel, lecture et effacement des codes DTC. " +
                                "Une licence personnelle débloque les fonctions avancées. " +
                                "Les outils Debug restent réservés au propriétaire."
                        else
                            "Without a license: live data and reading/clearing DTCs. " +
                                "A personal license unlocks advanced features. " +
                                "Debug tools remain owner-only.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (french)
                            "La licence est associée à votre compte Firebase et à l’adresse e-mail vérifiée utilisée pour vous connecter. "
                        else
                            "The license is associated with your Firebase account and its verified sign-in email.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (level == LicenseLevel.FREE) {
            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = {
                        busy = true
                        error = null
                        FirebaseFunctions.getInstance("europe-west1")
                            .getHttpsCallable("createLicenseCheckout")
                            .call()
                            .addOnCompleteListener { task ->
                                busy = false
                                val data = task.result?.data as? Map<*, *>
                                val url = data?.get("url") as? String
                                // No arbitrary remote redirect URL.
                                if (task.isSuccessful &&
                                    url != null && url.startsWith("https://checkout.stripe.com/")
                                ) {
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        )
                                    } catch (_: ActivityNotFoundException) {
                                        error = if (french)
                                            "Aucun navigateur disponible."
                                        else "No browser available."
                                    }
                                } else {
                                    error = if (french)
                                        "L’achat est momentanément indisponible. Le service Firebase/Stripe doit être configuré."
                                    else
                                        "Checkout is unavailable. Firebase/Stripe billing must be configured."
                                }
                            }
                    }
                ) {
                    Text(stringResource(R.string.license_buy))
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { error = null; onRefresh() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) {
                Text(stringResource(R.string.license_refresh))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Text(
                if (french)
                    "L’activation est automatique après confirmation du paiement par le serveur. " +
                        "Revenez ici et choisissez « Actualiser » ; un simple retour de la page d’achat ne délivre jamais de licence."
                else
                    "Your license activates after the payment is confirmed by the server. " +
                        "Return here and tap Refresh; a checkout redirect alone never grants a license.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
