package com.camarodiag.gen5.ui

import com.camarodiag.gen5.ui.J1979CatalogueJson.array
import com.camarodiag.gen5.ui.J1979CatalogueJson.bool
import com.camarodiag.gen5.ui.J1979CatalogueJson.fields
import com.camarodiag.gen5.ui.J1979CatalogueJson.int
import com.camarodiag.gen5.ui.J1979CatalogueJson.integer
import com.camarodiag.gen5.ui.J1979CatalogueJson.label
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Locale

/**
 * The same declarative catalogue semantics as the desktop/Linux tools.
 * All calculations are rational, including signed 64-bit and unsigned
 * 64-bit signals. No executable formulas or transport access.
 */
internal class J1979DocumentedCatalogue private constructor(
    val edition: String, val referenceName: String, val referenceSha256: String,
    val catalogueSha256: String, definitions: List<Definition>
) {
    private val definitions = definitions.toList()
    private val index = buildMap {
        definitions.forEach { row ->
            row.protocols.forEach { protocol ->
                require(put(row.pid to protocol, row) == null) { "Ambiguous PID/protocol mapping" }
            }
        }
    }

    internal data class Rational private constructor(val n: BigInteger, val d: BigInteger) :
        Comparable<Rational> {
        override fun compareTo(other: Rational): Int = (n * other.d).compareTo(other.n * d)
        operator fun plus(other: Rational): Rational = of(n * other.d + other.n * d, d * other.d)
        operator fun times(raw: BigInteger): Rational = of(n * raw, d)
        fun json() = mapOf("numerator" to n.toString(), "denominator" to d.toString())
        companion object {
            fun of(n: BigInteger, d: BigInteger): Rational {
                require(d.signum() > 0) { "Invalid denominator" }
                val gcd = n.abs().gcd(d)
                return Rational(n / gcd, d / gcd)
            }
        }
    }

    internal data class Bits(val offset: Int, val width: Int, val order: String) {
        fun extract(data: ByteArray): BigInteger {
            require(offset + width <= data.size * 8) { "Signal outside received payload" }
            var value = BigInteger.ZERO
            if (order == "LE_BYTES") {
                for (i in width / 8 - 1 downTo 0)
                    value = value.shiftLeft(8).or(BigInteger.valueOf((data[offset / 8 + i].toInt() and 255).toLong()))
            } else {
                for (i in offset until offset + width) {
                    val bit = (data[i / 8].toInt() ushr (7 - i % 8)) and 1
                    value = value.shiftLeft(1).or(BigInteger.valueOf(bit.toLong()))
                }
            }
            return value
        }
    }

    internal data class Presence(val bits: Bits, val mask: BigInteger, val expected: BigInteger) {
        fun matches(data: ByteArray) = bits.extract(data).and(mask) == expected
    }

    internal data class Signal(
        val name: String, val unit: String, val bits: Bits, val signed: Boolean,
        val scale: Rational, val offset: Rational, val unavailable: Set<BigInteger>,
        val presence: Presence?, val minimum: Rational?, val maximum: Rational?,
        val enumeration: Map<BigInteger, String>, val reference: String
    ) {
        fun project(data: ByteArray): Map<String, Any?> {
            val result = linkedMapOf<String, Any?>(
                "name" to name, "unit" to unit, "reference" to reference, "status" to null,
                "raw" to null, "value" to null, "enumeration" to null
            )
            if (presence != null && !presence.matches(data)) {
                result["status"] = "NOT_PRESENT"; return result
            }
            val raw = bits.extract(data)
            result["raw"] = raw.toString()
            if (raw in unavailable) result["status"] = "UNAVAILABLE"
            else if (enumeration.isNotEmpty()) {
                result["enumeration"] = enumeration[raw]
                result["status"] = if (raw in enumeration) "ENUMERATED" else "UNDEFINED_ENUMERATION"
            } else {
                val encoded = if (signed && raw.testBit(bits.width - 1))
                    raw - BigInteger.ONE.shiftLeft(bits.width) else raw
                val value = scale * encoded + offset
                result["value"] = value.json()
                result["status"] = if (minimum != null && maximum != null &&
                    (value < minimum || value > maximum)) "OUTSIDE_DECLARED_RANGE" else "DECODED"
            }
            return result
        }
    }

    internal data class Definition(val pid: Int, val width: Int, val protocols: List<String>,
                                  val reference: String, val signals: List<Signal>)

    fun inventory(): List<Map<String, Any>> = definitions
        .sortedWith(compareBy<Definition> { it.pid }.thenBy { it.protocols.joinToString(",") })
        .map { mapOf("pid" to it.pid, "payload_bytes" to it.width, "protocols" to it.protocols,
            "reference" to it.reference, "signals" to it.signals.map(Signal::name)) }

    data class Projection(val row: Map<String, Any?>, val signalCount: Int)

    fun project(pid: Int, protocol: String, cycle: Int, elapsed: Int, status: String,
                response: String?, remainingSignals: Int): Projection {
        require(protocol == "CAN" || protocol == "LEGACY")
        val row = linkedMapOf<String, Any?>(
            "cycle" to cycle, "pid" to pid, "elapsed_ms" to elapsed, "source_status" to status,
            "response_hex" to response, "projection_status" to null, "signals" to emptyList<Any>()
        )
        var count = 0
        if (status != "RAW") row["projection_status"] = "NO_CORRELATED_RAW_PAYLOAD"
        else {
            val data = hex(response ?: error("Missing raw response"))
            require(data.size >= 3 && data[0] == 0x41.toByte() && data[1].toInt() and 255 == pid &&
                (protocol != "LEGACY" || data.size <= 7)) { "Uncorrelated raw response" }
            val definition = index[pid to protocol]
            if (definition == null) row["projection_status"] = "NO_DOCUMENTED_DEFINITION"
            else if (data.size != definition.width + 2) row["projection_status"] = "DOCUMENTED_WIDTH_MISMATCH"
            else {
                count = definition.signals.size
                require(count <= remainingSignals) { "Projection signal count exceeds limit" }
                val payload = data.copyOfRange(2, data.size)
                row["projection_status"] = "PROJECTED"
                row["reference"] = definition.reference
                row["signals"] = definition.signals.map { it.project(payload) }
            }
        }
        return Projection(row, count)
    }

    companion object {
        const val MAX_CATALOGUE_BYTES = 4 * 1024 * 1024
        const val MAX_REFERENCE_BYTES = 128 * 1024 * 1024
        const val MAX_REPORT_BYTES = 16 * 1024 * 1024
        const val MAX_ROWS = 25_000
        private val scaleLimit = BigInteger.valueOf(1_000_000_000_000L)
        private val hexChars = "0123456789abcdef"
        fun sha(raw: ByteArray): String = digestText(MessageDigest.getInstance("SHA-256").digest(raw))
        fun digestText(bytes: ByteArray): String = buildString(bytes.size * 2) {
            bytes.forEach { b ->
                val x = b.toInt() and 255
                append(hexChars[x ushr 4]); append(hexChars[x and 15])
            }
        }
        fun digest(value: String): String = value.also {
            require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256" }
        }
        fun hex(value: String, maxBytes: Int = 4095): ByteArray {
            require(value.length <= maxBytes * 2 && value.length % 2 == 0 &&
                value.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) { "Invalid hex" }
            return ByteArray(value.length / 2) { i ->
                ((value[2 * i].digitToInt(16) shl 4) or value[2 * i + 1].digitToInt(16)).toByte()
            }
        }
        private fun fraction(value: Any?): Rational {
            val row = array(value, 2, 2)
            return Rational.of(integer(row[0], -scaleLimit, scaleLimit),
                integer(row[1], BigInteger.ONE, scaleLimit))
        }
        private fun mask(width: Int) = BigInteger.ONE.shiftLeft(width) - BigInteger.ONE
        private fun encoded(value: Any?, width: Int) = integer(value, BigInteger.ZERO, mask(width))
        private fun bits(value: Any?, payloadWidth: Int): Bits {
            val row = fields(value, setOf("offset", "width", "order"))
            val offset = int(row["offset"], 0, payloadWidth * 8 - 1)
            val width = int(row["width"], 1, 64)
            val order = label(row["order"])
            require(offset + width <= payloadWidth * 8 && order in setOf("MSB0", "LE_BYTES")) {
                "Invalid bit scope"
            }
            require(order != "LE_BYTES" || offset % 8 == 0 && width % 8 == 0) {
                "Unaligned little-endian field"
            }
            return Bits(offset, width, order)
        }
        private fun presence(value: Any?, width: Int): Presence {
            val row = fields(value, setOf("bits", "mask", "equals"))
            val bits = bits(row["bits"], width)
            val mask = integer(row["mask"], BigInteger.ONE, mask(bits.width))
            val expected = encoded(row["equals"], bits.width)
            require(expected.and(mask.not()) == BigInteger.ZERO) { "Presence value outside mask" }
            return Presence(bits, mask, expected)
        }
        private fun signal(value: Any?, width: Int): Signal {
            val row = fields(value, setOf("name", "unit", "bits", "signed", "scale", "offset", "reference"),
                setOf("unavailable", "presence", "range", "enumeration"))
            val bits = bits(row["bits"], width)
            val signed = bool(row["signed"])
            fun entries(key: String) = if (key in row) array(row[key], 0, 256) else emptyList()
            val unavailable = entries("unavailable").map { encoded(it, bits.width) }
            require(unavailable.distinct().size == unavailable.size) { "Duplicate unavailable value" }
            val enums = linkedMapOf<BigInteger, String>()
            entries("enumeration").forEach { raw ->
                val item = fields(raw, setOf("raw", "label"))
                val number = encoded(item["raw"], bits.width)
                require(number !in unavailable && enums.put(number, label(item["label"])) == null) {
                    "Ambiguous enumeration"
                }
            }
            val scale = fraction(row["scale"]); val offset = fraction(row["offset"])
            val limits = if (row["range"] == null) null else array(row["range"], 2, 2).map(::fraction)
            require(limits == null || limits[0] <= limits[1]) { "Inverted engineering range" }
            require(if (enums.isEmpty()) scale.n.signum() != 0 else
                !signed && scale.n == scale.d && offset.n.signum() == 0 && limits == null) {
                "Incompatible numeric or enumeration definition"
            }
            return Signal(label(row["name"]), label(row["unit"], 64), bits, signed, scale, offset,
                unavailable.toSet(), if ("presence" in row) presence(row["presence"], width) else null,
                limits?.get(0), limits?.get(1), enums, label(row["reference"]))
        }
        internal fun definition(value: Any?): Definition {
            val row = fields(value, setOf("pid", "payload_bytes", "protocols", "reference", "signals"))
            val pid = int(row["pid"], 1, 255); val width = int(row["payload_bytes"], 1, 4093)
            require(pid % 32 != 0) { "Support bitmap is not a measurement" }
            val protocols = array(row["protocols"], 1, 2).map { label(it) }
            require(protocols.all { it in setOf("CAN", "LEGACY") } &&
                protocols.distinct().size == protocols.size && ("LEGACY" !in protocols || width <= 5)) {
                "Invalid protocol scope"
            }
            val signals = array(row["signals"], 1, 64).map { signal(it, width) }
            require(signals.map(Signal::name).distinct().size == signals.size) { "Duplicate signal name" }
            return Definition(pid, width, protocols, label(row["reference"]), signals)
        }
        fun parse(raw: ByteArray, expectedReferenceSha: String): J1979DocumentedCatalogue {
            val row = fields(J1979CatalogueJson.parse(raw, MAX_CATALOGUE_BYTES),
                setOf("schema", "edition", "reference_name", "reference_sha256", "definitions"))
            require(row["schema"] == "camarodiag-j1979-pid-catalogue-v1") { "Unsupported catalogue schema" }
            val referenceSha = digest(label(row["reference_sha256"]))
            require(referenceSha == digest(expectedReferenceSha)) { "Reference digest differs from pin" }
            val definitions = array(row["definitions"], 1, 247).map(::definition)
            return J1979DocumentedCatalogue(label(row["edition"]), label(row["reference_name"]),
                referenceSha, sha(raw), definitions)
        }
    }
}
