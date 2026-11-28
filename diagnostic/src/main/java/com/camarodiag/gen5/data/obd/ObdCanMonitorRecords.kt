package com.camarodiag.gen5.data.obd

import java.util.Locale

/** SAE J1979 CAN Mode 06: support pages and complete 9-byte test records.
 * The functional ELM channel is anonymous, so never invent an ECU address. */
object ObdCanMonitorRecords {
    // ISO 15031-5:2006 Annex E, explicitly supported subset.
    // No legacy or OEM-specific scaling; no monitor verdict inferred.
    private data class Scale(val unit: String, val factor: Double, val signed: Boolean, val offset: Double)
    private val scales = mapOf(
        0x01 to Scale("1", 1.0, false, 0.0),
        0x02 to Scale("1", 0.1, false, 0.0),
        0x03 to Scale("1", 0.01, false, 0.0),
        0x04 to Scale("1", 0.001, false, 0.0),
        0x05 to Scale("1", 0.0000305, false, 0.0),
        0x06 to Scale("1", 0.000305, false, 0.0),
        0x07 to Scale("rpm", 0.25, false, 0.0),
        0x08 to Scale("km/h", 0.01, false, 0.0),
        0x09 to Scale("km/h", 1.0, false, 0.0),
        0x0A to Scale("mV", 0.122, false, 0.0),
        0x0B to Scale("V", 0.001, false, 0.0),
        0x0C to Scale("V", 0.01, false, 0.0),
        0x0D to Scale("mA", 0.00390625, false, 0.0),
        0x0E to Scale("A", 0.001, false, 0.0),
        0x0F to Scale("A", 0.01, false, 0.0),
        0x10 to Scale("ms", 1.0, false, 0.0),
        0x11 to Scale("ms", 100.0, false, 0.0),
        0x12 to Scale("s", 1.0, false, 0.0),
        0x13 to Scale("mOhm", 1.0, false, 0.0),
        0x14 to Scale("ohm", 1.0, false, 0.0),
        0x15 to Scale("kOhm", 1.0, false, 0.0),
        0x16 to Scale("degC", 0.1, false, -40.0),
        0x17 to Scale("kPa", 0.01, false, 0.0),
        0x18 to Scale("kPa", 0.0117, false, 0.0),
        0x19 to Scale("kPa", 0.079, false, 0.0),
        0x1A to Scale("kPa", 1.0, false, 0.0),
        0x1B to Scale("kPa", 10.0, false, 0.0),
        0x1C to Scale("deg", 0.01, false, 0.0),
        0x1D to Scale("deg", 0.5, false, 0.0),
        0x1E to Scale("ratio", 0.0000305, false, 0.0),
        0x1F to Scale("ratio", 0.05, false, 0.0),
        0x20 to Scale("ratio", 0.00390625, false, 0.0),
        0x21 to Scale("mHz", 1.0, false, 0.0),
        0x22 to Scale("Hz", 1.0, false, 0.0),
        0x23 to Scale("kHz", 1.0, false, 0.0),
        0x24 to Scale("count", 1.0, false, 0.0),
        0x25 to Scale("km", 1.0, false, 0.0),
        0x26 to Scale("mV/ms", 0.1, false, 0.0),
        0x27 to Scale("g/s", 0.01, false, 0.0),
        0x28 to Scale("g/s", 1.0, false, 0.0),
        0x29 to Scale("Pa/s", 0.25, false, 0.0),
        0x2A to Scale("kg/h", 0.001, false, 0.0),
        0x2B to Scale("count", 1.0, false, 0.0),
        0x2C to Scale("g/cyl", 0.01, false, 0.0),
        0x2D to Scale("mg/stroke", 0.01, false, 0.0),
        0x2E to Scale("bool", 1.0, false, 0.0),
        0x2F to Scale("%", 0.01, false, 0.0),
        0x30 to Scale("%", 0.001526, false, 0.0),
        0x31 to Scale("L", 0.001, false, 0.0),
        0x32 to Scale("in", 0.0000305, false, 0.0),
        0x33 to Scale("ratio", 0.00024414, false, 0.0),
        0x34 to Scale("min", 1.0, false, 0.0),
        0x35 to Scale("ms", 10.0, false, 0.0),
        0x36 to Scale("g", 0.01, false, 0.0),
        0x37 to Scale("g", 0.1, false, 0.0),
        0x38 to Scale("g", 1.0, false, 0.0),
        0x39 to Scale("%", 0.01, false, -327.68),
        0x81 to Scale("1", 1.0, true, 0.0),
        0x82 to Scale("1", 0.1, true, 0.0),
        0x83 to Scale("1", 0.01, true, 0.0),
        0x84 to Scale("1", 0.001, true, 0.0),
        0x85 to Scale("1", 0.0000305, true, 0.0),
        0x86 to Scale("1", 0.000305, true, 0.0),
        0x8A to Scale("mV", 0.122, true, 0.0),
        0x8B to Scale("V", 0.001, true, 0.0),
        0x8C to Scale("V", 0.01, true, 0.0),
        0x8D to Scale("mA", 0.00390625, true, 0.0),
        0x8E to Scale("A", 0.001, true, 0.0),
        0x90 to Scale("ms", 1.0, true, 0.0),
        0x96 to Scale("degC", 0.1, true, 0.0),
        0x9C to Scale("deg", 0.01, true, 0.0),
        0x9D to Scale("deg", 0.5, true, 0.0),
        0xA8 to Scale("g/s", 1.0, true, 0.0),
        0xA9 to Scale("Pa/s", 0.25, true, 0.0),
        0xAF to Scale("%", 0.01, true, 0.0),
        0xB0 to Scale("%", 0.003052, true, 0.0),
        0xB1 to Scale("mV/s", 2.0, true, 0.0),
        0xFD to Scale("kPa", 0.001, true, 0.0),
        0xFE to Scale("Pa", 0.25, true, 0.0),
    )
    data class Scaled(val unit: String, val value: Double, val minimum: Double,
        val maximum: Double, val numericRelation: String)
    data class Support(val mids: Set<Int>, val more: Boolean)
    data class Result(val mid: Int, val tid: Int, val uasid: Int,
        val value: Int, val minimum: Int, val maximum: Int) {
        fun scaled(): Scaled? {
            val scale = scales[uasid] ?: return null
            if (listOf(value, minimum, maximum).any { it !in 0..65535 }) return null
            if (uasid == 0x2E && listOf(value, minimum, maximum).any { it !in 0..1 }) return null
            fun decode(raw: Int): Double =
                (if (scale.signed && raw >= 32768) raw - 65536 else raw) * scale.factor + scale.offset
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

    data class EngineeringRecord(val requestHex:String,val recordIndex:Int,val result:Result)

    /** Original correlated CAN payload, not an adapter-formatted legacy reply. */
    fun project(requestHex:String,responseHex:String?,status:String):List<EngineeringRecord> {
        if(status!="PASSED"||!requestHex.matches(Regex("06[0-9A-Fa-f]{2}"))||
            responseHex==null||responseHex.length>8190||responseHex.length%2!=0||
            !responseHex.matches(Regex("[0-9A-Fa-f]+")))return emptyList()
        val mid=requestHex.takeLast(2).toInt(16)
        if(mid%32==0)return emptyList()
        val bytes=responseHex.chunked(2).map { it.toInt(16) }
        if(bytes.size<10||bytes[0]!=0x46||(bytes.size-1)%9!=0)return emptyList()
        val data=bytes.drop(1).chunked(9)
        if(data.any { it[0]!=mid })return emptyList()
        return data.mapIndexed { index,row->
            fun word(offset:Int)=(row[offset] shl 8) or row[offset+1]
            EngineeringRecord(requestHex.uppercase(Locale.ROOT),index,
                Result(mid,row[1],row[2],word(3),word(5),word(7)))
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
