package com.camarodiag.connectors

import java.io.IOException
import java.util.Collections

/** Replay of the exact legacy CJ/1 read-only campaign, independent of USB authentication. */
object J1979CampaignSemantics {
    private val states = arrayOf("", "PASSED", "RAW", "NEGATIVE", "TIMEOUT", "FAILED")
    private val ends = arrayOf("", "COMPLETE", "INITIAL_UNVERIFIED", "BUDGET", "LINK_FAULT", "SINK_STOPPED")
    private val one = intArrayOf(4,5,6,7,8,9,10,11,13,14,15,17,19,29,44,45,46,47,48,51,69,70,71,72,73,74,75,76,82,90,91,92,97,98)
    private val two = intArrayOf(12,16,31,33,34,35,49,50,60,61,62,63,66,67,68,77,78,83,84,89,93,94,99)
    private const val digits = "0123456789ABCDEF"
    private fun u(b: Byte) = b.toInt() and 255
    private fun hex(data: ByteArray): String = buildString(data.size * 2) {
        for (b in data) { append(digits[u(b) ushr 4]); append(digits[u(b) and 15]) }
    }
    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

    class Measurement(@JvmField val mode: Int, @JvmField val pid: Int,
                      @JvmField val name: String, @JvmField val number: Double, @JvmField val unit: String)

