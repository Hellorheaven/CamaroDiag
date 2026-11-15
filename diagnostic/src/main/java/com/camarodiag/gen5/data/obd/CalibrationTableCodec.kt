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
    val maximum: Double? = null
) {
    val cellCount: Int get() = rows * columns
    val byteLength: Long get() = cellCount.toLong() * valueType.byteWidth
    val endExclusive: Long get() = fileOffset + byteLength
}

data class CalibrationTable(
    val definition: CalibrationMapDefinition,
    val values: List<List<Double>>
)

data class CalibrationDefinitionParseResult(
    val definitions: List<CalibrationMapDefinition>,
    val warnings: List<String>
)

/**
 * Import and edit map tables using explicit user-supplied definitions.
 *
 * CamaroDiag intentionally ships without guessed addresses or scaling for
 * Camaro ECM memory. Every definition must identify an exact file offset,
 * dimensions, numeric type, byte order and engineering conversion.
 */
object CalibrationTableCodec {

    private const val MAX_TABLE_CELLS = 4096
    private const val MAX_DEFINITIONS = 500

    val template: String = listOf(
        "id;name;offset;rows;columns;type;endian;scale;bias;unit;minimum;maximum",
        "example_fuel;Example fuel table;0x1000;16;16;U16;LE;0.01;0;mg/stroke;0;1000"
    ).joinToString("\n")

