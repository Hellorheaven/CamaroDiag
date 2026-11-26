package com.camarodiag.gen5.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.camarodiag.gen5.data.obd.EcuPhysicalBackupInspection
import com.camarodiag.gen5.data.obd.EcuProgrammingDriverContract
import com.camarodiag.gen5.data.obd.EcuWriteTransaction
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
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
    internal data class Entry(val name: String, val id: String, val directory: Boolean)
    internal fun uniqueEntries(rows: List<Entry>): Boolean =
        rows.all { it.id.isNotBlank() } && rows.map { it.name }.distinct().size == rows.size &&
            rows.map { it.id }.distinct().size == rows.size
    internal fun sameInventory(before: List<Entry>, after: List<Entry>): Boolean =
        uniqueEntries(before) && uniqueEntries(after) && before.toSet() == after.toSet()

    internal fun uniqueTree(rootId: String, root: List<Entry>, blocks: List<Entry>): Boolean =
        rootId.isNotBlank() && uniqueEntries(root) && uniqueEntries(blocks) &&
            (listOf(rootId) + root.map { it.id } + blocks.map { it.id }).let {
                it.size == it.distinct().size
            }

    internal fun verifyStagedFile(file: File, expectedSize: Long, expectedHash: ByteArray,
        active: () -> Unit) {
        active()
        check(expectedSize > 0 && expectedHash.size == 32 &&
            Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                active()
                val n = input.read(buffer)
                if (n < 0) break
                check(n > 0)
                count += n
                check(count <= expectedSize)
                digest.update(buffer, 0, n)
            }
        }
        check(count == expectedSize && MessageDigest.isEqual(expectedHash, digest.digest())) {
            "Recovery staging bytes changed."
        }
        active()
    }

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
                check(uniqueEntries(rows)) { "Ambiguous recovery document inventory." }
                rows
            }
        }
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = children(rootId)
        val name = requireNotNull(journalName(root.filterNot { it.directory }.map { it.name }.toSet(),
            root.filter { it.directory }.map { it.name }.toSet()))
        val journal = root.single { it.name == name && !it.directory }
        val blocks = root.single { it.name == "$name.blocks" && it.directory }
        val files = children(blocks.id)
        check(uniqueTree(rootId, root, files)) { "Recovery document alias across directories." }
        check(files.isNotEmpty() && files.all { !it.directory && safeName(it.name) && it.name.endsWith(".ecu-block") })
        val staging = Files.createTempDirectory(cacheDir.toPath(), "ecu-inspection-").toFile()
        var total = 0L
        try {
            val digests = mutableMapOf<Entry, Pair<Long, ByteArray>>()
            fun copy(entry: Entry, destination: File, max: Long) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
                var count = 0L
                val digest = MessageDigest.getInstance("SHA-256")
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
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    }
                }
                check(count > 0)
                digests[entry] = count to digest.digest()
            }
            // SAF has no portable stable inode guarantee. Re-read the same
            // source document IDs with a separate bounded pass and compare
            // their bytes before accepting the copied evidence.
            fun verifySource(entry: Entry, max: Long) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
                val digest = MessageDigest.getInstance("SHA-256")
                val (originalSize, originalHash) = digests.getValue(entry)
                var count = 0L
                requireNotNull(resolver.openInputStream(uri)).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        active()
                        val n = input.read(buffer)
                        if (n < 0) break
                        check(n > 0)
                        count += n
                        check(count <= max && count <= originalSize)
                        digest.update(buffer, 0, n)
                    }
                }
                check(count == originalSize && MessageDigest.isEqual(originalHash, digest.digest())) {
                    "Recovery source changed during import."
                }
            }
            val journalFile = File(staging, name)
            val blockDir = File(staging, "$name.blocks")
            check(blockDir.mkdir())
            copy(journal, journalFile, MAX_JOURNAL)
            files.forEach { copy(it, File(blockDir, it.name), MAX_BLOCK) }
            active()
            check(sameInventory(root, children(rootId)) && sameInventory(files, children(blocks.id))) {
                "Recovery source inventory changed during import."
            }
            files.forEach { verifySource(it, MAX_BLOCK) }
            verifySource(journal, MAX_JOURNAL)
            check(sameInventory(root, children(rootId)) && sameInventory(files, children(blocks.id))) {
                "Recovery source inventory changed during verification."
            }
            fun verifyStaging() {
                active()
                check(staging.list()?.toSet() == setOf(name, "$name.blocks"))
                check(Files.isDirectory(blockDir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                check(blockDir.list()?.toSet() == files.map { it.name }.toSet())
                for ((entry, evidence) in digests) {
                    val destination = if (entry == journal) journalFile else File(blockDir, entry.name)
                    verifyStagedFile(destination, evidence.first, evidence.second, ::active)
                }
            }
            verifyStaging()
            val result = EcuWriteTransaction.inspectRecoveryEvidence(journalFile, identity.target(), identity.vin, ::active)
            verifyStaging()
            active()
            return result
        } finally {
            staging.deleteRecursively()
        }
    }
}
