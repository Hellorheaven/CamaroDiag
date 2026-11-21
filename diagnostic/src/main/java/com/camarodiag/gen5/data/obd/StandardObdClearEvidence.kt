package com.camarodiag.gen5.data.obd

import java.util.Locale

/** A positive service 04 ACK is exactly 44, not a hex substring.
 * Standard ELM formatting may preserve CAN headers and ISO-TP PCI.
 * A negative or ambiguous reply never authorizes clearing UI records.
 */
object StandardObdClearEvidence {
    fun acknowledged(raw: String): Boolean {
        val lines = raw.uppercase(Locale.ROOT)
            .replace("SEARCHING...", "")
            .replace('>', '\n').lines()
        var seen = false
        for (line in lines) {
            val value = line.filterNot(Char::isWhitespace)
            if (value.isEmpty() || value == "04") continue
            // Headerless ELM output after CAN automatic formatting.
            // Headerful output may contain the one-byte ISO-TP length.
            val body = when {
                value == "44" -> value
                value.matches(Regex("7E[89A-F](?:01)?44")) -> "44"
                value.matches(Regex("18DAF1[0-9A-F]{2}(?:01)?44")) -> "44"
                else -> return false
            }
            if (body == "44") seen = true
        }
        return seen
    }
}
