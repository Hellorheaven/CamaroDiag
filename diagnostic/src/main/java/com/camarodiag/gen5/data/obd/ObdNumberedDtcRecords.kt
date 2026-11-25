package com.camarodiag.gen5.data.obd

import java.util.Locale

/** Headerless ELM auto-formatted CAN DTC records. Each numbered block has
 * its own byte count. Never join a fragment from another numbered response. */
internal object ObdNumberedDtcRecords {
    private val length = Regex("[0-9A-F]{3}")
    private val row = Regex("([0-9A-F]):\\s*([0-9A-F\\s]+)")

    fun parse(response: String): List<List<Int>>? {
        val lines = response.lineSequence()
            .map { it.trim().removeSuffix(">").trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && it != "SEARCHING..." && it !in setOf("03", "07", "0A") }
            .toList()
        val records = mutableListOf<List<Int>>()
        var index = 0
        while (index < lines.size) {
            val header = lines[index++]
            if (!length.matches(header)) return null
            val expected = header.toInt(16)
            if (expected !in 2..512) return null
            val bytes = mutableListOf<Int>()
            var sequence = 0
            while (bytes.size < expected) {
                val match = lines.getOrNull(index++)?.let(row::matchEntire) ?: return null
                if (match.groupValues[1].toInt(16) != sequence % 16) return null
                val hex = match.groupValues[2].filterNot(Char::isWhitespace)
                val count = minOf(if (sequence == 0) 6 else 7, expected - bytes.size)
                if (hex.length != count * 2) return null
                bytes += hex.chunked(2).map { it.toInt(16) }
                sequence++
            }
            records += bytes
        }
        return records.takeIf { it.isNotEmpty() }
    }

    fun validPositive(bytes: List<Int>, positiveService: Int): Boolean {
        if (bytes.size < 2 || bytes[0] != positiveService) return false
        val count = bytes[1]
        return bytes.size == 2 + count * 2 &&
            bytes.drop(2).chunked(2).none { it.all { byte -> byte == 0 } }
    }
}
