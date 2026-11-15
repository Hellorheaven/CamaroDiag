package com.camarodiag.gen5.data.obd

/** A single-command OBD transport. Implement with Bluetooth/USB/Wi-Fi ELM or a documented VCX provider. */
fun interface ObdCommandChannel { suspend fun command(command: String, timeoutMs: Long): String }

data class ObdValue(val pid: Int, val name: String, val value: String, val unit: String = "", val raw: List<Int>)
data class ObdDtc(val code: String, val status: String)
data class ObdScanReport(
    val supportedPids: Set<Int>, val liveData: List<ObdValue>, val confirmedDtcs: List<ObdDtc>,
    val pendingDtcs: List<ObdDtc>, val permanentDtcs: List<ObdDtc>, val vin: String?,
    val calibrationIds: List<String>, val readiness: String?, val freezeFrame: Map<Int, List<Int>>,
    val monitorTestRaw: String?, val adapterInfo: Map<String, String>, val rawLog: List<String>
)

/** Read-only SAE J1979 feature set. No manufacturer-specific write/security access is attempted. */
class Obd2FeatureEngine(private val channel: ObdCommandChannel) {
    private val log = mutableListOf<String>()
    private suspend fun ask(cmd: String, timeout: Long = 1800): String {
        val response = channel.command(cmd, timeout).trim()
        log += "$ $cmd  ->  $response"
        return response
    }

    suspend fun initialize(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach { result[it] = ask(it, 2500) }
        result["PROTOCOL"] = ask("ATDP")
        result["VOLTAGE"] = ask("ATRV")
        return result
    }

    suspend fun discoverSupportedPids(): Set<Int> {
        val supported = linkedSetOf<Int>()
        var base = 0
        repeat(8) {
            val response = ask("01%02X".format(base))
            val bytes = payload(response, 0x41, base) ?: return@repeat
            if (bytes.size < 4) return@repeat
            for (bit in 0 until 32) if ((bytes[bit / 8] and (1 shl (7 - bit % 8))) != 0) supported += base + bit + 1
            val continues = (bytes[3] and 1) != 0
            if (!continues) return supported
            base += 0x20
        }
        return supported
    }

    suspend fun readLiveData(pids: Set<Int>): List<ObdValue> {
        val values = mutableListOf<ObdValue>()
        val supportedCommon = listOf(0x01,0x03,0x04,0x05,0x06,0x07,0x08,0x09,0x0A,0x0B,0x0C,0x0D,0x0E,0x0F,0x10,0x11,0x14,0x15,0x1C,0x1F,0x21,0x2F,0x33,0x42,0x43,0x44,0x45,0x46,0x47,0x49,0x4A,0x4C,0x5C,0x5E,0x5F)
        for (pid in supportedCommon.filter { it in pids }) {
            val b = payload(ask("01%02X".format(pid)), 0x41, pid) ?: continue
            decode(pid, b)?.let(values::add)
        }
        return values
    }

    suspend fun readDtcs(): Triple<List<ObdDtc>, List<ObdDtc>, List<ObdDtc>> = Triple(
        decodeDtcResponse(ask("03"), "confirmed"),
        decodeDtcResponse(ask("07"), "pending"),
        decodeDtcResponse(ask("0A"), "permanent")
    )

    suspend fun readFreezeFrame(): Map<Int, List<Int>> {
        val result = linkedMapOf<Int, List<Int>>()
        for (pid in listOf(0x02,0x03,0x04,0x05,0x0C,0x0D,0x0F,0x11,0x42)) {
            val b = payload(ask("02%02X00".format(pid)), 0x42, pid)?.drop(1)?.toIntArray() ?: continue
            result[pid] = b.toList()
        }
        return result
    }

    suspend fun readVin(): String? = decodeAsciiRecords(ask("0902"), 0x49, 0x02).firstOrNull()?.takeIf { it.length == 17 }
    suspend fun readCalibrationIds(): List<String> = decodeAsciiRecords(ask("0904"), 0x49, 0x04)
    suspend fun readEcuName(): List<String> = decodeAsciiRecords(ask("090A"), 0x49, 0x0A)
    suspend fun readReadiness(): String? {
        val b = payload(ask("0101"), 0x41, 0x01) ?: return null
        if (b.size < 4) return null
        val mil = (b[0] and 0x80) != 0
        val count = b[0] and 0x7F
        return "MIL=${if (mil) "ON" else "OFF"}; DTC_COUNT=$count; RAW=${b.joinToString(" ") { "%02X".format(it) }}"
    }
    suspend fun readMonitorTests(): String? = ask("0600").takeUnless { it.contains("NO DATA", true) || it.contains("?", true) }

    suspend fun fullReadOnlyScan(): ObdScanReport {
        log.clear()
        val adapter = initialize()
        val pids = discoverSupportedPids()
        val live = readLiveData(pids)
        val dtcs = readDtcs()
        val freeze = readFreezeFrame()
        val vin = readVin()
        val cal = readCalibrationIds()
        val readiness = readReadiness()
        val mode06 = readMonitorTests()
        return ObdScanReport(pids, live, dtcs.first, dtcs.second, dtcs.third, vin, cal, readiness, freeze, mode06, adapter, log.toList())
    }

