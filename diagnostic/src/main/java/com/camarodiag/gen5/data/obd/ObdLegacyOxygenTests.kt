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
        val clean = response.lineSequence().filterNot {
            it.trim().removeSuffix(">").filterNot(Char::isWhitespace)
                .equals(command, ignoreCase = true)
        }.joinToString("\n")
        if (ObdNumberedIdentityFrames.hasPhysicalHeaders(clean)) return null
        return ObdNumberedIdentityFrames.parseAll(clean)
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
                    it.size == 7 && it[0] == 0x45 && it[1] == base && it[2] == sensor
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
}
