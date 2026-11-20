package com.camarodiag.gen5.ui

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.camarodiag.gen5.BuildConfig
import com.camarodiag.gen5.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private data class CamaroDiagUpdate(val version: String, val channel: String, val url: String)
private data class CamaroDiagUpdateCheck(val update: CamaroDiagUpdate?, val hasNewerVersion: Boolean)

private object CamaroDiagUpdateChecker {
    private const val API = "https://api.github.com/repos/Hellorheaven/CamaroDiag/releases/latest"

    suspend fun check(context: Context): CamaroDiagUpdateCheck = withContext(Dispatchers.IO) {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val installedCode = info.longVersionCode
        val channel = if (BuildConfig.DEBUG) "debug" else "release"
        val wanted = if (channel == "debug") "CamaroDiag-debug.apk" else "CamaroDiag-release.apk"
        val connection = URL(API).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "CamaroDiag-Updater")
        try {
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@withContext CamaroDiagUpdateCheck(null, false)
            val versionCode = Regex(
                "Android versionCode\\s*:\\s*(\\d+)",
                RegexOption.IGNORE_CASE
            ).find(json.optString("body"))?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: throw IllegalStateException("Latest release does not publish an Android versionCode.")
            if (versionCode <= installedCode) return@withContext CamaroDiagUpdateCheck(null, false)

            val assets = json.optJSONArray("assets") ?: return@withContext CamaroDiagUpdateCheck(null, true)
            var downloadUrl: String? = null
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                if (asset.optString("name") == wanted) {
                    downloadUrl = asset.optString("browser_download_url")
                    break
                }
            }
            val safeUrl = downloadUrl?.takeIf { it.startsWith("https://github.com/") }
                ?: return@withContext CamaroDiagUpdateCheck(null, true)
            CamaroDiagUpdateCheck(
                CamaroDiagUpdate(json.optString("tag_name").removePrefix("v"), channel, safeUrl),
                true
            )
        } finally {
            connection.disconnect()
        }
    }

    fun enqueue(context: Context, update: CamaroDiagUpdate): Long {
        val fileName = "CamaroDiag-${update.channel}-${update.version}.apk"
        val request = DownloadManager.Request(update.url.toUri())
            .setTitle(context.getString(R.string.update_notification_title, update.version))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    fun signatureMatches(context: Context, file: File): Boolean {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val downloaded = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return false
        if (downloaded.packageName != context.packageName) return false
        val installed = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, flags)
        }
        val downloadedSigners = downloaded.signingInfo?.apkContentsSigners
            ?.map { it.toCharsString() }?.toSet()
        val installedSigners = installed.signingInfo?.apkContentsSigners
            ?.map { it.toCharsString() }?.toSet()
        return !downloadedSigners.isNullOrEmpty() && downloadedSigners == installedSigners
    }
}

@Composable
fun AboutUpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<CamaroDiagUpdate?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadId by remember { mutableLongStateOf(-1L) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val latestDownloadId = rememberUpdatedState(downloadId)
    val setDownloadedApk = rememberUpdatedState<(File?) -> Unit>({ downloadedApk = it })
    val setDownloading = rememberUpdatedState<(Boolean) -> Unit>({ downloading = it })
    val setStatus = rememberUpdatedState<(String?) -> Unit>({ status = it })

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (completedId != latestDownloadId.value || completedId < 0L) return

                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.query(DownloadManager.Query().setFilterById(completedId)).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        setDownloading.value(false)
                        setStatus.value(context.getString(R.string.update_download_failed))
                        return
                    }
                    val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val uriColumn = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val file = if (uriColumn >= 0) {
                        cursor.getString(uriColumn)?.toUri()?.path?.let(::File)
                    } else {
                        null
                    }
                    if (statusColumn < 0 ||
                        cursor.getInt(statusColumn) != DownloadManager.STATUS_SUCCESSFUL ||
                        file == null || !file.isFile ||
                        !CamaroDiagUpdateChecker.signatureMatches(context, file)
                    ) {
                        setDownloading.value(false)
                        setStatus.value(context.getString(R.string.update_download_invalid_signature))
                        return
                    }
                    setDownloadedApk.value(file)
                    setDownloading.value(false)
                    setStatus.value(context.getString(R.string.update_download_complete))
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    fun installDownloadedApk() {
        val file = downloadedApk ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri()
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            status = context.getString(R.string.update_allow_unknown_sources)
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.about_title))
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
            Text(stringResource(R.string.about_build, BuildConfig.VERSION_CODE))
            status?.let { Text(it) }
            update?.let {
                Text(stringResource(R.string.update_version_channel, it.version, it.channel.uppercase()))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !checking && !downloading,
                    onClick = {
                        scope.launch {
                            checking = true
                            update = null
                            downloadedApk = null
                            status = null
                            try {
                                val result = CamaroDiagUpdateChecker.check(context)
                                update = result.update
                                status = context.getString(
                                    when {
                                        result.update != null -> R.string.about_update_found
                                        result.hasNewerVersion -> R.string.about_channel_update_unavailable
                                        else -> R.string.about_up_to_date
                                    }
                                )
                            } catch (_: Exception) {
                                status = context.getString(R.string.about_check_failed)
                            } finally {
                                checking = false
                            }
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (checking) R.string.about_checking
                            else R.string.about_check_updates
                        )
                    )
                }
                if (update != null && downloadedApk == null) {
                    Button(
                        enabled = !downloading,
                        onClick = {
                            val available = update ?: return@Button
                            try {
                                downloadId = CamaroDiagUpdateChecker.enqueue(context, available)
                                downloading = true
                                status = context.getString(R.string.update_downloading)
                            } catch (_: Exception) {
                                downloading = false
                                status = context.getString(R.string.update_download_failed)
                            }
                        }
                    ) {
                        Text(
                            stringResource(
                                if (downloading) R.string.update_downloading
                                else R.string.update_download
                            )
                        )
                    }
                }
                if (downloadedApk != null) {
                    Button(onClick = ::installDownloadedApk) {
                        Text(stringResource(R.string.update_install))
                    }
                }
            }
        }
    }
}
