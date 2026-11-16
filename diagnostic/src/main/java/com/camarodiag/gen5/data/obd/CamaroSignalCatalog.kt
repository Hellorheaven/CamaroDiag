package com.camarodiag.gen5.data.obd

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.Locale

/**
 * First-party diagnostic signal model.
 *
 * Definitions are authored and maintained by CamaroDiag. This model deliberately
 * does not parse community signal-set formats or import their records.
 */
enum class SignalVerification {
    PROPOSED,
    DOCUMENTED,
    VEHICLE_VERIFIED
}

data class SignalYearFilter(
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

data class CamaroSignalFormat(
    val bitOffset: Int = 0,
    val bitLength: Int,
    val littleEndian: Boolean = false,
    val signed: Boolean = false,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val add: Double = 0.0,
    val multiply: Double = 1.0,
    val divide: Double = 1.0,
    val unit: String,
    val nullMinimum: Double? = null,
    val nullMaximum: Double? = null,
    val valueMap: Map<String, String> = emptyMap()
) {
    init {
        require(bitOffset >= 0) { "Signal bit offset cannot be negative." }
        require(bitLength in 1..256) { "Signal bit length must be between 1 and 256." }
        require(divide.isFinite() && divide != 0.0) { "Signal divisor must be finite and non-zero." }
        require(multiply.isFinite() && add.isFinite()) { "Signal scale must be finite." }
        require(minimum == null || minimum.isFinite())
        require(maximum == null || maximum.isFinite())
    }

    fun decode(payload: ByteArray): CamaroDecodedSignalValue {
        require(payload.isNotEmpty()) { "Empty ECU response payload." }
        val ordered = if (littleEndian) payload.reversedArray() else payload
        val totalBits = ordered.size * 8
        require(bitOffset + bitLength <= totalBits) {
            "Signal requires ${bitOffset + bitLength} bits; ECU returned $totalBits bits."
        }

        val packed = BigInteger(1, ordered)
        val shift = totalBits - bitOffset - bitLength
        val mask = BigInteger.ONE.shiftLeft(bitLength).subtract(BigInteger.ONE)
        val unsigned = packed.shiftRight(shift).and(mask)
        val raw = if (signed && unsigned.testBit(bitLength - 1)) {
            unsigned.subtract(BigInteger.ONE.shiftLeft(bitLength))
        } else {
            unsigned
        }
        val rawText = raw.toString()
        val rawDouble = raw.toDouble()
        if (nullMinimum?.let { rawDouble <= it } == true ||
            nullMaximum?.let { rawDouble >= it } == true
        ) {
            return CamaroDecodedSignalValue(rawText, null, unit, null, unavailable = true)
        }

        val scaled = rawDouble * multiply / divide + add
        val boundedMinimum = minimum?.let { maxOf(scaled, it) } ?: scaled
        val bounded = maximum?.let { minOf(boundedMinimum, it) } ?: boundedMinimum
        return CamaroDecodedSignalValue(
            raw = rawText,
            value = bounded,
            unit = unit,
            mappedLabel = valueMap[rawText],
            unavailable = false
        )
    }
}

data class CamaroDecodedSignalValue(
    val raw: String,
    val value: Double?,
    val unit: String,
    val mappedLabel: String?,
    val unavailable: Boolean
)

data class CamaroSignalDefinition(
    val id: String,
    val name: String,
    val category: String,
    val service: Int,
    val identifierHex: String,
    val requestAddress: String,
    val responseAddress: String?,
    val protocol: String?,
    val extendedAddressHex: String?,
    val frequencySeconds: Double,
    val flowControlRequired: Boolean,
    val debugOnly: Boolean,
    val hidden: Boolean,
    val description: String?,
    val yearFilter: SignalYearFilter?,
    val format: CamaroSignalFormat,
    val verification: SignalVerification,
    val evidenceReferences: List<String>,
    val verifiedVehicleConfigurations: Set<String>
) {
    val requestCanId: Int? get() = requestAddress.toCan11BitId()
    val responseCanId: Int? get() = responseAddress?.toCan11BitId()

    val isVehicleExecutable: Boolean
        get() = verification == SignalVerification.VEHICLE_VERIFIED &&
            service == 0x22 &&
            identifierHex.length == 4 &&
            identifierHex.isHex() &&
            requestCanId != null &&
            responseCanId != null &&
            requestCanId != responseCanId &&
            extendedAddressHex == null &&
            (protocol == null || protocol == "15765-4-11bit") &&
            evidenceReferences.isNotEmpty() &&
            verifiedVehicleConfigurations.isNotEmpty()

    fun isAvailableForYear(year: Int): Boolean =
        !hidden && !debugOnly && (yearFilter?.matches(year) != false)
}

data class CamaroSignalCatalog(
    val schemaVersion: Int,
    val signals: List<CamaroSignalDefinition>
) {
    fun forYear(year: Int): List<CamaroSignalDefinition> =
        signals.filter { it.isAvailableForYear(year) }

    fun executableFor(year: Int, vehicleConfiguration: String): List<CamaroSignalDefinition> =
        forYear(year).filter {
            it.isVehicleExecutable &&
                vehicleConfiguration in it.verifiedVehicleConfigurations
        }

    fun find(id: String): CamaroSignalDefinition? =
        signals.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun categories(year: Int): List<String> =
        forYear(year).map { it.category }.distinct().sorted()

    companion object {
        const val ASSET_NAME = "camaro-native-signalset-v1.json"
        private const val SUPPORTED_SCHEMA_VERSION = 1

        fun fromJson(json: String): CamaroSignalCatalog {
            val root = JSONObject(json)
            val version = root.optInt("schemaVersion", 0)
            require(version == SUPPORTED_SCHEMA_VERSION) {
                "Unsupported Camaro signal schema version: $version"
            }

            val signalArray = root.optJSONArray("signals")
                ?: error("Native Camaro catalogue must contain a signals array.")
            val definitions = signalArray.objects().map { item ->
                val filter = item.optJSONObject("yearFilter")?.let {
                    SignalYearFilter(
                        from = it.optNullableInt("from"),
                        to = it.optNullableInt("to"),
                        years = it.optJSONArray("years").ints()
                    )
                }
                val format = item.optJSONObject("format")
                    ?: error("Signal ${item.optString("id")} has no format definition.")
                val mapObject = format.optJSONObject("valueMap")
                val valueMap = mapObject?.keys()?.asSequence()
                    ?.associateWith { mapObject.optString(it) }.orEmpty()

                CamaroSignalDefinition(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    category = item.optString("category", "Other"),
                    service = item.optString("service").removePrefix("0x").toIntOrNull(16) ?: 0,
                    identifierHex = item.optString("identifier").normalizedHex(),
                    requestAddress = item.optString("requestAddress").normalizedHex(),
                    responseAddress = item.optString("responseAddress").normalizedHex()
                        .takeIf { it.isNotBlank() },
                    protocol = item.optString("protocol").takeIf { it.isNotBlank() },
                    extendedAddressHex = item.optString("extendedAddress").normalizedHex()
                        .takeIf { it.isNotBlank() },
                    frequencySeconds = item.optDouble("frequencySeconds", 1.0),
                    flowControlRequired = item.optBoolean("flowControlRequired", false),
                    debugOnly = item.optBoolean("debugOnly", false),
                    hidden = item.optBoolean("hidden", false),
                    description = item.optString("description").takeIf { it.isNotBlank() },
                    yearFilter = filter,
                    format = CamaroSignalFormat(
                        bitOffset = format.optInt("bitOffset", 0),
                        bitLength = format.optInt("bitLength", 0),
                        littleEndian = format.optBoolean("littleEndian", false),
                        signed = format.optBoolean("signed", false),
                        minimum = format.optNullableDouble("minimum"),
                        maximum = format.optNullableDouble("maximum"),
                        add = format.optDouble("add", 0.0),
                        multiply = format.optDouble("multiply", 1.0),
                        divide = format.optDouble("divide", 1.0),
                        unit = format.optString("unit", "unknown"),
                        nullMinimum = format.optNullableDouble("nullMinimum"),
                        nullMaximum = format.optNullableDouble("nullMaximum"),
                        valueMap = valueMap
                    ),
                    verification = runCatching {
                        SignalVerification.valueOf(
                            item.optString("verification", "PROPOSED").uppercase(Locale.ROOT)
                        )
                    }.getOrElse { error("Unknown verification status for ${item.optString("id")}.") },
                    evidenceReferences = item.optJSONArray("evidenceReferences").strings(),
                    verifiedVehicleConfigurations = item.optJSONArray("verifiedVehicleConfigurations")
                        .strings().toSet()
                ).also { signal ->
                    require(signal.id.isNotBlank() && signal.name.isNotBlank()) {
                        "Signal id and name are required."
                    }
                    require(signal.identifierHex.isNotBlank() && signal.identifierHex.isHex()) {
                        "Signal ${signal.id} has an invalid identifier."
                    }
                    require(signal.requestAddress.toCan11BitId() != null) {
                        "Signal ${signal.id} must use a valid 11-bit physical request CAN address."
                    }
                    require(signal.responseAddress == null || signal.responseAddress.toCan11BitId() != null) {
                        "Signal ${signal.id} has an invalid 11-bit physical response CAN address."
                    }
                    require(signal.frequencySeconds.isFinite() && signal.frequencySeconds > 0.0) {
                        "Signal ${signal.id} has an invalid polling frequency."
                    }
                    if (signal.verification == SignalVerification.VEHICLE_VERIFIED) {
                        require(signal.evidenceReferences.isNotEmpty()) {
                            "Vehicle-verified signal ${signal.id} requires evidence references."
                        }
                        require(signal.verifiedVehicleConfigurations.isNotEmpty()) {
                            "Vehicle-verified signal ${signal.id} requires a verified vehicle configuration."
                        }
                    }
                }
            }

            require(definitions.map { it.id.lowercase(Locale.ROOT) }.distinct().size == definitions.size) {
                "Duplicate Camaro signal identifiers."
            }
            return CamaroSignalCatalog(version, definitions)
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length())
        .mapNotNull { optString(it).takeIf(String::isNotBlank) }

private fun JSONArray?.ints(): Set<Int> =
    if (this == null) emptySet() else (0 until length())
        .mapNotNull { optInt(it).takeIf { value -> value > 0 } }.toSet()

private fun JSONObject.optNullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null

private fun String.normalizedHex(): String =
    trim().removePrefix("0x").removePrefix("0X").uppercase(Locale.ROOT)

private fun String.isHex(): Boolean =
    isNotEmpty() && all { it.digitToIntOrNull(16) != null }

private fun String.toCan11BitId(): Int? =
    uppercase(Locale.ROOT).takeIf { it.length == 3 && it.isHex() }
        ?.toIntOrNull(16)?.takeIf { it in 0x000..0x7FF }
