package com.camarodiag.gen5.data.obd

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Read-only DoIP memory scope and archive compatible with the Windows bridge. */
object DoIpMemoryBackup {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_BLOCKS_PER_PASS = 8192
    data class Scope(val vin: String, val gateway: Int, val tester: Int, val ecu: Int,
        val identityDid: Int, val identityHex: String, val start: Long, val length: Int,
        val blockSize: Int, val region: String) {
        fun validate() {
            require(vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")))
            require(listOf(gateway, tester, ecu, identityDid).all { it in 0..65535 } &&
                tester != gateway && tester != ecu && identityDid != 0xF190)
            require(identityHex.length in 2..512 && identityHex.length % 2 == 0 &&
                identityHex.matches(Regex("[0-9A-F]+")) && !identityHex.all { it == '0' } &&
                !identityHex.all { it == 'F' })
            require(length in 1..MAX_BYTES && blockSize in 1..256 &&
                start in 0..0xFFFFFFFFL && start + length <= 0x1_0000_0000L)
            require(region in setOf("CALIBRATION", "PROGRAM_FLASH", "DATA_FLASH", "EXTERNAL_EEPROM", "OTHER"))
        }
        /** Offline verification accepts the common archive size; actual capture
         * additionally requires a bounded number of documented blocks. */
        fun canCapture(): Boolean = runCatching {
            validate()
            (length.toLong() + blockSize - 1) / blockSize <= MAX_BLOCKS_PER_PASS
        }.getOrDefault(false)
        fun validateCapture() {
            require(canCapture()) { "DoIP memory profile exceeds capture limits." }
        }
        internal fun manifestScope() = JSONObject().put("Vin", vin).put("Gateway", gateway)
            .put("Tester", tester).put("Ecu", ecu).put("IdentityDid", identityDid)
            .put("IdentityHex", identityHex).put("Start", start).put("Length", length)
            .put("BlockSize", blockSize).put("Region", region)
    }

    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) {
                val byte = input.read()
                if (byte < 0) break
                require(out.size() < maximum) { "Input exceeds DoIP size limit." }
                out.write(byte)
                continue
            }
            require(out.size() + n <= maximum) { "Input exceeds DoIP size limit." }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun decodeUtf8(data: ByteArray): String =
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString()

