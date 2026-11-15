package com.camarodiag.gen5.data.obd

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.roundToLong

enum class CalibrationValueType(val byteWidth: Int) {
    U8(1), S8(1), U16(2), S16(2), U32(4), S32(4), F32(4)
}

enum class CalibrationEndian {
    LITTLE, BIG
}

data class CalibrationAxisDefinition(
    val fileOffset: Long,
    val count: Int,
    val valueType: CalibrationValueType,
    val endian: CalibrationEndian,
    val scale: Double,
    val bias: Double,
    val unit: String
) {
    val byteLength: Long get() = count.toLong() * valueType.byteWidth
    val endExclusive: Long
        get() = if (fileOffset < 0 || byteLength < 0 || fileOffset > Long.MAX_VALUE - byteLength) -1L
        else fileOffset + byteLength
}

data class CalibrationMapDefinition(
    val id: String,
    val name: String,
    val fileOffset: Long,
    val rows: Int,
    val columns: Int,
    val valueType: CalibrationValueType,
    val endian: CalibrationEndian,
    val scale: Double,
    val bias: Double,
    val unit: String,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val xAxis: CalibrationAxisDefinition? = null,
    val yAxis: CalibrationAxisDefinition? = null
) {
    val cellCount: Long get() = rows.toLong() * columns
    val byteLength: Long get() = cellCount * valueType.byteWidth
    val endExclusive: Long
        get() = if (fileOffset < 0 || byteLength < 0 || fileOffset > Long.MAX_VALUE - byteLength) -1L
        else fileOffset + byteLength
}

data class CalibrationTable(
    val definition: CalibrationMapDefinition,
    val values: List<List<Double>>,
    val xAxisValues: List<Double> = emptyList(),
    val yAxisValues: List<Double> = emptyList()
)

data class CalibrationDefinitionParseResult(
    val definitions: List<CalibrationMapDefinition>,
    val warnings: List<String>
)

/**
 * Offline table codec for explicitly supplied and verified binary definitions.
 *
 * No Camaro table offsets, axes, scaling or checksums are guessed or bundled.
 * Definitions may include separate X/Y axis arrays. The original binary is never
 * modified in place; applyEdits returns a new image.
 */
object CalibrationTableCodec {

    private const val MAX_TABLE_CELLS = 4096
    private const val MAX_DEFINITIONS = 500
    private const val REQUIRED_COLUMNS = 10

    private val baseColumns = listOf(
        "id", "name", "offset", "rows", "columns", "type", "endian", "scale", "bias", "unit"
    )
    private val xAxisColumns = listOf(
        "x_offset", "x_type", "x_endian", "x_scale", "x_bias", "x_unit"
    )
    private val yAxisColumns = listOf(
        "y_offset", "y_type", "y_endian", "y_scale", "y_bias", "y_unit"
    )

    val template: String = listOf(
        (baseColumns + listOf("minimum", "maximum") + xAxisColumns + yAxisColumns).joinToString("\t"),
        listOf(
            "example_fuel", "Example fuel table", "0x1000", "16", "16", "U16", "LE",
            "0.01", "0", "mg/stroke", "0", "1000",
            "", "", "", "", "", "",
            "", "", "", "", "", ""
        ).joinToString("\t")
    ).joinToString("\n")

