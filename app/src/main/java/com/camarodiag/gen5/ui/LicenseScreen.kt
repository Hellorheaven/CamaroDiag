package com.camarodiag.gen5.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
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
    if (!LicenseRolloutConfig.ENABLED) {
        Text(stringResource(R.string.license_disabled))
        return
    }
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
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
                        stringResource(R.string.license_feature_summary),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.license_account_link),
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
                                val data = if (task.isSuccessful) {
                                    task.result?.data as? Map<*, *>
                                } else null
                                val url = data?.get("url") as? String
                                // No arbitrary remote redirect URL.
                                if (task.isSuccessful &&
                                    url != null && url.startsWith("https://checkout.stripe.com/")
                                ) {
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, url.toUri())
                                        )
                                    } catch (_: ActivityNotFoundException) {
                                        error = R.string.license_no_browser
                                    } catch (_: SecurityException) {
                                        error = R.string.license_no_browser
                                    }
                                } else {
                                    error = R.string.license_checkout_unavailable
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
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        }
        item {
            Text(
                stringResource(R.string.license_activation_help),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
