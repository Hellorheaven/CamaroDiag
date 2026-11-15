package com.camarodiag.gen5.data.obd

import java.util.Locale

/** A single-command OBD transport. Implement with Bluetooth/USB/Wi-Fi ELM or a documented provider. */
fun interface ObdCommandChannel {
    suspend fun command(command: String, timeoutMs: Long): String
}

data class ObdValue(
    val pid: Int,
    val name: String,
    val value: String,
    val unit: String = "",
    val raw: List<Int>
)

data class ObdDtc(val code: String, val status: String)

data class ObdScanReport(
    val supportedPids: Set<Int>,
    val liveData: List<ObdValue>,
    val confirmedDtcs: List<ObdDtc>,
    val pendingDtcs: List<ObdDtc>,
    val permanentDtcs: List<ObdDtc>,
    val vin: String?,
    val calibrationIds: List<String>,
    val calibrationVerificationNumbers: List<String>,
    val ecuNames: List<String>,
    val readiness: String?,
    val freezeFrame: Map<Int, List<Int>>,
    val monitorTestRaw: String?,
    val adapterInfo: Map<String, String>,
    val rawLog: List<String>
)

/**
 * Read-only SAE J1979 scanner.
 *
 * It only sends standardized OBD-II read services and adapter setup commands.
 * It does not invoke UDS programming, security access, actuator control or ECU writes.
 */
class Obd2FeatureEngine(private val channel: ObdCommandChannel) {

    private val log = mutableListOf<String>()

    private suspend fun ask(command: String, timeoutMs: Long = 1800L): String {
        val response = channel.command(command, timeoutMs).trim()
        log += "> $command -> $response"
        return response
    }