    fun parseDefinitions(content: String): CalibrationDefinitionParseResult {
        val normalized = content.removePrefix("\uFEFF").replace("\r", "")
        val lines = normalized.split("\n")
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        require(lines.size >= 2) { "Le fichier de définitions doit contenir un en-tête et au moins une table." }
        require(lines.size <= MAX_DEFINITIONS + 1) {
            "Le fichier dépasse la limite de " + MAX_DEFINITIONS + " tables."
        }

        val delimiter = detectDelimiter(lines.first())
        val header = parseDelimitedLine(lines.first(), delimiter)
            .map { it.trim().removePrefix("\uFEFF").lowercase(Locale.ROOT) }
        require(header.take(REQUIRED_COLUMNS) == baseColumns) {
            "En-tête invalide. Les dix premières colonnes attendues sont : " + baseColumns.joinToString(delimiter.toString())
        }
        require(header.size in REQUIRED_COLUMNS..(REQUIRED_COLUMNS + 2 + xAxisColumns.size + yAxisColumns.size)) {
            "Nombre de colonnes invalide."
        }
        require(header.distinct().size == header.size) { "L'en-tête contient des noms de colonnes dupliqués." }

        val optionalColumns = header.drop(REQUIRED_COLUMNS)
        val allowedOptional = setOf("minimum", "maximum") + xAxisColumns + yAxisColumns
        require(optionalColumns.all { it in allowedOptional }) {
            "Une colonne optionnelle n'est pas reconnue."
        }
        require((xAxisColumns.any { it in header }) == xAxisColumns.all { it in header }) {
            "La définition de l'axe X doit fournir les six colonnes x_offset, x_type, x_endian, x_scale, x_bias et x_unit."
        }
        require((yAxisColumns.any { it in header }) == yAxisColumns.all { it in header }) {
            "La définition de l'axe Y doit fournir les six colonnes y_offset, y_type, y_endian, y_scale, y_bias et y_unit."
        }

        val definitions = mutableListOf<CalibrationMapDefinition>()
        val warnings = mutableListOf<String>()
        for ((lineIndex, line) in lines.drop(1).withIndex()) {
            val cells = parseDelimitedLine(line, delimiter).map(String::trim)
            require(cells.size == header.size) {
                "Ligne " + (lineIndex + 2) + " : nombre de colonnes incorrect."
            }
            val fields = header.zip(cells).toMap()
            val id = fields.getValue("id")
            require(id.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) {
                "Ligne " + (lineIndex + 2) + " : identifiant invalide."
            }
            require(definitions.none { it.id == id }) { "Identifiant de table dupliqué : " + id + "." }
            val name = fields.getValue("name")
            require(name.isNotBlank() && name.length <= 120) {
                "Ligne " + (lineIndex + 2) + " : nom de table invalide."
            }

            val offset = parseAddress(fields.getValue("offset"))
            val rows = fields.getValue("rows").toInt()
            val columns = fields.getValue("columns").toInt()
            require(rows in 1..256 && columns in 1..256 && rows.toLong() * columns <= MAX_TABLE_CELLS) {
                "Ligne " + (lineIndex + 2) + " : dimensions de table hors limites (max. " + MAX_TABLE_CELLS + " cellules)."
            }
            val type = parseValueType(fields.getValue("type"))
            val endian = parseEndian(fields.getValue("endian"))
            val scale = fields.getValue("scale").toDouble()
            val bias = fields.getValue("bias").toDouble()
            require(scale.isFinite() && scale != 0.0 && bias.isFinite()) {
                "Ligne " + (lineIndex + 2) + " : scale/bias invalide."
            }
            val unit = fields.getValue("unit")
            val minimum = fields["minimum"]?.takeIf(String::isNotBlank)?.toDouble()
            val maximum = fields["maximum"]?.takeIf(String::isNotBlank)?.toDouble()
            require(minimum == null || minimum.isFinite()) { "Minimum invalide à la ligne " + (lineIndex + 2) + "." }
            require(maximum == null || maximum.isFinite()) { "Maximum invalide à la ligne " + (lineIndex + 2) + "." }
            require(minimum == null || maximum == null || minimum <= maximum) {
                "Ligne " + (lineIndex + 2) + " : minimum supérieur au maximum."
            }

            val xAxis = parseAxis(fields, xAxisColumns, columns, lineIndex + 2)
            val yAxis = parseAxis(fields, yAxisColumns, rows, lineIndex + 2)
            val definition = CalibrationMapDefinition(
                id = id,
                name = name,
                fileOffset = offset,
                rows = rows,
                columns = columns,
                valueType = type,
                endian = endian,
                scale = scale,
                bias = bias,
                unit = unit,
                minimum = minimum,
                maximum = maximum,
                xAxis = xAxis,
                yAxis = yAxis
            )
            validateDefinition(definition)
            definitions += definition
        }

        require(definitions.isNotEmpty()) { "Aucune définition de table valide." }
        val ranges = definitions.flatMap { definition ->
            buildList {
                add(definition.fileOffset to definition.endExclusive)
                definition.xAxis?.let { add(it.fileOffset to it.endExclusive) }
                definition.yAxis?.let { add(it.fileOffset to it.endExclusive) }
            }
        }.sortedBy { it.first }
        for (index in 1 until ranges.size) {
            if (ranges[index].first < ranges[index - 1].second) {
                warnings += "Des zones de tables ou d'axes se chevauchent ; vérifie que ce recouvrement est volontaire."
                break
            }
        }
        return CalibrationDefinitionParseResult(definitions, warnings)
    }

