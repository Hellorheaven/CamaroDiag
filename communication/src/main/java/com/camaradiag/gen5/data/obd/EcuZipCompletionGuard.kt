package com.camarodiag.gen5.data.obd

import java.io.FilterInputStream
import java.io.InputStream

/**
 * ZipInputStream reads local ZIP entries but does NOT require the central
 * directory or end-of-central-directory record to exist. An interrupted ZIP
 * can therefore look complete despite having no valid archive footer.
 *
 * This bounded reader checks the ZIP central-directory structure after all
 * local entries have been inspected. It keeps only a small circular tail,
 * never buffers the entire (potentially 256 MiB) capture ZIP in RAM.
 */
internal class EcuZipCompletionGuard(
    input: InputStream,
    private val maximumBytes: Long
) : FilterInputStream(input) {
    private val capacity = 512 * 1024
    private val tail = ByteArray(capacity)
    private var total = 0L

    init {
        require(maximumBytes > 0L)
    }

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            check(total < maximumBytes) { "Compressed ECU ZIP exceeds the allowed size." }
            tail[(total % capacity).toInt()] = value.toByte()
            total++
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count <= 0) return count
        check(count.toLong() <= maximumBytes - total) {
            "Compressed ECU ZIP exceeds the allowed size."
        }
        val keep = minOf(count, capacity)
        val source = offset + count - keep
        val target = ((total + count - keep) % capacity).toInt()
        val first = minOf(keep, capacity - target)
        buffer.copyInto(tail, target, source, source + first)
        if (first < keep) buffer.copyInto(tail, 0, source + first, source + keep)
        total += count
        return count
    }

    private fun octet(at: Long): Int {
        require(at >= 0 && at < total && at >= total - capacity) {
            "ECU ZIP central directory exceeds the bounded verification window."
        }
        return tail[(at % capacity).toInt()].toInt() and 0xFF
    }

    private fun u16(at: Long): Long = octet(at).toLong() or (octet(at + 1).toLong() shl 8)
    private fun u32(at: Long): Long = u16(at) or (u16(at + 2) shl 16)

    private fun signature(at: Long, a: Int, b: Int): Boolean =
        octet(at) == 0x50 && octet(at + 1) == 0x4B &&
            octet(at + 2) == a && octet(at + 3) == b

    /**
     * Call only after ZipInputStream.nextEntry has returned null and each
     * decompressed entry has passed checksum/size limits. Drain the original
     * compressed source so its exact final ZIP footer can be inspected.
     */
    data class EntryEvidence(val size: Long, val compressedSize: Long, val crc: Long, val method: Int) {
        companion object {
            fun from(entry: java.util.zip.ZipEntry) =
                EntryEvidence(entry.size, entry.compressedSize, entry.crc, entry.method).also {
                    require(it.size >= 0 && it.compressedSize >= 0 && it.crc in 0..0xFFFF_FFFFL)
                }
        }
    }

    fun requireCompletedArchive(localEntryNames: List<String>,
        localEvidence: Map<String, EntryEvidence> = emptyMap()) {
        require(localEvidence.isEmpty() || localEvidence.keys == localEntryNames.toSet())
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            if (n == 0) {
                if (read() < 0) break
            }
        }
        require(localEntryNames.isNotEmpty()) { "ECU ZIP contains no local files." }
        val minimum = (total - 65557L).coerceAtLeast(0L)
        var eocd = -1L
        var at = total - 22L
        while (at >= minimum) {
            if (signature(at, 0x05, 0x06) &&
                at + 22L + u16(at + 20L) == total) {
                eocd = at
                break
            }
            at--
        }
        require(eocd >= 0L) { "ECU ZIP is missing its complete central directory footer." }
        require(u16(eocd + 4) == 0L && u16(eocd + 6) == 0L) {
            "Multi-disk ECU ZIP archives are not accepted."
        }
        val onDisk = u16(eocd + 8)
        val expected = u16(eocd + 10)
        require(onDisk == expected && expected == localEntryNames.size.toLong() &&
            expected < 0xFFFFL) { "ECU ZIP central entry count differs from its local entries." }
        val directoryLength = u32(eocd + 12)
        val directoryStart = u32(eocd + 16)
        require(directoryLength < 0xFFFF_FFFFL && directoryStart < 0xFFFF_FFFFL &&
            directoryStart + directoryLength == eocd &&
            directoryStart >= total - capacity) {
            "Missing, oversized or inconsistent ECU ZIP central directory."
        }
        var pos = directoryStart
        val centralNames = ArrayList<String>(localEntryNames.size)
        val localOffsets = mutableSetOf<Long>()
        repeat(localEntryNames.size) {
            require(pos + 46L <= eocd && signature(pos, 0x01, 0x02)) {
                "Malformed ECU ZIP central file header."
            }
            val fileNameBytes = u16(pos + 28).toInt()
            val extraBytes = u16(pos + 30).toInt()
            val commentBytes = u16(pos + 32).toInt()
            val next = pos + 46L + fileNameBytes + extraBytes + commentBytes
            require(fileNameBytes in 1..128 && next <= eocd) {
                "Invalid ECU ZIP central filename or metadata length."
            }
            // Archive entry names are restricted to the portable ASCII paths
            // already accepted by EcuBackupArchive.
            val name = buildString {
                repeat(fileNameBytes) { i ->
                    val byte = octet(pos + 46 + i)
                    require(byte in 0x20..0x7E) { "Invalid ECU ZIP filename." }
                    append(byte.toChar())
                }
            }
            localEvidence[name]?.let { observed ->
                val localOffset = u32(pos + 42)
                require(u16(pos + 34) == 0L && localOffset + 30L <= directoryStart &&
                    localOffsets.add(localOffset)) { "Invalid ECU ZIP local entry offset." }
                require(u16(pos + 10).toInt() == observed.method &&
                    u32(pos + 16) == observed.crc && u32(pos + 20) == observed.compressedSize &&
                    u32(pos + 24) == observed.size) {
                    "ECU ZIP central metadata differs from verified local entry."
                }
                require((u16(pos + 8) and 1L) == 0L) { "Encrypted ECU ZIP entries are not accepted." }
            }
            centralNames.add(name)
            pos = next
        }
        // ZIP does not require central records to share the order of local
        // entries. Verify the complete unique name set, not their ordering.
        require(pos == eocd && centralNames.size == localEntryNames.size &&
            centralNames.toSet().size == centralNames.size &&
            centralNames.toSet() == localEntryNames.toSet()) {
            "ECU ZIP central directory does not match its verified local entries."
        }
    }
}
