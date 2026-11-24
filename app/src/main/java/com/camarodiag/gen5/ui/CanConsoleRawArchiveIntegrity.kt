package com.camarodiag.gen5.ui

import java.io.File
import java.security.MessageDigest

/**
 * Independent identity of the complete private raw CAN archive at export time.
 *
 * The UI retains only its recent preview, but the downloaded archive must be
 * the exact file captured by the operator. Hash on Dispatchers.IO; compare
 * again immediately before the export is published. A file modified between
 * these stages is never silently presented as a complete physical capture.
 */
internal object CanConsoleRawArchiveIntegrity {
    data class Snapshot(val file: File, val sizeBytes: Long, val sha256: String)

    fun capture(file: File): Snapshot {
        require(file.isFile) { "CAN raw capture archive no longer exists" }
        val size = file.length()
        require(size >= 0L)
        val hash = sha256(file)
        check(file.isFile && file.length() == size) {
            "CAN raw capture archive changed while the export was prepared"
        }
        return Snapshot(file, size, hash)
    }

    fun isCurrent(snapshot: Snapshot): Boolean =
        snapshot.file.isFile &&
            snapshot.file.length() == snapshot.sizeBytes &&
            try {
                sha256(snapshot.file) == snapshot.sha256
            } catch (_: Exception) {
                false
            }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (n > 0) digest.update(chunk, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
