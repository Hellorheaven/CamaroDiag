package com.camarodiag.gen5.ui

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

private data class CamaroDiagUpdate(val version: String, val channel: String, val url: String, val versionCode: Long)
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
            val safeUrl = downloadUrl?.takeIf { CamaroDiagReleaseAssetPolicy.accepts(it, wanted) }
                ?: return@withContext CamaroDiagUpdateCheck(null, true)
            CamaroDiagUpdateCheck(
                CamaroDiagUpdate(
                    json.optString("tag_name").removePrefix("v"), channel, safeUrl, versionCode
                ),
                true
            )
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    fun enqueue(context: Context, update: CamaroDiagUpdate): Long {
        // Private DownloadManager transit only; verified APKs are published through SAF in CamaroDiag/APK.
        val safeVersion = update.version.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        require(safeVersion.isNotBlank()) { "Missing update version." }
        val fileName = "CamaroDiag-${update.channel}-${safeVersion}-${System.currentTimeMillis()}.apk"
        val request = DownloadManager.Request(update.url.toUri())
            .setTitle(context.getString(R.string.update_notification_title, update.version))
            .setDescription(CamaroDiagExportStorage.displayPath(CamaroDiagExportStorage.Category.APK))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "camarodiag-apk-staging", fileName)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = manager.enqueue(request)
        // Persist BEFORE starting the installer; only the installer success (new
        // installed versionCode) permits automatic deletion of this download.
        if (!CamaroDiagUpdateCleanup.recordDownload(
                context, PendingCamaroDiagApk(id, update.versionCode)
            )
        ) {
            manager.remove(id)
            error("Unable to persist APK cleanup record.")
        }
        return id
    }

    fun signatureMatches(context: Context, file: File, expectedVersionCode: Long): Boolean {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val downloaded = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return false
        if (downloaded.packageName != context.packageName ||
            downloaded.longVersionCode != expectedVersionCode
        ) return false
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

    /** Return the DownloadManager state without requiring direct public-file access. */
    suspend fun downloadStatus(context: Context, downloadId: Long): Int? =
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                val index = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (!cursor.moveToFirst() || index < 0) null else cursor.getInt(index)
            }
        }

    /**
     * Stage an owned, completed public-Downloads APK in private cache before the
     * installer starts. Never trust a path from an external broadcast.
     */
    suspend fun stageVerifiedDownload(context: Context, downloadId: Long): File? =
        withContext(Dispatchers.IO) {
            val version = CamaroDiagUpdateCleanup.targetVersion(context, downloadId)
                ?: return@withContext null
            if (downloadStatus(context, downloadId) != DownloadManager.STATUS_SUCCESSFUL) {
                return@withContext null
            }
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val file = CamaroDiagUpdateCleanup.verifiedInstallFile(context, downloadId)
            try {
                check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true) {
                    "Unable to create verified update cache."
                }
                manager.openDownloadedFile(downloadId).use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                check(signatureMatches(context, file, version)) {
                    "Downloaded APK certificate, package or version does not match."
                }
                file
            } catch (cancelled: CancellationException) {
                file.delete()
                throw cancelled
            } catch (_: Exception) {
                file.delete()
                null
            }
        }
}

