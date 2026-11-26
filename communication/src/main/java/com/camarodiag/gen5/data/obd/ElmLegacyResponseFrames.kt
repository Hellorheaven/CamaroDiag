package com.camarodiag.gen5.data.obd

import java.util.Locale

/** ATH1 legacy response records. Never fall back to anonymous application
 * bytes when a recipe requires ECU source attribution. */
internal object ElmLegacyResponseFrames {
    data class Frame(val source: Int, val rawHex: String, val payload: List<Int>)

    fun checksum(protocol: Int, bytes: List<Int>): Int {
        require(protocol in 1..5 && bytes.all { it in 0..255 })
        if (protocol >= 3) return bytes.sum() and 255
        var crc = 255
        for (byte in bytes) {
            crc = crc xor byte
            repeat(8) { crc = ((crc shl 1) xor (if (crc and 128 != 0) 0x1D else 0)) and 255 }
        }
        return crc xor 255
    }

    /** Mutation recipes use an explicitly fixed route, never ELM auto mode. */
    fun fixedProtocol(raw: String): Int? {
        val values = raw.replace('>', '\n').lineSequence()
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && it != "ATDPN" }.toList()
        return values.singleOrNull()?.takeIf { it.matches(Regex("[1-5]")) }?.toInt()
    }

    fun decode(protocol: Int, command: String, raw: String): List<Frame> {
        require(protocol in 1..5 && raw.length <= 32768)
        val rows = raw.replace('>', '\n').lineSequence()
            .map { it.uppercase(Locale.ROOT).filterNot(Char::isWhitespace) }
            .filter { it.isNotEmpty() && it != command && it != "SEARCHING..." }.toList()
        if (rows.isEmpty() || rows == listOf("NODATA")) return emptyList()
        require(rows.size <= 128)
        return rows.map { line ->
            require(line.matches(Regex("(?:[0-9A-F]{2}){5,11}"))) {
                "Malformed or unattributed legacy record."
            }
            val bytes = line.chunked(2).map { it.toInt(16) }
            val source = bytes[2]
            require(source !in setOf(0, 0xF1, 0xFF))
            if (protocol >= 4) {
                require(bytes[0] and 0xC0 == 0x80 && bytes[1] == 0xF1 &&
                    bytes[0] and 0x3F == bytes.size - 4)
            } else {
                require(bytes[0] == (if (protocol == 1) 0x41 else 0x48) && bytes[1] == 0x6B)
            }
            require(checksum(protocol, bytes.dropLast(1)) == bytes.last()) { "Legacy checksum mismatch." }
            Frame(source, line, bytes.subList(3, bytes.lastIndex).toList())
        }
    }

    fun hex(value: Int) = "%02X".format(Locale.ROOT, value)
    fun dtcs(mode: Int, frames: List<List<Int>>): List<String>? {
        require(mode in setOf(3, 7, 10))
        if (frames.isEmpty() || frames.any { it.size != 7 || it[0] != mode + 0x40 }) return null
        return frames.flatMap { row ->
            row.drop(1).chunked(2).mapNotNull { pair ->
                val word = (pair[0] shl 8) or pair[1]
                if (word == 0) null else "PCBU"[word ushr 14].toString() +
                    ((word ushr 12) and 3) + "%03X".format(Locale.ROOT, word and 0xFFF)
            }
        }.distinct()
    }
}