    fun decode(binary: ByteArray, definition: CalibrationMapDefinition): CalibrationTable {
        validateBounds(binary.size, definition)
        val values = List(definition.rows) { row ->
            List(definition.columns) { column ->
                val index = row * definition.columns + column
                val offset = safeByteOffset(definition.fileOffset, index, definition.valueType.byteWidth)
                readEngineeringValue(binary, offset, definition.valueType, definition.endian, definition.scale, definition.bias)
            }
        }
        val xValues = definition.xAxis?.let { decodeAxis(binary, it) }.orEmpty()
        val yValues = definition.yAxis?.let { decodeAxis(binary, it) }.orEmpty()
        return CalibrationTable(definition, values, xValues, yValues)
    }

    /** Returns a new byte array; original image is never modified in place. */
    fun applyEdits(
        binary: ByteArray,
        definition: CalibrationMapDefinition,
        values: List<List<Double>>
    ): ByteArray {
        validateBounds(binary.size, definition)
        require(values.size == definition.rows && values.all { it.size == definition.columns }) {
            "La matrice fournie ne correspond pas aux dimensions de la table."
        }
        val edited = binary.copyOf()
        values.forEachIndexed { row, rowValues ->
            rowValues.forEachIndexed { column, engineeringValue ->
                require(engineeringValue.isFinite()) { "Une cellule contient une valeur non numérique." }
                definition.minimum?.let {
                    require(engineeringValue >= it) { "Valeur inférieure à la limite de la table." }
                }
                definition.maximum?.let {
                    require(engineeringValue <= it) { "Valeur supérieure à la limite de la table." }
                }
                val cellIndex = row * definition.columns + column
                val byteOffset = safeByteOffset(definition.fileOffset, cellIndex, definition.valueType.byteWidth)
                writeEngineeringValue(
                    edited,
                    byteOffset,
                    definition.valueType,
                    definition.endian,
                    definition.scale,
                    definition.bias,
                    engineeringValue
                )
            }
        }
        return edited
    }

    fun formatEngineeringValue(value: Double): String =
        String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

    private fun detectDelimiter(header: String): Char {
        val candidates = listOf(';', '\t', ',')
        return candidates.maxByOrNull { delimiter -> header.count { it == delimiter } }
            ?.takeIf { delimiter -> header.contains(delimiter) }
            ?: error("Séparateur de fichier non reconnu : utiliser CSV, TSV ou point-virgule.")
    }