    private fun payload(response: String, service: Int, pid: Int): IntArray? {
        val bytes = hexBytes(response) ?: return null
        val index = bytes.indices.firstOrNull { it + 1 < bytes.size && bytes[it] == service && bytes[it + 1] == pid } ?: return null
        return bytes.drop(index + 2).toIntArray()
    }
    private fun hexBytes(s: String): List<Int>? {
        val normalized = s.uppercase()
            .replace(Regex("SEARCHING|BUS INIT[^\\r\\n]*|CAN ERROR|NO DATA|STOPPED|\\bOK\\b|\\bERROR\\b"), " ")
            .replace(Regex("[^0-9A-F]"), "")
        if (normalized.length < 2 || normalized.length % 2 != 0) return null
        return normalized.chunked(2).mapNotNull { it.toIntOrNull(16) }.takeIf { it.isNotEmpty() }
    }
    private fun decode(pid: Int, b: IntArray): ObdValue? {
        val a = b.getOrNull(0) ?: return null
        val second = b.getOrNull(1) ?: 0
        val (name, value, unit) = when (pid) {
            0x01 -> Triple("Monitor status", b.joinToString(" ") { "%02X".format(it) }, "raw")
            0x03 -> Triple("Fuel system status", b.joinToString(" ") { "%02X".format(it) }, "raw")
            0x04 -> Triple("Calculated engine load", a * 100.0 / 255, "%")
            0x05 -> Triple("Coolant temperature", a - 40.0, "°C")
            0x06,0x07,0x08,0x09 -> Triple("Fuel trim / O2 monitor", a * 100.0 / 128 - 100, "%")
            0x0A -> Triple("Fuel pressure", a * 3.0, "kPa")
            0x0B -> Triple("Intake manifold pressure", a.toDouble(), "kPa")
            0x0C -> if (b.size < 2) return null else Triple("Engine speed", (a * 256 + second) / 4.0, "rpm")
            0x0D -> Triple("Vehicle speed", a.toDouble(), "km/h")
            0x0E -> Triple("Timing advance", a / 2.0 - 64, "°")
            0x0F -> Triple("Intake air temperature", a - 40.0, "°C")
            0x10 -> if (b.size < 2) return null else Triple("MAF air flow", (a * 256 + second) / 100.0, "g/s")
            0x11 -> Triple("Throttle position", a * 100.0 / 255, "%")
            0x1F -> if (b.size < 2) return null else Triple("Run time since start", (a * 256 + second).toDouble(), "s")
            0x21 -> if (b.size < 2) return null else Triple("Distance with MIL on", (a * 256 + second).toDouble(), "km")
            0x2F -> Triple("Fuel tank level", a * 100.0 / 255, "%")
            0x33 -> Triple("Barometric pressure", a.toDouble(), "kPa")
            0x42 -> if (b.size < 2) return null else Triple("Control module voltage", (a * 256 + second) / 1000.0, "V")
            0x43 -> if (b.size < 2) return null else Triple("Absolute load", (a * 256 + second) * 100.0 / 255, "%")
            0x44 -> if (b.size < 4) return null else Triple("Commanded equivalence ratio", ((a * 256 + second) / 32768.0), "lambda")
            0x45,0x47,0x49,0x4A,0x4C -> Triple("Position", a * 100.0 / 255, "%")
            0x46 -> Triple("Ambient air temperature", a - 40.0, "°C")
            0x5C -> Triple("Engine oil temperature", a - 40.0, "°C")
            0x5E -> if (b.size < 2) return null else Triple("Engine fuel rate", (a * 256 + second) / 20.0, "L/h")
            else -> return null
        }
        val display = when (value) { is Double -> "%.2f".format(java.util.Locale.US, value); else -> value.toString() }
        return ObdValue(pid, name, display, unit, b.toList())
    }
    private fun decodeDtcResponse(response: String, status: String): List<ObdDtc> {
        val bytes = hexBytes(response) ?: return emptyList()
        val start = bytes.indexOf(0x40 + when(status) { "confirmed" -> 3; "pending" -> 7; else -> 10 })
        if (start < 0) return emptyList()
        return bytes.drop(start + 1).chunked(2).filter { it.size == 2 }.mapNotNull { pair ->
            val raw = (pair[0] shl 8) or pair[1]
            if (raw == 0) null else {
                val prefix = "PCBU"[(raw shr 14) and 3]
                val code = "$prefix${(raw shr 12) and 3}%X%02X".format((raw shr 8) and 15, raw and 255)
                ObdDtc(code, status)
            }
        }
    }
    private fun decodeAsciiRecords(response: String, service: Int, pid: Int): List<String> {
        val bytes = hexBytes(response) ?: return emptyList()
        val start = bytes.indices.firstOrNull { it + 1 < bytes.size && bytes[it] == service && bytes[it + 1] == pid } ?: return emptyList()
        val ascii = bytes.drop(start + 2).filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("")
        return ascii.split(Regex("\\s+")).filter { it.isNotBlank() }
    }
}
