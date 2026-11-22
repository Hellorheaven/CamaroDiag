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
    private const val FORMAT_WITH_VIN = "CamaroDiag ECU offline archive v2"
    private const val FORMAT_WITH_CHANNEL = "CamaroDiag ECU offline archive v3"
    private const val FORMAT = "CamaroDiag ECU offline archive v1"
    private const val MAX_ENTRIES = 128
    private const val MAX_REGION_BYTES = 64 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024

    /**
     * A Windows J2534 scoped capture may contain two separate 16 MiB reads.
     * Include room for the manifest; an exact 32 MiB Android import cap
     * rejected Windows' largest valid two-pass archive.
     */
    internal const val MAX_J2534_IMPORT_UNCOMPRESSED_BYTES =
        2L * 16 * 1024 * 1024 + 64L * 1024

    /**
     * The import limit includes decompressed manifest bytes, not just payloads.
     * Keep the same ceiling at export to guarantee that our own ZIP is readable.
     */
    internal fun fitsUncompressedLimit(payloadBytes: Long, manifestBytes: Long): Boolean =
        payloadBytes >= 0L && manifestBytes >= 0L &&
            payloadBytes <= MAX_ARCHIVE_BYTES &&
            manifestBytes <= MAX_ARCHIVE_BYTES - payloadBytes

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
        createdAtUtcMillis: Long = System.currentTimeMillis(),
        vehicleVin: String? = null,
        channel: EcuBackupManifest.Channel? = null,
        firmwareEvidence: EcuBackupManifest.FirmwareEvidence? = null
    ) {
        require(createdAtUtcMillis > 0L) { "Invalid ECU archive creation timestamp" }
        require(captures.isNotEmpty()) { "Cannot export an empty ECU backup" }
        require(captures.size <= MAX_ENTRIES) { "Too many memory segments" }
        require(captures.all { it.firstRead.isNotEmpty() && it.expectedBytes > 0 &&
            it.startAddress >= 0 && it.startAddress <= Long.MAX_VALUE - it.expectedBytes &&
            it.firstRead.size.toLong() <= it.expectedBytes }) { "Invalid capture range" }
        require(captures.all { it.firstRead.size <= MAX_REGION_BYTES &&
            (it.independentRead?.size ?: 0) <= MAX_REGION_BYTES }) { "Segment too large" }
        require(captures.sumOf { it.firstRead.size.toLong() + (it.independentRead?.size?.toLong() ?: 0L) } <= MAX_ARCHIVE_BYTES) { "Archive data exceeds size limit" }
        require(captures.none { it.firstRead === it.independentRead }) { "Independent read aliases primary buffer" }
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
        val manifest = EcuBackupManifest.inspect(
            controllerLabel, createdAtUtcMillis, snapshots, requiredRegions,
            vehicleVin, channel, firmwareEvidence
        )
        val verification = EcuBackupIntegrity.verify(manifest, snapshots, requiredRegions)
        require(verification.consistent) { "Inconsistent backup captures: ${verification.problems}" }
        val properties = Properties()
        properties.setProperty("format", when {
            channel != null -> FORMAT_WITH_CHANNEL
            vehicleVin != null -> FORMAT_WITH_VIN
            else -> FORMAT
        })
        if (channel != null) {
            properties.setProperty("channel.requestCanId", channel.requestCanId.toString())
            properties.setProperty("channel.responseCanId", channel.responseCanId.toString())
            properties.setProperty("channel.protocolNumber", channel.protocolNumber.toString())
            properties.setProperty("channel.session", channel.diagnosticSession.toString())
        }
        if (firmwareEvidence != null) {
            properties.setProperty("ecu.osId", firmwareEvidence.operatingSystemId.toString())
            properties.setProperty("ecu.osDid", firmwareEvidence.osDid.toString())
        }
        if (vehicleVin != null) properties.setProperty("vin", vehicleVin)
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
        val metadata = ByteArrayOutputStream().also { properties.store(it, properties.getProperty("format")) }.toByteArray()
        require(metadata.size <= 64 * 1024) { "Manifest too large" }
        val payloadBytes = snapshots.sumOf { it.firstRead.size.toLong() +
            (it.independentRead?.size?.toLong() ?: 0L) }
        require(fitsUncompressedLimit(payloadBytes, metadata.size.toLong())) {
            "Archive payload and manifest together exceed the uncompressed size limit"
        }
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

    fun import(input: InputStream, maxUncompressedBytes: Long = MAX_ARCHIVE_BYTES): Imported {
        require(maxUncompressedBytes in 1..MAX_ARCHIVE_BYTES) { "Invalid archive size limit" }
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        // A stream containing intact local entries but no ZIP footer must
        // never be reported as a complete ECU archive.
        val compressed = EcuZipCompletionGuard(input, MAX_ARCHIVE_BYTES + 4L * 1024 * 1024)
        ZipInputStream(compressed).use { zip ->
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
                    require(total <= maxUncompressedBytes && data.size() + n <= entryLimit) {
                        "Archive exceeds size limit"
                    }
                    data.write(buffer, 0, n)
                }
                files[entry.name] = data.toByteArray()
                zip.closeEntry()
            }
            compressed.requireCompletedArchive(files.keys.toList())
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
        val vehicleVin = when (properties.getProperty("format")) {
            FORMAT -> {
                require("vin" !in properties.stringPropertyNames()) { "Unexpected VIN in v1 archive" }
                null
            }
            FORMAT_WITH_VIN, FORMAT_WITH_CHANNEL ->
                requireNotNull(properties.getProperty("vin")) { "Missing archive VIN" }
            else -> throw IllegalArgumentException("Unsupported archive format")
        }
        val channel = if (properties.getProperty("format") == FORMAT_WITH_CHANNEL) {
            fun integer(name: String): Int {
                val raw = properties.getProperty("channel.$name")
                    ?: throw IllegalArgumentException("Missing ECU backup channel.$name")
                val parsed = raw.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid ECU backup channel.$name")
                require(parsed.toString() == raw) { "Noncanonical channel.$name" }
                return parsed
            }
            EcuBackupManifest.Channel(
                requestCanId = integer("requestCanId"),
                responseCanId = integer("responseCanId"),
                protocolNumber = integer("protocolNumber"),
                diagnosticSession = integer("session")
            )
        } else null
        val firmwareEvidence = if (properties.getProperty("format") == FORMAT_WITH_CHANNEL &&
            ("ecu.osId" in properties || "ecu.osDid" in properties)
        ) {
            fun canonicalLong(name: String): Long {
                val raw = properties.getProperty(name)
                    ?: throw IllegalArgumentException("Incomplete ECU firmware evidence.")
                val parsed = raw.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid ECU firmware evidence.")
                require(parsed.toString() == raw) { "Noncanonical ECU firmware evidence." }
                return parsed
            }
            val did = canonicalLong("ecu.osDid")
            require(did in 0..255) { "Invalid ECU firmware DID." }
            EcuBackupManifest.FirmwareEvidence(canonicalLong("ecu.osId"), did.toInt())
        } else null
        val count = properties.getProperty("count")?.toIntOrNull()
        require(count != null && count in 1..MAX_ENTRIES) { "Invalid segment count" }
        val declaredRegions = properties.getProperty("required", "")
        val requiredNames = if (declaredRegions.isEmpty()) emptyList()
            else declaredRegions.split(",")
        require(requiredNames.size == requiredNames.toSet().size &&
            requiredNames.none(String::isBlank)) {
            "Duplicate or empty required ECU region"
        }
        val required = requiredNames.map { EcuBackupCoverage.Region.valueOf(it) }.toSet()
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
            require(first.isNotEmpty()) { "Empty primary read for segment $index" }
            require(second != null) { "Missing independent read for segment $index" }
            require(EcuBenchExpertPlan.sha256Hex(first).equals(field("sha256"), ignoreCase = true)) {
                "Primary read checksum mismatch"
            }
            require(first.contentEquals(second)) {
                "Independent read differs from primary read for segment $index"
            }
            EcuBackupManifest.Capture(
                EcuBackupCoverage.Region.valueOf(field("region")),
                field("start").toLong(), field("expected").toLong(),
                first, second, EcuBackupManifest.Origin.valueOf(field("origin"))
            )
        }
        require(files.isEmpty()) { "Unreferenced archive data" }
        val allowedChannelKeys = if (channel != null) setOf(
            "channel.requestCanId", "channel.responseCanId",
            "channel.protocolNumber", "channel.session"
        ) else emptySet()
        val allowedFirmwareKeys = if (firmwareEvidence != null)
            setOf("ecu.osId", "ecu.osDid") else emptySet()
        require(properties.stringPropertyNames().all { key ->
            key in setOf("format", "controller", "created", "count", "required", "vin") ||
                key in allowedChannelKeys || key in allowedFirmwareKeys ||
                (0 until count).any { index ->
                    key in setOf("region", "start", "expected", "origin", "sha256", "second")
                        .map { "capture.$index.$it" }
                }
        }) { "Unknown archive manifest property" }
        val created = properties.getProperty("created")?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: throw IllegalArgumentException("Invalid creation timestamp")
        val manifest = EcuBackupManifest.inspect(
            properties.getProperty("controller").orEmpty().ifBlank { null },
            created, captures, required, vehicleVin, channel, firmwareEvidence
        )
        val verification = EcuBackupIntegrity.verify(manifest, captures, required)
        require(verification.consistent) { "Archive failed offline verification: ${verification.problems}" }
        return Imported(manifest, captures, required, verification)
    }
}