    // Preserve historical CJ/1 decoding; contextual projections remain separate.
    private fun measurement(req: ByteArray, res: ByteArray): Measurement? {
        val mode = u(req[0])
        if (mode != 1 && mode != 2) return null
        val pid = u(req[1])
        val offset = if (mode == 2) 3 else 2
        if (res.size <= offset) return null
        val a = u(res[offset])
        val b = if (res.size > offset + 1) u(res[offset + 1]) else 0
        return when (pid) {
            0x04 -> Measurement(mode, pid, "calculated-engine-load", a * 100.0 / 255, "%")
            0x05 -> Measurement(mode, pid, "coolant-temperature", a.toDouble() - 40, "degC")
            0x06 -> Measurement(mode, pid, "short-term-fuel-trim-bank-1", a * 100.0 / 128 - 100, "%")
            0x07 -> Measurement(mode, pid, "long-term-fuel-trim-bank-1", a * 100.0 / 128 - 100, "%")
            0x08 -> Measurement(mode, pid, "short-term-fuel-trim-bank-2", a * 100.0 / 128 - 100, "%")
            0x09 -> Measurement(mode, pid, "long-term-fuel-trim-bank-2", a * 100.0 / 128 - 100, "%")
            0x0C -> Measurement(mode, pid, "engine-rpm", (a * 256.0 + b) / 4, "rpm")
            0x0D -> Measurement(mode, pid, "vehicle-speed", a.toDouble(), "km/h")
            0x0E -> Measurement(mode, pid, "timing-advance", a / 2.0 - 64, "degBTDC")
            0x0F -> Measurement(mode, pid, "intake-air-temperature", a.toDouble() - 40, "degC")
            0x10 -> Measurement(mode, pid, "mass-air-flow", (a * 256.0 + b) / 100, "g/s")
            0x11 -> Measurement(mode, pid, "throttle-position", a * 100.0 / 255, "%")
            0x1F -> Measurement(mode, pid, "engine-runtime", a * 256.0 + b, "s")
            0x2F -> Measurement(mode, pid, "fuel-tank-level", a * 100.0 / 255, "%")
            0x33 -> Measurement(mode, pid, "barometric-pressure", a.toDouble(), "kPa")
            0x42 -> Measurement(mode, pid, "control-module-voltage", (a * 256.0 + b) / 1000, "V")
            0x46 -> Measurement(mode, pid, "ambient-air-temperature", a.toDouble() - 40, "degC")
            0x52 -> Measurement(mode, pid, "ethanol-content", a * 100.0 / 255, "%")
            0x5C -> Measurement(mode, pid, "engine-oil-temperature", a.toDouble() - 40, "degC")
            0x5E -> Measurement(mode, pid, "engine-fuel-rate", (a * 256.0 + b) / 20, "L/h")
            0x0A -> Measurement(mode, pid, "fuel-pressure-gauge", a * 3.0, "kPa")
            0x0B -> Measurement(mode, pid, "intake-manifold-absolute-pressure", a.toDouble(), "kPa")
            0x21 -> Measurement(mode, pid, "distance-with-mil-on", a * 256.0 + b, "km")
            0x22 -> Measurement(mode, pid, "fuel-rail-pressure-relative-vacuum", (a * 256.0 + b) * 0.079, "kPa")
            0x23 -> Measurement(mode, pid, "fuel-rail-pressure-direct-injection", (a * 256.0 + b) * 10, "kPa")
            0x2C -> Measurement(mode, pid, "commanded-egr", a * 100.0 / 255, "%")
            0x2D -> Measurement(mode, pid, "egr-error", (a - 128) * 100.0 / 128, "%")
            0x2E -> Measurement(mode, pid, "commanded-evaporative-purge", a * 100.0 / 255, "%")
            0x30 -> Measurement(mode, pid, "warm-ups-since-dtc-clear", a.toDouble(), "count")
            0x31 -> Measurement(mode, pid, "distance-since-dtc-clear", a * 256.0 + b, "km")
            0x32 -> Measurement(mode, pid, "evap-vapor-pressure", (a * 256 + b).toShort().toDouble() / 4.0, "Pa")
            0x3C -> Measurement(mode, pid, "catalyst-temperature-bank-1-sensor-1", (a * 256.0 + b) / 10 - 40, "degC")
            0x3D -> Measurement(mode, pid, "catalyst-temperature-bank-2-sensor-1", (a * 256.0 + b) / 10 - 40, "degC")
            0x3E -> Measurement(mode, pid, "catalyst-temperature-bank-1-sensor-2", (a * 256.0 + b) / 10 - 40, "degC")
            0x3F -> Measurement(mode, pid, "catalyst-temperature-bank-2-sensor-2", (a * 256.0 + b) / 10 - 40, "degC")
            0x43 -> Measurement(mode, pid, "absolute-engine-load", (a * 256.0 + b) * 100 / 255, "%")
            0x44 -> Measurement(mode, pid, "commanded-equivalence-ratio", (a * 256.0 + b) / 32768, "ratio")
            0x45 -> Measurement(mode, pid, "relative-throttle-position", a * 100.0 / 255, "%")
            0x47 -> Measurement(mode, pid, "absolute-throttle-position-b", a * 100.0 / 255, "%")
            0x48 -> Measurement(mode, pid, "absolute-throttle-position-c", a * 100.0 / 255, "%")
            0x49 -> Measurement(mode, pid, "accelerator-pedal-position-d", a * 100.0 / 255, "%")
            0x4A -> Measurement(mode, pid, "accelerator-pedal-position-e", a * 100.0 / 255, "%")
            0x4B -> Measurement(mode, pid, "accelerator-pedal-position-f", a * 100.0 / 255, "%")
            0x4C -> Measurement(mode, pid, "commanded-throttle-actuator", a * 100.0 / 255, "%")
            0x4D -> Measurement(mode, pid, "time-run-with-mil-on", a * 256.0 + b, "min")
            0x4E -> Measurement(mode, pid, "time-since-dtc-clear", a * 256.0 + b, "min")
            0x53 -> Measurement(mode, pid, "absolute-evap-vapor-pressure", (a * 256.0 + b) / 200, "kPa")
            0x54 -> Measurement(mode, pid, "evap-vapor-pressure-offset", (a * 256.0 + b) - 32767, "Pa")
            0x59 -> Measurement(mode, pid, "fuel-rail-pressure-absolute", (a * 256.0 + b) * 10, "kPa")
            0x5A -> Measurement(mode, pid, "relative-accelerator-pedal-position", a * 100.0 / 255, "%")
            0x5B -> Measurement(mode, pid, "hybrid-battery-pack-remaining-life", a * 100.0 / 255, "%")
            0x5D -> Measurement(mode, pid, "fuel-injection-timing", ((a * 256.0 + b) - 26880) / 128, "deg")
            0x61 -> Measurement(mode, pid, "driver-demand-engine-torque", a.toDouble() - 125, "%")
            0x62 -> Measurement(mode, pid, "actual-engine-torque", a.toDouble() - 125, "%")
            0x63 -> Measurement(mode, pid, "engine-reference-torque", a * 256.0 + b, "Nm")
            0xA6 -> Measurement(mode, pid, "odometer", (a*16777216.0+b*65536.0+u(res[offset+2])*256.0+u(res[offset+3]))/10.0, "km")
            else -> null
        }
    }

