package com.camarodiag.gen5.data.obd

import java.util.Locale

/**
 * ELM auto-formatted ISO-TP identity replies: total byte count followed by
 * numbered rows 0..F. The ECU payload is reconstructed only when every row
 * and the declared byte count are internally consistent.
 */
internal object ObdNumberedIdentityFrames {
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