    fun load(input: InputStream): Scope {
        val raw = decodeUtf8(readBounded(input, 16384))
        val fields = linkedMapOf<String, String>()
        val numeric = setOf("lengthBytes", "blockSize")
        val keys = setOf("vin", "gateway", "tester", "ecu", "identityDid",
            "identityHex", "startAddress", "lengthBytes", "blockSize", "region")
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(key in keys && key !in fields)
                require(reader.peek() == if (key in numeric) JsonToken.NUMBER else JsonToken.STRING)
                fields[key] = reader.nextString()
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && fields.keys == keys)
        }
        fun field(key: String) = fields.getValue(key)
        fun logical(key: String): Int {
            val value = field(key)
            require(value.matches(Regex("[0-9A-Fa-f]{4}")))
            return value.toInt(16)
        }
        val address = field("startAddress")
        require(address.matches(Regex("[0-9A-Fa-f]{1,8}")))
        for (key in numeric) require(field(key).matches(Regex("[0-9]+")))
        return Scope(field("vin"), logical("gateway"), logical("tester"), logical("ecu"),
            logical("identityDid"), field("identityHex").uppercase(Locale.ROOT), address.toLong(16),
            field("lengthBytes").toInt(), field("blockSize").toInt(), field("region")).also { it.validate() }
    }

    class Capture internal constructor(val scope: Scope, internal val first: ByteArray,
        internal val second: ByteArray, val sha256: String, internal val manifest: ByteArray)

    private fun sha(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 255) }

    internal fun createCapture(scope: Scope, first: ByteArray, second: ByteArray): Capture {
        scope.validateCapture()
        require(first !== second && first.size == scope.length && second.size == scope.length &&
            MessageDigest.isEqual(first, second)) { "Independent memory reads differ." }
        val hash = sha(first)
        val manifest = JSONObject().put("format", "CamaroDiag-DoIP-memory-v1")
            .put("transport", "DoIP").put("coverage", "SCOPED_REGION_ONLY")
            .put("origin", "REPORTED_HARDWARE_READ").put("capturedAtUtc", Instant.now().toString())
            .put("scope", scope.manifestScope()).put("sha256", hash).toString().toByteArray(Charsets.UTF_8)
        return Capture(scope, first.copyOf(), second.copyOf(), hash, manifest)
    }

    fun write(output: OutputStream, capture: Capture) {
        capture.scope.validateCapture()
        require(capture.first.size == capture.scope.length && capture.second.size == capture.scope.length &&
            MessageDigest.isEqual(capture.first, capture.second) && sha(capture.first) == capture.sha256)
        ZipOutputStream(output).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            add("manifest.json", capture.manifest)
            add("original.bin", capture.first)
            add("verification.bin", capture.second)
        }
    }

    data class Verification(val scope: Scope, val sha256: String, val capturedAtUtc: Instant)

    /** Offline verification of either Windows or Android archives, against an
     * independently selected profile. Archive integrity is not hardware provenance.
     */
    fun verify(input: InputStream, scope: Scope): Verification {
        scope.validate()
        val archive = readArchiveContents(input, scope.length)
        val manifest = decodeUtf8(archive.manifest)
        val fields = setOf("format", "transport", "coverage", "origin", "capturedAtUtc", "scope", "sha256")
        val values = linkedMapOf<String, String>()
        val expectedScope = scope.manifestScope()
        var verifiedScope = false
        JsonReader(StringReader(manifest)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            val remaining = fields.toMutableSet()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(remaining.remove(key)) { "Unexpected or duplicate manifest field." }
                if (key == "scope") {
                    reader.beginObject()
                    val keys = expectedScope.keys().asSequence().toMutableSet()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        require(keys.remove(name))
                        val expected = expectedScope.get(name)
                        val type = if (expected is Number) JsonToken.NUMBER else JsonToken.STRING
                        require(reader.peek() == type && reader.nextString() == expected.toString()) {
                            "DoIP archive scope differs from the selected profile."
                        }
                    }
                    reader.endObject()
                    require(keys.isEmpty())
                    verifiedScope = true
                } else {
                    require(reader.peek() == JsonToken.STRING)
                    values[key] = reader.nextString()
                }
            }
            reader.endObject()
            require(remaining.isEmpty() && reader.peek() == JsonToken.END_DOCUMENT)
        }
        require(verifiedScope && values["format"] == "CamaroDiag-DoIP-memory-v1" &&
            values["transport"] == "DoIP" && values["coverage"] == "SCOPED_REGION_ONLY" &&
            values["origin"] == "REPORTED_HARDWARE_READ")
        require(values["sha256"] == archive.sha256)
        return Verification(scope, archive.sha256, Instant.parse(values.getValue("capturedAtUtc")))
    }

    internal data class ArchiveContents(val manifest: ByteArray, val sha256: String)

    /** Keep at most one region in memory. Compare the other ZIP entry byte for
     * byte in bounded chunks, regardless of entry order; never extract paths. */
    internal fun readArchiveContents(input: InputStream, length: Int): ArchiveContents {
        require(length in 1..MAX_BYTES)
        val names = setOf("manifest.json", "original.bin", "verification.bin")
        val seen = mutableSetOf<String>()
        var manifest: ByteArray? = null
        var reference: ByteArray? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in names && seen.add(entry.name)) {
                    "Unexpected or duplicate DoIP archive entry."
                }
                if (entry.name == "manifest.json") {
                    manifest = readBounded(zip, 16384)
                } else {
                    val first = reference
                    if (first == null) {
                        val data = ByteArray(length)
                        java.io.DataInputStream(zip).readFully(data)
                        require(zip.read() == -1) { "Oversized DoIP archive region." }
                        reference = data
                    } else {
                        val buffer = ByteArray(8192)
                        val source = java.io.DataInputStream(zip)
                        var offset = 0
                        while (offset < length) {
                            val count = minOf(buffer.size, length - offset)
                            source.readFully(buffer, 0, count)
                            for (index in 0 until count)
                                require(buffer[index] == first[offset + index]) {
                                    "Independent memory reads differ."
                                }
                            offset += count
                        }
                        require(zip.read() == -1) { "Oversized DoIP archive region." }
                    }
                }
                zip.closeEntry()
            }
        }
        require(seen == names)
        return ArchiveContents(requireNotNull(manifest), sha(requireNotNull(reference)))
    }

    /** Check the real staged archive against the independent capture, not its own assertions. */
    fun verifyStaged(input: InputStream, capture: Capture) {
        val expected = linkedMapOf("manifest.json" to capture.manifest,
            "original.bin" to capture.first, "verification.bin" to capture.second)
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val data = expected.remove(entry.name) ?: error("Unexpected or duplicate DoIP archive entry.")
                require(!entry.isDirectory && MessageDigest.isEqual(readBounded(zip, data.size), data))
                zip.closeEntry()
            }
        }
        require(expected.isEmpty()) { "Incomplete DoIP backup." }
    }
}
