package com.camarodiag.gen5.data.obd

/** Fresh headerless Mode 01 response evidence for a stationary, stopped
 * vehicle. No missing, conflicting, malformed or negative reply is zero. */
object ObdStationaryEvidence {
    fun provesZero(pid: Int, raw: String): Boolean {
        val size = when (pid) {
            0x0C -> 2
            0x0D -> 1
            else -> return false
        }
        val command = "01%02X".format(java.util.Locale.ROOT, pid)
        val lines = raw.replace('>', '\n').lineSequence()
            .map { it.trim().uppercase(java.util.Locale.ROOT) }
            .filter { it.isNotEmpty() && it != "SEARCHING..." &&
                it.filterNot(Char::isWhitespace) != command }.toList()
        val records = ObdNumberedIdentityFrames.parseAll(lines.joinToString("\n"))
            ?: return false
        return records.isNotEmpty() && records.all {
            it.size == size + 2 && it[0] == 0x41 && it[1] == pid &&
                it.drop(2).all { value -> value == 0 }
        }
    }
}
