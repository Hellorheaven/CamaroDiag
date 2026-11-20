package com.camarodiag.gen5.data.obd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable, offline ECU capture archive. No hardware provenance or restore capability is implied.
 * The caller owns the source bytes and must retain its original immutable capture separately.
 */
object EcuBackupArchive {
    private const val FORMAT = "CamaroDiag ECU offline archive v1"
    private const val MAX_ENTRIES = 128
    private const val MAX_REGION_BYTES = 64 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024

    data class Imported(
        val manifest: EcuBackupManifest.Manifest,
        val captures: List<EcuBackupManifest.Capture>,
        val requiredRegions: Set<EcuBackupCoverage.Region>,
        val verification: EcuBackupIntegrity.Result
    )

    fun export(
        output: OutputStream,
        captures: List<EcuBackupManifest.Capture>,
        requiredRegions: Set<EcuBackupCoverage.Region>,
        controllerLabel: String? = null,
        createdAtUtcMillis: Long = System.currentTimeMillis()
    ) {
        require(captures.isNotEmpty()) { "Cannot export an empty ECU backup" }
        require(captures.size <= MAX_ENTRIES) { "Too many memory segments" }
        require(captures.all { it.firstRead.isNotEmpty() && it.expectedBytes > 0 &&
            it.startAddress >= 0 && it.startAddress <= Long.MAX_VALUE - it.expectedBytes &&
            it.firstRead.size.toLong() <= it.expectedBytes }) { "Invalid capture range" }
        require(captures.all { it.firstRead.size <= MAX_REGION_BYTES &&
            (it.independentRead?.size ?: 0) <= MAX_REGION_BYTES }) { "Segment too large" }
        require(captures.sumOf { it.firstRead.size.toLong() + (it.independentRead?.size?.toLong() ?: 0L) } <= MAX_ARCHIVE_BYTES) { "Archive data exceeds size limit" }
        // Freeze mutable byte arrays before hashing and serialization. Otherwise
        // a caller could change a ByteArray between checksum calculation and ZIP
        // writing, producing an archive with mismatched metadata and payload.
        val snapshots = captures.map { capture ->
            capture.copy(
                firstRead = capture.firstRead.copyOf(),
                independentRead = capture.independentRead?.copyOf()
            )
        }
        // Reject inconsistent captures before writing any archive header or bytes.
        // In particular, two independent reads must agree, or the archive
        // would be rejected by the corresponding import validation.
        val manifest = EcuBackupManifest.inspect(controllerLabel, createdAtUtcMillis, snapshots, requiredRegions)
        val verification = EcuBackupIntegrity.verify(manifest, snapshots, requiredRegions)
        require(verification.consistent) { "Inconsistent backup captures: ${verification.problems}" }
        val properties = Properties()
        properties.setProperty("format", FORMAT)
        properties.setProperty("controller", manifest.controllerLabel.orEmpty())
        properties.setProperty("created", createdAtUtcMillis.toString())
        properties.setProperty("count", snapshots.size.toString())
        properties.setProperty("required", requiredRegions.joinToString(",") { it.name })
        snapshots.forEachIndexed { index, capture ->
            val prefix = "capture.$index."
            properties.setProperty(prefix + "region", capture.region.name)
            properties.setProperty(prefix + "start", capture.startAddress.toString())
            properties.setProperty(prefix + "expected", capture.expectedBytes.toString())
            properties.setProperty(prefix + "origin", capture.origin.name)
            properties.setProperty(prefix + "sha256", manifest.entries[index].sha256)
            properties.setProperty(prefix + "second", (capture.independentRead != null).toString())
        }
        val metadata = ByteArrayOutputStream().also { properties.store(it, FORMAT) }.toByteArray()
        require(metadata.size <= 64 * 1024) { "Manifest too large" }
        ZipOutputStream(output).use { zip ->
            fun write(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            write("manifest.properties", metadata)
            snapshots.forEachIndexed { index, capture ->
                write("capture/$index/first.bin", capture.firstRead)
                capture.independentRead?.let { write("capture/$index/second.bin", it) }
            }
        }
    }

    fun import(input: InputStream): Imported {
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "Unexpected archive directory" }
                require(entry.name == "manifest.properties" ||
                    Regex("capture/[0-9]+/(first|second)\\.bin").matches(entry.name)) {
                    "Unexpected archive entry"
                }
                require(entry.name !in files) { "Duplicate archive entry" }
                require(files.size < 1 + 2 * MAX_ENTRIES) { "Too many archive entries" }
                val data = ByteArrayOutputStream()
                val entryLimit = if (entry.name == "manifest.properties") 64 * 1024 else MAX_REGION_BYTES
                val buffer = ByteArray(8192)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_ARCHIVE_BYTES && data.size() + n <= entryLimit) {
                        "Archive exceeds size limit"
                    }
                    data.write(buffer, 0, n)
                }
                files[entry.name] = data.toByteArray()
                zip.closeEntry()
            }
        }
        val metadata = files.remove("manifest.properties")
            ?: throw IllegalArgumentException("Missing archive manifest")
        // Properties.load normally accepts duplicate keys and silently keeps the last
        // value. Reject ambiguous manifests instead of allowing metadata shadowing.
        val properties = object : Properties() {
            override fun put(key: Any, value: Any): Any? {
                require(!containsKey(key)) { "Duplicate archive manifest property" }
                return super.put(key, value)
            }
        }.apply { load(ByteArrayInputStream(metadata)) }
        require(properties.getProperty("format") == FORMAT) { "Unsupported archive format" }
        val count = properties.getProperty("count")?.toIntOrNull()
        require(count != null && count in 1..MAX_ENTRIES) { "Invalid segment count" }
        val required = properties.getProperty("required", "").takeIf { it.isNotBlank() }
            ?.split(",")?.map { EcuBackupCoverage.Region.valueOf(it) }?.toSet().orEmpty()
        val captures = (0 until count).map { index ->
            val prefix = "capture.$index."
            fun field(key: String) = properties.getProperty(prefix + key)
                ?: throw IllegalArgumentException("Missing metadata $prefix$key")
            val first = files.remove("capture/$index/first.bin")
                ?: throw IllegalArgumentException("Missing primary read $index")
            val second = if (field("second") == "true") {
                files.remove("capture/$index/second.bin")
                    ?: throw IllegalArgumentException("Missing independent read $index")
            } else {
                require(field("second") == "false") { "Invalid second read flag" }
                null
            }
            require(EcuBenchExpertPlan.sha256Hex(first).equals(field("sha256"), ignoreCase = true)) {
                "Primary read checksum mismatch"
            }
            require(second == null || first.contentEquals(second)) {
                "Independent read differs from primary read for segment $index"
            }
            EcuBackupManifest.Capture(
                EcuBackupCoverage.Region.valueOf(field("region")),
                field("start").toLong(), field("expected").toLong(),
                first, second, EcuBackupManifest.Origin.valueOf(field("origin"))
            )
        }
        require(files.isEmpty()) { "Unreferenced archive data" }
        require(properties.stringPropertyNames().all { key ->
            key in setOf("format", "controller", "created", "count", "required") ||
                (0 until count).any { index ->
                    key in setOf("region", "start", "expected", "origin", "sha256", "second")
                        .map { "capture.$index.$it" }
                }
        }) { "Unknown archive manifest property" }
        val created = properties.getProperty("created")?.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid creation timestamp")
        val manifest = EcuBackupManifest.inspect(
            properties.getProperty("controller").orEmpty().ifBlank { null },
            created, captures, required
        )
        val verification = EcuBackupIntegrity.verify(manifest, captures, required)
        require(verification.consistent) { "Archive failed offline verification: ${verification.problems}" }
        return Imported(manifest, captures, required, verification)
    }
}
