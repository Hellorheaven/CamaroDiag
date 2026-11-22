package com.camarodiag.gen5.ui

import android.annotation.SuppressLint
import android.content.Context
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Only APKs whose completed publication was recorded by the updater are managed. */
internal object CamaroDiagApkRetention {
    private val pattern = Regex("CamaroDiag-Update-([1-9][0-9]*)-([1-9][0-9]*)\\.apk")
    private val sha256 = Regex("[a-f0-9]{64}")
    data class Entry(val downloadId: Long, val versionCode: Long)
    data class OwnedApk(val name: String, val fingerprint: CamaroDiagExportStorage.Fingerprint) {
        fun encode() = "$name:${fingerprint.length}:${fingerprint.sha256}"
    }

    fun decode(encoded: String): OwnedApk? {
        val parts = encoded.split(':', limit = 4)
        if (parts.size != 3 || parse(parts[0]) == null || !sha256.matches(parts[2])) return null
        val length = parts[1].toLongOrNull() ?: return null
        if (length !in 1..CamaroDiagReleaseAssetPolicy.MAX_APK_BYTES || length.toString() != parts[1]) {
            return null
        }
        return OwnedApk(parts[0], CamaroDiagExportStorage.Fingerprint(length, parts[2]))
    }

    fun unambiguous(encoded: Set<String>): Map<String, OwnedApk> =
        encoded.mapNotNull(::decode).groupBy { it.name }.mapNotNull { (name, records) ->
            records.singleOrNull()?.let { name to it }
        }.toMap()
    fun name(downloadId: Long, versionCode: Long): String {
        require(downloadId > 0 && versionCode > 0)
        return "CamaroDiag-Update-$downloadId-$versionCode.apk"
    }
    fun parse(name: String): Entry? {
        val match = pattern.matchEntire(name) ?: return null
        val id = match.groupValues[1].toLongOrNull() ?: return null
        val version = match.groupValues[2].toLongOrNull() ?: return null
        return Entry(id, version).takeIf { id > 0 && version > 0 }
    }

    // Prefer the newest installed-version candidates, then the newest download.
    // This selects candidates. The bytes must still match before deletion.
    fun excess(owned: Set<String>, present: Set<String>): List<String> =
        owned.intersect(present).mapNotNull { name -> parse(name)?.let { name to it } }
            .sortedWith(compareByDescending<Pair<String, Entry>> { it.second.versionCode }
                .thenByDescending { it.second.downloadId })
            .drop(5).map { it.first }
}

internal object CamaroDiagApkArchive {
    private const val PREFS = "camarodiag_verified_apk_archive"
    private const val KEY = "published_apks"
    private val mutex = Mutex()

    private fun owned(context: Context): Map<String, CamaroDiagApkRetention.OwnedApk> =
        CamaroDiagApkRetention.unambiguous(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY, emptySet()).orEmpty()
        )

    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun persist(context: Context, records: Collection<CamaroDiagApkRetention.OwnedApk>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY, records.map { it.encode() }.toSet()).commit()

    private fun files(context: Context, tree: android.net.Uri): Map<String, android.net.Uri> {
        val category = CamaroDiagExportStorage.Category.APK
        CamaroDiagExportStorage.categoryDirectory(context, tree, category)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, CamaroDiagExportStorage.categoryDocumentId(category)
        )
        val result = linkedMapOf<String, android.net.Uri>()
        context.contentResolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (CamaroDiagApkRetention.parse(name) == null) continue
                val id = cursor.getString(0)
                check(cursor.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR &&
                    CamaroDiagExportStorage.isCategoryFile(tree.authority, id, category, name) &&
                    !result.containsKey(name)) { "Unexpected APK archive document." }
                result[name] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            }
        } ?: error("Unable to list APK archive.")
        return result
    }

    /** Called only after package/signature/version validation of the private file. */
    suspend fun publish(context: Context, file: File, downloadId: Long, versionCode: Long) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val tree = CamaroDiagExportStorage.availableTree(context)
                    ?: error("CamaroDiag folder access is required.")
                val name = CamaroDiagApkRetention.name(downloadId, versionCode)
                val expected = file.inputStream().use {
                    CamaroDiagExportStorage.fingerprint(it, CamaroDiagReleaseAssetPolicy.MAX_APK_BYTES)
                }
                check(expected.length > 0) { "APK archive is empty." }
                val existing = files(context, tree)[name]
                if (existing != null) {
                    // Idempotent recovery after process death; never overwrite
                    // a conflicting public file or trust it for installation.
                    val actual = context.contentResolver.openInputStream(existing)?.use {
                        CamaroDiagExportStorage.fingerprint(it, expected.length)
                    }
                    check(expected == actual) { "APK archive conflict." }
                    // An existing identical file is usable for recovery, but its
                    // presence alone is insufficient to take ownership for pruning.
                } else {
                    CamaroDiagExportStorage.writeArchivedApk(context, name, file)
                    val record = CamaroDiagApkRetention.OwnedApk(name, expected)
                    check(persist(context, (owned(context) + (name to record)).values)) {
                        "Unable to record archived APK."
                    }
                }
                pruneLocked(context, tree)
            }
        }

    private suspend fun pruneLocked(context: Context, tree: android.net.Uri) {
        val present = files(context, tree)
        val recorded = owned(context)
        val remaining = recorded.filterKeys { it in present }.toMutableMap()
        for (name in CamaroDiagApkRetention.excess(recorded.keys, present.keys)) {
            val expected = recorded.getValue(name).fingerprint
            val uri = present.getValue(name)
            val actual = try {
                context.contentResolver.openInputStream(uri)?.use {
                    CamaroDiagExportStorage.fingerprint(it, expected.length)
                }
            } catch (_: IOException) {
                continue
            } catch (_: SecurityException) {
                continue
            }
            if (actual == null) continue
            if (actual != expected) {
                remaining.remove(name)
                continue
            }
            currentCoroutineContext().ensureActive()
            if (DocumentsContract.deleteDocument(context.contentResolver, uri)) remaining.remove(name)
        }
        if (remaining != recorded) {
            check(persist(context, remaining.values)) { "Unable to persist APK archive cleanup." }
        }
    }

    suspend fun prune(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tree = CamaroDiagExportStorage.availableTree(context) ?: return@withLock
            if (owned(context).isNotEmpty()) pruneLocked(context, tree)
        }
    }
}