    class MonitorRecord(data: ByteArray, i: Int) {
        @JvmField val monitorId = u(data[i])
        @JvmField val testId = u(data[i + 1])
        @JvmField val unitScalingId = u(data[i + 2])
        @JvmField val valueEncoded = u(data[i + 3]) * 256 + u(data[i + 4])
        @JvmField val minimumEncoded = u(data[i + 5]) * 256 + u(data[i + 6])
        @JvmField val maximumEncoded = u(data[i + 7]) * 256 + u(data[i + 8])
    }
    class VehicleInfo(@JvmField val infoType: Int, values: List<String>) {
        @JvmField val values = frozen(values)
    }
    private fun paddedAscii(data: ByteArray, start: Int, length: Int): String? {
        var end = start + length
        while (end > start && data[end - 1] == 0.toByte()) end--
        if (end == start || (start until end).any { u(data[it]) !in 32..126 }) return null
        return String(data, start, end - start, Charsets.US_ASCII)
    }
    class Readiness(response: ByteArray) {
        @JvmField val milOn = u(response[2]) and 128 != 0
        @JvmField val confirmedDtcCount = u(response[2]) and 127
        @JvmField val byteB = u(response[3])
        @JvmField val byteC = u(response[4])
        @JvmField val byteD = u(response[5])
        @JvmField val compressionIgnition = byteB and 8 != 0
        @JvmField val supportedMonitors: Map<String, Boolean>
        init {
            val monitors = linkedMapOf<String, Boolean>()
            fun add(names: List<String?>, support: Int, incomplete: Int) {
                names.forEachIndexed { bit, name ->
                    if (name != null && support and (1 shl bit) != 0)
                        monitors[name] = incomplete and (1 shl bit) == 0
                }
            }
            add(listOf("misfire", "fuel-system", "components"), byteB, byteB ushr 4)
            val names = if (compressionIgnition)
                listOf("nmhc-catalyst", "nox-aftertreatment", null, "boost-pressure", null,
                    "exhaust-gas-sensor", "particulate-filter", "egr-vvt")
            else listOf("catalyst", "heated-catalyst", "evaporative-system", "secondary-air", null,
                "oxygen-sensor", "oxygen-sensor-heater", "egr-vvt")
            add(names, byteC, byteD)
            supportedMonitors = Collections.unmodifiableMap(monitors)
        }
    }
    class SupportedPage(@JvmField val mode: Int, @JvmField val base: Int, response: ByteArray, offset: Int) {
        @JvmField val supportedIds = frozen((0 until 31).filter {
            u(response[offset + it / 8]) and (128 ushr (it % 8)) != 0
        }.map { base + it + 1 })
        @JvmField val hasNextPage = u(response[offset + 3]) and 1 != 0
    }
    private fun dtc(a: Int, b: Int): String = charArrayOf("PCBU"[a ushr 6], digits[(a ushr 4) and 3],
        digits[a and 15], digits[b ushr 4], digits[b and 15]).concatToString()
    class FreezeTrigger(a: Int, b: Int) {
        @JvmField val present = a != 0 || b != 0
        @JvmField val dtc = if (present) J1979CampaignSemantics.dtc(a, b) else null
    }
    private fun legacyRawOdometer(req: ByteArray, res: ByteArray): Boolean =
        req.size >= 2 && u(req[0]) in 1..2 && u(req[1]) == 0xa6 && positive(req, res) == 1

