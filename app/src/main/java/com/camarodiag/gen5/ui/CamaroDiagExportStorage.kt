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
    internal enum class Category(val folder: String) {
        ECU_BACKUPS("Backups/ECU"), DIAGNOSTIC_SESSIONS("Backups/Diagnostics"),
        REPORTS("Reports"), CONSOLE("Logs/Console"), RECOVERY("Logs/Recovery"),
        TELEMETRY("Telemetry"), CALIBRATIONS("Calibrations"), TESTS("Tests"),
        EXPORTS("Exports")
    }

    internal fun categoryDocumentId(category: Category): String = "$DOCUMENT_ID/${category.folder}"
    internal fun displayPath(category: Category): String = "$DISPLAY_PATH${category.folder}/"
    internal fun fileDisplayPath(category: Category, finalName: String): String {
        require(finalName.matches(Regex("[A-Za-z0-9._-]+")) && !finalName.startsWith('.'))
        return displayPath(category) + finalName
    }
    internal fun isCategoryFile(authority: String?, documentId: String?, category: Category, name: String): Boolean =
        authority == AUTHORITY && name.matches(Regex("[A-Za-z0-9._-]+")) && name !in setOf(".", "..") &&
            documentId == "${categoryDocumentId(category)}/$name"

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
        private var failed = false
        private var completedFingerprint: Fingerprint? = null

        // A provider can accept part of a write and then throw. Once any I/O
        // fails, neither a subsequent successful close nor a caught exception
        // in an export callback can make this stream a verified capture.
        private inline fun <T> guarded(action: () -> T): T {
            check(!closed && !failed) { "Export output is closed or failed." }
            return try {
                storageOperation(action)
            } catch (error: Exception) {
                failed = true
                throw error
            }
        }

        override fun write(value: Int) = guarded {
            target.write(value)
            digest.update(value.toByte())
            length++
            Unit
        }
        override fun write(bytes: ByteArray, offset: Int, count: Int) = guarded {
            require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
            target.write(bytes, offset, count)
            digest.update(bytes, offset, count)
            length += count
        }
        override fun flush() {
            if (!closed) guarded { target.flush() }
        }
        override fun close() {
            if (closed) return
            closed = true
            storageOperation { target.close() }
            if (!failed) completedFingerprint = Fingerprint(length, digest.digest().hex())
        }
        fun fingerprint(): Fingerprint = checkNotNull(completedFingerprint) {
            "Output must write and close successfully before its fingerprint is available."
        }
    }

    internal suspend fun fingerprint(input: InputStream, maximumBytes: Long = Long.MAX_VALUE): Fingerprint {
        require(maximumBytes >= 0)
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
                if (length >= maximumBytes) throw ExportStorageException("Export readback exceeds expected length.")
                digest.update(byte.toByte())
                length++
                continue
            }
            if (count.toLong() > maximumBytes - length)
                throw ExportStorageException("Export readback exceeds expected length.")
            digest.update(buffer, 0, count)
            length += count
        }
        return Fingerprint(length, digest.digest().hex())
    }

    // The primary-storage provider uses root-relative document IDs. Only fixed
    // category segments are used; never accept a path from an exported filename.
    @Synchronized
    private fun categoryDirectory(context: Context, tree: Uri, category: Category): Uri {
        val resolver = context.contentResolver
        var parentId = DOCUMENT_ID
        var parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        for (segment in category.folder.split('/')) {
            val expectedId = "$parentId/$segment"
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val existing = storageOperation {
                resolver.query(children, arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ), null, null, null)?.use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1).equals(segment, ignoreCase = true)) {
                            if (found || cursor.getString(0) != expectedId ||
                                cursor.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR)
                                throw ExportStorageException("Invalid or conflicting export folder: $segment")
                            found = true
                        }
                    }
                    found
                } ?: throw ExportStorageException("Could not list export folders.")
            }
            parent = if (existing) DocumentsContract.buildDocumentUriUsingTree(tree, expectedId)
                else storageOperation {
                    DocumentsContract.createDocument(resolver, parent,
                        DocumentsContract.Document.MIME_TYPE_DIR, segment)
                } ?: throw ExportStorageException("Could not create export folder: $segment")
            if (parent.authority != AUTHORITY || DocumentsContract.getDocumentId(parent) != expectedId)
                throw ExportStorageException("Unexpected export folder identity.")
            parentId = expectedId
        }
        return parent
    }

    /**
     * Create a uniquely named temporary document inside the approved category folder.
     * Publish the final name only after successful completion; cancel or failure deletes the temp.
     * A sudden process or device crash may leave a .partial document, never a completed filename.
     */
    suspend fun write(context: Context, requested: String, mimeType: String,
        category: Category, beforePublish: () -> Unit = {}, writeBytes: suspend (OutputStream) -> Unit): String =
        withContext(Dispatchers.IO) {
            require(mimeType.isNotBlank())
            val resolver = context.contentResolver
            val tree = availableTree(context) ?: throw ExportStorageException("CamaroDiag folder access is required.")
            val root = categoryDirectory(context, tree, category)
            val finalName = uniqueFileName(requested)
            // Use a generic MIME until publication: Android's file provider
            // otherwise may append the target extension to the partial name.
            val temporary = storageOperation {
                DocumentsContract.createDocument(resolver, root,
                    "application/octet-stream", temporaryFileName(finalName))
            } ?: throw ExportStorageException("Could not create export document.")
            if (!isCategoryFile(temporary.authority, DocumentsContract.getDocumentId(temporary),
                    category, temporaryFileName(finalName))) {
                // Do not write to or delete an unexpected provider document.
                throw ExportStorageException("Unexpected temporary export identity.")
            }
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
                }?.use { fingerprint(it, expected.length) }
                    ?: throw ExportStorageException("Could not read back export document.")
                if (expected.length == 0L || expected != actual)
                    throw ExportStorageException("Export readback differs from written bytes.")
                currentCoroutineContext().ensureActive()
                beforePublish()
                val finalDocument = storageOperation { DocumentsContract.renameDocument(resolver, temporary, finalName) }
                    ?: throw ExportStorageException("Could not publish completed export.")
                published = true
                if (!isCategoryFile(finalDocument.authority, DocumentsContract.getDocumentId(finalDocument),
                        category, finalName))
                    throw ExportStorageException("Unexpected published export identity; inspect the folder.")
                fileDisplayPath(category, finalName)
            } finally {
                if (!published) runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
            }
        }
}
