package com.camarodiag.gen5.data.obd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.Locale

data class ObdbEcuDefinition(
    val headerHex: String,
    val responseHex: String?,
    val type: String
) {
    val requestCanId: Int? = headerHex.toCan11BitId()
    val responseCanId: Int? = responseHex?.toCan11BitId()
}

data class ObdbYearFilter(
    val from: Int? = null,
    val to: Int? = null,
    val years: Set<Int> = emptySet()
) {
    fun matches(year: Int): Boolean {
        val hasRange = from != null || to != null
        val inRange = hasRange && (from == null || year >= from) && (to == null || year <= to)
        return if (years.isEmpty()) !hasRange || inRange else inRange || year in years
    }
}

data class ObdbSignalFormat(
    val bitIndex: Int = 0,
    val bitLength: Int,
    val leastSignificantByteFirst: Boolean = false,
    val signed: Boolean = false,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val add: Double = 0.0,
    val multiply: Double = 1.0,
    val divide: Double = 1.0,
    val unit: String,
    val nullMinimum: Double? = null,
    val nullMaximum: Double? = null,
    val optimalMinimum: Double? = null,
    val optimalMaximum: Double? = null,
    val optimalValue: Double? = null,
    val valueMap: Map<String, String> = emptyMap()
) {
    init {
        require(bitIndex >= 0)
        require(bitLength in 1..256)
        require(divide != 0.0)
    }

    fun decode(payload: ByteArray): ObdbDecodedValue {
        require(payload.isNotEmpty()) { "Empty UDS DID payload." }
        val ordered = if (leastSignificantByteFirst) payload.reversedArray() else payload
        val bits = BigInteger(1, ordered)
        val totalBits = ordered.size * 8
        require(bitIndex + bitLength <= totalBits) {
            "Signal needs ${bitIndex + bitLength} bits but the ECU returned only $totalBits bits."
        }
        val shift = totalBits - bitIndex - bitLength
        val mask = BigInteger.ONE.shiftLeft(bitLength).subtract(BigInteger.ONE)
        val unsigned = bits.shiftRight(shift).and(mask)
        val raw = if (signed && unsigned.testBit(bitLength - 1)) {
            unsigned.subtract(BigInteger.ONE.shiftLeft(bitLength))
        } else unsigned
        val rawDouble = raw.toDouble()
        if (nullMinimum?.let { rawDouble <= it } == true ||
            nullMaximum?.let { rawDouble >= it } == true) {
            return ObdbDecodedValue(raw.toString(), null, unit, null, true)
        }
        val scaled = rawDouble * multiply / divide + add
        val bounded = minimum?.let { maxOf(scaled, it) } ?: scaled
        val clamped = maximum?.let { minOf(bounded, it) } ?: bounded
        val mapped = valueMap[raw.toString()]
        return ObdbDecodedValue(raw.toString(), clamped, unit, mapped, false)
    }
}

data class ObdbDecodedValue(
    val raw: String,
    val value: Double?,
    val unit: String,
    val mappedLabel: String?,
    val unavailable: Boolean
)

data class ObdbCamaroSignal(
    val id: String,
    val name: String,
    val path: String,
    val service: Int,
    val identifierHex: String,
    val requestHeaderHex: String,
    val responseHeaderHex: String?,
    val extendedAddressHex: String?,
    val canPriorityHex: String?,
    val testerAddressHex: String?,
    val timeoutHex: String?,
    val diagnosticLevelIn: String?,
    val diagnosticLevelOut: String?,
    val protocol: String?,
    val requestCanId: Int?,
    val responseCanId: Int?,
    val frequencySeconds: Double,
    val flowControlRequired: Boolean,
    val debugSignal: Boolean,
    val hidden: Boolean,
    val description: String?,
    val filter: ObdbYearFilter?,
    val debugFilter: ObdbYearFilter?,
    val format: ObdbSignalFormat,
    val suggestedMetric: String?
) {
    val hasSupportedPhysicalCanAddressing: Boolean
        get() = requestCanId != null && responseCanId != null &&
            extendedAddressHex == null && canPriorityHex == null && testerAddressHex == null &&
            diagnosticLevelIn == null && diagnosticLevelOut == null &&
            (protocol == null || protocol in setOf("15765-4-11bit", "15765-4-29bit"))

    fun isAvailableForYear(modelYear: Int): Boolean =
        (filter?.matches(modelYear) != false) &&
            (debugFilter?.matches(modelYear) != false)

    fun requestPayload(): ByteArray {
        val identifier = identifierHex.chunked(2).map { it.toInt(16).toByte() }
        return byteArrayOf(service.toByte()) + identifier
    }

    fun decode(udsDidData: ByteArray): ObdbDecodedValue = format.decode(udsDidData)
}

