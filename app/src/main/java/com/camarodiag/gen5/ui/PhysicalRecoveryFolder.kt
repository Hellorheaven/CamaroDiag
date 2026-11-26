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

    internal fun verifyBytes(input: java.io.InputStream, expectedSize: Long,
        expectedHash: ByteArray, active: () -> Unit) {
        active()
        check(expectedSize > 0 && expectedHash.size == 32)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
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
        check(count == expectedSize && MessageDigest.isEqual(expectedHash, digest.digest())) {
            "Recovery evidence bytes changed."
        }
        active()
    }

    internal data class StagedIdentity(val key:Any?,val directory:Boolean)
    internal fun stagedIdentity(file:File,directory:Boolean):StagedIdentity {
        val attrs=Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(!attrs.isSymbolicLink && if(directory) attrs.isDirectory else attrs.isRegularFile) {
            "Recovery staging entry has unexpected file type."
        }
        return StagedIdentity(attrs.fileKey(),directory)
    }

    /** Some providers do not expose file keys. In that case retain the
     * independent type, inventory and byte checks; never claim inode proof. */
    internal fun requireStagedIdentity(file:File,expected:StagedIdentity) {
        val actual=stagedIdentity(file,expected.directory)
        check(expected.key==null || actual.key==expected.key) {
            "Recovery staging entry was replaced."
        }
    }

    internal fun uniqueStagedIdentities(entries:Collection<StagedIdentity>):Boolean {
        val known=entries.mapNotNull { it.key }
        return known.size==known.distinct().size
    }

    internal fun verifyStagedFile(file: File, expectedSize: Long, expectedHash: ByteArray,
        active: () -> Unit) {
        active()
        check(Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        // The open itself must refuse symlinks, not only the preceding check.
        Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
            verifyBytes(input, expectedSize, expectedHash, active)
        }
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
        val stagingIdentity=stagedIdentity(staging,true)
        var total = 0L
        try {
            val digests = mutableMapOf<Entry, Pair<Long, ByteArray>>()
            val stagedIdentities=mutableMapOf<Entry,StagedIdentity>()
            val journalFile = File(staging, name)
            val blockDir = File(staging, "$name.blocks")
            requireStagedIdentity(staging,stagingIdentity)
            check(blockDir.mkdir())
            val blockIdentity=stagedIdentity(blockDir,true)
            fun verifyDirectories() {
                active()
                requireStagedIdentity(staging,stagingIdentity)
                requireStagedIdentity(blockDir,blockIdentity)
            }
            fun copy(entry: Entry, destination: File, max: Long) {
                verifyDirectories()
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
                var count = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                requireNotNull(resolver.openInputStream(uri)).use { input ->
                    Files.newOutputStream(destination.toPath(),
                        java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS).use { output ->
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
                verifyDirectories()
                stagedIdentities[entry]=stagedIdentity(destination,false)
                check(uniqueStagedIdentities(listOf(stagingIdentity,blockIdentity)+stagedIdentities.values)) {
                    "Aliased recovery staging entries."
                }
                digests[entry] = count to digest.digest()
            }
            // SAF has no portable stable inode guarantee. Re-read the same
            // source document IDs with a separate bounded pass and compare
            // their bytes before accepting the copied evidence.
            fun verifySource(entry: Entry, max: Long) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
                val (originalSize, originalHash) = digests.getValue(entry)
                check(originalSize in 1..max)
                requireNotNull(resolver.openInputStream(uri)).use { input ->
                    verifyBytes(input, originalSize, originalHash, ::active)
                }
            }
            copy(journal, journalFile, MAX_JOURNAL)
            files.forEach { copy(it, File(blockDir, it.name), MAX_BLOCK) }
            fun verifySourceTree() {
                active()
                check(sameInventory(root, children(rootId)) && sameInventory(files, children(blocks.id))) {
                    "Recovery source inventory changed before verification."
                }
                files.forEach { verifySource(it, MAX_BLOCK) }
                verifySource(journal, MAX_JOURNAL)
                check(sameInventory(root, children(rootId)) && sameInventory(files, children(blocks.id))) {
                    "Recovery source inventory changed during verification."
                }
            }
            verifySourceTree()
            fun verifyStaging() {
                active()
                verifyDirectories()
                check(staging.list()?.toSet() == setOf(name, "$name.blocks"))
                check(uniqueStagedIdentities(listOf(stagingIdentity,blockIdentity)+stagedIdentities.values))
                check(blockDir.list()?.toSet() == files.map { it.name }.toSet())
                for ((entry, evidence) in digests) {
                    val destination = if (entry == journal) journalFile else File(blockDir, entry.name)
                    val expected=stagedIdentities.getValue(entry)
                    requireStagedIdentity(destination,expected)
                    verifyStagedFile(destination, evidence.first, evidence.second, ::active)
                    requireStagedIdentity(destination,expected)
                    verifyDirectories()
                }
            }
            verifyStaging()
            val result = EcuWriteTransaction.inspectRecoveryEvidence(journalFile, identity.target(), identity.vin, ::active)
            // The provider may have changed the original documents while the
            // staged evidence was being inspected. Refuse a stale report.
            // These bounded independent passes detect observed changes; SAF
            // still does not provide an atomic or authenticated snapshot.
            verifySourceTree()
            verifyStaging()
            active()
            return result
        } finally {
            // If the root was replaced, do not delete the substituted tree.
            // A provider without stable file keys still relies on private storage.
            if (!runCatching { requireStagedIdentity(staging,stagingIdentity) }.isSuccess) {
                throw IllegalStateException("Recovery staging root was replaced; refusing cleanup.")
            }
            // Do not traverse an unexpected symbolic-link directory when
            // cleaning private staged recovery evidence.
            Files.walkFileTree(staging.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: java.nio.file.Path,
                    exc: java.io.IOException?): java.nio.file.FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        }
    }
}
