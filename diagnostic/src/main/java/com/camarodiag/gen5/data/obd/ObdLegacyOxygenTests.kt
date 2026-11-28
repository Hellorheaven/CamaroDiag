package com.camarodiag.gen5.data.obd

import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Raw SAE J1979 / ISO 15031-5:2006 service 05 evidence, not a measured
 * oxygen sensor value or proof that the on-board monitor completed. */
data class ObdMode05Read(
    val command: String,
    val rawResponse: String,
    val positiveRecords: List<List<Int>>
)

data class ObdMode05Report(
    val protocolNumber: Int,
    val complete: Boolean,
    val sensorMasks: Set<Int>,
    val reads: List<ObdMode05Read>
) {
    val hasTestResults: Boolean get() = reads.any { read ->
        read.command.length == 6 && read.command.startsWith("05") &&
            read.command.substring(2, 4).toIntOrNull(16)?.let { it % 32 != 0 } == true &&
            read.positiveRecords.isNotEmpty()
    }
}

/** ELM legacy protocols 1..5 only. No guessed TIDs, no actuator commands.
 * Headerless functional replies are anonymous; a positive record is not
 * an attribution to a particular physical ECU. */
internal object ObdLegacyOxygenTests {
    fun protocolNumber(raw: String): Int? {
        val tokens = raw.lineSequence()
            .map { it.trim().removeSuffix(">").trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && it != "ATDPN" }.toList()
        val token = tokens.singleOrNull() ?: return null
        if (!token.matches(Regex("A?[1-9A]"))) return null
        return token.last().digitToInt(16)
    }

    private fun records(command: String, response: String): List<List<Int>>? {
        return ObdLegacyRecords.parse(command, response)
    }

