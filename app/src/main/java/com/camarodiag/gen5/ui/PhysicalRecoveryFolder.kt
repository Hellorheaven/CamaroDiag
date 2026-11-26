package com.camarodiag.gen5.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.camarodiag.gen5.data.obd.EcuPhysicalBackupInspection
import com.camarodiag.gen5.data.obd.EcuProgrammingDriverContract
import com.camarodiag.gen5.data.obd.EcuWriteTransaction
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class PhysicalRecoveryIdentity(
    val vin: String, val family: String, val controller: String, val firmware: String
) {
    val valid: Boolean get() = vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) &&
        listOf(family, controller, firmware).all { it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl) }
    fun target() = EcuProgrammingDriverContract.FirmwareTarget(family, controller, firmware)
}

/** Read-only SAF import of a journal and its sibling .blocks directory. */
internal object PhysicalRecoveryFolder {
    private const val MAX_JOURNAL = 8L * 1024 * 1024
    private const val MAX_BLOCK = 64L * 1024 * 1024 + 65536L
    private const val MAX_TOTAL = 256L * 1024 * 1024
    private const val MAX_ENTRIES = 4096
    internal fun safeName(name: String): Boolean =
        name.length in 1..180 && name != "." && name != ".." &&
            name.matches(Regex("[A-Za-z0-9._-]+"))
    internal fun journalName(files: Set<String>, directories: Set<String>): String? =
        files.filter { safeName(it) && "$it.blocks" in directories }.singleOrNull()
    private data class Entry(val name: String, val id: String, val directory: Boolean)

    suspend fun inspect(
        resolver: ContentResolver, tree: Uri, cacheDir: File, identity: PhysicalRecoveryIdentity,
        current: () -> Boolean
    ): EcuPhysicalBackupInspection.Report {
        require(identity.valid)
        val scope = currentCoroutineContext()
        fun active() { scope.ensureActive(); check(current()) }
        fun children(parent: String): List<Entry> {
            active()
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            return requireNotNull(resolver.query(uri, columns, null, null, null)).use { cursor ->
                val rows = mutableListOf<Entry>()
                while (cursor.moveToNext()) {
                    active()
                    check(rows.size < MAX_ENTRIES)
                    rows += Entry(cursor.getString(1), cursor.getString(0),
                        cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                }
                check(rows.map { it.name }.distinct().size == rows.size)
                rows
            }
        }
        val root = children(DocumentsContract.getTreeDocumentId(tree))
        val name = requireNotNull(journalName(root.filterNot { it.directory }.map { it.name }.toSet(),
            root.filter { it.directory }.map { it.name }.toSet()))
        val journal = root.single { it.name == name && !it.directory }
        val blocks = root.single { it.name == "$name.blocks" && it.directory }
        val files = children(blocks.id)
        check(files.isNotEmpty() && files.all { !it.directory && safeName(it.name) && it.name.endsWith(".ecu-block") })
        val staging = Files.createTempDirectory(cacheDir.toPath(), "ecu-inspection-").toFile()
        var total = 0L
        try {
            fun copy(entry: Entry, destination: File, max: Long) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
                var count = 0L
                requireNotNull(resolver.openInputStream(uri)).use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            active()
                            val n = input.read(buffer)
                            if (n < 0) break
                            check(n > 0)
                            count += n
                            total += n
                            check(count <= max && total <= MAX_TOTAL)
                            output.write(buffer, 0, n)
                        }
                    }
                }
                check(count > 0)
            }
            val journalFile = File(staging, name)
            val blockDir = File(staging, "$name.blocks")
            check(blockDir.mkdir())
            copy(journal, journalFile, MAX_JOURNAL)
            files.forEach { copy(it, File(blockDir, it.name), MAX_BLOCK) }
            active()
            return EcuWriteTransaction.inspectRecoveryEvidence(journalFile, identity.target(), identity.vin, ::active)
                .also { active() }
        } finally {
            staging.deleteRecursively()
        }
    }
}
