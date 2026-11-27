package com.camarodiag.gen5.data.obd

import java.util.Locale

/** SAE J1979 CAN Mode 06: support pages and complete 9-byte test records.
 * The functional ELM channel is anonymous, so never invent an ECU address. */
internal object ObdCanMonitorRecords {
    // ISO 15031-5:2006 Annex E, explicitly supported subset.
    // No legacy or OEM-specific scaling; no monitor verdict inferred.
    private data class Scale(val unit: String, val factor: Double, val signed: Boolean)
    private val scales = mapOf(
        0x0A to Scale("mV", 0.122, false),
        0x0B to Scale("V", 0.001, false),
        0x0D to Scale("mA", 0.00390625, false),
        0x0E to Scale("A", 0.001, false),
        0x0F to Scale("A", 0.01, false),
        0x10 to Scale("ms", 1.0, false),
        0x24 to Scale("count", 1.0, false),
        0x25 to Scale("km", 1.0, false),
        0x2F to Scale("%", 0.01, false),
        0x31 to Scale("L", 0.001, false),
        0x84 to Scale("1", 0.001, true),
        0x85 to Scale("1", 0.0000305, true),
        0x86 to Scale("1", 0.000305, true),
        0x8E to Scale("A", 0.001, true),
        0x90 to Scale("ms", 1.0, true),
        0x96 to Scale("degC", 0.1, true),
    )
    data class Scaled(val unit: String, val value: Double, val minimum: Double,
        val maximum: Double, val numericRelation: String)
    data class Support(val mids: Set<Int>, val more: Boolean)
    data class Result(val mid: Int, val tid: Int, val uasid: Int,
        val value: Int, val minimum: Int, val maximum: Int) {
        fun scaled(): Scaled? {
            val scale = scales[uasid] ?: return null
            if (listOf(value, minimum, maximum).any { it !in 0..65535 }) return null
            fun decode(raw: Int): Double =
                (if (scale.signed && raw >= 32768) raw - 65536 else raw) * scale.factor
            val observed = decode(value)
            val low = decode(minimum)
            val high = decode(maximum)
            val relation = when {
                low > high -> "INVERTED_LIMITS"
                observed < low -> "BELOW"
                observed > high -> "ABOVE"
                else -> "WITHIN"
            }
            return Scaled(scale.unit, observed, low, high, relation)
        }
        fun rawText(): String {
            val encoded = "MID=%02X TID=%02X UASID=%02X RAW=%04X MIN_RAW=%04X MAX_RAW=%04X"
                .format(Locale.ROOT, mid, tid, uasid, value, minimum, maximum)
            val decoded = scaled() ?: return encoded
            return encoded + " VALUE=%s MIN=%s MAX=%s UNIT=%s"
                .format(Locale.ROOT, decoded.value, decoded.minimum, decoded.maximum, decoded.unit)
        }
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
        // E0 is the last representable page: continuation would require MID 0x100.
        if (base == 0xE0 && records.any { it[5] and 1 != 0 }) return null
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
