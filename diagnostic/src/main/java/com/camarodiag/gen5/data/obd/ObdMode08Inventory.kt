package com.camarodiag.gen5.data.obd

import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Supported control tests only, not evidence of a successful actuation. */
data class ObdMode08Page(val base: Int, val command: String, val rawResponse: String,
    val positiveRecords: List<List<Int>>)
data class ObdMode08Inventory(val supportedTests: Set<Int>, val complete: Boolean,
    val pages: List<ObdMode08Page>, val physicalResponder: String? = null)

/** ISO 15031-5:2006 sections 6.8.2.1/2 and 7.8.2.1/2.
 * Only bitmap queries are generated, never a control test request. */
internal object ObdMode08Discovery {
    suspend fun discover(legacy: Boolean, physicalResponder: String? = null,
        request: suspend (String, Long) -> String): ObdMode08Inventory {
        val supported = linkedSetOf<Int>()
        val pages = mutableListOf<ObdMode08Page>()
        var complete = false
        for (base in 0..0xE0 step 32) {
            currentCoroutineContext().ensureActive()
            val command = "08%02X".format(Locale.ROOT, base) + if (legacy) "0000000000" else ""
            val raw = request(command, 2500L)
            currentCoroutineContext().ensureActive()
            val clean = raw.lineSequence().filterNot {
                it.trim().removeSuffix(">").filterNot(Char::isWhitespace)
                    .equals(command, ignoreCase = true)
            }.joinToString("\n")
            val messages = ObdNumberedIdentityFrames.parseAll(clean).orEmpty()
            val offset = if (legacy) 3 else 2
            val records = messages.filter {
                it.size == offset + 4 && it[0] == 0x48 && it[1] == base &&
                    (!legacy || it[2] == 0)
            }
            pages += ObdMode08Page(base, command, raw, records)
            if (records.isEmpty()) break
            for (record in records) for (bit in 0 until 31) {
                if (record[offset + bit / 8] and (1 shl (7 - bit % 8)) != 0)
                    supported += base + bit + 1
            }
            if (records.size != messages.size) break
            if (records.none { it.last() and 1 != 0 }) {
                complete = true
                break
            }
        }
        return ObdMode08Inventory(supported, complete, pages, physicalResponder)
    }
}
