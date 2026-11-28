package com.camarodiag.connectors

import java.io.IOException
import java.util.Collections
import java.util.Locale
import java.util.zip.CRC32

/** Independent offline CM/1 replay. A consistent journal cannot authorize any retry. */
object J1979MaintenanceJournal {
    const val HEADER = 12
    const val SCOPE = 31
    const val SUMMARY = 20
    const val ROW = 18
    const val MAX_ROWS = 16
    const val MAX_REPLY = 512
    const val MAX_RECORD = HEADER + SCOPE + SUMMARY + MAX_ROWS * (ROW + MAX_REPLY) + 4
    const val MAX_JOURNAL = 4 * MAX_RECORD
    private val kinds = arrayOf("", "OPEN", "BASELINE", "INTENT", "TERMINAL")
    private val outcomes = arrayOf("RUNNING", "NOT_TESTED", "REFUSED", "UNCERTAIN",
        "ACKNOWLEDGED_PARTIAL", "ACKNOWLEDGED_READBACK", "ACKNOWLEDGED_EVAP")
    private val reasons = arrayOf("NONE", "INVALID_SCOPE", "CONTEXT", "DEADLINE", "SEQUENCE", "IDENTITY",
        "STATIONARY", "UNSUPPORTED", "BASELINE", "JOURNAL", "TRANSPORT", "NEGATIVE", "READBACK", "DTC_REMAINING")
    private val flagsA = arrayOf("baseline_persisted", "intent_persisted", "attempted", "acknowledged",
        "initial_vin", "rechecked_vin", "final_vin", "before_confirmed")
    private val flagsB = arrayOf("after_confirmed", "after_pending", "after_confirmed_empty", "after_pending_empty",
        "physical_stop_declared", "recovery_required")
    private val clear = arrayOf("0902", "03", "07", "0A", "0101", "010D", "010C", "0902", "010D", "010C", "04",
        "03", "07", "0A", "0101", "0902")
    private val clearStages = intArrayOf(1, 2, 2, 2, 2, 2, 2, 4, 4, 4, 6, 7, 7, 7, 7, 7)
    private val evap = arrayOf("0902", "0800", "010D", "010C", "0902", "0800", "010D", "010C", "0801")
    private val evapStages = intArrayOf(1, 2, 2, 2, 4, 4, 4, 4, 6)

