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
    val respondingEcuAddresses: List<String>,
    val rawLog: List<String>,
    val freezeFrameDtc: ObdDtc? = null
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
        result["IDENTIFICATION"] = ask("ATI")
        result["DESCRIPTION"] = ask("AT@1")
        result["PROTOCOL"] = ask("ATDP")
        result["VOLTAGE"] = ask("ATRV")
        return result
    }

    /**
     * Detects 11-bit CAN response identifiers returned to the standard OBD
     * functional request 0100. These are observed response IDs only; they are
     * deliberately not mapped to GM module names without a verified vehicle profile.
     */
    suspend fun discoverFunctionalObdResponders(): List<String> {
        return try {
            ask("ATS1")
            ask("ATH1")
            val response = ask("0100", 2500L)
            val ids = Regex("(?i)(?:^|\\s)([0-9A-F]{3})\\s+(?=(?:[0-9A-F]{2}\\s*){4,})")
                .findAll(response)
                .mapNotNull { match ->
                    val id = match.groupValues[1].uppercase(Locale.ROOT)
                    val numeric = id.toIntOrNull(16) ?: return@mapNotNull null
                    id.takeIf { numeric in 0x700..0x7FF && numeric != 0x7DF }
                }
                .distinct()
                .sorted()
                .toList()
            ids
        } finally {
            // Restore normal ELM output framing regardless of the scan result.
            runCatching { ask("ATH0") }
            runCatching { ask("ATS0") }
        }
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
                if ((bytes[bit / 8] and mask) != 0 && bit != 31) {
                    // Bit 31 is the continuation flag for the next bitmap page,
                    // not a queryable Mode 01 PID (0x20, 0x40, ...).
                    supported += basePid + bit + 1
                }
            }

            val hasNextPage = (bytes[3] and 0x01) != 0
            if (!hasNextPage) return supported
            basePid += 0x20
        }
        return supported
    }

    /**
     * Reads one Mode 01 PID only when the ECU has advertised it in its support bitmap.
     * Returns the raw ELM response so vehicle-specific presentation code can retain
     * the original response alongside any decoded value.
     */
    suspend fun readMode01PidIfSupported(pid: Int, supportedPids: Set<Int>): String? {
        require(pid in 0x01..0xFF) { "Mode 01 PID must be a single byte." }
        if (pid !in supportedPids) return null
        return ask("01%02X".format(Locale.US, pid), 1800L)
    }

    suspend fun readLiveData(supportedPids: Set<Int>): List<ObdValue> {
        val requested = listOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
            0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x1C, 0x1F,
            0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A,
            0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34,
            0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E,
            0x3F, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A,
            0x4B, 0x4C, 0x4D, 0x4E, 0x52, 0x5A, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F,
            0x59, 0x61, 0x62, 0x63
        )
        val values = mutableListOf<ObdValue>()
        for (pid in requested) {
            if (pid !in supportedPids) continue
            val response = ask("01%02X".format(Locale.US, pid), 1200L)
            val data = payload(response, 0x41, pid) ?: continue
            values += decode(pid, data)
        }
        return values
    }

    suspend fun readDtcs(): Triple<List<ObdDtc>, List<ObdDtc>, List<ObdDtc>> = Triple(
        decodeDtcResponse(ask("03"), 0x43, "confirmed"),
        decodeDtcResponse(ask("07"), 0x47, "pending"),
        decodeDtcResponse(ask("0A"), 0x4A, "permanent")
    )

    /** Reads the DTC that caused freeze-frame storage (Mode 02, PID 02, frame 00). */
    suspend fun readFreezeFrameDtc(): ObdDtc? {
        val data = payload(ask("020200"), 0x42, 0x02) ?: return null
        // Mode 02 payload begins with the frame number, followed by the two DTC bytes.
        if (data.size < 3 || data[0] != 0x00) return null
        val raw = (data[1] shl 8) or data[2]
        if (raw == 0) return null
        val prefix = "PCBU"[(raw shr 14) and 0x03]
        val digit = (raw shr 12) and 0x03
        val code = "$prefix$digit%X%02X".format(
            Locale.US, (raw shr 8) and 0x0F, raw and 0xFF
        )
        return ObdDtc(code, "freeze-frame")
    }

    /** Discovers Mode 02 PIDs using the independent freeze-frame support bitmap (PID 00). */
    suspend fun discoverFreezeFrameSupportedPids(): Set<Int> {
        val supported = linkedSetOf<Int>()
        var basePid = 0x00
        repeat(8) {
            val response = ask("02%02X00".format(Locale.US, basePid))
            val data = payload(response, 0x42, basePid) ?: return supported
            // Mode 02 echoes the requested frame number before the four bitmap bytes.
            if (data.size < 5 || data[0] != 0x00) return supported
            val bitmap = data.drop(1).take(4)
            for (bit in 0 until 32) {
                val mask = 1 shl (7 - (bit % 8))
                if ((bitmap[bit / 8] and mask) != 0 && bit != 31) {
                    supported += basePid + bit + 1
                }
            }
            val hasNextPage = (bitmap[3] and 0x01) != 0
            if (!hasNextPage) return supported
            basePid += 0x20
        }
        return supported
    }

    /**
     * Reads freeze-frame values only for PIDs advertised by the Mode 02 support bitmap.
     * Each Mode 02 response contains the frame number before the actual PID data.
     */
    suspend fun readFreezeFrame(supportedPids: Set<Int>? = null): Map<Int, List<Int>> {
        val result = linkedMapOf<Int, List<Int>>()
        val pids = listOf(0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x42)
        for (pid in pids) {
            if (supportedPids != null && pid !in supportedPids) continue
            val data = payload(ask("02%02X00".format(Locale.US, pid)), 0x42, pid) ?: continue
            if (data.isEmpty() || data[0] != 0x00) continue
            val valueBytes = data.drop(1) // Drop only the echoed frame number.
            if (valueBytes.isNotEmpty()) result[pid] = valueBytes
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
        val respondingEcus = discoverFunctionalObdResponders()
        val supported = discoverSupportedPids()
        val live = readLiveData(supported)
        val (confirmed, pending, permanent) = readDtcs()
        val freezeFrameDtc = readFreezeFrameDtc()
        val freezeFrameSupported = if (freezeFrameDtc != null) discoverFreezeFrameSupportedPids() else emptySet()
        val freeze = if (freezeFrameDtc != null) readFreezeFrame(freezeFrameSupported) else emptyMap()
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
            respondingEcuAddresses = respondingEcus,
            rawLog = log.toList(),
            freezeFrameDtc = freezeFrameDtc
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
            .replace(
                Regex("SEARCHING(?:\\.\\.\\.)?|BUS INIT[^\\r\\n]*|CAN ERROR|BUS ERROR|DATA ERROR|BUFFER FULL|FB ERROR|LP ALERT|NO DATA|STOPPED|UNABLE TO CONNECT|\\bOK\\b|\\bERROR\\b|>"),
                " "
            )
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

    private fun decode(pid: Int, data: List<Int>): List<ObdValue> {
        val a = data.getOrNull(0) ?: return emptyList()
        val b = data.getOrNull(1) ?: 0
        fun word(): Int? = if (data.size >= 2) (a shl 8) or b else null
        fun percent(value: Int) = value * 100.0 / 255.0
        fun value(name: String, number: Double, unit: String) = listOf(
            ObdValue(
                pid = pid,
                name = name,
                value = String.format(Locale.US, "%.2f", number),
                unit = unit,
                raw = data
            )
        )
        fun raw(name: String = pidName(pid)) = listOf(
            ObdValue(
                pid = pid,
                name = name,
                value = data.joinToString(" ") { "%02X".format(Locale.US, it) },
                unit = "raw",
                raw = data
            )
        )

        if (pid in 0x24..0x2B) {
            if (data.size < 4) return emptyList()
            val sensorIndex = pid - 0x24
            val bank = if (sensorIndex < 4) 1 else 2
            val sensor = sensorIndex % 4 + 1
            val sensorLabel = "Bank $bank sensor $sensor"
            val equivalenceRatio = ((data[0] shl 8) or data[1]) * 2.0 / 65536.0
            val voltage = ((data[2] shl 8) or data[3]) * 8.0 / 65536.0
            return value("O2 $sensorLabel equivalence ratio", equivalenceRatio, "lambda") +
                value("O2 $sensorLabel voltage", voltage, "V")
        }

        if (pid in 0x34..0x3B) {
            if (data.size < 4) return emptyList()
            val sensorIndex = pid - 0x34
            val bank = if (sensorIndex < 4) 1 else 2
            val sensor = sensorIndex % 4 + 1
            val sensorLabel = "Bank $bank sensor $sensor"
            val equivalenceRatio = ((data[0] shl 8) or data[1]) * 2.0 / 65536.0
            val current = ((data[2] shl 8) or data[3]) / 256.0 - 128.0
            return value("O2 $sensorLabel equivalence ratio", equivalenceRatio, "lambda") +
                value("O2 $sensorLabel current", current, "mA")
        }

        val decoded: Pair<String, Pair<Double, String>> = when (pid) {
            0x01 -> return emptyList() // Monitor status is displayed in the dedicated readiness card.
            0x03 -> return raw("Fuel system status")
            0x04 -> "Calculated engine load" to (percent(a) to "%")
            0x05 -> "Coolant temperature" to ((a - 40).toDouble() to "°C")
            0x06 -> "Short-term fuel trim bank 1" to ((a * 100.0 / 128.0 - 100.0) to "%")
            0x07 -> "Long-term fuel trim bank 1" to ((a * 100.0 / 128.0 - 100.0) to "%")
            0x08 -> "Short-term fuel trim bank 2" to ((a * 100.0 / 128.0 - 100.0) to "%")
            0x09 -> "Long-term fuel trim bank 2" to ((a * 100.0 / 128.0 - 100.0) to "%")
            0x0A -> "Fuel pressure" to ((a * 3.0) to "kPa")
            0x0B -> "Intake manifold pressure" to (a.toDouble() to "kPa")
            0x0C -> "Engine speed" to ((word() ?: return emptyList()) / 4.0 to "rpm")
            0x0D -> "Vehicle speed" to (a.toDouble() to "km/h")
            0x0E -> "Timing advance" to ((a / 2.0 - 64.0) to "°")
            0x0F -> "Intake air temperature" to ((a - 40).toDouble() to "°C")
            0x10 -> "Mass air flow" to ((word() ?: return emptyList()) / 100.0 to "g/s")
            0x11 -> "Throttle position" to (percent(a) to "%")
            0x12, 0x13, 0x1C, 0x51 -> return raw()
            0x1F -> "Run time since engine start" to ((word() ?: return emptyList()).toDouble() to "s")
            0x21 -> "Distance with MIL on" to ((word() ?: return emptyList()).toDouble() to "km")
            0x22 -> "Fuel rail pressure relative to manifold vacuum" to ((word() ?: return emptyList()) * 0.079 to "kPa")
            0x23 -> "Fuel rail pressure" to ((word() ?: return emptyList()) * 10.0 to "kPa")
            0x2C -> "Commanded EGR" to (percent(a) to "%")
            0x2D -> "EGR error" to ((a * 100.0 / 128.0 - 100.0) to "%")
            0x2E -> "Commanded evaporative purge" to (percent(a) to "%")
            0x2F -> "Fuel tank level" to (percent(a) to "%")
            0x30 -> "Warm-ups since DTC clear" to (a.toDouble() to "count")
            0x31 -> "Distance since DTC clear" to ((word() ?: return emptyList()).toDouble() to "km")
            0x32 -> "Evaporative system vapour pressure" to (((word() ?: return emptyList()) - 32768) / 4.0 to "Pa")
            0x33 -> "Barometric pressure" to (a.toDouble() to "kPa")
            0x3C, 0x3D, 0x3E, 0x3F -> {
                val sensor = when (pid) {
                    0x3C -> "Bank 1 sensor 1"
                    0x3D -> "Bank 2 sensor 1"
                    0x3E -> "Bank 1 sensor 2"
                    else -> "Bank 2 sensor 2"
                }
                "$sensor catalyst temperature" to (((word() ?: return emptyList()) / 10.0 - 40.0) to "°C")
            }
            0x42 -> "Control module voltage" to ((word() ?: return emptyList()) / 1000.0 to "V")
            0x43 -> "Absolute load" to ((word() ?: return emptyList()) * 100.0 / 255.0 to "%")
            0x44 -> "Commanded equivalence ratio" to ((word() ?: return emptyList()) / 32768.0 to "lambda")
            0x45 -> "Relative throttle position" to (percent(a) to "%")
            0x46 -> "Ambient air temperature" to ((a - 40).toDouble() to "°C")
            0x47 -> "Absolute throttle position B" to (percent(a) to "%")
            0x48 -> "Absolute throttle position C" to (percent(a) to "%")
            0x49 -> "Accelerator pedal position D" to (percent(a) to "%")
            0x4A -> "Accelerator pedal position E" to (percent(a) to "%")
            0x4B -> "Accelerator pedal position F" to (percent(a) to "%")
            0x4C -> "Commanded throttle actuator" to (percent(a) to "%")
            0x4D -> "Time with MIL on" to ((word() ?: return emptyList()).toDouble() to "min")
            0x4E -> "Time since DTC clear" to ((word() ?: return emptyList()).toDouble() to "min")
            0x52 -> "Ethanol fuel percentage" to (percent(a) to "%")
            0x59 -> "Fuel rail absolute pressure" to ((word() ?: return emptyList()) * 10.0 to "kPa")
            0x5A -> "Relative accelerator pedal position" to (percent(a) to "%")
            0x5B -> "Hybrid battery pack remaining life" to (percent(a) to "%")
            0x5C -> "Engine oil temperature" to ((a - 40).toDouble() to "°C")
            0x5D -> "Fuel injection timing" to (((word() ?: return emptyList()) / 128.0) - 210.0 to "°")
            0x5E -> "Engine fuel rate" to ((word() ?: return emptyList()) / 20.0 to "L/h")
            0x5F -> "Hybrid / alternate fuel system status" to (percent(a) to "%")
            0x61 -> "Driver demand engine torque" to ((a - 125).toDouble() to "%")
            0x62 -> "Actual engine torque" to ((a - 125).toDouble() to "%")
            0x63 -> "Engine reference torque" to ((word() ?: return emptyList()).toDouble() to "Nm")
            else -> return emptyList()
        }
        return value(decoded.first, decoded.second.first, decoded.second.second)
    }

    private fun pidName(pid: Int): String = when (pid) {
        0x12 -> "Secondary air status"
        0x13 -> "Oxygen sensors present"
        0x1C -> "OBD standards compliance"
        else -> "PID %02X".format(Locale.US, pid)
    }
}
