package com.camaradiag.gen5.data.obd

import java.util.Locale

/** SAE J1979 CAN Mode 06: support pages and complete 9-byte test records.
 * The functional ELM channel is anonymous, so never invent an ECU address. */
internal object ObdCanMonitorRecords {
    data class Support(val mids: Set<Int>, val more: Boolean)
    data class Result(val mid: Int, val tid: Int, val uasid: Int,
        val value: Int, val minimum: Int, val maximum: Int) {
        fun rawText(): String = "MID=%02X TID=%02X UASID=%02X RAW=%04X MIN_RAW=%04X MAX_RAW=%04X"
            .format(Locale.ROOT, mid, tid, uasid, value, minimum, maximum)
    }

    private fun messages(response: String, mid: Int): List<List<Int>>? {
        // Remove only the exact command echo, not a prefix of a response.
        val command = "06%02X".format(Locale.ROOT, mid)
        val clean = response.lineSequence().map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.filterNot(Char::isWhitespace) != command }.joinToString("\n")
        val records = ObdNumberedIdentityFrames.parseAll(clean) ?: return null
        return records.takeIf { it.isNotEmpty() && it.all { record ->
            record.firstOrNull() == 0x46
        } }
    }

    fun support(response: String, base: Int): Support? {
        if (base !in 0..0xE0 || base % 32 != 0) return null
        val records = messages(response, base) ?: return null
        if (records.any { it.size != 6 || it[1] != base }) return null
        val mids = linkedSetOf<Int>()
        for (r in records) for (bit in 0 until 31) {
            if ((r[2 + bit / 8] and (1 shl (7 - bit % 8))) != 0)
                mids += base + bit + 1
        }
        return Support(mids, records.any { it[5] and 1 != 0 })
    }

    fun results(response: String, mid: Int): List<Result>? {
        if (mid !in 1..255 || mid % 32 == 0) return null
        val messages = messages(response, mid) ?: return null
        val result = mutableListOf<Result>()
        for (message in messages) {
            val data = message.drop(1)
            if (data.isEmpty() || data.size % 9 != 0) return null
            for (r in data.chunked(9)) {
                if (r[0] != mid) return null
                fun word(i: Int) = (r[i] shl 8) or r[i + 1]
                result += Result(mid, r[1], r[2], word(3), word(5), word(7))
            }
        }
        return result
    }
}