    /** Parses one CSV/TSV record, including doubled quotes and delimiters inside quoted fields. */
    private fun parseDelimitedLine(line: String, delimiter: Char): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                character == delimiter && !quoted -> {
                    fields += field.toString()
                    field.setLength(0)
                }
                else -> field.append(character)
            }
            index++
        }
        require(!quoted) { "Champ entre guillemets non terminé dans une ligne CSV/TSV." }
        fields += field.toString()
        return fields
    }

    private fun parseAxis(
        fields: Map<String, String>,
        columns: List<String>,
        count: Int,
        lineNumber: Int
    ): CalibrationAxisDefinition? {
        if (columns.none { fields[it].isNullOrBlank() }) return null
        require(columns.all { !fields[it].isNullOrBlank() }) {
            "Ligne " + lineNumber + " : une définition d'axe doit renseigner toutes ses colonnes."
        }
        val offset = parseAddress(fields.getValue(columns[0]))
        val type = parseValueType(fields.getValue(columns[1]))
        val endian = parseEndian(fields.getValue(columns[2]))
        val scale = fields.getValue(columns[3]).toDouble()
        val bias = fields.getValue(columns[4]).toDouble()
        require(scale.isFinite() && scale != 0.0 && bias.isFinite()) {
            "Ligne " + lineNumber + " : scale/bias de l'axe invalide."
        }
        return CalibrationAxisDefinition(
            fileOffset = offset,
            count = count,
            valueType = type,
            endian = endian,
            scale = scale,
            bias = bias,
            unit = fields.getValue(columns[5])
        ).also(::validateAxisDefinition)
    }

    private fun parseValueType(value: String): CalibrationValueType =
        CalibrationValueType.valueOf(value.trim().uppercase(Locale.ROOT))

    private fun parseEndian(value: String): CalibrationEndian =
        when (value.trim().uppercase(Locale.ROOT)) {
            "LE", "LITTLE" -> CalibrationEndian.LITTLE
            "BE", "BIG" -> CalibrationEndian.BIG
            else -> error("endian doit être LE ou BE.")
        }

    private fun parseAddress(value: String): Long {
        val address = value.trim()
        return when {
            address.startsWith("0x", true) -> address.substring(2).toLong(16)
            else -> address.toLong()
        }.also { require(it >= 0) { "L'adresse de table ne peut pas être négative." } }
    }

    private fun validateDefinition(definition: CalibrationMapDefinition) {
        require(definition.id.isNotBlank() && definition.name.isNotBlank()) { "Identifiant ou nom de table manquant." }
        require(definition.rows in 1..256 && definition.columns in 1..256) { "Dimensions de table hors limites." }
        require(definition.cellCount <= MAX_TABLE_CELLS) { "La table dépasse la limite de " + MAX_TABLE_CELLS + " cellules." }
        require(definition.scale.isFinite() && definition.scale != 0.0 && definition.bias.isFinite()) {
            "Scale/bias de table invalide."
        }
        require(definition.minimum == null || definition.minimum.isFinite()) { "Minimum de table invalide." }
        require(definition.maximum == null || definition.maximum.isFinite()) { "Maximum de table invalide." }
        require(definition.minimum == null || definition.maximum == null || definition.minimum <= definition.maximum) {
            "Minimum de table supérieur au maximum."
        }
        require(definition.fileOffset >= 0 && definition.endExclusive >= definition.fileOffset) {
            "Offset ou longueur de table invalide."
        }
        definition.xAxis?.let(::validateAxisDefinition)
        definition.yAxis?.let(::validateAxisDefinition)
    }

    private fun validateAxisDefinition(axis: CalibrationAxisDefinition) {
        require(axis.count in 1..256) { "Nombre de valeurs de l'axe hors limites." }
        require(axis.fileOffset >= 0 && axis.endExclusive >= axis.fileOffset) { "Offset ou longueur d'axe invalide." }
        require(axis.scale.isFinite() && axis.scale != 0.0 && axis.bias.isFinite()) { "Scale/bias d'axe invalide." }
    }

    private fun validateBounds(binarySize: Int, definition: CalibrationMapDefinition) {
        validateDefinition(definition)
        require(definition.endExclusive <= binarySize.toLong()) {
            "La table " + definition.name + " dépasse les limites du fichier (offset 0x" +
                definition.fileOffset.toString(16).uppercase(Locale.ROOT) + ")."
        }
        definition.xAxis?.let {
            require(it.endExclusive <= binarySize.toLong()) { "L'axe X dépasse les limites de l'image binaire." }
        }
        definition.yAxis?.let {
            require(it.endExclusive <= binarySize.toLong()) { "L'axe Y dépasse les limites de l'image binaire." }
        }
    }

    private fun decodeAxis(binary: ByteArray, axis: CalibrationAxisDefinition): List<Double> =
        List(axis.count) { index ->
            val offset = safeByteOffset(axis.fileOffset, index, axis.valueType.byteWidth)
            readEngineeringValue(binary, offset, axis.valueType, axis.endian, axis.scale, axis.bias)
        }

    private fun safeByteOffset(base: Long, index: Int, width: Int): Int {
        val offset = base + index.toLong() * width
        require(offset in 0..Int.MAX_VALUE.toLong()) { "Offset de table hors de la plage adressable." }
        return offset.toInt()
    }

    private fun buffer(bytes: ByteArray, offset: Int, type: CalibrationValueType, endian: CalibrationEndian): ByteBuffer =
        ByteBuffer.wrap(bytes, offset, type.byteWidth).order(
            if (endian == CalibrationEndian.LITTLE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        )

    private fun readEngineeringValue(
        bytes: ByteArray,
        offset: Int,
        type: CalibrationValueType,
        endian: CalibrationEndian,
        scale: Double,
        bias: Double
    ): Double {
        val input = buffer(bytes, offset, type, endian)
        val raw = when (type) {
            CalibrationValueType.U8 -> bytes[offset].toInt() and 0xFF
            CalibrationValueType.S8 -> bytes[offset].toInt()
            CalibrationValueType.U16 -> input.short.toInt() and 0xFFFF
            CalibrationValueType.S16 -> input.short.toInt()
            CalibrationValueType.U32 -> input.int.toLong() and 0xFFFFFFFFL
            CalibrationValueType.S32 -> input.int.toLong()
            CalibrationValueType.F32 -> input.float.toDouble()
        }
        val engineering = raw.toDouble() * scale + bias
        require(engineering.isFinite()) { "La valeur binaire décodée n'est pas finie." }
        return engineering
    }

    private fun writeEngineeringValue(
        bytes: ByteArray,
        offset: Int,
        type: CalibrationValueType,
        endian: CalibrationEndian,
        scale: Double,
        bias: Double,
        value: Double
    ) {
        val raw = (value - bias) / scale
        require(raw.isFinite()) { "Valeur brute invalide." }
        val output = buffer(bytes, offset, type, endian)
        when (type) {
            CalibrationValueType.U8 -> {
                val n = raw.roundToLong()
                require(n in 0L..0xFFL) { "Valeur U8 hors limites." }
                bytes[offset] = n.toByte()
            }
            CalibrationValueType.S8 -> {
                val n = raw.roundToLong()
                require(n in -128L..127L) { "Valeur S8 hors limites." }
                bytes[offset] = n.toByte()
            }
            CalibrationValueType.U16 -> {
                val n = raw.roundToLong()
                require(n in 0L..0xFFFFL) { "Valeur U16 hors limites." }
                output.putShort(n.toShort())
            }
            CalibrationValueType.S16 -> {
                val n = raw.roundToLong()
                require(n in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()) { "Valeur S16 hors limites." }
                output.putShort(n.toShort())
            }
            CalibrationValueType.U32 -> {
                val n = raw.roundToLong()
                require(n in 0L..0xFFFFFFFFL) { "Valeur U32 hors limites." }
                output.putInt(n.toInt())
            }
            CalibrationValueType.S32 -> {
                val n = raw.roundToLong()
                require(n in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Valeur S32 hors limites." }
                output.putInt(n.toInt())
            }
            CalibrationValueType.F32 -> {
                require(raw in -Float.MAX_VALUE.toDouble()..Float.MAX_VALUE.toDouble()) { "Valeur F32 hors limites." }
                output.putFloat(raw.toFloat())
            }
        }
    }
}
