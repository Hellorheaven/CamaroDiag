package com.camarodiag.gen5.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.UUID

/** User-authorized public internal-storage folder. Never requests broad storage permissions. */
internal object CamaroDiagExportStorage {
    const val AUTHORITY = "com.android.externalstorage.documents"
    const val DOCUMENT_ID = "primary:CamaroDiag"
    const val DISPLAY_PATH = "/storage/emulated/0/CamaroDiag/"
    private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    internal fun isExpectedDocument(authority: String?, documentId: String?): Boolean =
        authority == AUTHORITY && documentId == DOCUMENT_ID

    private fun isExpectedTree(uri: Uri): Boolean = runCatching {
        uri.scheme == "content" && DocumentsContract.isTreeUri(uri) &&
            isExpectedDocument(uri.authority, DocumentsContract.getTreeDocumentId(uri))
    }.getOrDefault(false)

    fun initialPickerUri(): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, "primary:")

    fun authorizedTree(context: Context): Uri? = context.contentResolver.persistedUriPermissions
        .firstOrNull { it.isReadPermission && it.isWritePermission && isExpectedTree(it.uri) }?.uri

    fun authorize(context: Context, uri: Uri): Boolean {
        if (!isExpectedTree(uri)) return false
        return runCatching {
            context.contentResolver.takePersistableUriPermission(uri, FLAGS)
            authorizedTree(context) != null
        }.getOrDefault(false)
    }

    // Preserve the extension and never overwrite an existing export, even on repeated actions.
    internal fun uniqueFileName(requested: String, suffix: String = UUID.randomUUID().toString()): String {
        require(suffix.matches(Regex("[a-zA-Z0-9-]+")))
        val cleaned = requested.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('.')
        require(cleaned.isNotBlank())
        val dot = cleaned.lastIndexOf('.')
        val extension = if (dot > 0 && cleaned.length - dot <= 12) cleaned.substring(dot) else ""
        val stem = (if (extension.isEmpty()) cleaned else cleaned.substring(0, dot)).take(100)
        return stem + "_" + suffix + extension
    }

    /**
     * Create a uniquely named temporary document inside the approved folder.
     * Publish the final name only after successful completion; cancel or failure deletes the temp.
     * A sudden process or device crash may leave a .partial document, never a completed filename.
     */
    suspend fun write(context: Context, requested: String, mimeType: String,
        beforePublish: () -> Unit = {}, writeBytes: suspend (OutputStream) -> Unit) =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val tree = authorizedTree(context) ?: error("CamaroDiag folder access is required.")
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DOCUMENT_ID)
            val available = resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
                ?.use { it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR } == true
            if (!available) {
                runCatching { resolver.releasePersistableUriPermission(tree, FLAGS) }
                error("CamaroDiag folder no longer exists.")
            }
            val finalName = uniqueFileName(requested)
            val temporary = DocumentsContract.createDocument(resolver, root, mimeType, ".${finalName}.partial")
                ?: error("Could not create export document.")
            var published = false
            try {
                val output = resolver.openOutputStream(temporary, "w") ?: error("Could not open export document.")
                output.use { writeBytes(it) }
                currentCoroutineContext().ensureActive()
                beforePublish()
                checkNotNull(DocumentsContract.renameDocument(resolver, temporary, finalName)) {
                    "Could not publish completed export."
                }
                published = true
            } finally {
                if (!published) runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
            }
        }
}