    suspend fun scan(
        protocol: Int,
        request: suspend (String, Long) -> String,
        budgetMs: Long = 20_000L,
        clockMs: () -> Long = { System.nanoTime() / 1_000_000L }
    ): ObdMode05Report {
        require(protocol in 1..5 && budgetMs > 0)
        val start = clockMs()
        val reads = mutableListOf<ObdMode05Read>()
        val sensors = linkedSetOf<Int>()
        var complete = true
        suspend fun read(command: String, valid: (List<Int>) -> Boolean): List<List<Int>> {
            currentCoroutineContext().ensureActive()
            val remaining = budgetMs - (clockMs() - start)
            if (remaining <= 0) {
                complete = false
                return emptyList()
            }
            val raw = request(command, minOf(1800L, remaining))
            currentCoroutineContext().ensureActive()
            val parsed = records(command, raw).orEmpty()
            val positive = parsed.filter(valid)
            if (positive.isEmpty() || positive.size != parsed.size) complete = false
            reads += ObdMode05Read(command, raw, positive)
            return positive
        }

        // Discover the standard oxygen sensor presence PIDs with legacy
        // headers disabled. Do not infer a sensor from a vehicle profile.
        val pids = read("0100") { it.size == 6 && it[0] == 0x41 && it[1] == 0 }
        for (pid in listOf(0x13, 0x1D)) {
            val bit = pid - 1
            if (pids.none { it[2 + bit / 8] and (1 shl (7 - bit % 8)) != 0 }) continue
            val locations = read("01%02X".format(Locale.ROOT, pid)) {
                it.size == 3 && it[0] == 0x41 && it[1] == pid
            }
            for (record in locations) for (bitIndex in 0..7) {
                val mask = 1 shl bitIndex
                if (record[2] and mask != 0) sensors += mask
            }
        }
        for (sensor in sensors.sorted()) {
            val tids = linkedSetOf<Int>()
            for (base in 0..0xE0 step 32) {
                val pages = read("05%02X%02X".format(Locale.ROOT, base, sensor)) {
                    it.size == 7 && it[0] == 0x45 && it[1] == base && it[2] == sensor &&
                        (base != 0xE0 || it.last() and 1 == 0)
                }
                if (pages.isEmpty()) break
                for (page in pages) for (bit in 0 until 31) {
                    if (page[3 + bit / 8] and (1 shl (7 - bit % 8)) != 0)
                        tids += base + bit + 1
                }
                val more = pages.any { it[6] and 1 != 0 }
                if (!more) break
                if (base == 0xE0) complete = false
            }
            for (tid in tids.sorted()) {
                if (budgetMs - (clockMs() - start) <= 0) {
                    complete = false
                    break
                }
                read("05%02X%02X".format(Locale.ROOT, tid, sensor)) {
                    it.size == (if (tid in 1..4) 4 else 6) &&
                        it[0] == 0x45 && it[1] == tid && it[2] == sensor
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return ObdMode05Report(protocol, complete, sensors, reads.toList())
    }

    /** Legacy Mode 06 has TID, component/limit type and one limit per record.
     * It is not the CAN MID/TID/UASID record. Keep encoded words and do
     * not declare a component passed without monitor completion and OEM data. */
    suspend fun scanMode06(
        request: suspend (String, Long) -> String,
        budgetMs: Long = 20_000L,
        clockMs: () -> Long = { System.nanoTime() / 1_000_000L }
    ): String? {
        require(budgetMs > 0)
        val start = clockMs()
        var partial = false
        var discovered = false
        val tids = linkedSetOf<Int>()
        suspend fun read(tid: Int): List<List<Int>>? {
            currentCoroutineContext().ensureActive()
            val remaining = budgetMs - (clockMs() - start)
            if (remaining <= 0) return null
            val command = "06%02X".format(Locale.ROOT, tid)
            val raw = request(command, minOf(1800L, remaining))
            currentCoroutineContext().ensureActive()
            return records(command, raw)
        }
        for (base in 0..0xE0 step 32) {
            val messages = read(base).orEmpty()
            val pages = messages.filter {
                it.size == 7 && it[0] == 0x46 && it[1] == base && it[2] == 0xFF &&
                    (base != 0xE0 || it.last() and 1 == 0)
            }
            if (pages.isEmpty()) {
                if (discovered) partial = true
                break
            }
            discovered = true
            if (messages.size != pages.size) partial = true
            for (page in pages) for (bit in 0 until 31) {
                if (page[3 + bit / 8] and (1 shl (7 - bit % 8)) != 0)
                    tids += base + bit + 1
            }
            if (pages.none { it[6] and 1 != 0 }) break
            if (base == 0xE0) partial = true
        }
        if (!discovered) return null
        val output = mutableListOf<String>()
        val requested = tids.sorted()
        for ((index, tid) in requested.withIndex()) {
            if (budgetMs - (clockMs() - start) <= 0) {
                partial = true
                output += "UNQUERIED_TIDS=" + requested.drop(index).joinToString(",") {
                    "%02X".format(Locale.ROOT, it)
                }
                break
            }
            val messages = read(tid).orEmpty()
            val matched = messages.filter { it.size == 7 && it[0] == 0x46 && it[1] == tid }
            if (matched.isEmpty() || matched.size != messages.size) partial = true
            if (matched.isEmpty()) output += "TID=%02X: N/A".format(Locale.ROOT, tid)
            for (record in matched) {
                val cid = record[2] and 0x7F
                val limitType = if (record[2] and 0x80 == 0) "MAX" else "MIN"
                val value = (record[3] shl 8) or record[4]
                val limit = (record[5] shl 8) or record[6]
                output += "TID=%02X CID=%02X LIMIT_TYPE=%s RAW=%04X LIMIT_RAW=%04X"
                    .format(Locale.ROOT, tid, cid, limitType, value, limit)
            }
        }
        if (output.isEmpty()) output += "DISCOVERED_TIDS=0"
        if (partial) output += "PARTIAL"
        currentCoroutineContext().ensureActive()
        return output.joinToString("\n")
    }
}
