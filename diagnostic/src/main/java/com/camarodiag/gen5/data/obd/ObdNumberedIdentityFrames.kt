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
    private val canHeader = Regex("^(7E[8-F]|18DAF1[0-9A-F]{2})(?=\\s|[0-9A-F]{4,})")

    private fun physicalLines(response: String): List<String> =
        response.uppercase(Locale.ROOT).lineSequence().flatMap { line ->
            line.split(Regex("\\s+(?=(?:7E[8-F]|18DAF1[0-9A-F]{2})(?:\\s|[0-9A-F]{4,}))")).asSequence()
        }.map { it.trim().removeSuffix(">").trim() }.filter { it.isNotEmpty() }.toList()

    fun hasPhysicalHeaders(response: String): Boolean = physicalLines(response).any { canHeader.containsMatchIn(it) }

    /** Reassemble classic CAN ISO-TP independently by actual responder.
     * Interleaved CFs are never paired using their arrival order alone. */
    data class AddressedRecord(val responder: String, val bytes: List<Int>)

    fun parsePhysical(response: String): List<List<Int>>? =
        parseAddressedPhysical(response)?.map { it.bytes }

    fun parseAddressedPhysical(response: String): List<AddressedRecord>? {
        data class Pending(val total: Int, val bytes: MutableList<Int>, var sequence: Int = 1)
        val pending = mutableMapOf<String, Pending>()
        val completed = mutableListOf<AddressedRecord>()
        var seen = false
        for (line in physicalLines(response)) {
            if (line in setOf("SEARCHING...", "0902", "0904", "0906", "090A")) continue
            val match = canHeader.find(line) ?: return null
            seen = true
            val address = match.groupValues[1]
            val body = line.drop(match.value.length).trim().split(Regex("\\s+"))
            val compact = if (body.firstOrNull()?.matches(Regex("[0-8]")) == true)
                "0" + body.joinToString("") else body.joinToString("")
            if (!compact.matches(Regex("(?:[0-9A-F]{2}){2,9}"))) return null
            var frame = compact.chunked(2).map { it.toInt(16) }
            // Explicit DLC (including a one-nibble DLC) is accepted only
            // when it describes all following bytes and the PCI is plausible.
            if (frame.size >= 3 && frame[0] == frame.size - 1 &&
                frame[1] ushr 4 in 0..2 && (frame.size == 9 ||
                    frame[1] ushr 4 != 0 || frame[2] in setOf(0x41, 0x42, 0x45, 0x46, 0x49, 0x7F))) frame = frame.drop(1)
            if (frame.size !in 2..8) return null
            when (frame[0] ushr 4) {
                0 -> {
                    if (address in pending) return null
                    val length = frame[0] and 15
                    if (length !in 1..7 || frame.size < length + 1) return null
                    completed += AddressedRecord(address, frame.drop(1).take(length))
                }
                1 -> {
                    if (address in pending || frame.size != 8) return null
                    val length = ((frame[0] and 15) shl 8) or frame[1]
                    if (length !in 8..1024) return null
                    pending[address] = Pending(length, frame.drop(2).toMutableList())
                }
                2 -> {
                    val record = pending[address] ?: return null
                    if ((frame[0] and 15) != record.sequence) return null
                    val remaining = record.total - record.bytes.size
                    val take = minOf(7, remaining)
                    if (frame.size - 1 < take) return null
                    record.bytes += frame.drop(1).take(take)
                    record.sequence = (record.sequence + 1) and 15
                    if (record.bytes.size == record.total) {
                        completed += AddressedRecord(address, record.bytes.toList())
                        pending.remove(address)
                    }
                }
                else -> return null
            }
        }
        return completed.takeIf { seen && pending.isEmpty() && it.isNotEmpty() }
    }

    /** A segmented physical response must not fall back to individual lines.
     * Check PCI after an optional DLC, including compact ELM headers. */
    fun hasSegmentedPhysical(response: String): Boolean = physicalLines(response).any { line ->
        val match = canHeader.find(line) ?: return@any false
        val body = line.drop(match.value.length).trim().split(Regex("\\s+"))
        val compact = if (body.firstOrNull()?.matches(Regex("[0-8]")) == true)
            "0" + body.joinToString("") else body.joinToString("")
        if (!compact.matches(Regex("(?:[0-9A-F]{2}){2,9}"))) return@any false
        val bytes = compact.chunked(2).map { it.toInt(16) }
        bytes[0] ushr 4 in 1..2 ||
            (bytes.size >= 3 && bytes[0] == bytes.size - 1 && bytes[1] ushr 4 in 1..2)
    }

    fun parseAll(response: String): List<List<Int>>? {
        if (hasPhysicalHeaders(response)) return parsePhysical(response)
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