    class Step @Throws(IOException::class) constructor(
        @JvmField val sequence: Int, internal val state: Int, @JvmField val durationMs: Long,
        req: ByteArray, res: ByteArray
    ) {
        init {
            if (!readOnly(req) || state !in 1..5 || (state <= 3) != res.isNotEmpty() ||
                (state <= 2 && positive(req, res) != state && !(state == 2 && legacyRawOdometer(req, res))) ||
                (state == 3 && (res.size != 3 || u(res[0]) != 0x7f || res[1] != req[0] ||
                    u(res[2]) == 0 || u(res[2]) == 0x78)))
                throw IOException("CJ/1 application evidence inconsistent")
        }
        private val request = req.copyOf()
        private val response = res.copyOf()
        @JvmField val requestHex = hex(request)
        @JvmField val responseHex = if (response.isEmpty()) null else hex(response)
        @JvmField val status = states[state]
        internal fun matches(req: IntArray): Boolean =
            request.size == req.size && req.indices.all { u(request[it]) == req[it] }
        internal fun positiveResponse(): ByteArray? = if (state == 1) response.copyOf() else null

        fun dtcCodes(): List<String>? {
            if (state != 1 || u(request[0]) !in listOf(3, 7, 10)) return null
            return frozen((2 until response.size step 2).map { dtc(u(response[it]), u(response[it + 1])) })
        }
        fun negativeResponseCode(): Int? = if (state == 3) u(response[2]) else null
        fun measurement(): Measurement? = if (state == 1) J1979CampaignSemantics.measurement(request, response) else null
        fun monitorRecords(): List<MonitorRecord>? {
            if (state != 1 || u(request[0]) != 6 || u(request[1]) % 32 == 0) return null
            return frozen((1 until response.size step 9).map { MonitorRecord(response, it) })
        }
        fun supportedPage(): SupportedPage? {
            if (state != 1 || request.size < 2 || u(request[1]) % 32 != 0) return null
            val mode = u(request[0])
            return SupportedPage(mode, u(request[1]), response, if (mode == 2) 3 else 2)
        }
        fun freezeTrigger(): FreezeTrigger? = if (state == 1 && request.contentEquals(byteArrayOf(2, 2, 0)))
            FreezeTrigger(u(response[3]), u(response[4])) else null
        fun readiness(): Readiness? = if (state == 1 && request.contentEquals(byteArrayOf(1, 1)))
            Readiness(response) else null
        fun observedVin(): String? = if (state == 1 && request.contentEquals(byteArrayOf(9, 2)))
            String(response, 3, 17, Charsets.US_ASCII) else null
        fun vehicleInfo(): VehicleInfo? {
            if (state != 1 || u(request[0]) != 9) return null
            val type = u(request[1])
            val values = ArrayList<String>()
            when (type) {
                2 -> values.add(observedVin() ?: return null)
                4 -> for (i in 3 until response.size step 16)
                    values.add(paddedAscii(response, i, 16) ?: return null)
                6 -> for (i in 3 until response.size step 4) values.add(hex(response.copyOfRange(i, i + 4)))
                10 -> {
                    if (u(response[2]) != 1 || response[7] != '-'.code.toByte()) return null
                    val acronym = paddedAscii(response, 3, 4) ?: return null
                    val description = paddedAscii(response, 8, 15) ?: return null
                    values.add("$acronym-$description")
                }
                else -> return null
            }
            return VehicleInfo(type, values)
        }
    }
    class Summary(end: Int, @JvmField val flags: Int, @JvmField val steps: Int) {
        @JvmField val end = ends[end]
    }

    @JvmStatic fun decodePositive(requestHex: String?, responseHex: String?): Step? =
        decodeObservation(requestHex, responseHex, "PASSED")