@Composable
fun AboutUpdateCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<CamaroDiagUpdate?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    val archivePath = CamaroDiagExportStorage.displayPath(CamaroDiagExportStorage.Category.APK)
    var downloadId by remember { mutableLongStateOf(-1L) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var installRequested by remember { mutableStateOf(false) }

    val latestDownloadId = rememberUpdatedState(downloadId)
    val setDownloadedApk = rememberUpdatedState<(File?) -> Unit>({ downloadedApk = it })
    val setDownloading = rememberUpdatedState<(Boolean) -> Unit>({ downloading = it })
    val setStatus = rememberUpdatedState<(String?) -> Unit>({ status = it })
    val latestInstallRequested = rememberUpdatedState(installRequested)
    val latestDownloadedApk = rememberUpdatedState(downloadedApk)
    var awaitingFolder by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        awaitingFolder = false
        if (uri != null && CamaroDiagExportStorage.authorize(context, uri)) {
            status = CamaroDiagExportStorage.displayPath(CamaroDiagExportStorage.Category.APK)
        } else {
            status = context.getString(R.string.export_folder_instruction)
        }
    }

    fun requestFolder() {
        if (awaitingFolder) return
        awaitingFolder = true
        status = context.getString(R.string.export_folder_instruction)
        folderPicker.launch(CamaroDiagExportStorage.initialPickerUri())
    }

    suspend fun archiveVerified(file: File, id: Long): Boolean {
        val version = CamaroDiagUpdateCleanup.targetVersion(context, id) ?: return false
        return try {
            CamaroDiagApkArchive.publish(context, file, id, version)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    // The DownloadManager broadcast is not guaranteed while this Compose
    // screen is absent or the process is killed. Recover *owned* downloads
    // from durable IDs without starting a second download or silently
    // relaunching the installer.
    LaunchedEffect(context) {
        val pending = withContext(Dispatchers.IO) {
            CamaroDiagUpdateCleanup.cleanupAfterSuccessfulInstall(context)
            CamaroDiagUpdateCleanup.pendingDownloads(context).firstOrNull()
        }
        if (pending != null) {
            val downloadState = CamaroDiagUpdateChecker.downloadStatus(context, pending.downloadId)
            when (downloadState) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    downloading = true
                    showDownloadDialog = true
                    val staged = CamaroDiagUpdateChecker.stageVerifiedDownload(
                        context, pending.downloadId
                    )
                    if (staged != null) {
                        downloadId = pending.downloadId
                        downloadedApk = staged
                        status = context.getString(if (archiveVerified(staged, pending.downloadId))
                            R.string.update_ready_to_install else R.string.export_folder_failed)
                        downloading = false
                    } else {
                        withContext(Dispatchers.IO) {
                            CamaroDiagUpdateCleanup.discardInvalidDownload(context, pending.downloadId)
                        }
                        downloading = false
                        status = context.getString(R.string.update_download_invalid_signature)
                    }
                }
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED -> {
                    downloadId = pending.downloadId
                    downloading = true
                    showDownloadDialog = true
                    status = context.getString(R.string.update_downloading)
                }
                DownloadManager.STATUS_FAILED -> {
                    withContext(Dispatchers.IO) {
                        CamaroDiagUpdateCleanup.discardInvalidDownload(context, pending.downloadId)
                    }
                    status = context.getString(R.string.update_download_failed)
                }
                else -> Unit
            }
        }
    }

    fun launchInstaller(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            installRequested = true
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            status = context.getString(R.string.update_allow_unknown_sources)
            return
        }
        installRequested = false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        // Android requires an explicit user confirmation for installing an APK.
        // The app opens the system installer automatically after signature validation.
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                latestInstallRequested.value &&
                latestDownloadedApk.value?.isFile == true &&
                context.packageManager.canRequestPackageInstalls()
            ) {
                latestDownloadedApk.value?.let { launchInstaller(it) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (completedId != latestDownloadId.value || completedId < 0L) return

                // DownloadManager owns the public file on Android 10-16. Read
                // using its file descriptor, not raw /storage/... access, which
                // scoped storage can reject. A private verification copy is used
                // for signature inspection and FileProvider installation.
                scope.launch {
                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val targetVersion = CamaroDiagUpdateCleanup.targetVersion(context, completedId)
                    val successful = withContext(Dispatchers.IO) {
                        manager.query(DownloadManager.Query().setFilterById(completedId)).use { cursor ->
                            if (!cursor.moveToFirst()) false
                            else {
                                val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                statusColumn >= 0 &&
                                    cursor.getInt(statusColumn) == DownloadManager.STATUS_SUCCESSFUL
                            }
                        }
                    }
                    if (!successful || targetVersion == null) {
                        withContext(Dispatchers.IO) {
                            CamaroDiagUpdateCleanup.discardInvalidDownload(context, completedId)
                        }
                        setDownloading.value(false)
                        setStatus.value(context.getString(R.string.update_download_failed))
                        return@launch
                    }

                    val verifiedFile = CamaroDiagUpdateChecker.stageVerifiedDownload(
                        context, completedId
                    )
                    if (verifiedFile == null) {
                        withContext(Dispatchers.IO) {
                            CamaroDiagUpdateCleanup.discardInvalidDownload(context, completedId)
                        }
                        setDownloadedApk.value(null)
                        setDownloading.value(false)
                        setStatus.value(context.getString(R.string.update_download_invalid_signature))
                        return@launch
                    }
                    setDownloadedApk.value(verifiedFile)
                    // The download is not ready until the verified public archive has been published.
                    if (!archiveVerified(verifiedFile, completedId)) {
                        setDownloading.value(false)
                        setStatus.value(context.getString(R.string.export_folder_failed))
                        return@launch
                    }
                    setDownloading.value(false)
                    setStatus.value(context.getString(R.string.update_download_complete))
                    try {
                        launchInstaller(verifiedFile)
                    } catch (_: Exception) {
                        // Installation not launched: preserve public APK so the
                        // user may retry. NEVER delete merely on installer open.
                        setStatus.value(context.getString(R.string.update_download_failed))
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            // DownloadManager may broadcast from a privileged process outside
            // system UID. Ignore spoofed events: only our persisted download
            // ID and an APK with matching signature/version can be installed.
            ContextCompat.RECEIVER_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }


    if (downloading && showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text(stringResource(R.string.update_downloading)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${stringResource(R.string.update_downloading)} →")
                    Text(archivePath)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                // Hiding the progress window does not cancel DownloadManager or installation validation.
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text(stringResource(R.string.update_later))
                }
            }
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
            Text(archivePath)
            if (downloading && !showDownloadDialog) {
                TextButton(onClick = { showDownloadDialog = true }) {
                    Text(stringResource(R.string.update_downloading))
                }
            }
            status?.let { Text(it) }
            update?.let {
                Text(stringResource(R.string.update_version_available, it.version))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !checking && !downloading,
                    onClick = {
                        scope.launch {
                            checking = true
                            update = null
                            // Preserve an already verified APK so checking the
                            // latest release does not force another download.
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
                            val available = update
                            if (available != null) {
                                scope.launch {
                                    try {
                                        if (downloading) return@launch
                                        if (CamaroDiagExportStorage.availableTree(context) == null) {
                                            requestFolder()
                                            return@launch
                                        }
                                        downloading = true
                                        showDownloadDialog = true
                                        status = context.getString(R.string.update_downloading)
                                        downloadId = withContext(Dispatchers.IO) {
                                            CamaroDiagUpdateChecker.enqueue(context, available)
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        downloading = false
                                        status = context.getString(R.string.update_download_failed)
                                    }
                                }
                            }
                        }
                    ) {
                        Text(
                            stringResource(
                                if (downloading) R.string.update_downloading
                                else R.string.update_install_update
                            )
                        )
                    }
                }
                val staged = downloadedApk
                if (staged != null && staged.isFile) {
                    Button(
                        enabled = !checking && !downloading,
                        onClick = {
                            scope.launch {
                                if (CamaroDiagExportStorage.availableTree(context) == null) {
                                    requestFolder()
                                    return@launch
                                }
                                if (!archiveVerified(staged, downloadId)) {
                                    status = context.getString(R.string.export_folder_failed)
                                    return@launch
                                }
                                try {
                                    launchInstaller(staged)
                                } catch (_: Exception) {
                                    status = context.getString(R.string.update_download_failed)
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.update_install_update))
                    }
                }
            }
        }
    }
}
