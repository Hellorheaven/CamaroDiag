package com.camarodiag.gen5.ui
import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.camarodiag.gen5.BuildConfig
import com.camarodiag.gen5.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private data class CamaroDiagUpdate(val version: String, val channel: String, val url: String)
private object CamaroDiagUpdateChecker {
    private const val API = "https://api.github.com/repos/Hellorheaven/CamaroDiag/releases/latest"
    suspend fun check(context: Context): CamaroDiagUpdate? = withContext(Dispatchers.IO) {
        val info = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)) else {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val installedCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        val channel = if (BuildConfig.DEBUG) "debug" else "release"
        val wanted = if (channel == "debug") "CamaroDiag-debug.apk" else "CamaroDiag-release.apk"
        val con = URL(API).openConnection() as HttpURLConnection
        con.connectTimeout = 15000; con.readTimeout = 15000
        con.setRequestProperty("Accept", "application/vnd.github+json")
        con.setRequestProperty("User-Agent", "CamaroDiag-Updater")
        try {
            val json = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@withContext null
            val code = Regex("Android versionCode\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(json.optString("body"))?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return@withContext null
            if (code <= installedCode) return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var link: String? = null
            for (i in 0 until assets.length()) if (assets.optJSONObject(i)?.optString("name") == wanted) link = assets.optJSONObject(i)?.optString("browser_download_url")
            val safe = link?.takeIf { it.startsWith("https://github.com/") } ?: return@withContext null
            CamaroDiagUpdate(json.optString("tag_name").removePrefix("v"), channel, safe)
        } finally { con.disconnect() }
    }
    fun enqueue(context: Context, u: CamaroDiagUpdate): Long {
        val file = "CamaroDiag-${u.channel}-${u.version}.apk"
        val req = DownloadManager.Request(Uri.parse(u.url)).setTitle("CamaroDiag ${u.version}").setMimeType("application/vnd.android.package-archive").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, file)
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    }
    fun signatureMatches(context: Context, file: File): Boolean {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val a = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
        if (a.packageName != context.packageName) return false
        val b = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong())) else {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, flags)
        }
        return a.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()?.let { it.isNotEmpty() && it == b.signingInfo?.apkContentsSigners?.map { s -> s.toCharsString() }?.toSet() } == true
    }
}
@Composable
fun UpdateManagerDialog() {
    val ctx = LocalContext.current
    var update by remember { mutableStateOf<CamaroDiagUpdate?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var id by remember { mutableStateOf(-1L) }
    var apk by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val latestId = rememberUpdatedState(id)
    val setApk = rememberUpdatedState<(File?) -> Unit>({ apk = it })
    val setDownloading = rememberUpdatedState<(Boolean) -> Unit>({ downloading = it })
    val setMessage = rememberUpdatedState<(String?) -> Unit>({ message = it })
    LaunchedEffect(Unit) { try { update = CamaroDiagUpdateChecker.check(ctx) } catch (_: Exception) { /* No network: continue normally. */ } }
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val done = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (done != latestId.value || done < 0) return
                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.query(DownloadManager.Query().setFilterById(done)).use { cur ->
                    if (!cur.moveToFirst()) { setDownloading.value(false); setMessage.value(ctx.getString(R.string.update_download_failed)); return }
                    val si = cur.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val ui = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val file = if (ui >= 0) cur.getString(ui)?.let { Uri.parse(it).path?.let(::File) } else null
                    if (si < 0 || cur.getInt(si) != DownloadManager.STATUS_SUCCESSFUL || file == null || !file.isFile || !CamaroDiagUpdateChecker.signatureMatches(ctx, file)) {
                        setDownloading.value(false); setMessage.value(ctx.getString(R.string.update_download_invalid_signature)); return
                    }
                    setApk.value(file); setDownloading.value(false); setMessage.value(ctx.getString(R.string.update_download_complete))
                }
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    if (update != null || message != null) AlertDialog(
        onDismissRequest = { update = null; message = null },
        title = { Text(if (apk != null) stringResource(R.string.update_ready_to_install) else if (downloading) stringResource(R.string.update_downloading) else stringResource(R.string.update_available)) },
        text = { androidx.compose.foundation.layout.Column {
            update?.let { Text("Version ${it.version} • ${it.channel.uppercase()}"); Text(stringResource(R.string.update_channel_only, it.channel)) }
            message?.let { Text(it) }
        } },
        confirmButton = { when {
            apk != null -> Button(onClick = {
                if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    message = ctx.getString(R.string.update_allow_unknown_sources)
                } else {
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk!!)
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    update = null; message = null
                }
            }) { Text(stringResource(R.string.update_install)) }
            downloading -> Button(onClick = {}, enabled = false) { Text("…") }
            update != null -> Button(onClick = { downloading = true; id = CamaroDiagUpdateChecker.enqueue(ctx, update!!) }) { Text(stringResource(R.string.update_download)) }
        } },
        dismissButton = { TextButton(onClick = { update = null; message = null }) { Text(stringResource(R.string.update_later)) } }
    )
}
