package com.camarodiag.gen5.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** User-authorized public internal-storage folder. Never requests broad storage permissions. */
internal object CamaroDiagExportStorage {
    internal class ExportStorageException(message: String, cause: Throwable? = null) :
        java.io.IOException(message, cause)
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

    private fun rootExists(context: Context, tree: Uri): Boolean = runCatching {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DOCUMENT_ID)
        context.contentResolver.query(root,
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            ?.use { it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR } == true
    }.getOrDefault(false)

    /** Called off the main thread: release obsolete grants after deletion or revocation. */
    suspend fun availableTree(context: Context): Uri? = withContext(Dispatchers.IO) {
        val tree = authorizedTree(context) ?: return@withContext null
        if (rootExists(context, tree)) tree
        else {
            runCatching { context.contentResolver.releasePersistableUriPermission(tree, FLAGS) }
            null
        }
    }

    internal fun temporaryFileName(finalName: String): String = ".camarodiag-" + finalName + ".partial"


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

    internal data class Fingerprint(val length: Long, val sha256: String)

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    // Distinguish filesystem errors from communication failures in ECU capture.
    private inline fun <T> storageOperation(action: () -> T): T =
        try { action() } catch (error: IOException) {
            if (error is ExportStorageException) throw error
            throw ExportStorageException("CamaroDiag storage I/O failed.", error)
        } catch (error: SecurityException) {
            throw ExportStorageException("CamaroDiag folder access was denied.", error)
        }

    internal class VerifiedOutput(private val target: OutputStream) : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var length = 0L
        private var closed = false
        override fun write(value: Int) = storageOperation {
            check(!closed)
            target.write(value)
            digest.update(value.toByte())
            length++
            Unit
        }
        override fun write(bytes: ByteArray, offset: Int, count: Int) = storageOperation {
            check(!closed)
            target.write(bytes, offset, count)
            digest.update(bytes, offset, count)
            length += count
        }
        override fun flush() = storageOperation { if (!closed) target.flush() }
        override fun close() {
            if (closed) return
            closed = true
            storageOperation { target.close() }
        }
        fun fingerprint(): Fingerprint {
            check(closed)
            return Fingerprint(length, digest.digest().hex())
        }
    }

    internal suspend fun fingerprint(input: InputStream): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = storageOperation { input.read(buffer) }
            if (count < 0) break
            if (count == 0) {
                val byte = storageOperation { input.read() }
                if (byte < 0) break
                digest.update(byte.toByte())
                length++
                continue
            }
            digest.update(buffer, 0, count)
            length += count
        }
        return Fingerprint(length, digest.digest().hex())
    }

    /**
     * Create a uniquely named temporary document inside the approved folder.
     * Publish the final name only after successful completion; cancel or failure deletes the temp.
     * A sudden process or device crash may leave a .partial document, never a completed filename.
     */
    suspend fun write(context: Context, requested: String, mimeType: String,
        beforePublish: () -> Unit = {}, writeBytes: suspend (OutputStream) -> Unit) =
        withContext(Dispatchers.IO) {
            require(mimeType.isNotBlank())
            val resolver = context.contentResolver
            val tree = availableTree(context) ?: throw ExportStorageException("CamaroDiag folder access is required.")
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DOCUMENT_ID)
            val finalName = uniqueFileName(requested)
            // Use a generic MIME until publication: Android's file provider
            // otherwise may append the target extension to the partial name.
            val temporary = storageOperation {
                DocumentsContract.createDocument(resolver, root,
                    "application/octet-stream", temporaryFileName(finalName))
            } ?: throw ExportStorageException("Could not create export document.")
            var published = false
            try {
                val output = VerifiedOutput(storageOperation {
                    resolver.openOutputStream(temporary, "w")
                } ?: throw ExportStorageException("Could not open export document."))
                output.use { writeBytes(it) }
                val expected = output.fingerprint()
                // Independently reopen the staged document. A successful close
                // or provider acknowledgement is not evidence of complete bytes.
                val actual = storageOperation {
                    resolver.openInputStream(temporary)
                }?.use { fingerprint(it) }
                    ?: throw ExportStorageException("Could not read back export document.")
                if (expected.length == 0L || expected != actual)
                    throw ExportStorageException("Export readback differs from written bytes.")
                currentCoroutineContext().ensureActive()
                beforePublish()
                storageOperation { DocumentsContract.renameDocument(resolver, temporary, finalName) }
                    ?: throw ExportStorageException("Could not publish completed export.")
                published = true
            } finally {
                if (!published) runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
            }
        }
}
