package com.camarodiag.gen5.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.SharedPreferences
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * A download is removed ONLY after the installed application's versionCode
 * proves that an update has been installed successfully. Cancellation or
 * rejection by the package installer preserves the private transit download.
 * Verified public copies in CamaroDiag/APK have independent five-file retention.
 */
internal data class PendingCamaroDiagApk(val downloadId: Long, val targetVersionCode: Long) {
    init {
        require(downloadId > 0L && targetVersionCode > 0L)
    }

    fun encode(): String = "$downloadId:$targetVersionCode"

    fun installed(installedVersionCode: Long): Boolean =
        installedVersionCode >= targetVersionCode

    companion object {
        /** Conflicting versions for one ID are ambiguous; never authorize deletion. */
        fun unambiguous(entries: Set<String>): List<PendingCamaroDiagApk> =
            entries.mapNotNull(::decode).groupBy { it.downloadId }.values
                .mapNotNull { records -> records.distinct().singleOrNull() }

        fun decode(value: String): PendingCamaroDiagApk? {
            val parts = value.split(':')
            if (parts.size != 2) return null
            val id = parts[0].toLongOrNull() ?: return null
            val code = parts[1].toLongOrNull() ?: return null
            if (id <= 0 || code <= 0 || value != "$id:$code") return null
            return PendingCamaroDiagApk(id, code)
        }
    }
}

internal object CamaroDiagUpdateCleanup {
    private const val PREFERENCES = "camarodiag_pending_apk_updates"
    private const val PENDING_KEY = "download_ids_and_target_versions"
    private const val CACHE_FOLDER = "camarodiag-install-apks"
    private val legacyApkName = Regex("^CamaroDiag-(debug|release)-[a-zA-Z0-9._-]+\\.apk$")

    /**
     * This safety state must be committed synchronously before opening the
     * package installer. Losing it would leave public Downloads APKs orphaned.
     * This is deliberately not an asynchronous SharedPreferences.apply().
     */
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun persistEntries(prefs: SharedPreferences, entries: Set<String>): Boolean =
        prefs.edit().putStringSet(PENDING_KEY, entries).commit()

    fun verifiedInstallFile(context: Context, downloadId: Long): File {
        require(downloadId > 0L)
        return File(File(context.cacheDir, CACHE_FOLDER), "update-$downloadId.apk")
    }

    private fun removeCachedApk(context: Context, downloadId: Long): Boolean {
        val file = verifiedInstallFile(context, downloadId)
        return file.delete() || !file.exists()
    }

    /** Persist before installation; survive app process death or an APK replacement. */
    @Synchronized
    fun recordDownload(context: Context, pending: PendingCamaroDiagApk): Boolean {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val entries = prefs.getStringSet(PENDING_KEY, emptySet()).orEmpty().toMutableSet()
        val existing = entries.mapNotNull(PendingCamaroDiagApk::decode)
            .filter { it.downloadId == pending.downloadId }
        if (existing.any { it != pending }) return false
        entries.add(pending.encode())
        return persistEntries(prefs, entries)
    }

    /** All owned downloads, newest first. Never scan or modify unrelated files. */
    fun pendingDownloads(context: Context): List<PendingCamaroDiagApk> =
        PendingCamaroDiagApk.unambiguous(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getStringSet(PENDING_KEY, emptySet()).orEmpty()
        ).sortedByDescending { it.downloadId }

    fun targetVersion(context: Context, downloadId: Long): Long? {
        if (downloadId <= 0) return null
        return pendingDownloads(context).firstOrNull { it.downloadId == downloadId }
            ?.targetVersionCode
    }

    /**
     * A failed/malformed download may be removed immediately. NEVER call this
     * for an installer cancellation: users must retain their download.
     */
    @Synchronized
    fun discardInvalidDownload(context: Context, downloadId: Long) {
        if (downloadId <= 0) return
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val entries = prefs.getStringSet(PENDING_KEY, emptySet()).orEmpty().toMutableSet()
        val pending = PendingCamaroDiagApk.unambiguous(entries)
            .firstOrNull { it.downloadId == downloadId } ?: return
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        try {
            manager.remove(downloadId)
            if (!removeCachedApk(context, downloadId)) return
            entries.remove(pending.encode())
            persistEntries(prefs, entries)
        } catch (_: Exception) {
            // Keep ownership record so the next app launch can retry cleanup.
        }
    }

    /**
     * Called on our own PACKAGE_REPLACED event and again on every app launch.
     * DownloadManager.remove(id) deletes the file even when it is in the
     * public /storage/emulated/0/Download folder (scoped-storage compatible).
     */
    suspend fun cleanupAfterSuccessfulInstall(context: Context, packageWasReplaced: Boolean = false) {
        try { CamaroDiagApkArchive.prune(context) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* Retry archive cleanup after access is restored. */ }
        cleanupTransit(context, packageWasReplaced)
    }

    @Synchronized
    private fun cleanupTransit(context: Context, packageWasReplaced: Boolean) {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val original = prefs.getStringSet(PENDING_KEY, emptySet()).orEmpty()
        // The previous updater did not record IDs. After an actual replacement,
        // still clean its legacy app-private APKs, even when no new ID exists.
        if (original.isEmpty() && !packageWasReplaced) return

        val installedCode = try {
            val pkg = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pkg.longVersionCode
        } catch (_: Exception) {
            return
        }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val remaining = original.toMutableSet()
        var successfullyRemoved = false
        for (pending in PendingCamaroDiagApk.unambiguous(original)) {
            val encoded = pending.encode()
            if (!pending.installed(installedCode)) continue
            try {
                // This removes the DownloadManager entry AND the downloaded APK.
                // Do not try deleting arbitrary public files by filename.
                manager.remove(pending.downloadId)
                if (!removeCachedApk(context, pending.downloadId)) continue
                remaining.remove(encoded)
                successfullyRemoved = true
            } catch (_: Exception) {
                // Keep the record, retry safely on the next launch.
            }
        }
        if (remaining != original && !persistEntries(prefs, remaining)) {
            return
        }
        if (successfullyRemoved || packageWasReplaced) {
            // Also tidy APKs downloaded by the OLD updater into its private
            // app-specific Downloads folder, but touch no other user files.
            val oldFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            oldFolder?.listFiles()?.filter { it.isFile && legacyApkName.matches(it.name) }
                ?.forEach { runCatching { it.delete() } }
        }
    }
}

/** System broadcasts this to the upgraded app only after successful replacement. */
class CamaroDiagUpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                CamaroDiagUpdateCleanup.cleanupAfterSuccessfulInstall(
                    context.applicationContext, packageWasReplaced = true
                )
            } finally {
                pending.finish()
            }
        }
    }
}