data class ObdbCamaroSignalSet(
    val ecus: List<ObdbEcuDefinition>,
    val signals: List<ObdbCamaroSignal>,
    val source: String = SOURCE_URL
) {
    fun forYear(modelYear: Int): List<ObdbCamaroSignal> =
        signals.filter { !it.hidden && it.isAvailableForYear(modelYear) }

    fun find(id: String): ObdbCamaroSignal? =
        signals.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun categories(modelYear: Int): List<String> =
        forYear(modelYear).map { it.path }.distinct().sorted()

    companion object {
        const val SOURCE_URL = "https://github.com/OBDb/Chevrolet-Camaro"
        const val ASSET_NAME = "camaro-obdb-signalset-v3.json"

        fun load(context: Context): ObdbCamaroSignalSet =
            context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use {
                fromJson(it.readText())
            }

        fun fromJson(json: String): ObdbCamaroSignalSet {
            val root = JSONObject(json)
            val ecus = root.optJSONArray("ecu").toObjectList().mapNotNull { ecu ->
                val header = ecu.optString("hdr").normalizedHex()
                if (header.isBlank()) null else ObdbEcuDefinition(
                    headerHex = header,
                    responseHex = ecu.optString("rax").normalizedHex().takeIf { it.isNotBlank() },
                    type = ecu.optString("type", "unknown")
                )
            }
            val commands = root.optJSONArray("commands").toObjectList()
            val signals = commands.flatMap { command ->
                val commandObject = command.optJSONObject("cmd") ?: return@flatMap emptyList()
                val serviceKey = commandObject.keys().asSequence().firstOrNull()
                    ?: return@flatMap emptyList()
                val service = serviceKey.toIntOrNull(16) ?: return@flatMap emptyList()
                val identifier = commandObject.optString(serviceKey).normalizedHex()
                if (identifier.isBlank() || identifier.length % 2 != 0) return@flatMap emptyList()
                val requestHeader = command.optString("hdr").normalizedHex()
                val responseHeader = command.optString("rax").normalizedHex().takeIf { it.isNotBlank() }
                val formatFilter = command.optJSONObject("filter")?.toYearFilter()
                val debugFilter = command.optJSONObject("dbgfilter")?.toYearFilter()
                val frequency = command.optDouble("freq", 1.0).takeIf { it > 0.0 } ?: 1.0
                val formatSignals = command.optJSONArray("signals").toObjectList()
                formatSignals.mapNotNull { signal ->
                    val formatObject = signal.optJSONObject("fmt") ?: return@mapNotNull null
                    val length = formatObject.optInt("len", 0)
                    if (length !in 1..256) return@mapNotNull null
                    val divisor = formatObject.optDouble("div", 1.0)
                    if (divisor == 0.0) return@mapNotNull null
                    val mapObject = formatObject.optJSONObject("map")
                    val map = if (mapObject == null) emptyMap() else
                        mapObject.keys().asSequence().associateWith { key -> mapObject.optString(key) }
                    val format = ObdbSignalFormat(
                        bitIndex = formatObject.optInt("bix", 0),
                        bitLength = length,
                        leastSignificantByteFirst = formatObject.optBoolean("blsb", false),
                        signed = formatObject.optBoolean("sign", false),
                        minimum = formatObject.optNullableDouble("min"),
                        maximum = formatObject.optNullableDouble("max"),
                        add = formatObject.optDouble("add", 0.0),
                        multiply = formatObject.optDouble("mul", 1.0),
                        divide = divisor,
                        unit = formatObject.optString("unit", "unknown"),
                        nullMinimum = formatObject.optNullableDouble("nullmin"),
                        nullMaximum = formatObject.optNullableDouble("nullmax"),
                        optimalMinimum = formatObject.optNullableDouble("omin"),
                        optimalMaximum = formatObject.optNullableDouble("omax"),
                        optimalValue = formatObject.optNullableDouble("oval"),
                        valueMap = map
                    )
                    ObdbCamaroSignal(
                        id = signal.optString("id"),
                        name = signal.optString("name"),
                        path = signal.optString("path", "OBD"),
                        service = service,
                        identifierHex = identifier,
                        requestHeaderHex = requestHeader,
                        responseHeaderHex = responseHeader,
                        extendedAddressHex = command.optString("eax").normalizedHex().takeIf { it.isNotBlank() },
                        canPriorityHex = command.optString("pri").normalizedHex().takeIf { it.isNotBlank() },
                        testerAddressHex = command.optString("tst").normalizedHex().takeIf { it.isNotBlank() },
                        timeoutHex = command.optString("tmo").normalizedHex().takeIf { it.isNotBlank() },
                        diagnosticLevelIn = command.optString("din").normalizedHex().takeIf { it.isNotBlank() },
                        diagnosticLevelOut = command.optString("dout").normalizedHex().takeIf { it.isNotBlank() },
                        protocol = command.optString("proto").takeIf { it.isNotBlank() },
                        requestCanId = requestHeader.toCan11BitId(),
                        responseCanId = responseHeader?.toCan11BitId(),
                        frequencySeconds = frequency,
                        flowControlRequired = command.optBoolean("fcm1", false),
                        debugSignal = command.optBoolean("dbg", false),
                        hidden = signal.optBoolean("hidden", false),
                        description = signal.optString("description").takeIf { it.isNotBlank() },
                        filter = formatFilter,
                        debugFilter = debugFilter,
                        format = format,
                        suggestedMetric = signal.optString("suggestedMetric").takeIf { it.isNotBlank() }
                    ).takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
                }
            }
            return ObdbCamaroSignalSet(ecus, signals)
        }
    }
}

private fun JSONArray?.toObjectList(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

private fun JSONObject.toYearFilter(): ObdbYearFilter = ObdbYearFilter(
    from = optNullableInt("from"),
    to = optNullableInt("to"),
    years = optJSONArray("years").let { years ->
        if (years == null) emptySet() else (0 until years.length()).mapNotNull { years.optInt(it).takeIf { value -> value > 0 } }.toSet()
    }
)

private fun JSONObject.optNullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null

private fun String.normalizedHex(): String =
    trim().removePrefix("0x").removePrefix("0X").uppercase(Locale.ROOT)

private fun String.toCan11BitId(): Int? {
    if (length != 3 || any { it.digitToIntOrNull(16) == null }) return null
    return toIntOrNull(16)?.takeIf { it in 0..0x7FF }
}
