package com.camarodiag.gen5.data.obd

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import java.util.Locale

enum class CalibrationFileFormat(val label: String) {
    RAW_BINARY("BIN / raw binary"),
    INTEL_HEX("Intel HEX"),
    MOTOROLA_S_RECORD("Motorola S-record"),
    ZIP_ARCHIVE("Archive de cartographie (ZIP)"),
    EXTERNAL_DEFINITION("Définition de tables externe"),
    EXTERNAL_TUNE_PROJECT("Projet de cartographie externe (conservé tel quel)"),
    UNKNOWN("Fichier externe non reconnu")
}

data class CalibrationAddressRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

data class CalibrationFileInspection(
    val format: CalibrationFileFormat,
    val valid: Boolean,
    val summary: String,
    val payloadBytes: Long,
    val recordCount: Int,
    val addressRanges: List<CalibrationAddressRange>,
    val checksumValidation: String,
    val warnings: List<String>
)

/**
 * Read-only structural inspection for common calibration dump formats.
 *
 * This class never changes a file, infers ECU compatibility, fixes checksums or
 * creates a flash image. CRC32/SHA-256 are file-integrity hashes, not GM ECU
 * calibration checksums.
 */
object CalibrationFileInspector {

    private const val MAX_RECORDS = 2_000_000
    private const val MAX_ADDRESS_SPAN = 64L * 1024L * 1024L
    private const val MAX_ARCHIVE_ENTRIES = 1_000
    private const val MAX_ARCHIVE_UNCOMPRESSED_BYTES = 128L * 1024L * 1024L
    private const val MAX_ARCHIVE_DISPLAY_ENTRIES = 30
    private val externalTuneProjectExtensions = setOf(
        "ctz", "coz", "ols", "olsx", "ptm", "ptx", "xpr", "7z", "rar"
    )