    private fun checkWire(value: Boolean, message: String) {
        if (!value) throw IOException(message)
    }
    private fun u(b: Byte) = CuWire.unsigned(b)
    private fun u16(b: ByteArray, at: Int) = CuCaptureReader.le16(b, at)
    private fun u32(b: ByteArray, at: Int) = CuCaptureReader.le32(b, at)
    private fun zero(b: ByteArray, begin: Int, end: Int) = (begin until end).all { b[it] == 0.toByte() }
    private fun hex(b: ByteArray) = J1979LiveNativeReplay.hex(b)
    private fun stage(n: Int): String = when (n) {
        1 -> "INITIAL"; 2 -> "BASELINE"; 4 -> "RECHECK"; 6 -> "COMMAND"; 7 -> "READBACK"
        else -> throw IOException("Invalid stage")
    }
    private fun status(n: Int): String = when (n) {
        1 -> "POSITIVE"; 2 -> "NEGATIVE"; 3 -> "TIMEOUT"; 4 -> "FAULT"
        else -> throw IOException("Invalid status")
    }
    private fun map(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        Collections.unmodifiableMap(linkedMapOf(*pairs))
    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

    private class Scope(data: ByteArray) {
        init { checkWire(data.size == SCOPE && u(data[0]) in 1..2 && u(data[1]) <= 1, "Invalid CM/1 scope") }
        val operation = u(data[0])
        val extended = u(data[1]) == 1
        val tx = u32(data, 2)
        val rx = u32(data, 6)
        val budget = u32(data, 10)
        val vin = String(data, 14, 17, Charsets.US_ASCII)
        val wire = data.copyOf()
        init {
            checkWire((if (extended) tx == 0x18da10f1L && rx == 0x18daf110L else tx == 0x7e0L && rx == 0x7e8L) &&
                budget in 1000L..60000L && CuWire.validVin(vin), "Invalid maintenance target")
        }
        val plan get() = if (operation == 1) clear else evap
        val stages get() = if (operation == 1) clearStages else evapStages
        val baseline get() = if (operation == 1) 7 else 4
        val intent get() = if (operation == 1) 10 else 8
        fun view() = map("operation" to if (operation == 1) "CLEAR" else "EVAP",
            "extended" to extended, "request_can_id" to tx.toString(16).uppercase(Locale.ROOT),
            "response_can_id" to rx.toString(16).uppercase(Locale.ROOT),
            "budget_ms" to budget, "expected_vin" to vin)
    }

    private class Event(data: ByteArray, pos: Int, size: Int) {
        val stage = u(data[pos])
        val status = u(data[pos + 1])
        val start = u32(data, pos + 8)
        val end = u32(data, pos + 12)
        val timeout = u16(data, pos + 16)
        private val n = u(data[pos + 2])
        init {
            J1979MaintenanceJournal.stage(stage)
            J1979MaintenanceJournal.status(status)
            checkWire(n in 1..2 && data[pos + 5] == 0.toByte() &&
                (n == 2 || data[pos + 4] == 0.toByte()) &&
                size <= MAX_REPLY && start <= end && timeout in 1..1800, "Malformed CM/1 observation")
        }
        val request = data.copyOfRange(pos + 3, pos + 3 + n)
        val cmd = hex(request)
        val response = data.copyOfRange(pos + ROW, pos + ROW + size)
        val wire = data.copyOfRange(pos, pos + ROW + size)
        fun view() = map("stage" to J1979MaintenanceJournal.stage(stage),
            "status" to J1979MaintenanceJournal.status(status), "request_hex" to cmd,
            "response_hex" to if (response.isEmpty()) null else hex(response),
            "started_ms" to start, "ended_ms" to end, "timeout_ms" to timeout)
        fun shape(): Int {
            val r = response
            if (r.isEmpty()) return if (status in 3..4) status else 0
            if (u(r[0]) == 0x7f) return if (r.size == 3 && r[1] == request[0] &&
                u(r[2]) != 0 && u(r[2]) != 0x78) 2 else 0
            if (cmd == "04") return if (r.size == 1 && u(r[0]) == 0x44) 1 else 0
            if (cmd == "0801") return if (r.size == 2 && u(r[0]) == 0x48 && u(r[1]) == 1) 1 else 0
            if (u(request[0]) in listOf(3, 7, 10)) {
                if (r.size < 2 || u(r[0]) != u(request[0]) + 64 || r.size != 2 + 2 * u(r[1])) return 0
                return if ((2 until r.size step 2).any { r[it] == 0.toByte() && r[it + 1] == 0.toByte() }) 0 else 1
            }
            if (cmd == "0902") return if (r.size == 20 && u(r[0]) == 0x49 &&
                u(r[1]) == 2 && u(r[2]) == 1 && CuWire.validVin(String(r, 3, 17, Charsets.US_ASCII))) 1 else 0
            val size = when (cmd) { "0101", "0800" -> 6; "010D" -> 3; "010C" -> 4; else -> 0 }
            return if (size > 0 && r.size == size && u(r[0]) == u(request[0]) + 64 && r[1] == request[1]) 1 else 0
        }
    }

    private data class Summary(var outcome: Int = 0, var reason: Int = 0, var count: Int = 0,
                               var nrc: Int = 0, var a: Int = 0, var b: Int = 0, var duration: Long = 0) {
        fun abort(why: Int) {
            reason = why
            outcome = if (a and 8 != 0) 4 else if (a and 6 != 0) 3 else 1
        }
        fun view(): Map<String, Any?> {
            val d = linkedMapOf<String, Any?>("outcome" to outcomes[outcome], "reason" to reasons[reason],
                "observations" to count, "nrc" to nrc, "duration_ms" to duration)
            flagsA.forEachIndexed { i, key -> d[key] = a and (1 shl i) != 0 }
            flagsB.forEachIndexed { i, key -> d[key] = b and (1 shl i) != 0 }
            return Collections.unmodifiableMap(d)
        }
    }
    private fun summary(data: ByteArray, at: Int, count: Int): Summary {
        val s = Summary(u(data[at]), u(data[at + 1]), u(data[at + 2]), u(data[at + 3]),
            u(data[at + 4]), u(data[at + 5]), u32(data, at + 8))
        checkWire(s.outcome < outcomes.size && s.reason < reasons.size && s.count == count &&
            s.b and 0xc0 == 0 && zero(data, at + 6, at + 8) && zero(data, at + 12, at + SUMMARY),
            "Invalid CM/1 summary")
        return s
    }
    private data class Entry(val kind: Int, val scope: Scope, val summary: Summary?, val rows: List<Event>)
    private fun parse(data: ByteArray): List<Entry> {
        checkWire(data.isNotEmpty() && data.size <= MAX_JOURNAL, "Invalid CM/1 input size")
        var pos = 0
        var prior = 0
        val result = ArrayList<Entry>()
        while (pos < data.size) {
            checkWire(result.size < 4 && data.size - pos >= HEADER, "Truncated or excessive CM/1 records")
            val kind = u(data[pos + 3])
            val count = u(data[pos + 5])
            val len = u32(data, pos + 8)
            checkWire(data[pos] == 'C'.code.toByte() && data[pos + 1] == 'M'.code.toByte() &&
                u(data[pos + 2]) == 1 && u(data[pos + 4]) == result.size && u16(data, pos + 6) == 0 &&
                kind in 1..4 && count <= MAX_ROWS && prior != 4 &&
                (if (prior == 0) kind == 1 else kind == prior + 1 || kind == 4), "Unexpected CM/1 header")
            checkWire(len in SCOPE.toLong()..(MAX_RECORD - HEADER - 4).toLong() &&
                len + HEADER + 4 <= data.size - pos, "CM/1 body size")
            val end = pos + HEADER + len.toInt()
            var cursor = pos + HEADER
            val crc = CRC32().also { it.update(data, pos, end - pos) }
            checkWire(crc.value == u32(data, end), "CM/1 CRC mismatch")
            val scope = Scope(data.copyOfRange(cursor, cursor + SCOPE))
            cursor += SCOPE
            var claimed: Summary? = null
            val rows = ArrayList<Event>()
            if (kind == 1) checkWire(count == 0 && cursor == end, "Unexpected open payload")
            else {
                checkWire(cursor + SUMMARY <= end, "Truncated summary")
                claimed = summary(data, cursor, count)
                cursor += SUMMARY
                repeat(count) {
                    checkWire(cursor + ROW <= end, "Truncated observation header")
                    val size = u16(data, cursor + 6)
                    checkWire(size <= MAX_REPLY && cursor + ROW + size <= end, "Truncated observation")
                    rows.add(Event(data, cursor, size))
                    cursor += ROW + size
                }
                checkWire(cursor == end, "Trailing CM/1 record bytes")
            }
            result.add(Entry(kind, scope, claimed, rows))
            prior = kind
            pos = end + 4
        }
        return result
    }

    private fun replay(scope: Scope, rows: List<Event>, baseline: Boolean, intent: Boolean): Summary {
        val s = Summary()
        var now = 0L
        var identity = -1L
        var speed = -1L
        var rpm = -1L
        val plan = scope.plan
        val stages = scope.stages
        checkWire(rows.size <= plan.size, "Too many observations")
        for ((i, e) in rows.withIndex()) {
            checkWire(s.outcome == 0 && e.stage == stages[i] && e.cmd == plan[i], "Unexpected maintenance request")
            checkWire(e.start >= now && e.start < scope.budget && e.timeout <= scope.budget - e.start, "Invalid deadline")
            if (i == scope.baseline) {
                checkWire(baseline, "Missing baseline")
                s.a = s.a or 1
            }
            if (i == scope.intent) {
                checkWire(intent && baseline && identity >= 0 && speed >= 0 && rpm >= 0 &&
                    e.start - identity + e.timeout <= 5000 && e.start - speed + e.timeout <= 5000 &&
                    e.start - rpm + e.timeout <= 5000, "Command without fresh checks and intent")
                s.a = s.a or 6
            }
            now = e.end
            s.count++
            s.duration = now
            if (now >= scope.budget || now - e.start > e.timeout) { s.abort(3); continue }
            if (e.status == 4) {
                checkWire(e.response.isEmpty(), "Fault carries data")
                s.abort(10)
                continue
            }
            if (e.shape() != e.status) { s.abort(10); continue }
            val positive = e.status == 1
            when {
                e.stage == 6 -> {
                    if (e.status == 2) { s.outcome = 2; s.reason = 11; s.nrc = u(e.response[2]) }
                    else if (!positive) s.abort(10)
                    else {
                        s.a = s.a or 8
                        if (scope.operation == 2) s.outcome = 6
                    }
                }
                e.cmd == "0902" -> {
                    if (!positive || String(e.response, 3, 17, Charsets.US_ASCII) != scope.vin) s.abort(5)
                    else when (e.stage) {
                        1 -> s.a = s.a or 16
                        4 -> { s.a = s.a or 32; identity = now }
                        else -> s.a = s.a or 64
                    }
                }
                e.cmd == "010D" || e.cmd == "010C" -> {
                    if (!positive || !zero(e.response, 2, e.response.size)) s.abort(6)
                    else if (e.stage == 4) {
                        if (e.cmd == "010D") speed = now else rpm = now
                    }
                }
                e.cmd == "0800" -> if (!positive || u(e.response[2]) and 0x80 == 0) s.abort(7)
                e.stage == 2 && e.cmd == "03" -> if (!positive) s.abort(8) else s.a = s.a or 128
                e.stage == 7 && e.cmd in listOf("03", "07") -> if (positive) {
                    val bit = if (e.cmd == "03") 1 else 2
                    s.b = s.b or bit
                    if (u(e.response[1]) == 0) s.b = s.b or (bit shl 2)
                }
            }
            if (i == plan.lastIndex && scope.operation == 1 && s.outcome == 0) {
                if (s.b and 3 == 3 && s.a and 64 != 0) {
                    s.outcome = 5
                    s.reason = if (s.b and 12 == 12) 0 else 13
                } else s.abort(12)
            }
        }
        return s
    }

    @JvmStatic @Throws(IOException::class)
    fun inspectPrefix(data: ByteArray?, expectedVin: String?): Map<String, Any?> {
        if (data == null || data.isEmpty() || data.size > MAX_JOURNAL || !CuWire.validVin(expectedVin))
            throw IOException("Invalid interrupted CM/1 input")
        var pos = 0
        var number = 0
        var prior = 0
        var declaredKind: String? = null
        var firstScope: Scope? = null
        val magic = byteArrayOf('C'.code.toByte(), 'M'.code.toByte(), 1)
        while (pos < data.size) {
            checkWire(number < 4 && prior != 4, "Trailing bytes after CM/1 terminal")
            val available = minOf(HEADER, data.size - pos)
            for (i in 0 until minOf(3, available))
                checkWire(data[pos + i] == magic[i], "Invalid CM/1 prefix magic/version")
            if (available >= 4) {
                val k = u(data[pos + 3])
                checkWire(k in 1..4 && (if (number == 0) k == 1 else k == prior + 1 || k == 4), "Unexpected prefix record")
                declaredKind = kinds[k]
            }
            if (available >= 5) checkWire(u(data[pos + 4]) == number, "Invalid prefix sequence")
            if (available >= 6) checkWire(u(data[pos + 5]) <= MAX_ROWS &&
                (number != 0 || data[pos + 5] == 0.toByte()), "Invalid prefix count")
            for (i in 6 until minOf(8, available)) checkWire(data[pos + i] == 0.toByte(), "Invalid prefix reserved field")
            if (available < HEADER) break
            val kind = u(data[pos + 3])
            val count = u(data[pos + 5])
            val length = u32(data, pos + 8)
            checkWire(length in SCOPE.toLong()..(MAX_RECORD - HEADER - 4).toLong() &&
                (if (kind == 1) length == SCOPE.toLong() && count == 0 else length >= SCOPE + SUMMARY + count * ROW),
                "Invalid prefix body size")
            val end = pos + HEADER + length.toInt() + 4
            if (data.size - pos >= HEADER + SCOPE) {
                val scope = Scope(data.copyOfRange(pos + HEADER, pos + HEADER + SCOPE))
                checkWire(scope.vin == expectedVin && (firstScope == null || firstScope.wire.contentEquals(scope.wire)),
                    "CM/1 prefix scope changed")
                if (firstScope == null) firstScope = scope
            }
            if (end > data.size) break
            val crc = CRC32().also { it.update(data, pos, end - pos - 4) }
            checkWire(crc.value == u32(data, end - 4), "Complete prefix record has bad CRC")
            pos = end
            number++
            prior = kind
            declaredKind = null
        }
        checkWire(number > 0 && prior != 4, "Interrupted CM/1 requires verified open and no terminal")
        val verified = inspect(data.copyOf(pos), expectedVin)
        checkWire(verified["terminal_present"] == false && verified["recovery_required"] == true, "Invalid prefix state")
        return map("schema" to "camarodiag-j1979-native-maintenance-prefix-v1", "protocol" to "SAE-J1979",
            "scope" to verified["scope"], "prefix_integrity_verified" to true, "record_consistency_verified" to false,
            "terminal_present" to false, "verified_prefix_bytes" to pos, "unverified_trailing_bytes" to data.size - pos,
            "journal_bytes_received" to data.size, "next_record_kind_declared" to declaredKind, "verified_records" to verified["records"],
            "baseline_record_present" to verified["baseline_record_present"], "intent_record_present" to verified["intent_record_present"],
            "observations" to verified["observations"], "recovery_required" to true, "command_outcome_unknown" to true,
            "physical_vehicle_verified" to false, "physical_stop_independently_verified" to false,
            "durable_before_tx_independently_verified" to false, "operator_consent_verified" to false,
            "monitor_completion_verified" to false, "evidence_authenticated" to false,
            "permits_automatic_retry" to false, "permits_programming" to false)
    }

    @JvmStatic @Throws(IOException::class)
    fun inspect(raw: ByteArray?, expectedVin: String?): Map<String, Any?> {
        if (raw == null || !CuWire.validVin(expectedVin)) throw IOException("Independent VIN and journal required")
        val entries = parse(raw)
        val scope = entries.first().scope
        checkWire(scope.vin == expectedVin, "CM/1 differs from independent VIN")
        var previous = emptyList<Event>()
        val recordKinds = arrayListOf("OPEN")
        var baseline = false
        var intent = false
        var terminal: Summary? = null
        for (e in entries.drop(1)) {
            recordKinds.add(kinds[e.kind])
            checkWire(scope.wire.contentEquals(e.scope.wire) && e.rows.size >= previous.size, "CM/1 prior evidence changed")
            for (n in previous.indices)
                checkWire(previous[n].wire.contentEquals(e.rows[n].wire), "CM/1 prior evidence changed")
            val state = replay(scope, e.rows, baseline, intent)
            val claimed = e.summary ?: throw IOException("Missing CM/1 summary")
            when (e.kind) {
                2 -> {
                    checkWire(e.rows.size == scope.baseline && state.outcome == 0 && state == claimed, "Invalid baseline")
                    baseline = true
                }
                3 -> {
                    checkWire(baseline && e.rows.size == scope.intent && state.outcome == 0 && state == claimed, "Invalid intent")
                    intent = true
                }
                else -> {
                    checkWire(claimed.b and 16 != 0, "Terminal missing shutdown declaration")
                    if (state.outcome == 0) {
                        val why = claimed.reason
                        checkWire(why in listOf(2, 3, 4, 6, 9, 10) && claimed.duration >= state.duration &&
                            (why != 3 || claimed.duration >= scope.budget), "Invalid external terminal declaration")
                        if (e.rows.size == scope.baseline && baseline) state.a = (state.a and 1.inv()) or (claimed.a and 1)
                        if (e.rows.size == scope.intent && intent) state.a = (state.a and 2.inv()) or (claimed.a and 2)
                        state.abort(why)
                        state.duration = claimed.duration
                    }
                    state.b = state.b or 16 or (claimed.b and 32)
                    checkWire((state.outcome != 3 || state.b and 32 != 0) && state == claimed,
                        "Terminal summary differs from replay")
                    terminal = state
                }
            }
            previous = e.rows
        }
        return map("schema" to "camarodiag-j1979-native-maintenance-inspection-v1", "protocol" to "SAE-J1979",
            "scope" to scope.view(), "record_consistency_verified" to true, "records" to frozen(recordKinds),
            "baseline_record_present" to baseline, "intent_record_present" to intent, "terminal_present" to (terminal != null),
            "terminal" to terminal?.view(), "observations" to frozen(previous.map { it.view() }),
            "recovery_required" to (terminal == null || terminal.b and 32 != 0), "physical_vehicle_verified" to false,
            "physical_stop_independently_verified" to false, "durable_before_tx_independently_verified" to false,
            "operator_consent_verified" to false, "monitor_completion_verified" to false, "evidence_authenticated" to false,
            "permits_automatic_retry" to false, "permits_programming" to false)
    }
}
