package com.camarodiag.gen5.data.obd

import java.util.Locale

/**
 * ELM auto-formatted ISO-TP identity replies: total byte count followed by
 * numbered rows 0..F. The ECU payload is reconstructed only when every row
 * and the declared byte count are internally consistent.
 */
internal object ObdNumberedIdentityFrames {
    /**
     * A functional request may return several complete ECU records. Keep
     * each separate; a truncated second record must not validate the first
     * as the only observed identity.
     */
    fun parseAll(response: String): List<List<Int>>? {
        val lines = response.trim().removeSuffix(">").trim().uppercase(Locale.ROOT)
            .lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && it != "SEARCHING..." &&
                it !in setOf("0902", "0904", "0906", "090A") }.toList()
        if (lines.isEmpty()) return null
        val records = mutableListOf<List<Int>>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.matches(Regex("[0-9A-F]{3}"))) {
                val block = mutableListOf(line)
                index++
                while (index < lines.size && lines[index].matches(Regex("[0-9A-F]:.*"))) {
                    block += lines[index++]
                }
                records += parse(block.joinToString("\n")) ?: return null
            } else {
                // Headerless complete records may be mixed with numbered
                // responses. A physical header is not inferred here.
                val compact = line.filterNot(Char::isWhitespace)
                if (!compact.matches(Regex("(?:[0-9A-F]{2}){3,255}"))) {
                    // Retain support for a single compact numbered block.
                    return if (records.isEmpty() && index == 0) parse(lines.joinToString("\n"))?.let(::listOf)
                    else null
                }
                records += compact.chunked(2).map { it.toInt(16) }
                index++
            }
        }
        return records
    }

    fun parse(response: String): List<Int>? {
        val text = response.trim().removeSuffix(">").trim().uppercase(Locale.ROOT)
            .removePrefix("SEARCHING...").trim()
        val markers = Regex("""(?:^|\s)([0-9A-F]):""").findAll(text).toList()
        if (markers.isEmpty()) return null
        val header = text.substring(0, markers.first().range.first).trim()
        if (!header.matches(Regex("[0-9A-F]{3}"))) return null
        val expected = header.toInt(16)
        if (expected !in 3..255) return null
        val bytes = mutableListOf<Int>()
        for ((row, marker) in markers.withIndex()) {
            if (marker.groupValues[1].toInt(16) != row % 16) return null
            val end = markers.getOrNull(row + 1)?.range?.first ?: text.length
            val hex = text.substring(marker.range.last + 1, end).filterNot(Char::isWhitespace)
            val rowSize = minOf(if (row == 0) 6 else 7, expected - bytes.size)
            if (rowSize <= 0 || hex.length != 2 * rowSize ||
                hex.any { it !in "0123456789ABCDEF" }) return null
            bytes += hex.chunked(2).map { it.toInt(16) }
        }
        return bytes.takeIf { it.size == expected }
    }
}