    fun inspect(fileName: String, content: ByteArray): CalibrationFileInspection {
        if (content.isEmpty()) return invalid(CalibrationFileFormat.UNKNOWN, "Fichier vide.", "Empty file.")
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            isZipArchive(content) -> inspectZipArchive(content)
            extension in externalTuneProjectExtensions -> externalTuneProjectInspection(content.size.toLong(), extension)
            extension in setOf("xdf", "a2l", "xml", "mapdef", "dam", "dcm") -> externalDefinitionInspection(content.size.toLong(), extension)
            extension in setOf("hex", "ihx") -> inspectIntelHex(content)
            extension in setOf("s19", "srec", "s28", "s37", "mot") -> inspectSRecord(content)
            extension in setOf("bin", "rom", "ori", "mod", "cal", "img") ->
                CalibrationFileInspection(
                    CalibrationFileFormat.RAW_BINARY, true,
                    "Fichier binaire brut : structure interne non interprétée.",
                    content.size.toLong(), 0, emptyList(), "Sans checksum de format",
                    listOf("Le SHA-256/CRC32 ne valide ni la compatibilité ECU ni le checksum GM.")
                )
            else -> {
                val text = content.toString(StandardCharsets.US_ASCII).trimStart()
                when {
                    text.startsWith(":") -> inspectIntelHex(content)
                    text.startsWith("S0") || text.startsWith("S1") || text.startsWith("S2") || text.startsWith("S3") ->
                        inspectSRecord(content)
                    else -> CalibrationFileInspection(
                        CalibrationFileFormat.UNKNOWN, false,
                        "Format de fichier non reconnu ; données conservées sans interprétation.",
                        content.size.toLong(), 0, emptyList(), "Non vérifié",
                        listOf("Importer un BIN, Intel HEX (.hex) ou Motorola S-record (.s19/.srec) pour une inspection structurelle.")
                    )
                }
            }
        }
    }

    private fun isZipArchive(content: ByteArray): Boolean =
        content.size >= 4 &&
            content[0] == 0x50.toByte() &&
            content[1] == 0x4B.toByte() &&
            (
                (content[2] == 0x03.toByte() && content[3] == 0x04.toByte()) ||
                (content[2] == 0x05.toByte() && content[3] == 0x06.toByte()) ||
                (content[2] == 0x07.toByte() && content[3] == 0x08.toByte())
            )

    /**
     * Archives are imported byte-for-byte. Inspection lists a bounded set of
     * members but does not extract or decode vendor-specific binary payloads.
     */
    private fun inspectZipArchive(content: ByteArray): CalibrationFileInspection {
        val entries = mutableListOf<String>()
        val suffixes = linkedSetOf<String>()
        var entryCount = 0
        var uncompressedBytes = 0L
        return try {
            ZipInputStream(ByteArrayInputStream(content)).use { zip ->
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        entryCount++
                        if (entryCount > MAX_ARCHIVE_ENTRIES) {
                            return CalibrationFileInspection(
                                CalibrationFileFormat.ZIP_ARCHIVE, false,
                                "Archive détectée, mais nombre de fichiers internes supérieur à la limite d'inspection.",
                                content.size.toLong(), entryCount, emptyList(), "Inspection interrompue",
                                listOf("L'archive originale reste importée sans extraction.")
                            )
                        }
                        val name = entry.name.replace('\\', '/').take(160)
                        if (entries.size < MAX_ARCHIVE_DISPLAY_ENTRIES) entries += name
                        name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                            .takeIf(String::isNotBlank)?.let(suffixes::add)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            uncompressedBytes += read
                            if (uncompressedBytes > MAX_ARCHIVE_UNCOMPRESSED_BYTES) {
                                return CalibrationFileInspection(
                                    CalibrationFileFormat.ZIP_ARCHIVE, false,
                                    "Archive détectée ; limite de décompression d'inspection atteinte.",
                                    content.size.toLong(), entryCount, emptyList(), "Inspection interrompue",
                                    listOf("Archive conservée telle quelle ; aucun fichier interne n'a été extrait.")
                                )
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val details = buildList {
                add("Archive conservée telle quelle ; aucune cartographie interne n'est extraite ou décodée automatiquement.")
                if (entries.isNotEmpty()) add("Fichiers détectés : " + entries.joinToString(", "))
                if (entryCount > entries.size) add("${entryCount - entries.size} autre(s) entrée(s) non affichée(s).")
                if (suffixes.isNotEmpty()) add("Extensions internes : " + suffixes.take(20).joinToString(", "))
            }
            CalibrationFileInspection(
                CalibrationFileFormat.ZIP_ARCHIVE,
                valid = entryCount > 0,
                summary = "Archive de cartographie importable : $entryCount fichier(s), $uncompressedBytes octets décompressés inspectés.",
                payloadBytes = uncompressedBytes,
                recordCount = entryCount,
                addressRanges = emptyList(),
                checksumValidation = "Conteneur ZIP lisible ; contenu ECU non vérifié",
                warnings = details
            )
        } catch (error: Exception) {
            CalibrationFileInspection(
                CalibrationFileFormat.ZIP_ARCHIVE, false,
                "Archive reconnue mais impossible à inspecter entièrement : ${error.localizedMessage ?: "contenu protégé ou invalide"}.",
                content.size.toLong(), entryCount, emptyList(), "Non vérifié",
                listOf("Le fichier original est conservé sans extraction ni interprétation.")
            )
        }
    }

    private fun externalTuneProjectInspection(size: Long, extension: String) =
        CalibrationFileInspection(
            CalibrationFileFormat.EXTERNAL_TUNE_PROJECT,
            valid = false,
            summary = "Projet de cartographie externe détecté (.$extension) ; format interne non décodé.",
            payloadBytes = size,
            recordCount = 0,
            addressRanges = emptyList(),
            checksumValidation = "Non vérifié",
            warnings = listOf(
                "Le fichier original est conservé octet par octet et peut être exporté.",
                "Pour éditer les tables dans CamaroDiag, exporte depuis le logiciel source une image BIN/ORI/MOD brute et importe une définition de tables vérifiée pour le CAL ID et l'OS exacts.",
                "La détection du conteneur ne valide ni son contenu, ni sa compatibilité ECU, ni sa possibilité de programmation."
            )
        )

    private fun externalDefinitionInspection(size: Long, extension: String) =
        CalibrationFileInspection(
            CalibrationFileFormat.EXTERNAL_DEFINITION, false,
            "Fichier de définition externe .$extension importable et conservé, mais non encore converti en tables CamaroDiag.",
            size, 0, emptyList(), "Non vérifié",
            listOf(
                "Le contenu est conservé à l'identique mais ses adresses, axes, types numériques et facteurs d'échelle ne sont pas appliqués.",
                "Pour modifier les tables hors ligne, convertir la définition vers le modèle TSV documenté puis vérifier chaque offset et chaque facteur sur le CAL ID/OS exact."
            )
        )

    private fun inspectIntelHex(content: ByteArray): CalibrationFileInspection {
        val lines = content.toString(StandardCharsets.US_ASCII)
            .replace("\r", "")
            .split("\n")
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (lines.isEmpty() || lines.size > MAX_RECORDS) {
            return invalid(CalibrationFileFormat.INTEL_HEX, "Aucun enregistrement ou fichier trop complexe.", "No records or file too complex.")
        }
        var baseAddress = 0L
        var recordCount = 0
        var dataBytes = 0L
        var minAddress = Long.MAX_VALUE
        var maxAddress = -1L
        var eofSeen = false
        val ranges = mutableListOf<CalibrationAddressRange>()
        val warnings = mutableListOf<String>()

        for ((lineIndex, line) in lines.withIndex()) {
            if (eofSeen) return invalid(CalibrationFileFormat.INTEL_HEX, "Donnée présente après l'enregistrement EOF.", "Data found after EOF record.")
            if (!line.startsWith(":")) return invalid(CalibrationFileFormat.INTEL_HEX, "Ligne HEX invalide à la ligne ${lineIndex + 1}.", "Invalid HEX line ${lineIndex + 1}.")
            val encoded = line.substring(1)
            if (encoded.length < 10 || encoded.length % 2 != 0) {
                return invalid(CalibrationFileFormat.INTEL_HEX, "Longueur d'enregistrement invalide à la ligne ${lineIndex + 1}.", "Invalid record length at line ${lineIndex + 1}.")
            }
            val record = decodeHex(encoded) ?: return invalid(CalibrationFileFormat.INTEL_HEX, "Caractère HEX invalide à la ligne ${lineIndex + 1}.", "Invalid HEX character at line ${lineIndex + 1}.")
            val count = record[0]
            if (record.size != count + 5) {
                return invalid(CalibrationFileFormat.INTEL_HEX, "Longueur incohérente à la ligne ${lineIndex + 1}.", "Record byte count mismatch at line ${lineIndex + 1}.")
            }
            if ((record.sum() and 0xFF) != 0) {
                return invalid(CalibrationFileFormat.INTEL_HEX, "Checksum Intel HEX incorrect à la ligne ${lineIndex + 1}.", "Intel HEX checksum mismatch at line ${lineIndex + 1}.")
            }
            val address = (record[1] shl 8) or record[2]
            val type = record[3]
            val data = record.copyOfRange(4, 4 + count)
            recordCount++

            when (type) {
                0x00 -> if (count > 0) {
                    val start = baseAddress + address
                    val end = start + count - 1
                    if (end < start || end > 0xFFFFFFFFL || end - start > MAX_ADDRESS_SPAN) {
                        return invalid(CalibrationFileFormat.INTEL_HEX, "Adresse hors limite.", "Address is out of range.")
                    }
                    ranges += CalibrationAddressRange(start, end)
                    minAddress = minOf(minAddress, start)
                    maxAddress = maxOf(maxAddress, end)
                    dataBytes += count
                }
                0x01 -> {
                    if (count != 0 || address != 0) return invalid(CalibrationFileFormat.INTEL_HEX, "Enregistrement EOF invalide.", "Invalid EOF record.")
                    eofSeen = true
                }
                0x02 -> {
                    if (count != 2) return invalid(CalibrationFileFormat.INTEL_HEX, "Enregistrement segment étendu invalide.", "Invalid extended segment record.")
                    baseAddress = ((data[0] shl 8) or data[1]).toLong() shl 4
                }
                0x04 -> {
                    if (count != 2) return invalid(CalibrationFileFormat.INTEL_HEX, "Enregistrement adresse linéaire étendue invalide.", "Invalid extended linear address record.")
                    baseAddress = ((data[0] shl 8) or data[1]).toLong() shl 16
                }
                0x03, 0x05 -> if (count != 4) return invalid(CalibrationFileFormat.INTEL_HEX, "Enregistrement d'adresse de départ invalide.", "Invalid start-address record.")
                else -> return invalid(CalibrationFileFormat.INTEL_HEX, "Type d'enregistrement Intel HEX inconnu : $type.", "Unknown Intel HEX record type: $type.")
            }
        }
        if (!eofSeen) return invalid(CalibrationFileFormat.INTEL_HEX, "Enregistrement EOF absent.", "Missing EOF record.")
        if (ranges.isEmpty()) return invalid(CalibrationFileFormat.INTEL_HEX, "Aucune donnée dans le fichier HEX.", "No data records in HEX file.")
        val sorted = ranges.sortedBy { it.start }
        for (i in 1 until sorted.size) {
            if (sorted[i].start <= sorted[i - 1].endInclusive) {
                return invalid(CalibrationFileFormat.INTEL_HEX, "Chevauchement d'adresses dans les enregistrements.", "Overlapping data addresses in records.")
            }
        }
        if (maxAddress - minAddress > MAX_ADDRESS_SPAN) warnings += "Plage d'adresses très étendue ; aucune image mémoire dense n'est construite."
        return CalibrationFileInspection(
            CalibrationFileFormat.INTEL_HEX, true,
            "Intel HEX valide : $recordCount enregistrements, $dataBytes octets de données.",
            dataBytes, recordCount, sorted, "Tous les checksums d'enregistrement sont valides", warnings
        )
    }

    private fun inspectSRecord(content: ByteArray): CalibrationFileInspection {
        val lines = content.toString(StandardCharsets.US_ASCII)
            .replace("\r", "")
            .split("\n")
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (lines.isEmpty() || lines.size > MAX_RECORDS) {
            return invalid(CalibrationFileFormat.MOTOROLA_S_RECORD, "Aucun enregistrement ou fichier trop complexe.", "No records or file too complex.")
        }
        var dataBytes = 0L
        var recordCount = 0
        var terminationSeen = false
        val ranges = mutableListOf<CalibrationAddressRange>()
        val warnings = mutableListOf<String>()
        for ((lineIndex, line) in lines.withIndex()) {
            if (terminationSeen) return invalid(CalibrationFileFormat.MOTOROLA_S_RECORD, "Donnée après enregistrement de terminaison.", "Data after termination record.")
            if (line.length < 4 || line[0] != 'S' || line[1] !in '0'..'9') {
                return invalid(CalibrationFileFormat.MOTOROLA_S_RECORD, "Enregistrement S invalide à la ligne ${lineIndex + 1}.", "Invalid S-record at line ${lineIndex + 1}.")
            }
            val type = line[1].digitToInt()
            val encoded = line.substring(2)
            if (encoded.length % 2 != 0) return invalid(CalibrationFileFormat.MOTOROLA_S_RECORD, "Longueur HEX invalide à la ligne ${lineIndex + 1}.", "Odd HEX length at line ${lineIndex + 1}.")
            val bytes = decodeHex(encoded) ?: return invalid(CalibrationFileFormat.MOTOROLA_S_RECORD, "Caractère HEX invalide à la ligne ${lineIndex + 1}.", "Invalid HEX character at line ${lineIndex + 1}.")
            if (bytes.isEmpty() || bytes.size != bytes[0] + 1) return invalid(CalibrationFileFormat.MOTOROLA_S_RECORD, "Champ count incohérent à la ligne ${lineIndex + 1}.", "Count field mismatch at line ${lineIndex + 1}.")
            if ((bytes.sum() and 0xFF) != 0xFF) return invalid(CalibrationFileFormat.MOTOROLA_S_RECORD, "Checksum S-record incorrect à la ligne ${lineIndex + 1}.", "S-record checksum mismatch at line ${lineIndex + 1}.")
            val addressWidth = when (type) {
                0, 1, 5, 9 -> 2
                2, 6, 8 -> 3
                3, 7 -> 4
                4 -> return invalid(CalibrationFileFormat.MOTOROLA_S_RECORD, "Type S4 réservé.", "S4 record type is reserved.")
                else -> return invalid(CalibrationFileFormat.MOTOROLA_S_RECORD, "Type S-record inconnu : S$type.", "Unknown S-record type: S$type.")
            }
            val count = bytes[0]
            if (count < addressWidth + 1) return invalid(CalibrationFileFormat.MOTOROLA_S_RECORD, "Enregistrement trop court à la ligne ${lineIndex + 1}.", "Record too short at line ${lineIndex + 1}.")
            var address = 0L
            for (i in 0 until addressWidth) address = (address shl 8) or bytes[1 + i].toLong()
            val dataLength = count - addressWidth - 1
            recordCount++
            if (type in 1..3 && dataLength > 0) {
                val end = address + dataLength - 1
                if (end < address || end > 0xFFFFFFFFL) return invalid(CalibrationFileFormat.MOTOROLA_S_RECORD, "Adresse S-record hors limite.", "S-record address out of range.")
                ranges += CalibrationAddressRange(address, end)
                dataBytes += dataLength
            }
            if (type in setOf(7, 8, 9)) terminationSeen = true
        }
        if (!terminationSeen) return invalid(CalibrationFileFormat.MOTOROLA_S_RECORD, "Enregistrement de terminaison absent.", "Missing termination record.")
        if (ranges.isEmpty()) return invalid(CalibrationFileFormat.MOTOROLA_S_RECORD, "Aucune donnée dans le fichier S-record.", "No data records in S-record file.")
        val sorted = ranges.sortedBy { it.start }
        for (i in 1 until sorted.size) {
            if (sorted[i].start <= sorted[i - 1].endInclusive) {
                return invalid(CalibrationFileFormat.MOTOROLA_S_RECORD, "Chevauchement d'adresses dans les enregistrements.", "Overlapping data addresses in records.")
            }
        }
        return CalibrationFileInspection(
            CalibrationFileFormat.MOTOROLA_S_RECORD, true,
            "Motorola S-record valide : $recordCount enregistrements, $dataBytes octets de données.",
            dataBytes, recordCount, sorted, "Tous les checksums d'enregistrement sont valides", warnings
        )
    }

    private fun decodeHex(value: String): IntArray? {
        if (value.length % 2 != 0) return null
        return runCatching { value.chunked(2).map { it.toInt(16) }.toIntArray() }.getOrNull()
    }

    private fun invalid(format: CalibrationFileFormat, fr: String, en: String) =
        CalibrationFileInspection(format, false, fr, 0, 0, emptyList(), en, listOf(en))
}