    suspend fun initialize(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6").forEach {
            result[it] = ask(it, 2500L)
        }
        result["PROTOCOL"] = ask("ATDP")
        result["VOLTAGE"] = ask("ATRV")
        return result
    }

    /** Reads Mode 01 supported-PID bitmaps (PID 00, 20, ... E0). */
    suspend fun discoverSupportedPids(): Set<Int> {
        val supported = linkedSetOf<Int>()
        var basePid = 0x00
        repeat(8) {
            val response = ask("01%02X".format(Locale.US, basePid))
            val bytes = payload(response, 0x41, basePid) ?: return supported
            if (bytes.size < 4) return supported

            for (bit in 0 until 32) {
                val mask = 1 shl (7 - (bit % 8))
                if ((bytes[bit / 8] and mask) != 0) {
                    supported += basePid + bit + 1
                }
            }

            val hasNextPage = (bytes[3] and 0x01) != 0
            if (!hasNextPage) return supported
            basePid += 0x20
        }
        return supported
    }

    suspend fun readLiveData(supportedPids: Set<Int>): List<ObdValue> {
        val requested = listOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
            0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x1C, 0x1F,
            0x21, 0x23, 0x2C, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x42, 0x43,
            0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D,
            0x4E, 0x5C, 0x5D, 0x5E, 0x5F, 0x61, 0x62, 0x63
        )
        val values = mutableListOf<ObdValue>()
        for (pid in requested) {
            if (pid !in supportedPids) continue
            val response = ask("01%02X".format(Locale.US, pid), 1200L)
            val data = payload(response, 0x41, pid) ?: continue
            decode(pid, data)?.let(values::add)
        }
        return values
    }

    suspend fun readDtcs(): Triple<List<ObdDtc>, List<ObdDtc>, List<ObdDtc>> = Triple(
        decodeDtcResponse(ask("03"), 0x43, "confirmed"),
        decodeDtcResponse(ask("07"), 0x47, "pending"),
        decodeDtcResponse(ask("0A"), 0x4A, "permanent")
    )

    suspend fun readFreezeFrame(): Map<Int, List<Int>> {
        val result = linkedMapOf<Int, List<Int>>()
        val pids = listOf(0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x42)
        for (pid in pids) {
            val data = payload(ask("02%02X00".format(Locale.US, pid)), 0x42, pid) ?: continue
            // The first two bytes after the PID are the DTC associated with the freeze frame.
            if (data.size >= 2) result[pid] = data.drop(2)
        }
        return result
    }

    suspend fun readVin(): String? =
        decodeAsciiRecords(ask("0902"), 0x49, 0x02, 17).firstOrNull()?.takeIf { it.length == 17 }

    suspend fun readCalibrationIds(): List<String> =
        decodeAsciiRecords(ask("0904"), 0x49, 0x04, 16)

    suspend fun readCalibrationVerificationNumbers(): List<String> =
        decodeHexRecords(ask("0906"), 0x49, 0x06, 4)

    suspend fun readEcuName(): List<String> =
        decodeAsciiRecords(ask("090A"), 0x49, 0x0A, 20)

    suspend fun readReadiness(): String? {
        val data = payload(ask("0101"), 0x41, 0x01) ?: return null
        if (data.size < 4) return null
        val mil = (data[0] and 0x80) != 0
        val dtcCount = data[0] and 0x7F
        return "MIL=${if (mil) "ON" else "OFF"}; DTC_COUNT=$dtcCount; RAW=${data.joinToString(" ") { "%02X".format(Locale.US, it) }}"
    }

    suspend fun readMonitorTests(): String? =
        ask("0600", 2500L).takeUnless {
            it.contains("NO DATA", true) || it.contains("?", true) || it.contains("UNABLE TO CONNECT", true)
        }

    suspend fun fullReadOnlyScan(): ObdScanReport {
        log.clear()
        val adapter = initialize()
        val supported = discoverSupportedPids()
        val live = readLiveData(supported)
        val (confirmed, pending, permanent) = readDtcs()
        val freeze = readFreezeFrame()
        val vin = readVin()
        val calIds = readCalibrationIds()
        val cvns = readCalibrationVerificationNumbers()
        val ecuNames = readEcuName()
        val readiness = readReadiness()
        val mode06 = readMonitorTests()
        return ObdScanReport(
            supportedPids = supported,
            liveData = live,
            confirmedDtcs = confirmed,
            pendingDtcs = pending,
            permanentDtcs = permanent,
            vin = vin,
            calibrationIds = calIds,
            calibrationVerificationNumbers = cvns,
            ecuNames = ecuNames,
            readiness = readiness,
            freezeFrame = freeze,
            monitorTestRaw = mode06,
            adapterInfo = adapter,
            rawLog = log.toList()
        )
    }

    private fun payload(response: String, service: Int, pid: Int): List<Int>? {
        val bytes = parseResponseBytes(response) ?: return null
        val index = bytes.indices.firstOrNull {
            it + 1 < bytes.size && bytes[it] == service && bytes[it + 1] == pid
        } ?: return null
        return bytes.drop(index + 2)
    }

    /**
     * Handles ELM output with or without spaces and with optional 11-bit CAN IDs.
     * Three-nibble tokens such as 7E8 are treated as CAN headers, not payload bytes.
     */
    private fun parseResponseBytes(response: String): List<Int>? {
        val cleaned = response.uppercase(Locale.ROOT)
            .replace(Regex("SEARCHING|BUS INIT[^\\r\\n]*|CAN ERROR|NO DATA|STOPPED|\\bOK\\b|\\bERROR\\b|UNABLE TO CONNECT"), " ")
        val tokens = cleaned.split(Regex("[^0-9A-F]+")).filter(String::isNotBlank)
        val bytes = mutableListOf<Int>()
        for (token in tokens) {
            if (token.length == 3) continue
            if (token.length % 2 != 0) continue
            for (pair in token.chunked(2)) {
                val value = pair.toIntOrNull(16) ?: return null
                bytes += value
            }
        }
        return bytes.takeIf { it.isNotEmpty() }
    }

    private fun decodeAsciiRecords(
        response: String,
        service: Int,
        pid: Int,
        recordWidth: Int
    ): List<String> {
        val data = payload(response, service, pid) ?: return emptyList()
        val records = recordData(data, recordWidth) ?: return emptyList()
        return records.map { record ->
            record.filter { it in 0x20..0x7E }.map(Int::toChar).joinToString("").trim()
        }.filter(String::isNotBlank)
    }

    private fun decodeHexRecords(
        response: String,
        service: Int,
        pid: Int,
        recordWidth: Int
    ): List<String> {
        val data = payload(response, service, pid) ?: return emptyList()
        val records = recordData(data, recordWidth) ?: return emptyList()
        return records.map { record -> record.joinToString("") { "%02X".format(Locale.US, it) } }
    }

    /**
     * Mode 09 records begin with the record count followed by fixed-width records.
     * A count-less payload is accepted only when its length is an exact multiple
     * of the known record width (some legacy adapters strip count bytes).
     */
    private fun recordData(data: List<Int>, recordWidth: Int): List<List<Int>>? {
        if (data.isEmpty() || recordWidth <= 0) return null
        val count = data.first()
        if (count in 1..32 && data.size >= 1 + count * recordWidth) {
            return data.drop(1).take(count * recordWidth).chunked(recordWidth)
        }
        if (data.size % recordWidth == 0) return data.chunked(recordWidth)
        return null
    }

    private fun decodeDtcResponse(response: String, positiveService: Int, status: String): List<ObdDtc> {
        val bytes = parseResponseBytes(response) ?: return emptyList()
        val start = bytes.indexOf(positiveService)
        if (start < 0) return emptyList()
        val payload = bytes.drop(start + 1)
        return payload.chunked(2).filter { it.size == 2 }.mapNotNull { pair ->
            val raw = (pair[0] shl 8) or pair[1]
            if (raw == 0) null else {
                val prefix = "PCBU"[(raw shr 14) and 0x03]
                val digit = (raw shr 12) and 0x03
                val code = "$prefix$digit%X%02X".format(Locale.US, (raw shr 8) and 0x0F, raw and 0xFF)
                ObdDtc(code, status)
            }
        }
    }

    private fun decode(pid: Int, data: List<Int>): ObdValue? {
        val a = data.getOrNull(0) ?: return null
        val b = data.getOrNull(1) ?: 0
        fun word(): Int? = if (data.size >= 2) (a shl 8) or b else null
        fun percent(value: Int) = value * 100.0 / 255.0
        val decoded: Triple<String, Double, String> = when (pid) {
            0x01 -> return null // Monitor status is displayed in the dedicated readiness card.
            0x03 -> return ObdValue(pid, "Fuel system status", data.joinToString(" ") { "%02X".format(Locale.US, it) }, "raw", data)
            0x04 -> Triple("Calculated engine load", percent(a), "%")
            0x05 -> Triple("Coolant temperature", (a - 40).toDouble(), "°C")
            0x06 -> Triple("Short-term fuel trim bank 1", a * 100.0 / 128.0 - 100.0, "%")
            0x07 -> Triple("Long-term fuel trim bank 1", a * 100.0 / 128.0 - 100.0, "%")
            0x08 -> Triple("Short-term fuel trim bank 2", a * 100.0 / 128.0 - 100.0, "%")
            0x09 -> Triple("Long-term fuel trim bank 2", a * 100.0 / 128.0 - 100.0, "%")
            0x0A -> Triple("Fuel pressure", (a * 3.0), "kPa")
            0x0B -> Triple("Intake manifold pressure", a.toDouble(), "kPa")
            0x0C -> Triple("Engine speed", (word() ?: return null) / 4.0, "rpm")
            0x0D -> Triple("Vehicle speed", a.toDouble(), "km/h")
            0x0E -> Triple("Timing advance", a / 2.0 - 64.0, "°")
            0x0F -> Triple("Intake air temperature", (a - 40).toDouble(), "°C")
            0x10 -> Triple("Mass air flow", (word() ?: return null) / 100.0, "g/s")
            0x11 -> Triple("Throttle position", percent(a), "%")
            0x12, 0x13, 0x1C -> return ObdValue(pid, pidName(pid), data.joinToString(" ") { "%02X".format(Locale.US, it) }, "raw", data)
            0x1F -> Triple("Run time since engine start", (word() ?: return null).toDouble(), "s")
            0x21 -> Triple("Distance with MIL on", (word() ?: return null).toDouble(), "km")
            0x23 -> Triple("Fuel rail pressure", (word() ?: return null) * 10.0, "kPa")
            0x2C -> Triple("Commanded EGR", percent(a), "%")
            0x2F -> Triple("Fuel tank level", percent(a), "%")
            0x30 -> Triple("Warm-ups since DTC clear", a.toDouble(), "count")
            0x31 -> Triple("Distance since DTC clear", (word() ?: return null).toDouble(), "km")
            0x32 -> Triple("Evaporative system vapour pressure", ((word() ?: return null) - 32767) / 4.0, "Pa")
            0x33 -> Triple("Barometric pressure", a.toDouble(), "kPa")
            0x42 -> Triple("Control module voltage", (word() ?: return null) / 1000.0, "V")
            0x43 -> Triple("Absolute load", (word() ?: return null) * 100.0 / 255.0, "%")
            0x44 -> Triple("Commanded equivalence ratio", (word() ?: return null) / 32768.0, "lambda")
            0x45 -> Triple("Relative throttle position", percent(a), "%")
            0x46 -> Triple("Ambient air temperature", (a - 40).toDouble(), "°C")
            0x47 -> Triple("Absolute throttle position B", percent(a), "%")
            0x48 -> Triple("Absolute throttle position C", percent(a), "%")
            0x49 -> Triple("Accelerator pedal position D", percent(a), "%")
            0x4A -> Triple("Accelerator pedal position E", percent(a), "%")
            0x4B -> Triple("Accelerator pedal position F", percent(a), "%")
            0x4C -> Triple("Commanded throttle actuator", percent(a), "%")
            0x4D -> Triple("Time with MIL on", (word() ?: return null).toDouble(), "min")
            0x4E -> Triple("Time since DTC clear", (word() ?: return null).toDouble(), "min")
            0x5C -> Triple("Engine oil temperature", (a - 40).toDouble(), "°C")
            0x5D -> Triple("Fuel injection timing", ((word() ?: return null) / 128.0) - 210.0, "°")
            0x5E -> Triple("Engine fuel rate", (word() ?: return null) / 20.0, "L/h")
            0x5F -> Triple("Hybrid / alternate fuel system status", percent(a), "%")
            0x61 -> Triple("Driver demand engine torque", (a - 125).toDouble(), "%")
            0x62 -> Triple("Actual engine torque", (a - 125).toDouble(), "%")
            0x63 -> Triple("Engine reference torque", (word() ?: return null).toDouble(), "Nm")
            else -> return null
        }
        return ObdValue(
            pid = pid,
            name = decoded.first,
            value = String.format(Locale.US, "%.2f", decoded.second),
            unit = decoded.third,
            raw = data
        )
    }

    private fun pidName(pid: Int): String = when (pid) {
        0x12 -> "Secondary air status"
        0x13 -> "Oxygen sensors present"
        0x1C -> "OBD standards compliance"
        else -> "PID %02X".format(Locale.US, pid)
    }
}
