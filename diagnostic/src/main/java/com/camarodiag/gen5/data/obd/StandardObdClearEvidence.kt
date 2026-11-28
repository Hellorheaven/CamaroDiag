package com.camarodiag.gen5.data.obd

import java.util.Locale

/** Mode 04 positive ACK is one byte (44). A functional ACK is not
 * proof that every ECU cleared its records, and never replaces a reread. */
object StandardObdClearEvidence {
    private val responder = Regex("^(7E[8-F]|18DAF1[0-9A-F]{2})")
    private val bytes = Regex("(?:[0-9A-F]{2})+")

    private fun isAck(line: String): Boolean {
        val compact = line.filterNot(Char::isWhitespace)
        if (compact == "44") return true
        val address = responder.find(compact)?.value ?: return false
        val body = compact.drop(address.length)
        if (body == "44" || body == "0144") return true

        // Optional explicit DLC on header-bearing responses. The DLC
        // must exactly match the remaining bytes, including PCI.
        val tokens = line.trim().split(Regex("\\s+"))
        val frame = if (tokens.firstOrNull() == address &&
            tokens.size >= 4 && tokens[1].length in 1..2 &&
            tokens[1].toIntOrNull(16) == tokens.size - 2 &&
            tokens.drop(2).all { it.length == 2 && bytes.matches(it) } &&
            tokens[2] == "01") {
            tokens.drop(2).joinToString("")
        } else body
        if (!bytes.matches(frame) || !frame.startsWith("0144")) return false
        // A normal unpadded single frame or a full, zero-padded classic CAN
        // frame is unambiguous. Reject arbitrary trailing diagnostic bytes.
        return frame == "0144" ||
            (frame.length == 16 && frame.drop(4).all { it == '0' })
    }

    fun acknowledged(raw: String): Boolean {
        val lines = raw.uppercase(Locale.ROOT).replace("SEARCHING...", "")
            .replace('>', '\n').lines()
        var seen = false
        for (line in lines) {
            val value = line.filterNot(Char::isWhitespace)
            if (value.isEmpty() || value == "04") continue
            if (!isAck(line)) return false
            seen = true
        }
        return seen
    }
}