    fun parseDefinitions(content: String): CalibrationDefinitionParseResult {
        val lines = content.replace("\r", "").split("\n").map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        require(lines.size >= 2) { "Le fichier de définitions doit contenir un en-tête et au moins une table." }
        require(lines.size <= MAX_DEFINITIONS + 1) { "Le fichier dépasse la limite de $MAX_DEFINITIONS tables." }

        val header = lines.first().split(';').map { it.trim().lowercase(Locale.ROOT) }
        val required = listOf("id", "name", "offset", "rows", "columns", "type", "endian", "scale", "bias", "unit")
        require(header.take(required.size) == required) {
            "En-tête invalide. Colonnes attendues : ${required.joinToString(";")} ; minimum et maximum sont facultatifs."
        }
        require(header.size in 10..12) { "Le fichier doit contenir 10 à 12 colonnes." }

        val definitions = mutableListOf<CalibrationMapDefinition>()
        val warnings = mutableListOf<String>()
        for ((lineIndex, line) in lines.drop(1).withIndex()) {
            val cells = line.split(';').map { it.trim() }
            require(cells.size == header.size) { "Ligne ${lineIndex + 2} : nombre de colonnes incorrect." }
            val fields = header.zip(cells).toMap()
            val id = fields.getValue("id")
            require(id.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Ligne ${lineIndex + 2} : identifiant invalide." }
            require(definitions.none { it.id == id }) { "Identifiant de table dupliqué : $id." }
            val name = fields.getValue("name")
            require(name.isNotBlank() && name.length <= 120) { "Ligne ${lineIndex + 2} : nom de table invalide." }
            val offset = parseAddress(fields.getValue("offset"))
            val rows = fields.getValue("rows").toInt()
            val columns = fields.getValue("columns").toInt()
            require(rows in 1..256 && columns in 1..256 && rows.toLong() * columns <= MAX_TABLE_CELLS) {
                "Ligne ${lineIndex + 2} : dimensions de table hors limites (max. $MAX_TABLE_CELLS cellules)."
            }
            val type = CalibrationValueType.valueOf(fields.getValue("type").uppercase(Locale.ROOT))
            val endian = when (fields.getValue("endian").uppercase(Locale.ROOT)) {
                "LE", "LITTLE" -> CalibrationEndian.LITTLE
                "BE", "BIG" -> CalibrationEndian.BIG
                else -> error("Ligne ${lineIndex + 2} : endian doit être LE ou BE.")
            }
            val scale = fields.getValue("scale").toDouble()
            val bias = fields.getValue("bias").toDouble()
            require(scale.isFinite() && scale != 0.0 && bias.isFinite()) { "Ligne ${lineIndex + 2} : scale/bias invalide." }
            val unit = fields.getValue("unit")
            val minimum = fields["minimum"]?.takeIf(String::isNotBlank)?.toDouble()
            val maximum = fields["maximum"]?.takeIf(String::isNotBlank)?.toDouble()
            require(minimum == null || minimum.isFinite()) { "Ligne ${lineIndex + 2} : minimum invalide." }
            require(maximum == null || maximum.isFinite()) { "Ligne ${lineIndex + 2} : maximum invalide." }
            require(minimum == null || maximum == null || minimum <= maximum) { "Ligne ${lineIndex + 2} : minimum supérieur au maximum." }
            val definition = CalibrationMapDefinition(
                id = id, name = name, fileOffset = offset, rows = rows, columns = columns,
                valueType = type, endian = endian, scale = scale, bias = bias, unit = unit,
                minimum = minimum, maximum = maximum
            )
            require(definition.endExclusive >= definition.fileOffset) { "Ligne ${lineIndex + 2} : débordement d'adresse." }
            definitions += definition
        }
        if (definitions.isEmpty()) error("Aucune définition de table valide.")
        val ordered = definitions.sortedBy { it.fileOffset }
        for (i in 1 until ordered.size) {
            if (ordered[i].fileOffset < ordered[i - 1].endExclusive) {
                warnings += "Les tables ${ordered[i - 1].id} et ${ordered[i].id} se chevauchent ; vérifie volontairement les définitions."
            }
        }
        return CalibrationDefinitionParseResult(definitions, warnings)
    }

    fun decode(binary: ByteArray, definition: CalibrationMapDefinition): CalibrationTable {
        validateBounds(binary.size, definition)
        val values = List(definition.rows) { row ->
            List(definition.columns) { column ->
                val index = row * definition.columns + column
                val offset = definition.fileOffset.toInt() + index * definition.valueType.byteWidth
                readEngineeringValue(binary, offset, definition)
            }
        }
        return CalibrationTable(definition, values)
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
                definition.minimum?.let { require(engineeringValue >= it) { "Valeur inférieure à la limite de la table." } }
                definition.maximum?.let { require(engineeringValue <= it) { "Valeur supérieure à la limite de la table." } }
                val cellIndex = row * definition.columns + column
                val byteOffset = definition.fileOffset.toInt() + cellIndex * definition.valueType.byteWidth
                writeEngineeringValue(edited, byteOffset, definition, engineeringValue)
            }
        }
        return edited
    }

    fun formatEngineeringValue(value: Double): String =
        String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

    private fun parseAddress(value: String): Long {
        val address = value.trim()
        return when {
            address.startsWith("0x", true) -> address.substring(2).toLong(16)
            else -> address.toLong()
        }.also { require(it >= 0) { "L'adresse de table ne peut pas être négative." } }
    }

    private fun validateBounds(binarySize: Int, definition: CalibrationMapDefinition) {
        require(definition.fileOffset >= 0 && definition.endExclusive <= binarySize.toLong()) {
            "La table ${definition.name} dépasse les limites du fichier (offset 0x${definition.fileOffset.toString(16).uppercase()})."
        }
    }

    private fun buffer(bytes: ByteArray, offset: Int, definition: CalibrationMapDefinition): ByteBuffer =
        ByteBuffer.wrap(bytes, offset, definition.valueType.byteWidth).order(
            if (definition.endian == CalibrationEndian.LITTLE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        )

    private fun readEngineeringValue(bytes: ByteArray, offset: Int, definition: CalibrationMapDefinition): Double {
        val input = buffer(bytes, offset, definition)
        val raw = when (definition.valueType) {
            CalibrationValueType.U8 -> bytes[offset].toInt() and 0xFF
            CalibrationValueType.S8 -> bytes[offset].toInt()
            CalibrationValueType.U16 -> input.short.toInt() and 0xFFFF
            CalibrationValueType.S16 -> input.short.toInt()
            CalibrationValueType.U32 -> input.int.toLong() and 0xFFFFFFFFL
            CalibrationValueType.S32 -> input.int.toLong()
            CalibrationValueType.F32 -> input.float.toDouble()
        }
        return raw.toDouble() * definition.scale + definition.bias
    }

    private fun writeEngineeringValue(bytes: ByteArray, offset: Int, definition: CalibrationMapDefinition, value: Double) {
        val raw = (value - definition.bias) / definition.scale
        require(raw.isFinite()) { "Valeur brute invalide." }
        val output = buffer(bytes, offset, definition)
        when (definition.valueType) {
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
