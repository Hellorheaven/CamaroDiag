package com.camarodiag.gen5.data.obd

import java.util.Locale

/** SAE J1979 non-CAN application records (ISO 15031-5:2006 clause 6).
 * Headerless responses are anonymous, never proof of physical ECU identity. */
internal object ObdLegacyRecords {
    fun parse(command: String, raw: String): List<List<Int>>? {
        val rows = raw.replace('>', '\n').lineSequence()
            .map { it.trim().uppercase(Locale.ROOT).filterNot(Char::isWhitespace) }
            .filter { it.isNotEmpty() && it != command && it != "SEARCHING..." }.toList()
        if (rows.isEmpty() || rows.any { !it.matches(Regex("(?:[0-9A-F]{2}){1,7}")) }) return null
        return rows.map { it.chunked(2).map { byte -> byte.toInt(16) } }
    }

    fun dtcs(command: String, raw: String, status: String): List<ObdDtc>? {
        val mode = command.toInt(16)
        require(mode in setOf(3, 7, 10))
        val rows = parse(command, raw) ?: return null
        // Fixed three pairs; optional legacy silence is not a positive zero.
        if (rows.any { it.size != 7 || it[0] != mode + 0x40 }) return null
        return rows.flatMap { row ->
            row.drop(1).chunked(2).mapNotNull { pair ->
                val word = (pair[0] shl 8) or pair[1]
                if (word == 0) null else {
                    val prefix = "PCBU"[word ushr 14]
                    val digit = (word ushr 12) and 3
                    ObdDtc("$prefix$digit%03X".format(Locale.ROOT, word and 0xFFF), status)
                }
            }
        }.distinctBy(ObdDtc::code)
    }

    fun infoPage(base: Int, raw: String): List<List<Int>>? {
        val rows = parse("09%02X".format(Locale.ROOT, base), raw) ?: return null
        return rows.takeIf { it.all { row ->
            row.size == 7 && row[0] == 0x49 && row[1] == base &&
                row[2] == 1 && (base != 0xE0 || row.last() and 1 == 0)
        } }
    }

    fun infoRecords(type: Int, raw: String): List<List<Int>>? {
        val rows = parse("09%02X".format(Locale.ROOT, type), raw) ?: return null
        return rows.takeIf { it.all { row ->
            row.size == (if (type in ObdJ1979ExtendedVehicleInfo.legacyCountTypes) 3 else 7) &&
                row[0] == 0x49 && row[1] == type && row[2] > 0
        } }
    }

    /** A single ordered set of five anonymous VIN fragments, corroborated
     * by its preceding count. Never concatenate duplicate or mixed sequences. */
    fun anonymousVin(count: List<List<Int>>, fragments: List<List<Int>>): String? {
        if (count != listOf(listOf(0x49, 1, 5)) || fragments.size != 5 ||
            fragments.withIndex().any { (index, row) ->
                row.size != 7 || row[0] != 0x49 || row[1] != 2 || row[2] != index + 1
            }) return null
        val data = fragments.flatMap { it.drop(3) }
        if (data.take(3) != listOf(0, 0, 0)) return null
        return data.drop(3).map(Int::toChar).joinToString("")
            .takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) }
    }
}
