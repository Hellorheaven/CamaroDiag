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

    private val physicalHeader = Regex("^(7E[8-F]|18DAF1[0-9A-F]{2})(?=\\s|[0-9A-F]{4,})")
    private val nextHeader = Regex("\\s+(?=(?:7E[8-F]|18DAF1[0-9A-F]{2})(?:\\s|[0-9A-F]{4,}))")

    private fun physicalLines(response: String): List<String> = response.uppercase(Locale.ROOT)
        .lineSequence().flatMap { it.split(nextHeader).asSequence() }
        .map { it.trim().removeSuffix(">").trim() }
        .filter { it.isNotEmpty() && it != "SEARCHING..." && it !in setOf("03", "07", "0A") }
        .toList()

    private data class PhysicalFrame(val responder: String, val bytes: List<Int>)

    private fun frame(line: String, positiveService: Int): PhysicalFrame? {
        val match = physicalHeader.find(line) ?: return null
        val tokens = line.drop(match.value.length).trim().split(Regex("\\s+"))
        val explicitNibbleDlc = tokens.size > 1 && tokens.first().matches(Regex("[0-8]"))
        val compact = (if (explicitNibbleDlc) "0" else "") + tokens.joinToString("")
        if (!compact.matches(Regex("(?:[0-9A-F]{2}){2,9}"))) return null
        var data = compact.chunked(2).map { it.toInt(16) }
        // A one-nibble DLC is explicit. For a byte DLC, strip only when the
        // declared length matches and the next byte is a plausible ISO-TP PCI.
        if (explicitNibbleDlc) {
            if (data.first() != data.size - 1) return null
            // ELM also prints a one-nibble SF length followed by its payload.
            if (data[1] != positiveService && data[1] != 0x7F) data = data.drop(1)
        } else if (data.size >= 3 && data.first() == data.size - 1 &&
            data.first() in 1..8 &&
            ((data[1] ushr 4) in 1..2 || (data[1] in 1..7 &&
                data[2] in setOf(positiveService, 0x7F)))) {
            data = data.drop(1)
        }
        if (data.size !in 2..8) return null
        return PhysicalFrame(match.groupValues[1], data)
    }

    fun hasMultiFrame(response: String, positiveService: Int): Boolean =
        physicalLines(response).any { line ->
            val bytes = frame(line, positiveService)?.bytes
            bytes != null && (bytes.first() ushr 4) in 1..2
        }

    /** Reassemble ISO-TP independently per physical CAN responder. No frame
     * from another ECU may complete a truncated or out-of-sequence record.
     * Reject ambiguous/incomplete multi-frame reports instead of claiming a
     * successful empty DTC service. */
    fun parsePhysical(response: String, positiveService: Int): List<List<Int>>? {
        data class Pending(val total: Int, val bytes: MutableList<Int>, var sequence: Int = 1)
        val pending = mutableMapOf<String, Pending>()
        val complete = mutableMapOf<String, List<Int>>()
        for (line in physicalLines(response)) {
            val (address, bytes) = frame(line, positiveService) ?: return null
            val pci = bytes.first()
            when (pci ushr 4) {
                0 -> {
                    if (address in pending) return null
                    val size = pci and 15
                    if (size !in 1..7 || bytes.size < size + 1) return null
                    val payload = bytes.drop(1).take(size)
                    if (complete[address]?.let { it != payload } == true) return null
                    complete[address] = payload
                }
                1 -> {
                    if (address in pending || bytes.size != 8) return null
                    val total = ((pci and 15) shl 8) or bytes[1]
                    if (total !in 8..512) return null
                    pending[address] = Pending(total, bytes.drop(2).toMutableList())
                }
                2 -> {
                    val record = pending[address] ?: return null
                    if ((pci and 15) != record.sequence) return null
                    val count = minOf(7, record.total - record.bytes.size)
                    if (bytes.size - 1 < count) return null
                    record.bytes += bytes.drop(1).take(count)
                    record.sequence = (record.sequence + 1) and 15
                    if (record.bytes.size == record.total) {
                        val payload = record.bytes.toList()
                        if (complete[address]?.let { it != payload } == true) return null
                        complete[address] = payload
                        pending.remove(address)
                    }
                }
                else -> return null
            }
        }
        return complete.values.toList().takeIf { pending.isEmpty() && it.isNotEmpty() }
    }

    fun validPositive(bytes: List<Int>, positiveService: Int): Boolean {
        if (bytes.size < 2 || bytes[0] != positiveService) return false
        val count = bytes[1]
        return bytes.size == 2 + count * 2 &&
            bytes.drop(2).chunked(2).none { it.all { byte -> byte == 0 } }
    }
}
