package com.camarodiag.gen5.data.obd

/** Classic Mode 09, SAE J1979DA October 2011 G8–G15.
 * Unsigned counters and text are not proof of successful monitoring, identity
 * authenticity or legal emissions compliance. The caller binds the source. */
object ObdJ1979ExtendedVehicleInfo {
    data class Monitor(val monitor: String, val numerator: Int, val denominator: Int) {
        val ratio: Double? get() = if (denominator == 0) null else numerator.toDouble()/denominator
    }
    data class Result(
        val infoType: Int,
        val values: List<String> = emptyList(),
        val generalDenominator: Int? = null,
        val ignitionCycles: Int? = null,
        val counterCount: Int? = null,
        val monitors: List<Monitor> = emptyList()
    )
    private val spark = listOf("catalyst-bank-1", "catalyst-bank-2", "oxygen-sensor-bank-1", "oxygen-sensor-bank-2", "egr-vvt", "secondary-air", "evap", "secondary-oxygen-bank-1", "secondary-oxygen-bank-2")
    private val diesel = listOf("nmhc-catalyst", "nox-scr", "nox-adsorber", "particulate-filter", "exhaust-gas-sensor", "egr-vvt", "boost-pressure", "fuel-system")
    val extendedTypes = setOf(8, 11, 13, 15)
    val legacyCountTypes = setOf(1, 3, 5, 7, 9, 12, 14)
    fun countType(type: Int): Int = if (type == 11) 7 else type-1
    private fun words(type: Int, bytes: List<Int>): Result? {
        if (type == 8 && bytes.size !in setOf(32,40) ||
            type == 11 && bytes.size !in setOf(32,36) ||
            type !in setOf(8,11)) return null
        val values = bytes.chunked(2).map { it[0]*256+it[1] }
        val names = if (type == 8) spark else diesel
        val monitors = values.drop(2).chunked(2).mapIndexed { index, pair ->
            Monitor(names[index], pair[0], pair[1])
        }
        return Result(type, generalDenominator=values[0], ignitionCycles=values[1],
            counterCount=values.size, monitors=monitors)
    }
    private fun ascii(bytes: List<Int>): String? =
        bytes.takeIf { it.isNotEmpty() && it.all { b -> b in 32..126 } }
            ?.map(Int::toChar)?.joinToString("")
    private fun text(type: Int, bytes: List<Int>): Result? {
        val value = when (type) {
            10 -> {
                if (bytes.size != 20 || bytes[4] != 45) return null
                val first = ascii(bytes.take(4).dropLastWhile { it == 0 }) ?: return null
                val last = ascii(bytes.drop(5).dropLastWhile { it == 0 }) ?: return null
                "$first-$last"
            }
            13,15 -> {
                if (bytes.size != 17) return null
                ascii(bytes.dropWhile { it == 0 }) ?: return null
            }
            else -> return null
        }
        return Result(type,values=listOf(value))
    }
    fun can(type: Int, bytes: List<Int>): Result? {
        if (bytes.size < 3 || bytes.any { it !in 0..255 } ||
            bytes.take(2) != listOf(0x49,type)) return null
        return when (type) {
            8,11 -> if (bytes.size == 3+2*bytes[2]) words(type,bytes.drop(3)) else null
            13,15 -> if (bytes[2] == 1) text(type,bytes.drop(3)) else null
            else -> null
        }
    }
    /** Historical CJ/1 RAW status remains unchanged. Derive only from a
     * correlated, bounded response, not from a producer's interpreted fields. */
    fun tryRaw(requestHex:String,responseHex:String?,status:String):Result? {
        if(status!="RAW" || !requestHex.matches(Regex("09[0-9A-F]{2}"))) return null
        val type=requestHex.takeLast(2).toInt(16)
        if(type !in extendedTypes || responseHex==null ||
            responseHex.length !in 6..86 || responseHex.length%2!=0 ||
            !responseHex.matches(Regex("[0-9A-F]+"))) return null
        return can(type,responseHex.chunked(2).map { it.toInt(16) })
    }
    fun legacy(type: Int, count: Int, rows: List<List<Int>>): Result? {
        if (count !in 1..10 || rows.size != count ||
            rows.withIndex().any { (index,row) ->
                row.size != 7 || row.any { it !in 0..255 } ||
                    row.take(3) != listOf(0x49,type,index+1)
            }) return null
        val bytes = rows.flatMap { it.drop(3) }
        return when (type) {
            8,11 -> words(type,bytes)
            10 -> if (count == 5) text(type,bytes) else null
            13,15 -> if (count == 5 && bytes.take(3) == listOf(0,0,0))
                text(type,bytes.drop(3)) else null
            else -> null
        }
    }
}