    @JvmStatic fun decodeObservation(requestHex: String?, responseHex: String?, status: String?): Step? {
        val state = if (status == null) -1 else states.indexOf(status)
        val request = parseHex(requestHex, 3) ?: return null
        val response = if (responseHex.isNullOrEmpty()) ByteArray(0) else parseHex(responseHex, 4095) ?: return null
        if (state < 1) return null
        return try { Step(0, state, 0, request, response) } catch (_: IOException) { null }
    }
    private fun parseHex(value: String?, maxBytes: Int): ByteArray? {
        if (value == null || value.length !in 2..maxBytes * 2 || value.length % 2 != 0) return null
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            val a = digits.indexOf(value[i * 2])
            val b = digits.indexOf(value[i * 2 + 1])
            if (a < 0 || b < 0) return null
            out[i] = (a * 16 + b).toByte()
        }
        return out
    }
    private fun readOnly(r: ByteArray): Boolean {
        if (r.isEmpty()) return false
        val m = u(r[0])
        return r.size == 1 && m in listOf(3, 7, 10) ||
            r.size == 2 && (m in listOf(1, 6, 9) || m == 8 && u(r[1]) % 32 == 0) ||
            r.size == 3 && m == 2 && r[2] == 0.toByte()
    }
    private fun positive(req: ByteArray, r: ByteArray): Int {
        val m = u(req[0])
        if (r.isEmpty() || u(r[0]) != m + 64) return 0
        if (m in listOf(3, 7, 10)) {
            if (r.size < 2 || r.size != 2 + 2 * u(r[1])) return 0
            return if ((2 until r.size step 2).any { r[it] == 0.toByte() && r[it + 1] == 0.toByte() }) 0 else 1
        }
        if (r.size < 2 || r[1] != req[1]) return 0
        val pid = u(req[1])
        val off = if (m == 2) 3 else 2
        if (m == 2 && (r.size < 3 || r[2] != req[2])) return 0
        if (pid % 32 == 0) return if (r.size == off + 4 && !(pid == 224 && u(r.last()) and 1 != 0)) 1 else 0
        if (m == 1 || m == 2) {
            if (pid in 6..9 || pid in 0x55..0x58) {
                if (r.size == off + 1) return if (pid < 0x55) 1 else 2
                return if (r.size == off + 2) 2 else 0
            }
            val width = when {
                m == 2 && pid == 2 -> 2
                pid == 1 || pid == 0xa6 -> 4
                one.binarySearch(pid) >= 0 -> 1
                two.binarySearch(pid) >= 0 -> 2
                else -> 0
            }
            return if (width > 0) { if (r.size == off + width) 1 else 0 } else if (r.size > off) 2 else 0
        }
        if (m == 6) {
            if (r.size < 10 || (r.size - 1) % 9 != 0) return 0
            return if ((1 until r.size step 9).all { r[it] == req[1] }) 1 else 0
        }
        if (m == 9) {
            if (pid == 2) return if (r.size == 20 && r[2] == 1.toByte() &&
                CuWire.validVin(String(r, 3, 17, Charsets.US_ASCII))) 1 else 0
            val width = when (pid) { 4 -> 16; 6 -> 4; 10 -> 20; else -> 0 }
            return if (width > 0) {
                if (r.size >= 3 && u(r[2]) > 0 && r.size == 3 + u(r[2]) * width) 1 else 0
            } else if (r.size > 2) 2 else 0
        }
        return 0
    }

    @JvmStatic @Throws(IOException::class)
    fun verify(steps: List<Step>, end: Int, flags: Int): Summary {
        if (end !in 1..5 || flags !in 0..255) throw IOException("Invalid CJ/1 summary")
        return Replay(steps).verify(end, flags)
    }
    private class Replay(private val steps: List<Step>) {
        private var index = 0
        private var stop = 0
        private var computed = 0
        private var initial: ByteArray? = null

        private fun read(vararg req: Int): ByteArray? {
            if (stop != 0) return null
            if (index == steps.size) { stop = 1; return null }
            val step = steps[index++]
            if (!step.matches(req)) throw IOException("Unexpected CJ/1 request order")
            if (step.state == 5) stop = 2
            return step.positiveResponse()
        }
        private fun page(m: Int, p: Int): ByteArray? = if (m == 2) read(m, p, 0) else read(m, p)

        private fun inventory(m: Int, cached: Boolean, members: Boolean): Boolean {
            val off = if (m == 2) 3 else 2
            for (base in 0..224 step 32) {
                if (stop != 0) break
                val b = (if (cached && base == 0) initial else page(m, base)) ?: return false
                if (members) for (bit in 0 until 31) {
                    if (stop != 0) break
                    if (u(b[off + bit / 8]) and (128 ushr (bit % 8)) == 0) continue
                    val p = base + bit + 1
                    if ((m == 2 || m == 9) && p == 2) continue
                    page(m, p)
                }
                if (u(b[off + 3]) and 1 == 0) return stop == 0
            }
            return false
        }
        fun verify(end: Int, flags: Int): Summary {
            initial = read(1, 0)
            if (initial != null) {
                computed = computed or 1
                read(9, 2); read(3); read(7); read(10)
                if (inventory(1, true, true)) computed = computed or 2
                val trigger = read(2, 2, 0)
                if (trigger != null) {
                    computed = computed or 4
                    if (trigger[3] != 0.toByte() || trigger[4] != 0.toByte()) {
                        computed = computed or 8
                        if (inventory(2, false, true)) computed = computed or 16
                    }
                }
                if (inventory(6, false, true)) computed = computed or 32
                if (inventory(8, false, false)) computed = computed or 64
                if (inventory(9, false, true)) computed = computed or 128
            }
            val expected = when {
                stop == 2 -> 4
                stop == 1 -> if (steps.isEmpty() && end == 4) 4 else 3
                initial == null -> 2
                else -> 1
            }
            if (index != steps.size || expected != end || computed != flags)
                throw IOException("CJ/1 summary contradicts exact campaign plan")
            return Summary(end, flags, steps.size)
        }
    }
}
